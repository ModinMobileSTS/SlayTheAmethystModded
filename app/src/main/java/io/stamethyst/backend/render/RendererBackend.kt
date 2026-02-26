package io.stamethyst.backend.render

enum class RendererBackend(
    private val rendererIdValue: String,
    private val selectorLabelValue: String,
    private val statusLabelValue: String
) {
    OPENGL_ES2("opengles2", "OpenGL ES2 (default)", "OpenGL ES2"),
    ANGLE("opengles2_angle", "ANGLE (OpenGL ES2)", "ANGLE"),
    MOBILEGLUES("opengles3_mobileglues", "MobileGlues (OpenGL ES3)", "MobileGlues"),
    KOPPER_ZINK("opengles3_desktopgl_zink_kopper", "Mesa Zink (Kopper)", "Mesa Zink (Kopper)");

    fun rendererId(): String = rendererIdValue

    fun selectorLabel(): String = selectorLabelValue

    fun statusLabel(): String = statusLabelValue

    fun lwjglOpenGlLibName(): String {
        return when (this) {
            KOPPER_ZINK -> "libglxshim.so"
            ANGLE -> "libGLESv2_angle.so"
            MOBILEGLUES -> "libmobileglues.so"
            OPENGL_ES2 -> "libGLESv2.so"
        }
    }

    companion object {
        @JvmStatic
        fun fromRendererId(value: String?): RendererBackend {
            if (value != null) {
                val normalized = value.trim()
                for (backend in entries) {
                    if (backend.rendererIdValue.equals(normalized, ignoreCase = true)) {
                        return backend
                    }
                }
            }
            return OPENGL_ES2
        }
    }
}
