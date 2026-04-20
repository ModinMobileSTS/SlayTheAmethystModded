package io.stamethyst.compatmod;

import com.megacrit.cardcrawl.core.Settings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtBehavior;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;

final class MobileUiLayoutClassPatcher {
    private static final String CLASS_ENTRY_SUFFIX = ".class";
    private static final String SETTINGS_CLASS_NAME = Settings.class.getName();
    private static final String SETTINGS_CLASS_INTERNAL_NAME =
        "com/megacrit/cardcrawl/core/Settings";
    private static final String IS_MOBILE_FIELD_NAME = "isMobile";
    private static final String[] EXCLUDED_CLASS_NAMES = new String[] {
        "com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen",
        "com.megacrit.cardcrawl.screens.mainMenu.MenuButton"
    };
    private static final String[] EXCLUDED_SPEECH_BUBBLE_CLASS_SUFFIXES = new String[] {
        ".SpeechBubble",
        ".ShopSpeechBubble",
        ".MegaSpeechBubble",
        ".InfiniteSpeechBubble",
        ".SpeechTextEffect",
        ".MegaDialogTextEffect"
    };
    private static final byte[] SETTINGS_CLASS_MARKER =
        SETTINGS_CLASS_INTERNAL_NAME.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] IS_MOBILE_MARKER =
        IS_MOBILE_FIELD_NAME.getBytes(StandardCharsets.US_ASCII);
    private static final Set<String> PATCHED_CLASS_NAMES = new HashSet<String>();

    private MobileUiLayoutClassPatcher() {
    }

    static void patchJar(File jarFile, ClassPool classPool, String sourceLabel) throws IOException {
        if (jarFile == null || !jarFile.isFile()) {
            return;
        }

        int patchedClasses = 0;
        int patchedReads = 0;
        int skippedClasses = 0;
        try (JarFile inputJar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = inputJar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!isClassEntry(entry)) {
                    continue;
                }
                byte[] classBytes = readEntryBytes(inputJar, entry);
                if (!referencesMobileLayoutFlag(classBytes)) {
                    continue;
                }

                String className = toClassName(entry.getName());
                if (shouldSkipClass(className)) {
                    continue;
                }

                int replacedReads;
                try {
                    replacedReads = patchClass(classPool, className);
                } catch (Exception exception) {
                    skippedClasses++;
                    continue;
                }
                if (replacedReads <= 0) {
                    continue;
                }
                patchedClasses++;
                patchedReads += replacedReads;
            }
        }

        if (patchedClasses > 0) {
            System.out.println(
                "[amethyst-runtime-compat] mobile UI layout patched source="
                    + sourceLabel
                    + " classes="
                    + patchedClasses
                    + " reads="
                    + patchedReads
                    + " skipped="
                    + skippedClasses
            );
        }
    }

    private static boolean isClassEntry(JarEntry entry) {
        return entry != null
            && !entry.isDirectory()
            && entry.getName() != null
            && entry.getName().endsWith(CLASS_ENTRY_SUFFIX);
    }

    private static boolean referencesMobileLayoutFlag(byte[] classBytes) {
        return containsAscii(classBytes, SETTINGS_CLASS_MARKER)
            && containsAscii(classBytes, IS_MOBILE_MARKER);
    }

    private static boolean shouldSkipClass(String className) {
        return className == null
            || className.length() == 0
            || className.startsWith("io.stamethyst.compatmod.")
            || className.startsWith("com.evacipated.cardcrawl.modthespire.")
            || isExcludedClassName(className)
            || isExcludedSpeechBubbleClass(className);
    }

    private static boolean isExcludedClassName(String className) {
        for (String excludedClassName : EXCLUDED_CLASS_NAMES) {
            if (excludedClassName.equals(className)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExcludedSpeechBubbleClass(String className) {
        for (String suffix : EXCLUDED_SPEECH_BUBBLE_CLASS_SUFFIXES) {
            if (className.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static int patchClass(ClassPool classPool, String className)
        throws CannotCompileException, javassist.NotFoundException {
        synchronized (PATCHED_CLASS_NAMES) {
            if (PATCHED_CLASS_NAMES.contains(className)) {
                return 0;
            }
        }

        CtClass ctClass = classPool.get(className);
        if (ctClass.isFrozen()) {
            ctClass.defrost();
        }

        int replacedReads = 0;
        for (CtConstructor constructor : ctClass.getDeclaredConstructors()) {
            replacedReads += patchBehavior(constructor);
        }
        for (CtMethod method : ctClass.getDeclaredMethods()) {
            replacedReads += patchBehavior(method);
        }

        if (replacedReads > 0) {
            synchronized (PATCHED_CLASS_NAMES) {
                PATCHED_CLASS_NAMES.add(className);
            }
        }
        return replacedReads;
    }

    private static int patchBehavior(CtBehavior behavior) throws CannotCompileException {
        if (behavior == null || behavior.isEmpty()) {
            return 0;
        }

        final int[] replacedReads = new int[] {0};
        behavior.instrument(new ExprEditor() {
            @Override
            public void edit(FieldAccess access) throws CannotCompileException {
                if (!access.isReader()) {
                    return;
                }
                if (!SETTINGS_CLASS_NAME.equals(access.getClassName())) {
                    return;
                }
                if (!IS_MOBILE_FIELD_NAME.equals(access.getFieldName())) {
                    return;
                }
                access.replace(
                    "{ $_ = "
                        + CompatRuntimeState.class.getName()
                        + ".resolveMobileLayoutFlag($proceed()); }"
                );
                replacedReads[0]++;
            }
        });
        return replacedReads[0];
    }

    private static String toClassName(String entryName) {
        return entryName
            .substring(0, entryName.length() - CLASS_ENTRY_SUFFIX.length())
            .replace('/', '.');
    }

    private static byte[] readEntryBytes(JarFile jarFile, JarEntry entry) throws IOException {
        try (InputStream inputStream = jarFile.getInputStream(entry);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        }
    }

    private static boolean containsAscii(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || needle.length == 0 || haystack.length < needle.length) {
            return false;
        }
        int maxStart = haystack.length - needle.length;
        for (int i = 0; i <= maxStart; ++i) {
            boolean matched = true;
            for (int j = 0; j < needle.length; ++j) {
                if (haystack[i + j] != needle[j]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return true;
            }
        }
        return false;
    }
}
