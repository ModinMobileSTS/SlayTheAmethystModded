package io.stamethyst.backend.render

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererBackendTest {
    @Test
    fun fromRendererId_mapsLegacyLtwPreferenceToGl4es() {
        assertEquals(
            RendererBackend.OPENGL_ES2_GL4ES,
            RendererBackend.fromRendererId("opengles3_ltw")
        )
    }

    @Test
    fun supportsSwappyFramePacing_onlyForSystemEglBackends() {
        assertEquals(true, RendererBackend.OPENGL_ES2_NATIVE.supportsSwappyFramePacing)
        assertEquals(true, RendererBackend.OPENGL_ES2_GL4ES.supportsSwappyFramePacing)
        assertEquals(false, RendererBackend.OPENGL_ES_MOBILEGLUES.supportsSwappyFramePacing)
        assertEquals(false, RendererBackend.OPENGL_ES3_DESKTOPGL_ZINK_KOPPER.supportsSwappyFramePacing)
        assertEquals(false, RendererBackend.VULKAN_ZINK.supportsSwappyFramePacing)
    }
}
