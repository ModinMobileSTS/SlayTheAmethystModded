package com.badlogic.gdx.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Patched for SlayTheAmethyst:
 * - Prefer loading from POJAV_NATIVEDIR/System.loadLibrary first.
 * - Keep fallback extraction logic for compatibility.
 */
public class SharedLibraryLoader {
    public static boolean isWindows = System.getProperty("os.name").contains("Windows");
    public static boolean isLinux = System.getProperty("os.name").contains("Linux");
    public static boolean isMac = System.getProperty("os.name").contains("Mac");
    public static boolean isIos = false;
    public static boolean isAndroid = false;
    public static boolean isARM = System.getProperty("os.arch").startsWith("arm");
    public static boolean is64Bit = System.getProperty("os.arch").equals("amd64")
            || System.getProperty("os.arch").equals("x86_64");

    public static String abi = (System.getProperty("sun.arch.abi") != null ? System.getProperty("sun.arch.abi") : "");

    static {
        String vm = System.getProperty("java.runtime.name");
        if (vm != null && vm.contains("Android Runtime")) {
            isAndroid = true;
            isWindows = false;
            isLinux = false;
            isMac = false;
            is64Bit = false;
        }
        if (!isAndroid && !isWindows && !isLinux && !isMac) {
            isIos = true;
            is64Bit = false;
        }

        // Support JVMs that report aarch64/arm64.
        String arch = System.getProperty("os.arch", "");
        if ("aarch64".equals(arch) || arch.startsWith("arm64")) {
            isARM = true;
            is64Bit = true;
        }
    }

    private static final HashSet<String> loadedLibraries = new HashSet<String>();
    private final String nativesJar;

    public SharedLibraryLoader() {
        this.nativesJar = null;
    }

    public SharedLibraryLoader(String nativesJar) {
        this.nativesJar = nativesJar;
    }

    public String crc(InputStream input) {
        if (input == null) throw new IllegalArgumentException("input cannot be null.");
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[4096];
        try {
            while (true) {
                int length = input.read(buffer);
                if (length == -1) break;
                crc.update(buffer, 0, length);
            }
        } catch (Exception ignored) {
        } finally {
            StreamUtils.closeQuietly(input);
        }
        return Long.toString(crc.getValue(), 16);
    }

    public String mapLibraryName(String libraryName) {
        if (isWindows) return libraryName + (is64Bit ? "64.dll" : ".dll");
        if (isAndroid) return "lib" + libraryName + (isARM ? "arm" + abi : "") + (is64Bit ? "64.so" : ".so");
        if (isLinux) return "lib" + libraryName + (isARM ? "arm" + abi : "") + (is64Bit ? "64.so" : ".so");
        if (isMac) return "lib" + libraryName + (is64Bit ? "64.dylib" : ".dylib");
        return libraryName;
    }

    public void load(String libraryName) {
        if (isIos) return;

        synchronized (SharedLibraryLoader.class) {
            if (isLoaded(libraryName)) return;
            String platformName = mapLibraryName(libraryName);
            try {
                System.out.println("[gdx-patch] SharedLibraryLoader.load name=" + libraryName
                        + " mapped=" + platformName
                        + " os.arch=" + System.getProperty("os.arch")
                        + " org.lwjgl.librarypath=" + System.getProperty("org.lwjgl.librarypath"));
                if (!tryLoadFromKnownLocations(libraryName, platformName)) {
                    if (isAndroid) {
                        System.loadLibrary(platformName);
                    } else {
                        loadFile(platformName);
                    }
                }
                setLoaded(libraryName);
            } catch (Throwable ex) {
                // Optional extension on STS. Don't hard-fail game startup if unavailable.
                if ("gdx-controllers-desktop".equals(libraryName)) {
                    setLoaded(libraryName);
                    return;
                }
                throw new GdxRuntimeException("Couldn't load shared library '" + platformName + "' for target: "
                        + System.getProperty("os.name") + (is64Bit ? ", 64-bit" : ", 32-bit"), ex);
            }
        }
    }

    private boolean tryLoadFromKnownLocations(String libraryName, String platformName) {
        if (tryLoadFromAmethystNativeDir(libraryName, platformName)) return true;
        String nativeDir = System.getenv("POJAV_NATIVEDIR");
        if (nativeDir != null && nativeDir.length() > 0) {
            if (tryLoadAbsolute(new File(nativeDir, "lib" + libraryName + ".so"))) return true;
            if (tryLoadAbsolute(new File(nativeDir, platformName))) return true;
        }
        if (tryLoadLibrary(libraryName)) return true;
        return false;
    }

    private boolean tryLoadFromAmethystNativeDir(String libraryName, String platformName) {
        String[] nativeDirs = resolveAmethystNativeDirs();
        if (nativeDirs.length == 0) return false;

        String[] candidateNames = buildNativeCandidateNames(libraryName, platformName);
        for (String nativeDir : nativeDirs) {
            File baseDir = new File(nativeDir);
            if (!baseDir.exists() && !baseDir.mkdirs()) continue;

            for (String candidateName : candidateNames) {
                File candidate = new File(baseDir, candidateName);
                if (!candidate.exists()) {
                    tryExtractToFile(candidateName, candidate);
                }
                if (tryLoadAbsolute(candidate)) {
                    System.out.println("[gdx-patch] SharedLibraryLoader.load loaded from amethyst native dir: "
                            + candidate.getAbsolutePath());
                    return true;
                }
            }
        }
        return false;
    }

    private String[] resolveAmethystNativeDirs() {
        String value = System.getProperty("amethyst.gdx.native_dir");
        if (value == null || value.trim().length() == 0) {
            value = System.getenv("AMETHYST_GDX_NATIVE_DIR");
        }
        if (value == null) return new String[0];

        String[] rawValues = value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator));
        java.util.ArrayList<String> directories = new java.util.ArrayList<String>();
        for (String rawValue : rawValues) {
            if (rawValue == null) continue;
            String trimmed = rawValue.trim();
            if (trimmed.length() == 0) continue;
            if (!directories.contains(trimmed)) directories.add(trimmed);
        }
        return directories.toArray(new String[0]);
    }

    private String[] buildNativeCandidateNames(String libraryName, String platformName) {
        java.util.ArrayList<String> names = new java.util.ArrayList<String>();
        addCandidateName(names, platformName);
        addCandidateName(names, mapLibraryName(libraryName));
        addCandidateName(names, "lib" + libraryName + ".so");
        addCandidateName(names, "lib" + libraryName + "arm64.so");
        addCandidateName(names, "lib" + libraryName + "arm.so");
        return names.toArray(new String[0]);
    }

    private void addCandidateName(java.util.ArrayList<String> names, String candidateName) {
        if (candidateName == null) return;
        String value = candidateName.trim();
        if (value.length() == 0) return;
        if (!names.contains(value)) names.add(value);
    }

    private void tryExtractToFile(String sourcePath, File targetFile) {
        InputStream input = null;
        FileOutputStream output = null;
        try {
            input = readFile(sourcePath);
            File parent = targetFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return;
            output = new FileOutputStream(targetFile, false);
            byte[] buffer = new byte[4096];
            while (true) {
                int length = input.read(buffer);
                if (length == -1) break;
                output.write(buffer, 0, length);
            }
            output.flush();
            targetFile.setReadable(true, false);
            targetFile.setExecutable(true, false);
        } catch (Throwable ignored) {
        } finally {
            StreamUtils.closeQuietly(input);
            StreamUtils.closeQuietly(output);
        }
    }

    private boolean tryLoadLibrary(String libraryName) {
        try {
            System.loadLibrary(libraryName);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean tryLoadAbsolute(File file) {
        if (!file.exists()) return false;
        try {
            System.load(file.getAbsolutePath());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private InputStream readFile(String path) {
        if (nativesJar == null) {
            InputStream input = SharedLibraryLoader.class.getResourceAsStream("/" + path);
            if (input == null) throw new GdxRuntimeException("Unable to read file for extraction: " + path);
            return input;
        }

        try {
            ZipFile file = new ZipFile(nativesJar);
            ZipEntry entry = file.getEntry(path);
            if (entry == null) throw new GdxRuntimeException("Couldn't find '" + path + "' in JAR: " + nativesJar);
            return file.getInputStream(entry);
        } catch (IOException ex) {
            throw new GdxRuntimeException("Error reading '" + path + "' in JAR: " + nativesJar, ex);
        }
    }

    public File extractFile(String sourcePath, String dirName) throws IOException {
        try {
            String sourceCrc = crc(readFile(sourcePath));
            if (dirName == null) dirName = sourceCrc;

            File extractedFile = getExtractedFile(dirName, new File(sourcePath).getName());
            if (extractedFile == null) {
                extractedFile = getExtractedFile(UUID.randomUUID().toString(), new File(sourcePath).getName());
                if (extractedFile == null) {
                    throw new GdxRuntimeException("Unable to find writable path to extract file. Is the user home directory writable?");
                }
            }
            return extractFile(sourcePath, sourceCrc, extractedFile);
        } catch (RuntimeException ex) {
            File file = new File(System.getProperty("java.library.path"), sourcePath);
            if (file.exists()) return file;
            throw ex;
        }
    }

    public void extractFileTo(String sourcePath, File dir) throws IOException {
        extractFile(sourcePath, crc(readFile(sourcePath)), new File(dir, new File(sourcePath).getName()));
    }

    private File getExtractedFile(String dirName, String fileName) {
        File idealFile = new File(System.getProperty("java.io.tmpdir") + "/libgdx" + System.getProperty("user.name") + "/" + dirName, fileName);
        if (canWrite(idealFile)) return idealFile;

        try {
            File file = File.createTempFile(dirName, null);
            if (file.delete()) {
                file = new File(file, fileName);
                if (canWrite(file)) return file;
            }
        } catch (IOException ignored) {
        }

        File file = new File(System.getProperty("user.home") + "/.libgdx/" + dirName, fileName);
        if (canWrite(file)) return file;

        file = new File(".temp/" + dirName, fileName);
        if (canWrite(file)) return file;

        if (System.getenv("APP_SANDBOX_CONTAINER_ID") != null) return idealFile;
        return null;
    }

    private boolean canWrite(File file) {
        File parent = file.getParentFile();
        File testFile;
        if (file.exists()) {
            if (!file.canWrite() || !canExecute(file)) return false;
            testFile = new File(parent, UUID.randomUUID().toString());
        } else {
            parent.mkdirs();
            if (!parent.isDirectory()) return false;
            testFile = file;
        }
        try {
            new FileOutputStream(testFile).close();
            return canExecute(testFile);
        } catch (Throwable ex) {
            return false;
        } finally {
            testFile.delete();
        }
    }

    private boolean canExecute(File file) {
        try {
            Method canExecute = File.class.getMethod("canExecute");
            if ((Boolean) canExecute.invoke(file)) return true;

            Method setExecutable = File.class.getMethod("setExecutable", boolean.class, boolean.class);
            setExecutable.invoke(file, true, false);
            return (Boolean) canExecute.invoke(file);
        } catch (Exception ignored) {
            return false;
        }
    }

    private File extractFile(String sourcePath, String sourceCrc, File extractedFile) throws IOException {
        String extractedCrc = null;
        if (extractedFile.exists()) {
            try {
                extractedCrc = crc(new FileInputStream(extractedFile));
            } catch (IOException ignored) {
            }
        }

        if (extractedCrc == null || !extractedCrc.equals(sourceCrc)) {
            try {
                InputStream input = readFile(sourcePath);
                extractedFile.getParentFile().mkdirs();
                FileOutputStream output = new FileOutputStream(extractedFile);
                byte[] buffer = new byte[4096];
                while (true) {
                    int length = input.read(buffer);
                    if (length == -1) break;
                    output.write(buffer, 0, length);
                }
                input.close();
                output.close();
            } catch (IOException ex) {
                throw new GdxRuntimeException("Error extracting file: " + sourcePath + "\nTo: " + extractedFile.getAbsolutePath(), ex);
            }
        }
        return extractedFile;
    }

    private void loadFile(String sourcePath) {
        String sourceCrc = crc(readFile(sourcePath));
        String fileName = new File(sourcePath).getName();

        File file = new File(System.getProperty("java.io.tmpdir") + "/libgdx" + System.getProperty("user.name") + "/" + sourceCrc, fileName);
        Throwable ex = loadFile(sourcePath, sourceCrc, file);
        if (ex == null) return;

        try {
            file = File.createTempFile(sourceCrc, null);
            if (file.delete() && loadFile(sourcePath, sourceCrc, file) == null) return;
        } catch (Throwable ignored) {
        }

        file = new File(System.getProperty("user.home") + "/.libgdx/" + sourceCrc, fileName);
        if (loadFile(sourcePath, sourceCrc, file) == null) return;

        file = new File(".temp/" + sourceCrc, fileName);
        if (loadFile(sourcePath, sourceCrc, file) == null) return;

        file = new File(System.getProperty("java.library.path"), sourcePath);
        if (file.exists()) {
            System.load(file.getAbsolutePath());
            return;
        }

        throw new GdxRuntimeException(ex);
    }

    private Throwable loadFile(String sourcePath, String sourceCrc, File extractedFile) {
        try {
            System.load(extractFile(sourcePath, sourceCrc, extractedFile).getAbsolutePath());
            return null;
        } catch (Throwable ex) {
            return ex;
        }
    }

    public static synchronized void setLoaded(String libraryName) {
        loadedLibraries.add(libraryName);
    }

    public static synchronized boolean isLoaded(String libraryName) {
        return loadedLibraries.contains(libraryName);
    }
}
