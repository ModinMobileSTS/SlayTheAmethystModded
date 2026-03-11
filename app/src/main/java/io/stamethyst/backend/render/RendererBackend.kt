package io.stamethyst.backend.render

import io.stamethyst.config.RenderSurfaceBackend

enum class RendererBackend(
    private val rendererIdValue: String,
    val displayName: String,
    private val lwjglOpenGlLibNameValue: String,
    val requiredNativeLibraries: Set<String>,
    val requiresVulkan: Boolean = false,
    val forcedSurfaceBackend: RenderSurfaceBackend? = null
) {
    OPENGL_ES_MOBILEGLUES(
        rendererIdValue = "opengles_mobileglues",
        displayName = "MobileGlues",
        lwjglOpenGlLibNameValue = "libmobileglues.so",
        requiredNativeLibraries = setOf("libmobileglues.so")
    ),
    OPENGL_ES2_NATIVE(
        rendererIdValue = "opengles2_native",
        displayName = "OpenGL ES 2",
        lwjglOpenGlLibNameValue = "libGLESv2.so",
        requiredNativeLibraries = emptySet()
    ),
    OPENGL_ES2_GL4ES(
        rendererIdValue = "opengles2",
        displayName = "GL4ES",
        lwjglOpenGlLibNameValue = "libgl4es_114.so",
        requiredNativeLibraries = setOf("libgl4es_114.so")
    ),
    OPENGL_ES3_DESKTOPGL_ZINK_KOPPER(
        rendererIdValue = "opengles3_desktopgl_zink_kopper",
        displayName = "Kopper Zink",
        lwjglOpenGlLibNameValue = "libglxshim.so",
        requiredNativeLibraries = setOf(
            "libc++_shared.so",
            "libglxshim.so",
            "libEGL_mesa.so",
            "libglapi.so",
            "libzink_dri.so"
        ),
        requiresVulkan = true,
        forcedSurfaceBackend = RenderSurfaceBackend.TEXTURE_VIEW
    ),
    VULKAN_ZINK(
        rendererIdValue = "vulkan_zink",
        displayName = "Vulkan Zink",
        lwjglOpenGlLibNameValue = "libOSMesa.so",
        requiredNativeLibraries = setOf(
            "libc++_shared.so",
            "libOSMesa.so"
        ),
        requiresVulkan = true
    );

    fun rendererId(): String = rendererIdValue

    fun lwjglOpenGlLibName(): String = lwjglOpenGlLibNameValue

    companion object {
        @JvmStatic
        fun fromRendererId(value: String?): RendererBackend? {
            if (value.isNullOrBlank()) {
                return null
            }
            return entries.firstOrNull { it.rendererIdValue == value.trim() }
        }
    }
}
