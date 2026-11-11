package io.stamethyst.ui.main

/** A display-only snapshot. Assignment and order are published together, only on valid release. */
internal class ModDragTransaction(
    folders: Map<String, List<String>>,
    draggedPath: String,
    selectedPaths: Set<String>,
    private val dragLocked: Boolean,
    private val onCommit: (Map<String, List<String>>) -> Unit,
) {
    private val original = folders.mapValues { it.value.toList() }
    private val visiblePaths = original.values.flatten()
    val movingPaths = if (draggedPath !in visiblePaths) emptyList() else visiblePaths.filter {
        if (draggedPath in selectedPaths) it in selectedPaths else it == draggedPath
    }
    private val remaining = original.mapValues { (_, paths) -> paths.filterNot { it in movingPaths } }
    var previewFolders: Map<String, List<String>> = original
        private set
    private var valid = false
    private var finished = false

    init {
        val source = original.entries.firstOrNull { draggedPath in it.value }
        if (!dragLocked && source != null) {
            val index = source.value.takeWhile { it != draggedPath }.count { it !in movingPaths }
            place(source.key, index)
        }
    }

    private fun place(folder: String, index: Int) {
        previewFolders = remaining.mapValues { (token, paths) ->
            if (token == folder) paths.toMutableList().apply { addAll(index, movingPaths) } else paths
        }
        valid = true
    }

    fun preview(folderToken: String, targetPath: String?, after: Boolean) {
        if (finished || dragLocked || movingPaths.isEmpty()) return
        val paths = remaining[folderToken]
        if (paths == null) {
            valid = false
        } else if (targetPath in movingPaths) {
            previewPlaceholder()
        } else if (targetPath == null) {
            place(folderToken, paths.size)
        } else {
            val index = paths.indexOf(targetPath)
            if (index < 0) valid = false else place(folderToken, index + if (after) 1 else 0)
        }
    }

    fun previewPlaceholder() {
        if (!finished && !dragLocked && movingPaths.isNotEmpty()) valid = true
    }

    fun previewOutsideViewport() {
        valid = false
    }

    fun drop() {
        if (finished) return
        finished = true
        if (valid && !dragLocked) onCommit(previewFolders) else previewFolders = original
    }

    fun cancel() {
        finished = true
        valid = false
        previewFolders = original
    }
}
