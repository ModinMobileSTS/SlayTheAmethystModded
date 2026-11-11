package io.stamethyst.ui.main

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A single archive avoids launching multiple document pickers or share sheets. */
internal fun writeModBatchArchive(sources: List<Pair<File, String>>, destination: File) {
    require(sources.isNotEmpty()) { "No exportable mods" }
    val usedNames = mutableSetOf<String>()
    try {
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            sources.forEach { (file, suggestedName) ->
                require(file.isFile) { "Missing mod: ${file.name}" }
                val base = suggestedName.substringAfterLast('/').substringAfterLast('\\')
                    .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "mod.jar"
                var name = base
                var suffix = 2
                while (!usedNames.add(name.lowercase(java.util.Locale.ROOT))) {
                    name = "${base.substringBeforeLast('.', base)}-${suffix++}.${base.substringAfterLast('.', "jar")}"
                }
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    } catch (error: Throwable) {
        destination.delete()
        throw error
    }
}
