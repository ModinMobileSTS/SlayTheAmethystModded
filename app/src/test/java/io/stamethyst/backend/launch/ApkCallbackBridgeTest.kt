package io.stamethyst.backend.launch

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkCallbackBridgeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val callbackJar = File(requireNotNull(System.getProperty("sts.callbackBridgeJar")))
    private val runtimeJar = File(requireNotNull(System.getProperty("sts.lwjglRuntimeJar")))
    private val legacyJar = File(requireNotNull(System.getProperty("sts.legacyLwjglJar")))
    private val bridgeName = "org.lwjgl.glfw.CallbackBridge"

    @Test fun legacyResourceRuntimeReproducesMissingAudioContract() {
        URLClassLoader(arrayOf(legacyJar.toURI().toURL()), null).use { loader ->
            val bridge = loader.loadClass(bridgeName)
            assertFalse(bridge.declaredMethods.any { it.name == "nativeSetAudioRuntimeState" })
            assertThrows(NoSuchMethodException::class.java) {
                bridge.getMethod("nativeGetAudioOperationSnapshot")
            }
        }
    }

    @Test fun apkBridgeWinsOverOldResourcePackForBothLaunchClasspaths() {
        val installed = File(temporary.root, "callback_bridge/callback-bridge.jar")
        ComponentInstaller.copyFile(installed) { callbackJar.inputStream() }
        val boot = File(temporary.root, "boot-bridge.jar").absolutePath
        val game = File(temporary.root, "desktop.jar").absolutePath
        val vanilla = listOf(boot, "gdx-patch.jar", legacyJar.absolutePath, game)
        val mts = listOf(boot, legacyJar.absolutePath, "mts-gdx-api.jar", "ModTheSpire.jar")
        for (entries in listOf(vanilla, mts)) {
            val classpath = StsLaunchSpec.withApkCallbackBridge(installed, entries)
            URLClassLoader(classpath.split(':').map { File(it).toURI().toURL() }.toTypedArray(), null).use { loader ->
                val bridge = loader.loadClass(bridgeName)
                assertEquals(installed.toURI().toURL(), bridge.protectionDomain!!.codeSource.location)
                val state = bridge.getMethod("nativeSetAudioRuntimeState", Int::class.javaPrimitiveType)
                assertEquals(Void.TYPE, state.returnType)
                assertTrue(Modifier.isStatic(state.modifiers) && Modifier.isNative(state.modifiers))
                assertEquals(Long::class.javaPrimitiveType, bridge.getMethod("nativeGetAudioOperationSnapshot").returnType)
                assertEquals(Boolean::class.javaPrimitiveType, bridge.getMethod("nativeHasQueuedAudioCommands").returnType)
                assertEquals(Void.TYPE, bridge.getMethod("nativeProcessQueuedAudioCommands").returnType)
                // Other LWJGL classes still come from the unchanged resource runtime.
                assertEquals(legacyJar.toURI().toURL(), loader.loadClass("org.lwjgl.openal.AL10")
                    .protectionDomain!!.codeSource.location)
            }
        }
    }

    @Test fun splitArtifactsContainOneApkBridgeAndKeepOpenalExternal() {
        ZipFile(callbackJar).use { zip ->
            assertEquals(listOf("org/lwjgl/glfw/CallbackBridge.class"), zip.entries().asSequence()
                .filter { !it.isDirectory }.map { it.name }.toList())
        }
        ZipFile(runtimeJar).use { zip ->
            assertNull(zip.getEntry("org/lwjgl/glfw/CallbackBridge.class"))
            assertNotNull(zip.getEntry("org/lwjgl/openal/AL10.class"))
        }
        URLClassLoader(arrayOf(callbackJar.toURI().toURL(), runtimeJar.toURI().toURL()), null).use { loader ->
            assertEquals(callbackJar.toURI().toURL(), loader.loadClass(bridgeName).protectionDomain!!.codeSource.location)
        }
    }

    @Test fun contentCheckRepairsStaleCorruptAndMissingBridgeWithoutResourceVersionChange() {
        val installed = File(temporary.root, "callback-bridge.jar")
        val openApk = { callbackJar.inputStream() }
        assertFalse(ComponentInstaller.isComponentFileCurrent(installed, openApk))
        installed.writeBytes(byteArrayOf())
        assertFalse(ComponentInstaller.isComponentFileCurrent(installed, openApk))
        installed.writeText("old APK bridge")
        assertFalse(ComponentInstaller.isComponentFileCurrent(installed, openApk))
        ComponentInstaller.copyFile(installed, openApk)
        assertTrue(ComponentInstaller.isComponentFileCurrent(installed, openApk))
        val corrupt = installed.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        installed.writeBytes(corrupt)
        assertFalse(ComponentInstaller.isComponentFileCurrent(installed, openApk))
        ComponentInstaller.copyFile(installed, openApk)
        assertTrue(ComponentInstaller.isComponentFileCurrent(installed, openApk))
        assertFalse(ComponentInstaller.isComponentFileCurrent(installed) { throw IOException("missing APK asset") })
    }

    @Test fun failedPartialCopyPreservesPreviousBridgeAndRemovesStagingFiles() {
        val installed = File(temporary.root, "callback-bridge.jar")
        installed.writeText("previous bridge")
        assertThrows(IOException::class.java) {
            ComponentInstaller.copyFile(installed) {
                object : InputStream() {
                    private var reads = 0
                    override fun read(): Int = throw IOException("read failed")
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        if (reads++ > 0) throw IOException("read failed after partial copy")
                        bytes[offset] = 42
                        return 1
                    }
                }
            }
        }
        assertEquals("previous bridge", installed.readText())
        assertEquals(listOf("callback-bridge.jar"), temporary.root.list()!!.toList())
    }

    @Test fun missingApkBridgeCannotSilentlyFallBackToOldResourceClass() {
        val missing = File(temporary.root, "missing.jar")
        assertThrows(IllegalStateException::class.java) {
            StsLaunchSpec.withApkCallbackBridge(missing, listOf(legacyJar.absolutePath))
        }
        missing.writeBytes(byteArrayOf())
        assertThrows(IllegalStateException::class.java) {
            StsLaunchSpec.withApkCallbackBridge(missing, listOf(legacyJar.absolutePath))
        }
    }
}
