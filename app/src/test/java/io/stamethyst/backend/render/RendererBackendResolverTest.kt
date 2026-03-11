package io.stamethyst.backend.render

import io.stamethyst.config.RenderSurfaceBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererBackendResolverTest {
    @Test
    fun resolve_autoModePrefersFirstAvailableBackend() {
        val decision = RendererBackendResolver.resolve(
            requestedSurfaceBackend = RenderSurfaceBackend.SURFACE_VIEW,
            selectionMode = RendererSelectionMode.AUTO,
            manualBackend = null,
            runtimeInfo = runtimeInfo(
                libraries = setOf("libmobileglues.so", "libgl4es_114.so")
            )
        )

        assertEquals(RendererBackend.OPENGL_ES_MOBILEGLUES, decision.automaticBackend)
        assertEquals(RendererBackend.OPENGL_ES_MOBILEGLUES, decision.effectiveBackend)
    }

    @Test
    fun resolve_manualModeUsesRequestedBackendWhenAvailable() {
        val decision = RendererBackendResolver.resolve(
            requestedSurfaceBackend = RenderSurfaceBackend.SURFACE_VIEW,
            selectionMode = RendererSelectionMode.MANUAL,
            manualBackend = RendererBackend.OPENGL_ES2_GL4ES,
            runtimeInfo = runtimeInfo(
                libraries = setOf("libmobileglues.so", "libgl4es_114.so")
            )
        )

        assertEquals(RendererBackend.OPENGL_ES2_GL4ES, decision.effectiveBackend)
        assertFalse(decision.usedAutomaticFallback)
    }

    @Test
    fun resolve_manualModeUsesNativeOpengles2WithoutPackagedLibraries() {
        val decision = RendererBackendResolver.resolve(
            requestedSurfaceBackend = RenderSurfaceBackend.SURFACE_VIEW,
            selectionMode = RendererSelectionMode.MANUAL,
            manualBackend = RendererBackend.OPENGL_ES2_NATIVE,
            runtimeInfo = runtimeInfo(libraries = emptySet())
        )

        assertEquals(RendererBackend.OPENGL_ES2_NATIVE, decision.effectiveBackend)
        assertFalse(decision.usedAutomaticFallback)
    }

    @Test
    fun resolve_manualUnavailableFallsBackToAutoSelection() {
        val decision = RendererBackendResolver.resolve(
            requestedSurfaceBackend = RenderSurfaceBackend.SURFACE_VIEW,
            selectionMode = RendererSelectionMode.MANUAL,
            manualBackend = RendererBackend.OPENGL_ES3_DESKTOPGL_ZINK_KOPPER,
            runtimeInfo = runtimeInfo(
                libraries = setOf("libmobileglues.so", "libgl4es_114.so"),
                hasVulkanSupport = false
            )
        )

        assertEquals(RendererBackend.OPENGL_ES_MOBILEGLUES, decision.effectiveBackend)
        assertTrue(decision.usedAutomaticFallback)
        assertNotNull(decision.manualFallbackAvailability)
        assertTrue(
            decision.manualFallbackAvailability!!.reasons.contains(
                RendererAvailabilityReason.VULKAN_UNSUPPORTED
            )
        )
    }

    @Test
    fun resolve_autoFallsBackToNativeOpengles2WhenOptionalBackendsUnavailable() {
        val decision = RendererBackendResolver.resolve(
            requestedSurfaceBackend = RenderSurfaceBackend.SURFACE_VIEW,
            selectionMode = RendererSelectionMode.AUTO,
            manualBackend = null,
            runtimeInfo = runtimeInfo(
                libraries = emptySet(),
                hasVulkanSupport = false
            )
        )

        assertEquals(RendererBackend.OPENGL_ES2_NATIVE, decision.automaticBackend)
        assertEquals(RendererBackend.OPENGL_ES2_NATIVE, decision.effectiveBackend)
    }

    @Test
    fun resolve_kopperForcesTextureViewSurface() {
        val decision = RendererBackendResolver.resolve(
            requestedSurfaceBackend = RenderSurfaceBackend.SURFACE_VIEW,
            selectionMode = RendererSelectionMode.MANUAL,
            manualBackend = RendererBackend.OPENGL_ES3_DESKTOPGL_ZINK_KOPPER,
            runtimeInfo = runtimeInfo(
                libraries = setOf(
                    "libmobileglues.so",
                    "libgl4es_114.so",
                    "libglxshim.so",
                    "libEGL_mesa.so",
                    "libglapi.so",
                    "libzink_dri.so"
                ),
                hasVulkanSupport = true
            )
        )

        assertEquals(RendererBackend.OPENGL_ES3_DESKTOPGL_ZINK_KOPPER, decision.effectiveBackend)
        assertEquals(RenderSurfaceBackend.TEXTURE_VIEW, decision.effectiveSurfaceBackend)
        assertTrue(decision.surfaceBackendForced)
    }

    @Test
    fun resolve_vulkanZinkUnavailableWhenOsMesaMissing() {
        val decision = RendererBackendResolver.resolve(
            requestedSurfaceBackend = RenderSurfaceBackend.SURFACE_VIEW,
            selectionMode = RendererSelectionMode.MANUAL,
            manualBackend = RendererBackend.VULKAN_ZINK,
            runtimeInfo = runtimeInfo(
                libraries = setOf("libmobileglues.so", "libgl4es_114.so"),
                hasVulkanSupport = true
            )
        )

        val availability = decision.availableBackends.first { it.backend == RendererBackend.VULKAN_ZINK }
        assertFalse(availability.available)
        assertTrue(
            availability.reasons.contains(RendererAvailabilityReason.MISSING_NATIVE_LIBRARIES)
        )
        assertTrue(availability.missingLibraries.contains("libOSMesa.so"))
    }

    private fun runtimeInfo(
        libraries: Set<String>,
        hasVulkanSupport: Boolean = false
    ) = RendererBackendResolver.RuntimeInfo(
        packagedLibraryNames = libraries,
        hasVulkanSupport = hasVulkanSupport,
        supportsGles3 = true,
        manufacturer = "test",
        brand = "test",
        model = "test",
        hardware = "test",
        socModel = "test",
        oneUiVersion = ""
    )
}
