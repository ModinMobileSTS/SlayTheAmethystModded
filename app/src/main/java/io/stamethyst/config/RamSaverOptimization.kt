package io.stamethyst.config

/** Independent runtime switches. Requires a new game JVM, not a patch-cache rebuild. */
enum class RamSaverOptimization(val propertyName: String, val defaultEnabled: Boolean = false) {
    SHARED_RELEASE_BUDGET("ramsaver.release.shared_budget_enabled"),
    BACKGROUND_DECODE("ramsaver.decode.enabled"),
    VISUAL_RELEASE_DEBUG("ramsaver.debug.visual", false);

    val preferenceKey: String get() = "ram_saver_optimization_${name.lowercase(java.util.Locale.ROOT)}"
}
