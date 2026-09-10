package io.stamethyst.backend.resources

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.LinkedHashSet

class RuntimeResourceProvider(
    context: Context,
    private val assets: AssetManager = context.assets
) {
    private val externalAssetsDir: File? = ResourcePackStore.activeAssetsDir(context)

    @Throws(IOException::class)
    fun list(path: String): Array<String> {
        val names = LinkedHashSet<String>()
        val assetNames = try {
            assets.list(path)
        } catch (_: IOException) {
            null
        }
        assetNames
            ?.filter(String::isNotEmpty)
            ?.forEach(names::add)

        val external = externalFile(path)
        if (external?.isDirectory == true) {
            external.list()
                ?.filter(String::isNotEmpty)
                ?.forEach(names::add)
        }
        return names.toTypedArray()
    }

    @Throws(IOException::class)
    fun open(path: String): InputStream {
        val external = externalFile(path)
        if (external?.isFile == true) {
            return FileInputStream(external)
        }
        try {
            return assets.open(path)
        } catch (assetError: IOException) {
            throw assetError
        }
    }

    fun exists(path: String): Boolean {
        if (externalFile(path)?.isFile == true) {
            return true
        }
        return assetFileExists(path)
    }

    fun contentVersion(path: String): Long {
        val external = externalFile(path)
        if (external?.isFile == true) {
            return external.lastModified().takeIf { it > 0L }
                ?: external.length().coerceAtLeast(1L)
        }
        return if (assetFileExists(path)) {
            -1L
        } else {
            0L
        }
    }

    fun hasChildren(path: String): Boolean {
        return try {
            list(path).isNotEmpty()
        } catch (_: IOException) {
            false
        }
    }

    private fun assetFileExists(path: String): Boolean {
        return try {
            assets.open(path).use { _: InputStream -> }
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun externalFile(path: String): File? {
        val root = externalAssetsDir ?: return null
        val normalizedPath = path
            .replace('\\', '/')
            .trimStart('/')
        if (normalizedPath.isEmpty()) {
            return null
        }
        val rootPath = runCatching { root.canonicalFile.toPath() }.getOrNull() ?: return null
        val candidate = File(root, normalizedPath)
        val candidatePath = runCatching { candidate.canonicalFile.toPath() }.getOrNull() ?: return null
        if (!candidatePath.startsWith(rootPath)) {
            return null
        }
        return candidate
    }
}
