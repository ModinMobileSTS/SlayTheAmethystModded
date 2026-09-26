package io.stamethyst

import java.io.File

/** Avoids rereading unchanged runtime bridge files during fallback scans. */
internal class RuntimeRequestFileReader {
    private data class Snapshot(val modifiedAtMs: Long, val size: Long)

    private val snapshots = mutableMapOf<File, Snapshot>()

    fun readChanged(file: File, force: Boolean): String {
        if (!file.isFile) {
            snapshots.remove(file)
            return ""
        }
        val snapshot = Snapshot(file.lastModified(), file.length())
        if (!force && snapshots[file] == snapshot) return ""
        val payload = file.readText().trim()
        snapshots[file] = snapshot
        return payload
    }

    fun clear() {
        snapshots.clear()
    }
}
