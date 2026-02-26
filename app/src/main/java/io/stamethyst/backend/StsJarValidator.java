package io.stamethyst.backend;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public final class StsJarValidator {
    private static final String DESKTOP_LAUNCHER_CLASS = "com/megacrit/cardcrawl/desktop/DesktopLauncher.class";
    private static final String DESKTOP_LAUNCHER_MAIN = "com.megacrit.cardcrawl.desktop.DesktopLauncher";

    private StsJarValidator() {
    }

    public static void validate(File jarFile) throws IOException {
        if (!jarFile.exists() || jarFile.length() == 0) {
            throw new IOException("desktop-1.0.jar is missing or empty");
        }

        try {
            validateStrict(jarFile);
            return;
        } catch (ZipException zipError) {
            String message = zipError.getMessage();
            if (message == null || !message.toLowerCase().contains("duplicate")) {
                throw zipError;
            }
        }

        // Fallback for jars with duplicate ZIP entries (some modded/packed jars have this).
        validateLenient(jarFile);
    }

    private static void validateStrict(File jarFile) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            Manifest manifest = jar.getManifest();
            if (manifest != null) {
                String mainClass = manifest.getMainAttributes().getValue("Main-Class");
                if (mainClass != null && !DESKTOP_LAUNCHER_MAIN.equals(mainClass.trim())) {
                    throw new IOException("Main-Class mismatch: " + mainClass);
                }
            }

            if (jar.getEntry(DESKTOP_LAUNCHER_CLASS) == null) {
                throw new IOException("DesktopLauncher class not found in jar");
            }
        }
    }

    private static void validateLenient(File jarFile) throws IOException {
        String manifestMainClass = null;
        boolean hasDesktopLauncher = false;

        try (ZipInputStream zipInput = new ZipInputStream(new BufferedInputStream(new FileInputStream(jarFile)))) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                String name = entry.getName();
                if (DESKTOP_LAUNCHER_CLASS.equals(name)) {
                    hasDesktopLauncher = true;
                } else if ("META-INF/MANIFEST.MF".equalsIgnoreCase(name)) {
                    byte[] manifestBytes = readCurrentEntry(zipInput);
                    Manifest manifest = new Manifest(new ByteArrayInputStream(manifestBytes));
                    manifestMainClass = manifest.getMainAttributes().getValue("Main-Class");
                }
                zipInput.closeEntry();
            }
        }

        if (manifestMainClass != null && !DESKTOP_LAUNCHER_MAIN.equals(manifestMainClass.trim())) {
            throw new IOException("Main-Class mismatch: " + manifestMainClass);
        }

        if (!hasDesktopLauncher) {
            throw new IOException("DesktopLauncher class not found in jar");
        }
    }

    private static byte[] readCurrentEntry(ZipInputStream zipInput) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = zipInput.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
