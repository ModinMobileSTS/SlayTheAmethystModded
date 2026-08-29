package io.stamethyst.backend.launch

import android.content.Context
import android.util.Log
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * Disables the ModTheSpire "imgui" (LWJGL3) launch flag before the game JVM starts.
 *
 * MTS reads `imgui=true` from its config file (`ModTheSpire.properties`) and, when set,
 * boots the game through the LWJGL3 desktop backend. Amethyst's LWJGL3 bridge
 * (`lwjgl-glfw-classes.jar`) is incomplete and reliably crashes while creating the
 * window (`BufferOverflowException` in `GLFW.glfwGetWindowSize`, the "stuck at 96%"
 * symptom). Some mods (for example Mega Transparent The Spire) flip this flag on and
 * persist it, breaking every subsequent launch even after the mod is disabled.
 *
 * Amethyst does not support the MTS LWJGL3 mode, so the flag is force-cleared here on
 * every launch. The runtime guard in [io.stamethyst.backend.mods.MtsLoaderCrashPatcher]
 * additionally forces `Loader.LWJGL3_ENABLED = false` as a second line of defense.
 */
object MtsImguiGuard {

    private const val TAG = "MtsImguiGuard"

    private const val CONFIG_FILE_NAME = "ModTheSpire.properties"
    private const val IMGUI_KEY = "imgui"

    /**
     * Clears the `imgui` flag in the MTS config file so the game never boots through the
     * unsupported LWJGL3 path. Best-effort: failures are logged and never block startup.
     */
    @JvmStatic
    fun disableImguiIfEnabled(context: Context) {
        val configFile = File(RuntimePaths.modTheSpireConfigDir(context), CONFIG_FILE_NAME)
        if (!configFile.isFile) {
            return
        }
        try {
            val properties = Properties()
            configFile.inputStream().use { input ->
                properties.load(input)
            }
            val imguiValue = properties.getProperty(IMGUI_KEY)?.trim()
            if (!imguiValue.equals("true", ignoreCase = true)) {
                return
            }
            properties.remove(IMGUI_KEY)
            configFile.outputStream().use { output ->
                properties.store(output, null)
            }
            Log.i(TAG, "Disabled ModTheSpire imgui (LWJGL3) flag at ${configFile.absolutePath}")
        } catch (error: IOException) {
            Log.w(TAG, "Failed to clear MTS imgui flag at ${configFile.absolutePath}", error)
        }
    }
}
