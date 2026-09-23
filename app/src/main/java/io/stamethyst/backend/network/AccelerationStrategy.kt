package io.stamethyst.backend.network

/**
 * Selects how accelerated links pick their upstream hop.
 *
 * Both strategies share the same request pipeline and official fallback; they only
 * differ in how the forward hop for a logical host is chosen.
 */
enum class AccelerationStrategy(val persistedValue: String) {
    /**
     * Bundled rmbgame.net hop first, official origin as the fallback.
     *
     * No Watt Toolkit rule discovery and no latency probing: the route configured in
     * the route profile is used directly and a failure within the same request walks
     * over to the official origin. Route profiles without a bundled hop keep using
     * [BEST_PATH] so those links lose no acceleration.
     */
    RMBGAME_FIRST("rmbgame_first"),

    /**
     * Legacy behaviour: fetch Watt Toolkit rules, probe every candidate hop and rank
     * them by success rate/latency, then fall back to the official origin.
     */
    BEST_PATH("best_path");

    companion object {
        val DEFAULT: AccelerationStrategy = RMBGAME_FIRST

        fun fromPersistedValue(value: String?): AccelerationStrategy? {
            if (value.isNullOrBlank()) {
                return null
            }
            val normalized = value.trim()
            return entries.firstOrNull { it.persistedValue == normalized }
        }
    }
}
