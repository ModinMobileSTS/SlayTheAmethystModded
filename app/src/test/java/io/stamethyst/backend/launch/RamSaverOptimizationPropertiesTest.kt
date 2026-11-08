package io.stamethyst.backend.launch

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RamSaverOptimization
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class RamSaverOptimizationPropertiesTest {
    @Test fun defaultsKeepBothOptimizationsAndVisualDebugDisabled() {
        assertEquals(
            listOf("-Dramsaver.release.shared_budget_enabled=false", "-Dramsaver.decode.enabled=false", "-Dramsaver.debug.visual=false"),
            StsLaunchSpec.ramSaverOptimizationArgs(emptyMap()),
        )
        assertEquals(
            setOf("ram_saver_optimization_shared_release_budget", "ram_saver_optimization_background_decode", "ram_saver_optimization_visual_release_debug"),
            RamSaverOptimization.entries.map { it.preferenceKey }.toSet(),
        )
    }

    @Test fun everyCombinationProducesIndependentExplicitProperties() {
        for (mask in 0..7) {
            val selections = RamSaverOptimization.entries.mapIndexed { i, option -> option to (mask and (1 shl i) != 0) }.toMap()
            val args = StsLaunchSpec.ramSaverOptimizationArgs(selections)
            assertEquals(3, args.size)
            selections.forEach { (option, enabled) -> assertTrue(args.contains("-D${option.propertyName}=$enabled")) }
        }
    }

    @Test fun persistedSwitchesRoundTripIndependentlyIntoLaunchProperties() {
        val stores = mutableMapOf<String, MutableMap<String, Boolean>>()
        fun context(): Context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val values = stores.getOrPut(name) { mutableMapOf() }
                return Proxy.newProxyInstance(
                    SharedPreferences::class.java.classLoader,
                    arrayOf(SharedPreferences::class.java),
                ) { _, method, args ->
                    when (method.name) {
                        "getBoolean" -> values[args!![0] as String] ?: args[1]
                        "edit" -> {
                            val pending = mutableMapOf<String, Boolean>()
                            Proxy.newProxyInstance(
                                SharedPreferences.Editor::class.java.classLoader,
                                arrayOf(SharedPreferences.Editor::class.java),
                            ) { editor, edit, arguments ->
                                when (edit.name) {
                                    "putBoolean" -> {
                                        pending[arguments!![0] as String] = arguments[1] as Boolean
                                        editor
                                    }
                                    "apply" -> { values.putAll(pending); null }
                                    else -> error("Unexpected preferences edit: ${edit.name}")
                                }
                            }
                        }
                        else -> error("Unexpected preferences read: ${method.name}")
                    }
                } as SharedPreferences
            }
        }

        assertEquals(RamSaverOptimization.entries.associateWith { it.defaultEnabled },
            LauncherConfig.readRamSaverOptimizations(context()))
        // A leftover preference from the removed experiment must not affect either retained switch.
        stores.values.single()["ram_saver_optimization_unified_pressure"] = false
        assertEquals(
            listOf("-Dramsaver.release.shared_budget_enabled=false", "-Dramsaver.decode.enabled=false", "-Dramsaver.debug.visual=false"),
            StsLaunchSpec.ramSaverOptimizationArgs(LauncherConfig.readRamSaverOptimizations(context())),
        )
        val expected = RamSaverOptimization.entries.associateWith { it.defaultEnabled }.toMutableMap()
        for (enabled in listOf(false, true)) {
            for (option in RamSaverOptimization.entries) {
                LauncherConfig.setRamSaverOptimization(context(), option, enabled)
                expected[option] = enabled
                val restored = LauncherConfig.readRamSaverOptimizations(context())
                assertEquals("Saving one switch must preserve the other", expected, restored)
                assertTrue(StsLaunchSpec.ramSaverOptimizationArgs(restored).contains("-D${option.propertyName}=$enabled"))
            }
        }
    }
}
