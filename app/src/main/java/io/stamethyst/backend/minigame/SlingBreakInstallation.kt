package io.stamethyst.backend.minigame

import android.content.Context
import io.stamethyst.backend.update.UpdateReleaseInfo
import org.json.JSONObject
import java.io.File

/** One atomic activation record; immutable payloads and old generations are retained. */
object SlingBreakInstallation {
    @JvmStatic fun root(context: Context) = File(context.noBackupFilesDir, "godot-prototype")

    @JvmStatic fun read(context: Context): UpdateReleaseInfo? = runCatching {
        val json = JSONObject(File(root(context), "installed-release.json").readText())
        val release = SlingBreakReleases.parseLatest("[${json}]") ?: return null
        if (!File(root(context), "game-${release.assetSha256}.pck").isFile ||
            !File(root(context), "assets-${release.assetSha256}.zip").isFile ||
            !File(root(context), "engine-${SlingBreakReleases.ENGINE_SHA256}/engine.apk").isFile) return null
        release
    }.getOrNull()

    internal fun activateWhenReady(context: Context, release: UpdateReleaseInfo, prepare: () -> Unit) {
        prepare()
        activate(context, release)
    }

    internal fun activate(context: Context, release: UpdateReleaseInfo) {
        val json = JSONObject().put("id", release.releaseId).put("tag_name", release.rawTagName)
            .put("published_at", release.publishedAtRaw).put("body", release.notesText)
            .put("html_url", release.releasePageUrl).put("assets", org.json.JSONArray().put(
                JSONObject().put("id", release.assetId).put("name", release.assetName)
                    .put("browser_download_url", release.assetDownloadUrl).put("size", release.assetSize)
                    .put("digest", "sha256:${release.assetSha256}")))
        // Same-filesystem atomic rename: readers in :game/:minigame see old or new, never a
        // partial record, and unlike AtomicFile.openRead they cannot restore a writer's backup.
        val temp = File(root(context), "release-${java.util.UUID.randomUUID()}.part")
        try {
            java.io.FileOutputStream(temp).use { stream ->
                stream.write(json.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            java.nio.file.Files.move(temp.toPath(), File(root(context), "installed-release.json").toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }
}
