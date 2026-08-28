package io.stamethyst.backend.launch

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentInstallerLwjglBridgeTest {
    @Test
    fun removeLegacyMarketNatives_removesNestedLibgdxVideoCopiesOnly() {
        val root = Files.createTempDirectory("legacy-market-natives-").toFile()
        try {
            val nested = File(root, "historical/arm64").apply { mkdirs() }
            val legacy = File(nested, "libgdx-video-desktoparm64.so").apply {
                writeText("legacy")
            }
            val retained = File(nested, "libjnitensorflow.so").apply {
                writeText("retained")
            }

            ComponentInstaller.removeLegacyMarketNatives(root)

            assertFalse(legacy.exists())
            assertTrue(retained.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun lwjglBridgeVersionCheck_rejectsStalePersistentBridge() {
        val root = Files.createTempDirectory("lwjgl-bridge-version-").toFile()
        try {
            val versionFile = writeFile(root, "version", "old-contract")
            val jarFile = writeFile(root, "lwjgl-glfw-classes.jar", "old bridge")

            assertFalse(
                ComponentInstaller.isLwjglBridgeVersionCurrent(
                    expectedVersion = "new-contract",
                    installedVersionFile = versionFile,
                    installedJar = jarFile
                )
            )

            versionFile.writeText("new-contract", StandardCharsets.UTF_8)

            assertTrue(
                ComponentInstaller.isLwjglBridgeVersionCurrent(
                    expectedVersion = "new-contract",
                    installedVersionFile = versionFile,
                    installedJar = jarFile
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun lwjglBridgeVersionCheck_requiresNonEmptyBridgeJarAndVersion() {
        val root = Files.createTempDirectory("lwjgl-bridge-version-missing-").toFile()
        try {
            val versionFile = writeFile(root, "version", "current-contract")
            val jarFile = File(root, "lwjgl-glfw-classes.jar")
            jarFile.createNewFile()

            assertFalse(
                ComponentInstaller.isLwjglBridgeVersionCurrent(
                    expectedVersion = "current-contract",
                    installedVersionFile = versionFile,
                    installedJar = jarFile
                )
            )
            assertFalse(
                ComponentInstaller.isLwjglBridgeVersionCurrent(
                    expectedVersion = null,
                    installedVersionFile = versionFile,
                    installedJar = writeFile(root, "valid.jar", "bridge")
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun hotfix3Remediation_detectsTheBadV158LwjglBridgeVersion() {
        assertTrue(
            ComponentInstaller.isAffectedLwjglBridgeVersion(
                "18bc219264fdc7621d8ff1966c85df9172cffc27"
            )
        )
        assertFalse(
            ComponentInstaller.isAffectedLwjglBridgeVersion(
                "fb99c2f281d742efda85bcbd3d3ce18b8c25ac5c"
            )
        )
        assertFalse(ComponentInstaller.isAffectedLwjglBridgeVersion(null))
    }

    @Test
    fun streamComparison_detectsStaleCriticalComponentBytes() {
        assertTrue(
            ComponentInstaller.streamsHaveSameBytes(
                ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                ByteArrayInputStream(byteArrayOf(1, 2, 3))
            )
        )
        assertFalse(
            ComponentInstaller.streamsHaveSameBytes(
                ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                ByteArrayInputStream(byteArrayOf(1, 2, 4))
            )
        )
        assertFalse(
            ComponentInstaller.streamsHaveSameBytes(
                ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                ByteArrayInputStream(byteArrayOf(1, 2))
            )
        )
    }

    private fun writeFile(root: File, name: String, contents: String): File {
        return File(root, name).also { file ->
            file.writeText(contents, StandardCharsets.UTF_8)
        }
    }
}
