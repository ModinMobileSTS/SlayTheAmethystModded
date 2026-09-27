package io.stamethyst.backend.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class MtsLoaderCrashPatcherTest {
    @Test
    fun ensurePatchedMtsJar_removesRunModsSwallowHandlersAndInjectsStartupHooks() {
        val sourceJar = sequenceOf(
            File("src/main/assets/components/mods/ModTheSpire.jar"),
            File("app/src/main/assets/components/mods/ModTheSpire.jar")
        ).firstOrNull { it.isFile }
            ?: error("Missing test fixture jar: ModTheSpire.jar")
        check(sourceJar.isFile) { "Missing test fixture jar: ${sourceJar.absolutePath}" }

        val tempJar = Files.createTempFile("mts-loader-patch-", ".jar").toFile()
        try {
            JarFileIoUtils.copyFileReplacing(sourceJar, tempJar)

            val originalLoaderBytes = JarFileIoUtils.readJarEntryBytes(
                tempJar,
                "com/evacipated/cardcrawl/modthespire/Loader.class"
            )
            requireNotNull(originalLoaderBytes)
            assertFalse(MtsLoaderCrashPatcher.isPatchedLoaderClass(originalLoaderBytes))
            val originalConsoleBytes = requireNotNull(
                JarFileIoUtils.readJarEntryBytes(tempJar, MtsConsoleLogPatcher.MESSAGE_CONSOLE_CLASS_ENTRY)
            )
            assertFalse(MtsConsoleLogPatcher.isPatchedMessageConsoleClass(originalConsoleBytes))

            assertTrue(MtsLoaderCrashPatcher.ensurePatchedMtsJar(tempJar))

            val patchedLoaderBytes = JarFileIoUtils.readJarEntryBytes(
                tempJar,
                "com/evacipated/cardcrawl/modthespire/Loader.class"
            )
            requireNotNull(patchedLoaderBytes)
            val patchedPackageJarBytes = JarFileIoUtils.readJarEntryBytes(
                tempJar,
                "com/evacipated/cardcrawl/modthespire/PackageJar.class"
            )
            requireNotNull(patchedPackageJarBytes)
            val patchedPatcherBytes = JarFileIoUtils.readJarEntryBytes(
                tempJar,
                "com/evacipated/cardcrawl/modthespire/Patcher.class"
            )
            requireNotNull(patchedPatcherBytes)
            val patchedPrepackagedLauncherBytes = JarFileIoUtils.readJarEntryBytes(
                tempJar,
                "com/evacipated/cardcrawl/modthespire/PackageJar\$PrepackagedLauncher.class"
            )
            requireNotNull(patchedPrepackagedLauncherBytes)
            assertTrue(MtsLoaderCrashPatcher.isPatchedLoaderClass(patchedLoaderBytes))
            assertTrue(MtsLoaderCrashPatcher.isPatchedPatcherClass(patchedPatcherBytes))
            assertTrue(MtsLoaderCrashPatcher.isPatchedPackageJarClass(patchedPackageJarBytes))
            assertTrue(MtsLoaderCrashPatcher.hasPackageDirOverride(patchedPackageJarBytes))
            assertTrue(MtsLoaderCrashPatcher.hasPackageJarFastPathHook(patchedPackageJarBytes))
            assertTrue(MtsLoaderCrashPatcher.isPatchedPrepackagedLauncherClass(patchedPrepackagedLauncherBytes))
            assertTrue(
                MtsConsoleLogPatcher.isPatchedMessageConsoleClass(
                    requireNotNull(
                        JarFileIoUtils.readJarEntryBytes(tempJar, MtsConsoleLogPatcher.MESSAGE_CONSOLE_CLASS_ENTRY)
                    )
                )
            )
            assertTrue(MtsLoaderCrashPatcher.hasPatchCacheLaunchHook(patchedLoaderBytes))
            assertTrue(MtsLoaderCrashPatcher.hasPatchCacheStoreHook(patchedLoaderBytes))
            assertTrue(MtsLoaderCrashPatcher.hasPatchCacheStoreHookWithCompiledClassPathArg(patchedLoaderBytes))
            assertTrue(MtsLoaderCrashPatcher.hasOutJarPrimingHook(patchedLoaderBytes))
            assertTrue(MtsLoaderCrashPatcher.hasCloseWindowNullGuard(patchedLoaderBytes))
            assertTrue(MtsLoaderCrashPatcher.hasPrepackagedPackageUrlsHook(patchedPrepackagedLauncherBytes))
            assertTrue(MtsLoaderCrashPatcher.hasPrepackagedPrepareHook(patchedPrepackagedLauncherBytes))
            assertTrue(MtsLoaderCrashPatcher.hasPrepackagedEnumCacheHook(patchedPrepackagedLauncherBytes))
            assertTrue(MtsLoaderCrashPatcher.hasPrepackagedCallInitializersCall(patchedPrepackagedLauncherBytes))

            val patchedAgainBytes = MtsLoaderCrashPatcher.patchLoaderBytes(patchedLoaderBytes)
            assertTrue(patchedAgainBytes.contentEquals(patchedLoaderBytes))
            val patchedPatcherAgainBytes = MtsLoaderCrashPatcher.patchPatcherBytes(patchedPatcherBytes)
            assertTrue(patchedPatcherAgainBytes.contentEquals(patchedPatcherBytes))
            val patchedPackageAgainBytes = MtsLoaderCrashPatcher.patchPackageJarBytes(patchedPackageJarBytes)
            assertTrue(patchedPackageAgainBytes.contentEquals(patchedPackageJarBytes))
            val patchedPrepackagedAgainBytes =
                MtsLoaderCrashPatcher.patchPrepackagedLauncherBytes(patchedPrepackagedLauncherBytes)
            assertTrue(patchedPrepackagedAgainBytes.contentEquals(patchedPrepackagedLauncherBytes))
        } finally {
            tempJar.delete()
        }
    }

    @Test
    fun ensurePatchedMtsJar_upgradesOlderLauncherPatchedJarFromDeviceFixture() {
        val sourceJar = File("agent-tmp/patch-cache-benchmark/device/ModTheSpire.jar")
        org.junit.Assume.assumeTrue("Missing local device ModTheSpire.jar fixture", sourceJar.isFile)

        val tempJar = Files.createTempFile("mts-loader-patch-upgrade-", ".jar").toFile()
        try {
            JarFileIoUtils.copyFileReplacing(sourceJar, tempJar)

            val oldLoaderBytes = JarFileIoUtils.readJarEntryBytes(
                tempJar,
                "com/evacipated/cardcrawl/modthespire/Loader.class"
            )
            requireNotNull(oldLoaderBytes)
            assertFalse(MtsLoaderCrashPatcher.isPatchedLoaderClass(oldLoaderBytes))

            assertTrue(MtsLoaderCrashPatcher.ensurePatchedMtsJar(tempJar))

            val patchedLoaderBytes = JarFileIoUtils.readJarEntryBytes(
                tempJar,
                "com/evacipated/cardcrawl/modthespire/Loader.class"
            )
            requireNotNull(patchedLoaderBytes)
            val patchedPrepackagedLauncherBytes = JarFileIoUtils.readJarEntryBytes(
                tempJar,
                "com/evacipated/cardcrawl/modthespire/PackageJar\$PrepackagedLauncher.class"
            )
            requireNotNull(patchedPrepackagedLauncherBytes)
            val patchedPatcherBytes = JarFileIoUtils.readJarEntryBytes(
                tempJar,
                "com/evacipated/cardcrawl/modthespire/Patcher.class"
            )
            requireNotNull(patchedPatcherBytes)
            val patchedPackageJarBytes = JarFileIoUtils.readJarEntryBytes(
                tempJar,
                "com/evacipated/cardcrawl/modthespire/PackageJar.class"
            )
            requireNotNull(patchedPackageJarBytes)
            assertTrue(MtsLoaderCrashPatcher.isPatchedLoaderClass(patchedLoaderBytes))
            assertTrue(MtsLoaderCrashPatcher.isPatchedPatcherClass(patchedPatcherBytes))
            assertTrue(MtsLoaderCrashPatcher.isPatchedPackageJarClass(patchedPackageJarBytes))
            assertTrue(MtsLoaderCrashPatcher.hasPackageDirOverride(patchedPackageJarBytes))
            assertTrue(MtsLoaderCrashPatcher.hasPackageJarFastPathHook(patchedPackageJarBytes))
            assertTrue(MtsLoaderCrashPatcher.hasOutJarPrimingHook(patchedLoaderBytes))
            assertTrue(MtsLoaderCrashPatcher.hasPatchCacheStoreHookWithCompiledClassPathArg(patchedLoaderBytes))
            assertTrue(MtsLoaderCrashPatcher.hasCloseWindowNullGuard(patchedLoaderBytes))
            assertTrue(MtsLoaderCrashPatcher.isPatchedPrepackagedLauncherClass(patchedPrepackagedLauncherBytes))
            assertTrue(MtsLoaderCrashPatcher.hasPrepackagedPackageUrlsHook(patchedPrepackagedLauncherBytes))
            assertTrue(MtsLoaderCrashPatcher.hasPrepackagedEnumCacheHook(patchedPrepackagedLauncherBytes))
            assertTrue(MtsLoaderCrashPatcher.hasPrepackagedCallInitializersCall(patchedPrepackagedLauncherBytes))
        } finally {
            tempJar.delete()
        }
    }

    @Test
    fun patchLoaderBytes_insertsOutJarPrimingBeforeCacheStoreHook() {
        val sourceJar = sequenceOf(
            File("src/main/assets/components/mods/ModTheSpire.jar"),
            File("app/src/main/assets/components/mods/ModTheSpire.jar")
        ).firstOrNull { it.isFile }
            ?: error("Missing test fixture jar: ModTheSpire.jar")

        val originalLoaderBytes = JarFileIoUtils.readJarEntryBytes(
            sourceJar,
            "com/evacipated/cardcrawl/modthespire/Loader.class"
        )
        requireNotNull(originalLoaderBytes)

        val patchedLoaderBytes = MtsLoaderCrashPatcher.patchLoaderBytes(originalLoaderBytes)
        assertTrue(MtsLoaderCrashPatcher.hasOutJarPrimingHook(patchedLoaderBytes))
    }

    @Test
    fun patchLoaderBytes_wrapsCompilePatchesWithCacheCaptureHooks() {
        val sourceJar = sequenceOf(
            File("src/main/assets/components/mods/ModTheSpire.jar"),
            File("app/src/main/assets/components/mods/ModTheSpire.jar")
        ).firstOrNull { it.isFile }
            ?: error("Missing test fixture jar: ModTheSpire.jar")

        val originalLoaderBytes = JarFileIoUtils.readJarEntryBytes(
            sourceJar,
            "com/evacipated/cardcrawl/modthespire/Loader.class"
        )
        requireNotNull(originalLoaderBytes)

        val patchedLoaderBytes = MtsLoaderCrashPatcher.patchLoaderBytes(originalLoaderBytes)
        assertTrue(MtsLoaderCrashPatcher.hasOutJarPrimingHook(patchedLoaderBytes))
    }

    @Test
    fun patchPatcherBytes_skipsOptionalMissingMethodFailures() {
        val sourceJar = sequenceOf(
            File("src/main/assets/components/mods/ModTheSpire.jar"),
            File("app/src/main/assets/components/mods/ModTheSpire.jar")
        ).firstOrNull { it.isFile }
            ?: error("Missing test fixture jar: ModTheSpire.jar")

        val originalPatcherBytes = JarFileIoUtils.readJarEntryBytes(
            sourceJar,
            "com/evacipated/cardcrawl/modthespire/Patcher.class"
        )
        requireNotNull(originalPatcherBytes)
        assertFalse(MtsLoaderCrashPatcher.isPatchedPatcherClass(originalPatcherBytes))

        val patchedPatcherBytes = MtsLoaderCrashPatcher.patchPatcherBytes(originalPatcherBytes)
        assertTrue(MtsLoaderCrashPatcher.isPatchedPatcherClass(patchedPatcherBytes))
    }

    @Test
    fun patchLoaderBytes_guardsCloseWindowWhenLoaderWindowIsMissing() {
        val sourceJar = sequenceOf(
            File("src/main/assets/components/mods/ModTheSpire.jar"),
            File("app/src/main/assets/components/mods/ModTheSpire.jar")
        ).firstOrNull { it.isFile }
            ?: error("Missing test fixture jar: ModTheSpire.jar")

        val originalLoaderBytes = JarFileIoUtils.readJarEntryBytes(
            sourceJar,
            "com/evacipated/cardcrawl/modthespire/Loader.class"
        )
        requireNotNull(originalLoaderBytes)
        assertFalse(MtsLoaderCrashPatcher.hasCloseWindowNullGuard(originalLoaderBytes))

        val patchedLoaderBytes = MtsLoaderCrashPatcher.patchLoaderBytes(originalLoaderBytes)
        assertTrue(MtsLoaderCrashPatcher.hasCloseWindowNullGuard(patchedLoaderBytes))
    }

    @Test
    fun ensurePatchedMtsJar_upgradesCurrentStartupHooksWithOriginalSwingConsole() {
        val sourceJar = sequenceOf(
            File("src/main/assets/components/mods/ModTheSpire.jar"),
            File("app/src/main/assets/components/mods/ModTheSpire.jar")
        ).firstOrNull { it.isFile } ?: error("Missing test fixture jar: ModTheSpire.jar")
        val originalConsoleBytes = requireNotNull(
            JarFileIoUtils.readJarEntryBytes(sourceJar, MtsConsoleLogPatcher.MESSAGE_CONSOLE_CLASS_ENTRY)
        )
        val tempJar = Files.createTempFile("mts-console-patch-upgrade-", ".jar").toFile()
        val olderJar = Files.createTempFile("mts-console-patch-older-", ".jar").toFile()
        try {
            JarFileIoUtils.copyFileReplacing(sourceJar, tempJar)
            assertTrue(MtsLoaderCrashPatcher.ensurePatchedMtsJar(tempJar))
            // Model an installed jar whose existing startup/cache fixes are current, but whose
            // console predates this fix. It must not be skipped by the jar-level idempotency check.
            ZipFile(tempJar).use { zipFile ->
                ZipOutputStream(olderJar.outputStream()).use { zipOut ->
                    for (entry in zipFile.entries().asSequence()) {
                        zipOut.putNextEntry(ZipEntry(entry.name))
                        if (entry.name == MtsConsoleLogPatcher.MESSAGE_CONSOLE_CLASS_ENTRY) {
                            zipOut.write(originalConsoleBytes)
                        } else {
                            zipFile.getInputStream(entry).use { JarFileIoUtils.copyStream(it, zipOut) }
                        }
                        zipOut.closeEntry()
                    }
                }
            }
            val oldLoaderBytes = requireNotNull(
                JarFileIoUtils.readJarEntryBytes(olderJar, "com/evacipated/cardcrawl/modthespire/Loader.class")
            )
            assertTrue(MtsLoaderCrashPatcher.isPatchedLoaderClass(oldLoaderBytes))
            assertTrue(MtsLoaderCrashPatcher.ensurePatchedMtsJar(olderJar))
            assertTrue(
                requireNotNull(
                    JarFileIoUtils.readJarEntryBytes(olderJar, "com/evacipated/cardcrawl/modthespire/Loader.class")
                ).contentEquals(oldLoaderBytes)
            )
            assertTrue(
                MtsConsoleLogPatcher.isPatchedMessageConsoleClass(
                    requireNotNull(
                        JarFileIoUtils.readJarEntryBytes(olderJar, MtsConsoleLogPatcher.MESSAGE_CONSOLE_CLASS_ENTRY)
                    )
                )
            )
            assertFalse(MtsLoaderCrashPatcher.ensurePatchedMtsJar(olderJar))
        } finally {
            tempJar.delete()
            olderJar.delete()
        }
    }

    @Test
    fun ensurePatchedMtsJar_returnsFalseWhenJarIsAlreadyCurrent() {
        val sourceJar = sequenceOf(
            File("src/main/assets/components/mods/ModTheSpire.jar"),
            File("app/src/main/assets/components/mods/ModTheSpire.jar")
        ).firstOrNull { it.isFile }
            ?: error("Missing test fixture jar: ModTheSpire.jar")

        val tempJar = Files.createTempFile("mts-loader-patch-current-", ".jar").toFile()
        try {
            JarFileIoUtils.copyFileReplacing(sourceJar, tempJar)

            assertTrue(MtsLoaderCrashPatcher.ensurePatchedMtsJar(tempJar))
            val firstModified = tempJar.lastModified()

            Thread.sleep(5)

            assertFalse(MtsLoaderCrashPatcher.ensurePatchedMtsJar(tempJar))
            assertEquals(firstModified, tempJar.lastModified())
        } finally {
            tempJar.delete()
        }
    }
}
