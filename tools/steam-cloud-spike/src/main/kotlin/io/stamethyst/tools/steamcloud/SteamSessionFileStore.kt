package io.stamethyst.tools.steamcloud

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/** Small, secret-safe reader/writer for the local Steam session env file. */
object SteamSessionFileStore {
    @JvmStatic
    fun read(path: Path): Map<String, String> {
        require(Files.isRegularFile(path)) { "Session file does not exist: $path" }
        return Files.readAllLines(path, Charsets.UTF_8)
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()
    }

    @JvmStatic
    fun write(path: Path, contents: String) {
        path.toAbsolutePath().normalize().parent?.let(Files::createDirectories)
        val temporary = path.resolveSibling(".${path.fileName}.tmp-${ProcessHandle.current().pid()}")
        try {
            Files.writeString(temporary, contents, Charsets.UTF_8)
            restrictToOwner(temporary)
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            restrictToOwner(path)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun restrictToOwner(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}
