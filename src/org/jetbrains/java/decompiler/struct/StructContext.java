// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.java.decompiler.struct;

import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import org.jetbrains.java.decompiler.struct.lazy.LazyLoader;
import org.jetbrains.java.decompiler.util.DataInputFullStream;
import org.jetbrains.java.decompiler.util.InterpreterUtil;

import java.io.*;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class StructContext {
  private final IResultSaver saver;
  private final IDecompiledData decompiledData;
  private final LazyLoader loader;
  private final Map<String, ContextUnit> units = new HashMap<>();
  private final Map<String, StructClass> classes = new HashMap<>();

  public StructContext(IResultSaver saver, IDecompiledData decompiledData, LazyLoader loader) {
    this.saver = saver;
    this.decompiledData = decompiledData;
    this.loader = loader;

    ContextUnit defaultUnit = new ContextUnit(ContextUnit.TYPE_FOLDER, null, "", true, saver, decompiledData);
    units.put("", defaultUnit);
  }

  public StructClass getClass(String name) {
    return classes.get(name);
  }

  public void reloadContext() throws IOException {
    for (ContextUnit unit : units.values()) {
      for (StructClass cl : unit.getClasses()) {
        classes.remove(cl.qualifiedName);
      }

      unit.reload(loader);

      // adjust global class collection
      for (StructClass cl : unit.getClasses()) {
        classes.put(cl.qualifiedName, cl);
      }
    }
  }

  public void saveContext() {
    for (ContextUnit unit : units.values()) {
      if (unit.isOwn()) {
        unit.save();
      }
    }
  }

  public void addSpace(File file, boolean isOwn) {
    addSpace("", file, isOwn, 0);
  }

  private void addSpace(String path, File file, boolean isOwn, int level) {
    if (file.isDirectory()) {
      if (level == 1) path += file.getName();
      else if (level > 1) path += "/" + file.getName();

      File[] files = file.listFiles();
      if (files != null) {
        for (int i = files.length - 1; i >= 0; i--) {
          addSpace(path, files[i], isOwn, level + 1);
        }
      }
    }
    else {
      String filename = file.getName();

      boolean isArchive = false;
      try {
        if (filename.endsWith(".jar")) {
          isArchive = true;
          addArchive(path, file, ContextUnit.TYPE_JAR, isOwn);
        }
        else if (filename.endsWith(".zip")
              || filename.endsWith(".war")
              || filename.endsWith(".ear")) {
          isArchive = true;
          addArchive(path, file, ContextUnit.TYPE_ZIP, isOwn);
        }
      }
      catch (IOException ex) {
        String message = "Corrupted archive file: " + file;
        DecompilerContext.getLogger().writeMessage(message, ex);
      }
      if (isArchive) {
        return;
      }

      ContextUnit unit = units.get(path);
      if (unit == null) {
        unit = new ContextUnit(ContextUnit.TYPE_FOLDER, null, path, isOwn, saver, decompiledData);
        units.put(path, unit);
      }

      if (filename.endsWith(".class")) {
        try (DataInputFullStream in = loader.getClassStream(file.getAbsolutePath(), null)) {
          StructClass cl = StructClass.create(in, isOwn, loader);
          classes.put(cl.qualifiedName, cl);
          unit.addClass(cl, filename);
          loader.addClassLink(cl.qualifiedName, new LazyLoader.Link(file.getAbsolutePath(), null));
        }
        catch (IOException ex) {
          String message = "Corrupted class file: " + file;
          DecompilerContext.getLogger().writeMessage(message, ex);
        }
      }
      else {
        unit.addOtherEntry(file.getAbsolutePath(), filename);
      }
    }
  }

  private void addArchive(String path, File file, int type, boolean isOwn) throws IOException {
    try (ZipFile archive = type == ContextUnit.TYPE_JAR ? new JarFile(file) : new ZipFile(file)) {
      Enumeration<? extends ZipEntry> entries = archive.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (entry.getName().startsWith("META-INF/versions")) continue; // workaround for multi release Jars (see IDEA-285079)
        ContextUnit unit = units.get(path + "/" + file.getName());
        if (unit == null) {
          unit = new ContextUnit(type, path, file.getName(), isOwn, saver, decompiledData);
          if (type == ContextUnit.TYPE_JAR) {
            unit.setManifest(((JarFile)archive).getManifest());
          }
          units.put(path + "/" + file.getName(), unit);
        }

        String name = entry.getName();
        File test = new File(file.getAbsolutePath(), name);
        if (!test.getCanonicalPath().startsWith(file.getCanonicalPath() + File.separator)) { // check for zip slip exploit
          throw new RuntimeException("Zip entry '" + entry.getName() + "' tries to escape target directory");
        }

        if (!entry.isDirectory()) {
          if (name.endsWith(".class")) {
            byte[] bytes = InterpreterUtil.getBytes(archive, entry);
            StructClass cl = StructClass.create(new DataInputFullStream(bytes), isOwn, loader);
            classes.put(cl.qualifiedName, cl);
            unit.addClass(cl, name);
            loader.addClassLink(cl.qualifiedName, new LazyLoader.Link(file.getAbsolutePath(), name));
          } else if (name.endsWith(".jar")
                  || name.endsWith(".zip")
                  || name.endsWith(".ear")
                  || name.endsWith(".war")) {
            byte[] bytes = InterpreterUtil.getBytes(archive, entry);
            File f = writeZipEntry(bytes, file.getName() + File.separator + name);

            if (name.indexOf('/') >= 0) {
              addArchive(path + File.separator + file.getName() + ".src" + File.separator + name.substring(0, name.lastIndexOf("/")), f, ContextUnit.TYPE_ZIP, isOwn);
            } else {
              addArchive(path + File.separator + file.getName() + ".src" + File.separator + name, f, ContextUnit.TYPE_ZIP, isOwn);
            }
            f.deleteOnExit();
          }
          else {
            unit.addOtherEntry(file.getAbsolutePath(), name);
          }
        }
        else {
          unit.addDirEntry(name);
        }
      }
    }
  }

  /*
   * write zip entry .jar to temp file
   */
  private File writeZipEntry(byte[] bytes, String name) {
    FileOutputStream fos = null;
    File f = null;
    try {
      File tf = File.createTempFile(name, "");
      String target = tf.getParent() + File.separator + name;
      f = new File(target);
      mkDir(f);
      fos = new FileOutputStream(target);
      fos.write(bytes);
      fos.flush();
      fos.close();
    } catch (Exception ex) {
      DecompilerContext.getLogger().writeMessage("Exception RAISED!!! ", ex);
    } finally {
      closeQuietly(fos);
    }
    return f;
  }

  private void mkDir(File f) {
    File dir = new File(f.getParent());
    if (!dir.exists()) {
      mkDir(dir);
    }
    dir.mkdir();
  }

  private static final int BUFFER_SIZE = 4096;
  public static long copy(final InputStream is, final OutputStream os) throws IOException {
    DecompilerContext.getLogger().writeMessage(
      "Copying inputStream to outputStream",
      IFernflowerLogger.Severity.TRACE);
    final byte[] buffer = new byte[BUFFER_SIZE];
    long count = 0;
    int n;
    while (-1 != (n = is.read(buffer))) {
      os.write(buffer, 0, n);
      count += n;
    }
    DecompilerContext.getLogger().writeMessage(
      count + " bytes copied from IS to OS",
      IFernflowerLogger.Severity.TRACE);
    return count;
  }

  public static void closeQuietly(final InputStream is) {
    try {
      if (is != null) {
        is.close();
      }
    } catch (IOException ioe) {
      DecompilerContext.getLogger().writeMessage("Closing InputStream failed.", ioe);
    }
  }

  public static void closeQuietly(final OutputStream os) {
    try {
      if (os != null) {
        os.close();
      }
    } catch (IOException ioe) {
      DecompilerContext.getLogger().writeMessage("Closing OutputStream failed.", ioe);
    }
  }

  public Map<String, StructClass> getClasses() {
    return classes;
  }
}
