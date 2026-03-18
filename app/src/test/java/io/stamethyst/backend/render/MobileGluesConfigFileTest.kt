package io.stamethyst.backend.render

import java.io.File
import kotlin.io.path.createTempDirectory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileGluesConfigFileTest {
    private val aggressiveSettings = MobileGluesSettings(
        anglePolicy = MobileGluesAnglePolicy.ENABLE,
        noErrorPolicy = MobileGluesNoErrorPolicy.IGNORE_SHADER_PROGRAM_AND_FRAMEBUFFER,
        multidrawMode = MobileGluesMultidrawMode.FORCE_DRAW_ELEMENTS,
        extComputeShaderEnabled = true,
        extTimerQueryEnabled = true,
        extDirectStateAccessEnabled = true,
        glslCacheSizePreset = MobileGluesGlslCacheSizePreset.MB_128,
        angleDepthClearFixMode = MobileGluesAngleDepthClearFixMode.WORKAROUND_2,
        customGlVersion = MobileGluesCustomGlVersion.OPENGL_3_3,
        fsr1QualityPreset = MobileGluesFsr1QualityPreset.BALANCED,
    )

    @Test
    fun mergeConfig_preservesExistingKeysAndUpdatesMobileGluesSettings() {
        val existing = JSONObject().apply {
            put("customGLVersion", 40)
            put("enableExtComputeShader", 0)
            put("enableExtGL43", 1)
            put("enableANGLE", MobileGluesAnglePolicy.PREFER_DISABLED.mobileGluesConfigValue)
            put("someLauncherOwnedKey", "keep-me")
        }

        val merged = MobileGluesConfigFile.mergeConfig(existing, aggressiveSettings)

        assertEquals("keep-me", merged.getString("someLauncherOwnedKey"))
        assertEquals(
            MobileGluesAnglePolicy.ENABLE.mobileGluesConfigValue,
            merged.getInt("enableANGLE")
        )
        assertEquals(
            MobileGluesNoErrorPolicy.IGNORE_SHADER_PROGRAM_AND_FRAMEBUFFER.mobileGluesConfigValue,
            merged.getInt("enableNoError")
        )
        assertEquals(1, merged.getInt("enableExtComputeShader"))
        assertTrue(!merged.has("enableExtGL43"))
        assertEquals(1, merged.getInt("enableExtTimerQuery"))
        assertEquals(1, merged.getInt("enableExtDirectStateAccess"))
        assertEquals(128, merged.getInt("maxGlslCacheSize"))
        assertEquals(
            MobileGluesMultidrawMode.FORCE_DRAW_ELEMENTS.mobileGluesConfigValue,
            merged.getInt("multidrawMode")
        )
        assertEquals(
            MobileGluesAngleDepthClearFixMode.WORKAROUND_2.mobileGluesConfigValue,
            merged.getInt("angleDepthClearFixMode")
        )
        assertEquals(
            MobileGluesCustomGlVersion.OPENGL_3_3.mobileGluesConfigValue,
            merged.getInt("customGLVersion")
        )
        assertEquals(
            MobileGluesFsr1QualityPreset.BALANCED.mobileGluesConfigValue,
            merged.getInt("fsr1Setting")
        )
    }

    @Test
    fun write_createsConfigFileAndStoresMobileGluesSettings() {
        val root = createTempDirectory("mobileglues-config-test").toFile()
        val file = File(root, "MobileGlues/config.json")

        MobileGluesConfigFile.write(file, aggressiveSettings)

        assertTrue(file.isFile)
        val parsed = JSONObject(file.readText())
        assertEquals(
            MobileGluesAnglePolicy.ENABLE.mobileGluesConfigValue,
            parsed.getInt("enableANGLE")
        )
        assertEquals(
            MobileGluesMultidrawMode.FORCE_DRAW_ELEMENTS.mobileGluesConfigValue,
            parsed.getInt("multidrawMode")
        )
        assertEquals(128, parsed.getInt("maxGlslCacheSize"))
    }

    @Test
    fun fromPersistedValue_returnsMatchingPolicy() {
        assertEquals(
            MobileGluesAnglePolicy.PREFER_DISABLED,
            MobileGluesAnglePolicy.fromPersistedValue(0)
        )
        assertEquals(
            MobileGluesAnglePolicy.PREFER_ENABLED,
            MobileGluesAnglePolicy.fromPersistedValue(1)
        )
        assertEquals(
            MobileGluesAnglePolicy.DISABLE,
            MobileGluesAnglePolicy.fromPersistedValue(2)
        )
        assertEquals(
            MobileGluesAnglePolicy.ENABLE,
            MobileGluesAnglePolicy.fromPersistedValue(3)
        )
    }
}
