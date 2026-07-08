package top.apricityx.workshop.workshop

import java.io.File

internal sealed interface PreparedManifestEntry {
    data class DirectoryEntry(val target: File) : PreparedManifestEntry

    data class FileEntry(val target: File) : PreparedManifestEntry
}

internal fun DepotManifest.requiresDirectory(file: ManifestFile): Boolean {
    if ((file.flags and DEPOT_FILE_FLAG_DIRECTORY) != 0u) {
        return true
    }
    val prefix = "${file.path.trimEnd('/')}/"
    return files.any { other -> other !== file && other.path.startsWith(prefix) }
}

internal object WorkshopOutputPathManager {
    fun prepare(
        outputDir: File,
        manifest: DepotManifest,
        file: ManifestFile,
    ): PreparedManifestEntry {
        val target = File(outputDir, file.path.replace('/', File.separatorChar))
        return if (manifest.requiresDirectory(file)) {
            ensureDirectory(target)
            PreparedManifestEntry.DirectoryEntry(target)
        } else {
            ensureDirectory(target.parentFile)
            if (target.isDirectory && !target.deleteRecursively()) {
                throw WorkshopDownloadException("Failed to replace directory with file target: ${target.absolutePath}")
            }
            PreparedManifestEntry.FileEntry(target)
        }
    }

    private fun ensureDirectory(directory: File?) {
        if (directory == null) {
            return
        }
        if (directory.isFile && !directory.delete()) {
            throw WorkshopDownloadException("Failed to replace file with directory target: ${directory.absolutePath}")
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw WorkshopDownloadException("Failed to create directory: ${directory.absolutePath}")
        }
    }
}

internal const val DEPOT_FILE_FLAG_DIRECTORY = 64u
