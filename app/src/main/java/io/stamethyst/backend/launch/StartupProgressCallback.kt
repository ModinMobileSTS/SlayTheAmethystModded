package io.stamethyst.backend.launch

fun interface StartupProgressCallback {
    fun onProgress(percent: Int, message: String)
}
