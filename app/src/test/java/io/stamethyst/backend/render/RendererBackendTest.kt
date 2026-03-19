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
}
