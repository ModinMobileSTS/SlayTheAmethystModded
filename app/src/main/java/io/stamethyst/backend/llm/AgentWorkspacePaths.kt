package io.stamethyst.backend.llm

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.nio.file.Files

/** A fixed, public namespace; never interpret an agent path as an Android filesystem path. */
internal class AgentWorkspacePaths(root: File) {
    private val physicalRoots = listOf(root.absoluteFile.toPath().normalize(), root.canonicalFile.toPath()).distinct()
    private val diagnosticRoots = (listOf(root.absolutePath) + physicalRoots.map { it.toString() }).distinct()
    private val root = root.canonicalFile
    private val mounts = setOf("source", "patch_source", "tool_output")

    fun resolve(path: String, writable: Boolean = false, deleting: Boolean = false): File {
        val segments = normalize(path)
        val virtual = "/" + segments.joinToString("/")
        if (segments.isNotEmpty() && segments.first() !in mounts) {
            fail("path_not_exposed", virtual, "Paths start at /. Use /source/, /patch_source/<patch_id>/ or /tool_output/; there is no current patch directory.")
        }
        if (writable) {
            if (segments.firstOrNull() == "source" || segments.firstOrNull() == "tool_output") {
                fail("read_only_path", virtual, "This tree is read-only. Write patch files under /patch_source/<patch_id>/ instead.")
            }
            if (segments.size < 3 || !segments[1].startsWith("patch-")) {
                fail(if (deleting) "protected_patch_directory" else "incomplete_patch_path", virtual,
                    "Use /patch_source/<patch_id>/<file or subdirectory>. Use delete_agent_patch_mod to remove an entire patch revision.")
            }
        }
        val lexical = File(root, segments.joinToString("/"))
        val candidate = try { lexical.canonicalFile } catch (_: Exception) {
            fail("invalid_path", virtual, "Use a valid virtual path with / separators.")
        }
        if (!candidate.toPath().startsWith(root.toPath())) {
            fail("path_outside_workspace", virtual, "The path or a symbolic link escapes the virtual workspace.")
        }
        if (segments.isNotEmpty()) {
            val mount = File(root, segments.first())
            val canonicalMount = try { mount.canonicalFile } catch (_: Exception) {
                fail("invalid_path", virtual, "The virtual mount cannot be resolved; check workspace storage access.")
            }
            if (canonicalMount != mount || !candidate.toPath().startsWith(mount.toPath())) {
                fail("unsafe_symlink", virtual, "Symbolic links must stay inside the same exposed tree; mount roots cannot be redirected.")
            }
            if (writable) {
                val actual = mount.toPath().relativize(candidate.toPath())
                if (actual.nameCount < 2 || !actual.getName(0).toString().startsWith("patch-")) {
                    fail("unsafe_symlink", virtual, "The resolved path is not a file or subdirectory inside a patch revision.")
                }
            }
        }
        return candidate
    }

    /** Converts only exposed files. Returning a path must mean a subsequent read can resolve it. */
    fun virtual(file: File): String {
        val absolute = file.absoluteFile.toPath().normalize()
        val physicalRoot = physicalRoots.firstOrNull { absolute.startsWith(it) }
            ?: fail("path_outside_workspace", "/", "The file is outside the virtual workspace.")
        val path = "/" + physicalRoot.relativize(absolute).joinToString("/")
        val resolved = resolve(path)
        return "/" + root.toPath().relativize(resolved.toPath()).joinToString("/")
    }

    /** Do not traverse directory symlinks (including cycles) or unexposed internal state. */
    fun files(base: File): Sequence<File> = base.walkTopDown()
        .onEnter { directory ->
            !Files.isSymbolicLink(directory.toPath()) && runCatching { virtual(directory) }.isSuccess
        }
        .filter { it.isFile && runCatching { virtual(it) }.isSuccess }

    /** Reject escaping links before recursive deletion; Files.walk never follows directory links. */
    fun checkDeleteTree(base: File) {
        Files.walk(base.toPath()).use { entries ->
            entries.forEach { entry ->
                val path = "/" + root.toPath().relativize(entry.toAbsolutePath()).joinToString("/")
                resolve(path, writable = true, deleting = true)
            }
        }
    }

    /** Replace physical workspace paths in compiler/preflight output, not in source file contents. */
    fun diagnostics(text: String): String {
        var result = text
        diagnosticRoots.sortedByDescending { it.length }.forEach { physical ->
            result = result.replace(physical.trimEnd('/') + "/", "/").replace(physical, "/")
        }
        return result
    }

    fun patchDiagnostic(text: String, patchRoot: File): String {
        val relative = text.substringBefore(':')
        val expanded = if (relative.endsWith(".class") && !relative.startsWith("/") && '\n' !in relative) {
            "${virtual(File(patchRoot, relative))}${text.substring(relative.length)}"
        } else text
        return diagnostics(expanded)
    }

    private fun normalize(path: String): List<String> {
        if (path.isBlank() || '\u0000' in path || '\\' in path || Regex("^[A-Za-z]:").containsMatchIn(path)) {
            fail("invalid_path", "", "Use a nonempty virtual path with / separators, not a device filesystem path.")
        }
        val result = ArrayList<String>()
        path.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (result.isEmpty()) {
                    fail("path_outside_workspace", path, ".. cannot escape the virtual root /.")
                } else result.removeAt(result.lastIndex)
                else -> result += part
            }
        }
        return result
    }

    private fun fail(code: String, path: String, hint: String): Nothing = throw AgentWorkspacePathException(code, path, hint)
}

internal class AgentWorkspacePathException(val code: String, val path: String, val hint: String) : IllegalArgumentException(code) {
    fun json(): String = buildJsonObject {
        put("error", JsonPrimitive(code))
        put("path", JsonPrimitive(path))
        put("hint", JsonPrimitive(hint))
    }.toString()
}
