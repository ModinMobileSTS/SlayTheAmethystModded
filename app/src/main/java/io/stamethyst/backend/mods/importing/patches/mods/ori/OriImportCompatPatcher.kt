package io.stamethyst.backend.mods.importing.patches.mods.ori

import io.stamethyst.backend.mods.JarFileIoUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class OriImportCompatPatchResult(
    val patchedShaderEntries: Int,
    val patchedGaussianBlurShaderEntries: Int,
    val patchedBoxBlurShaderEntries: Int,
    val estimatedTextureSamplesBefore: Int,
    val estimatedTextureSamplesAfter: Int
) {
    val hasAnyPatch: Boolean
        get() = patchedShaderEntries > 0
}

internal object OriImportCompatPatcher {
    private const val GAUSSIAN_BLUR_FRAGMENT_ENTRY = "oriShaders/gaussianBlur/fragment.glsl"
    private const val BOX_BLUR_FRAGMENT_ENTRY = "oriShaders/boxBlur/fragment.glsl"
    private const val ESTIMATED_TEXTURE_SAMPLES_BEFORE = 103
    private const val ESTIMATED_TEXTURE_SAMPLES_AFTER = 19

    @Throws(IOException::class)
    fun patchInPlace(modJar: File): OriImportCompatPatchResult {
        if (!modJar.isFile) {
            throw IOException("Mod jar not found: ${modJar.absolutePath}")
        }

        val replacements = LinkedHashMap<String, ByteArray>()
        var patchedGaussianBlurShaderEntries = 0
        var patchedBoxBlurShaderEntries = 0

        ZipFile(modJar).use { zipFile ->
            patchShaderEntry(
                zipFile = zipFile,
                entryName = GAUSSIAN_BLUR_FRAGMENT_ENTRY,
                replacementSource = gaussianBlurFragmentSource
            )?.let { (entryName, patchedBytes) ->
                replacements[entryName] = patchedBytes
                patchedGaussianBlurShaderEntries++
            }
            patchShaderEntry(
                zipFile = zipFile,
                entryName = BOX_BLUR_FRAGMENT_ENTRY,
                replacementSource = boxBlurFragmentSource
            )?.let { (entryName, patchedBytes) ->
                replacements[entryName] = patchedBytes
                patchedBoxBlurShaderEntries++
            }
        }

        if (replacements.isNotEmpty()) {
            rewriteJarWithReplacements(modJar, replacements)
        }

        return OriImportCompatPatchResult(
            patchedShaderEntries = replacements.size,
            patchedGaussianBlurShaderEntries = patchedGaussianBlurShaderEntries,
            patchedBoxBlurShaderEntries = patchedBoxBlurShaderEntries,
            estimatedTextureSamplesBefore = ESTIMATED_TEXTURE_SAMPLES_BEFORE,
            estimatedTextureSamplesAfter = ESTIMATED_TEXTURE_SAMPLES_AFTER
        )
    }

    @Throws(IOException::class)
    private fun patchShaderEntry(
        zipFile: ZipFile,
        entryName: String,
        replacementSource: String
    ): Pair<String, ByteArray>? {
        val entry = JarFileIoUtils.findEntryIgnoreCase(zipFile, entryName) ?: return null
        if (entry.isDirectory) {
            return null
        }
        val originalSource = JarFileIoUtils.readEntry(zipFile, entry)
        if (originalSource == replacementSource || originalSource.contains(PATCH_MARKER)) {
            return null
        }
        return entry.name to replacementSource.toByteArray(StandardCharsets.UTF_8)
    }

    @Throws(IOException::class)
    private fun rewriteJarWithReplacements(modJar: File, replacements: Map<String, ByteArray>) {
        val tempJar = File(modJar.absolutePath + ".oripatch.tmp")
        val seenNames = LinkedHashSet<String>()
        try {
            ZipFile(modJar).use { zipFile ->
                FileOutputStream(tempJar, false).use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        val entries = zipFile.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val entryName = entry.name
                            if (!seenNames.add(entryName)) {
                                continue
                            }

                            val outEntry = ZipEntry(entryName)
                            if (entry.time > 0) {
                                outEntry.time = entry.time
                            }
                            zipOut.putNextEntry(outEntry)
                            if (!entry.isDirectory) {
                                val replacement = replacements[entryName]
                                if (replacement != null) {
                                    zipOut.write(replacement)
                                } else {
                                    zipFile.getInputStream(entry).use { input ->
                                        JarFileIoUtils.copyStream(input, zipOut)
                                    }
                                }
                            }
                            zipOut.closeEntry()
                        }
                    }
                }
            }

            JarFileIoUtils.moveFileReplacing(tempJar, modJar)
        } finally {
            if (tempJar.exists()) {
                tempJar.delete()
            }
        }
    }

    private const val PATCH_MARKER = "Amethyst Ori fast blur"

    private val gaussianBlurFragmentSource = """
#ifdef GL_ES
    #define LOWP lowp
    precision mediump float;
#else
    #define LOWP
#endif
varying LOWP vec4 v_color;
varying vec2 v_texCoords;

uniform sampler2D u_texture;
uniform float resolution;
uniform float radius;
uniform vec2 dir;

// Amethyst Ori fast blur: 3-tap separable blur for the pre-bloom passes.
void main() {
    vec2 delta = dir * (radius / resolution);
    vec4 sum = texture2D(u_texture, v_texCoords) * 0.5;
    sum += texture2D(u_texture, v_texCoords + delta * 0.5) * 0.25;
    sum += texture2D(u_texture, v_texCoords - delta * 0.5) * 0.25;
    gl_FragColor = v_color * sum;
}
""".trimIndent()

    private val boxBlurFragmentSource = """
#ifdef GL_ES
    #define LOWP lowp
    precision mediump float;
#else
    #define LOWP
#endif
varying LOWP vec4 v_color;
varying vec2 v_texCoords;

uniform sampler2D u_texture;
uniform float blurSize;
uniform float prec;
uniform vec2 resolution;

// Amethyst Ori fast blur: sparse wide-kernel bloom approximation.
void main() {
    vec2 halfRadius = vec2(blurSize * 0.5) / resolution;
    vec2 mid = halfRadius * 0.55;
    vec2 far = halfRadius * 0.9;

    vec4 sum = texture2D(u_texture, v_texCoords) * 0.16;
    sum += texture2D(u_texture, v_texCoords + vec2(mid.x, 0.0)) * 0.10;
    sum += texture2D(u_texture, v_texCoords - vec2(mid.x, 0.0)) * 0.10;
    sum += texture2D(u_texture, v_texCoords + vec2(0.0, mid.y)) * 0.10;
    sum += texture2D(u_texture, v_texCoords - vec2(0.0, mid.y)) * 0.10;
    sum += texture2D(u_texture, v_texCoords + mid) * 0.07;
    sum += texture2D(u_texture, v_texCoords - mid) * 0.07;
    sum += texture2D(u_texture, v_texCoords + vec2(mid.x, -mid.y)) * 0.07;
    sum += texture2D(u_texture, v_texCoords + vec2(-mid.x, mid.y)) * 0.07;
    sum += texture2D(u_texture, v_texCoords + vec2(far.x, 0.0)) * 0.04;
    sum += texture2D(u_texture, v_texCoords - vec2(far.x, 0.0)) * 0.04;
    sum += texture2D(u_texture, v_texCoords + vec2(0.0, far.y)) * 0.04;
    sum += texture2D(u_texture, v_texCoords - vec2(0.0, far.y)) * 0.04;

    gl_FragColor = v_color * sum * 2.4;
}
""".trimIndent()
}
