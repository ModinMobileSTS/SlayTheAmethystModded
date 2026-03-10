package com.evacipated.cardcrawl.modthespire.steam;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Android-specific override for MTS SteamSearch.
 *
 * <p>MTS normally prefers a relative {@code jre/bin/java} path, which lands under
 * app external storage in this launcher and is not executable on many Android devices.
 * Prefer the active runtime pointed to by JAVA_HOME/java.home, then fall back to
 * MTS's original relative-path and Steam-install heuristics.</p>
 */
public class SteamSearch {

    private static final int APP_ID = 646570;
    private static String installDir;

    public SteamSearch() {
    }

    public static String findJRE() {
        return findJRE("jre");
    }

    public static String findJRE51() {
        return findJRE("jre1.8.0_51");
    }

    public static String findJRE(String directoryName) {
        if (isAndroid() && "jre".equals(directoryName)) {
            String workshopStub = findExistingFile(Paths.get("/system/bin/true"), null);
            if (workshopStub != null) {
                System.out.println("Using Android workshop stub @ " + workshopStub);
                return workshopStub;
            }
        }

        String executableName = isWindows() ? "java.exe" : "java";

        String activeRuntime = findActiveRuntimeJava(executableName);
        if (activeRuntime != null) {
            System.out.println("Using active Java runtime @ " + activeRuntime);
            return activeRuntime;
        }

        Path localJre = Paths.get(directoryName, "bin", executableName);
        String localRuntime = findExistingFile(localJre, "Using local StS JRE @ ");
        if (localRuntime != null) {
            return localRuntime;
        }

        prepare();
        if (installDir == null) {
            return null;
        }

        Path installJre = Paths.get(installDir, directoryName, "bin", executableName);
        return findExistingFile(installJre, "Using install StS JRE @ ");
    }

    public static String findDesktopJar() {
        Path localDesktopJar = Paths.get("desktop-1.0.jar");
        if (Files.isRegularFile(localDesktopJar)) {
            return localDesktopJar.toString();
        }

        prepare();
        if (installDir == null) {
            return null;
        }
        return Paths.get(installDir, "desktop-1.0.jar").toString();
    }

    private static String findActiveRuntimeJava(String executableName) {
        List<Path> candidates = new ArrayList<Path>(4);

        String javaHomeEnv = System.getenv("JAVA_HOME");
        if (javaHomeEnv != null && !javaHomeEnv.trim().isEmpty()) {
            candidates.add(Paths.get(javaHomeEnv, "bin", executableName));
        }

        String javaHomeProperty = System.getProperty("java.home");
        if (javaHomeProperty != null && !javaHomeProperty.trim().isEmpty()) {
            candidates.add(Paths.get(javaHomeProperty, "bin", executableName));
        }

        String processCommand = System.getProperty("sun.java.command");
        if (processCommand != null && processCommand.trim().endsWith(".jar")) {
            Path siblingJava = Paths.get(executableName);
            candidates.add(siblingJava);
        }

        for (Path candidate : candidates) {
            String resolved = findExistingFile(candidate, null);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private static String findExistingFile(Path candidate, String logPrefix) {
        if (candidate == null) {
            return null;
        }
        try {
            if (!Files.isRegularFile(candidate)) {
                return null;
            }
        } catch (Throwable ignored) {
            return null;
        }

        String path = candidate.toString();
        if (logPrefix != null && !logPrefix.isEmpty()) {
            System.out.println(logPrefix + candidate.toAbsolutePath());
        }
        return path;
    }

    private static void prepare() {
        if (installDir != null) {
            return;
        }

        Path steamPath = getSteamPath();
        if (steamPath == null) {
            System.err.println("ERROR: Failed to find Steam installation.");
            return;
        }

        if (containsAcfFile(steamPath)) {
            installDir = steamToSTSPath(steamPath).toString();
            return;
        }

        File libraryFoldersFile = Paths.get(steamPath.toString(), "libraryfolders.vdf").toFile();
        if (!libraryFoldersFile.exists()) {
            return;
        }

        List<Path> libraryFolders = readLibraryFolders2(libraryFoldersFile);
        for (Path folder : libraryFolders) {
            if (containsAcfFile(folder)) {
                installDir = steamToSTSPath(folder).toString();
                return;
            }
        }
    }

    private static Path getSteamPath() {
        if (isWindows()) {
            String programFiles = System.getenv("ProgramFiles");
            if (programFiles == null || programFiles.isEmpty()) {
                return null;
            }

            Path steamApps = Paths.get(programFiles + " (x86)", "Steam", "steamapps");
            if (steamApps.toFile().exists()) {
                return steamApps;
            }

            steamApps = Paths.get(programFiles, "Steam", "steamapps");
            if (steamApps.toFile().exists()) {
                return steamApps;
            }
            return null;
        }

        if (isMac()) {
            String userHome = System.getProperty("user.home");
            if (userHome == null || userHome.isEmpty()) {
                return null;
            }
            Path steamApps = Paths.get(userHome, "Library/Application Support/Steam/steamapps");
            return steamApps.toFile().exists() ? steamApps : null;
        }

        if (!isLinux()) {
            return null;
        }

        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isEmpty()) {
            return null;
        }

        Path[] candidates = new Path[] {
            Paths.get(userHome, ".steam/steam/SteamApps"),
            Paths.get(userHome, ".steam/steam/steamapps"),
            Paths.get(userHome, ".local/share/steam/SteamApps"),
            Paths.get(userHome, ".local/share/steam/steamapps")
        };
        for (Path candidate : candidates) {
            if (candidate.toFile().exists()) {
                return candidate;
            }
        }
        return null;
    }

    private static Path steamToSTSPath(Path steamPath) {
        return Paths.get(steamPath.toString(), "common", "SlayTheSpire");
    }

    private static boolean containsAcfFile(Path steamPath) {
        return Paths.get(steamPath.toString(), "appmanifest_" + APP_ID + ".acf").toFile().exists();
    }

    private static List<Path> readLibraryFolders(File file) {
        List<Path> output = new ArrayList<Path>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length != 4) {
                    continue;
                }
                String index = stripQuotes(parts[1]);
                if (!isInteger(index)) {
                    continue;
                }
                String path = stripQuotes(parts[3]);
                output.add(Paths.get(path, "steamapps"));
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
        return output;
    }

    private static List<Path> readLibraryFolders2(File file) {
        List<Path> output = new ArrayList<Path>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] rawParts = line.split("\t");
                List<String> parts = new ArrayList<String>(rawParts.length);
                for (String part : rawParts) {
                    if (part != null && !part.isEmpty()) {
                        parts.add(part);
                    }
                }
                if (parts.size() != 2) {
                    continue;
                }
                if (!"\"path\"".equals(parts.get(0))) {
                    continue;
                }
                output.add(Paths.get(stripQuotes(parts.get(1)), "steamapps"));
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }

        if (output.isEmpty()) {
            return readLibraryFolders(file);
        }
        return output;
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        } catch (NullPointerException ignored) {
            return false;
        }
    }

    private static boolean isWindows() {
        return normalizedOsName().contains("win");
    }

    private static boolean isMac() {
        String osName = normalizedOsName();
        return osName.contains("mac") || osName.contains("darwin");
    }

    private static boolean isLinux() {
        return normalizedOsName().contains("linux");
    }

    private static boolean isAndroid() {
        String osVersion = System.getProperty("os.version", "");
        if (osVersion.toLowerCase(Locale.US).contains("android")) {
            return true;
        }
        return findExistingFile(Paths.get("/system/bin/true"), null) != null;
    }

    private static String normalizedOsName() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase(Locale.US);
    }
}
