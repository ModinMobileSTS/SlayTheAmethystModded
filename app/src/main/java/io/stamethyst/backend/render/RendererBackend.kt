package io.stamethyst.backend.render

enum class RendererBackend(
    private val rendererIdValue: String
) {
    OPENGL_ES2("opengles2");

    fun rendererId(): String = rendererIdValue

    fun lwjglOpenGlLibName(): String = "libGLESv2.so"
}
