package io.stamethyst.backend.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeAudioRecoveryProtocolTest {
    private val nativeSource = File("src/main/jni/input_bridge_v3.c").readText()

    @Test
    fun recoveryRequestIsExplicitAndPollDoesNotCreateStaleRequests() {
        assertTrue(nativeSource.contains("nativeRequestAudioRecovery"))
        assertTrue(nativeSource.contains("if (requestedGeneration > completedGeneration)"))

        val pollFunction = nativeSource.substringAfter(
            "JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeRecoverAudioOutput"
        ).substringBefore("JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeRequestAudioRecovery")
        assertFalse(pollFunction.contains("queueAudioRecoveryCommand();"))
    }

    @Test
    fun openalResolutionCanRetryAfterAnEarlyFailure() {
        assertFalse(nativeSource.contains("pojav_openal_resolution_attempted"))
        assertTrue(nativeSource.contains("resolveOpenalSymbolsFromHandle(RTLD_DEFAULT);"))
        assertTrue(nativeSource.contains("pojav_openal_handle = dlopen(\"libopenal.so\""))
    }
}
