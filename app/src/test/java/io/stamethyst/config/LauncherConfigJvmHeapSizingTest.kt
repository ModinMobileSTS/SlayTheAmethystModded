package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherConfigJvmHeapSizingTest {
    @Test
    fun resolveJvmHeapStartMb_keepsRequestedHeap_whenWithinDefaultStartupBudget() {
        assertEquals(256, LauncherConfig.resolveJvmHeapStartMb(256))
        assertEquals(512, LauncherConfig.resolveJvmHeapStartMb(512))
    }

    @Test
    fun resolveJvmHeapStartMb_capsStartupHeap_whenMaxHeapIsRaised() {
        assertEquals(512, LauncherConfig.resolveJvmHeapStartMb(1024))
        assertEquals(512, LauncherConfig.resolveJvmHeapStartMb(2048))
    }

    @Test
    fun resolveJvmHeapStartMb_usesNormalizedHeapSteps() {
        assertEquals(512, LauncherConfig.resolveJvmHeapStartMb(1900))
    }
}
