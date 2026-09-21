package io.stamethyst.backend.mods

import android.content.Context

/**
 * Java-callable view of the smoke-test run marker.
 *
 * `ExitActivity` is Java and sits outside the Kotlin-internal surface, so it needs a plain static
 * entry point to ask whether an invisible smoke-test session owns the current JVM exit.
 */
object AgentPatchSmokeTestRunGuard {
    @JvmStatic
    fun isRunActive(context: Context): Boolean = AgentPatchSmokeTestProtocol.isRunActive(context)
}
