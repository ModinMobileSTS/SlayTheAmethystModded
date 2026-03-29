package io.stamethyst.backend.launch

import org.junit.Assert.assertEquals
import org.junit.Test

class AdditionalNativeLibraryPreloaderTest {
    @Test
    fun buildCompatibilityAliasPlan_addsMissingTensorFlowShortNames() {
        val aliases = AdditionalNativeLibraryPreloader.buildCompatibilityAliasPlan(
            setOf(
                "libtensorflow_framework.so.2",
                "libtensorflow.so.2",
                "libjnitensorflow.so"
            )
        )

        assertEquals(
            linkedMapOf(
                "libtensorflow_framework.so" to "libtensorflow_framework.so.2",
                "libtensorflow.so" to "libtensorflow.so.2"
            ),
            aliases
        )
    }

    @Test
    fun buildCompatibilityAliasPlan_skipsAliasesThatAlreadyExist() {
        val aliases = AdditionalNativeLibraryPreloader.buildCompatibilityAliasPlan(
            setOf(
                "libtensorflow_framework.so.2",
                "libtensorflow_framework.so",
                "libtensorflow.so.2",
                "libtensorflow.so"
            )
        )

        assertEquals(emptyMap<String, String>(), aliases)
    }
}
