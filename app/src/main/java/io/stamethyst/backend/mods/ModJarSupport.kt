package io.stamethyst.backend.mods

import android.content.Context
import java.io.File
import java.io.IOException

object ModJarSupport {
    class ModManifestInfo(
        @JvmField val modId: String,
        @JvmField val normalizedModId: String,
        @JvmField val name: String,
        @JvmField val version: String,
        @JvmField val description: String,
        dependencies: List<String>?
    ) {
        @JvmField
        val dependencies: List<String> = dependencies ?: ArrayList()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun validateMtsJar(jarFile: File) {
        ModJarSupportLegacy.validateMtsJar(jarFile)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun validateBaseModJar(jarFile: File) {
        ModJarSupportLegacy.validateBaseModJar(jarFile)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun validateStsLibJar(jarFile: File) {
        ModJarSupportLegacy.validateStsLibJar(jarFile)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readModManifest(modJar: File): ModManifestInfo {
        return ModJarSupportLegacy.readModManifest(modJar).toKotlinModel()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun resolveModId(modJar: File): String {
        return ModJarSupportLegacy.resolveModId(modJar)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun prepareMtsClasspath(context: Context) {
        ModJarSupportLegacy.prepareMtsClasspath(context)
    }

    @JvmStatic
    fun appendCompatDiagnosticSnapshot(context: Context, stage: String) {
        ModJarSupportLegacy.appendCompatDiagnosticSnapshot(context, stage)
    }

    private fun ModJarSupportLegacy.ModManifestInfo.toKotlinModel(): ModManifestInfo {
        return ModManifestInfo(
            modId = modId,
            normalizedModId = normalizedModId,
            name = name,
            version = version,
            description = description,
            dependencies = dependencies
        )
    }
}
