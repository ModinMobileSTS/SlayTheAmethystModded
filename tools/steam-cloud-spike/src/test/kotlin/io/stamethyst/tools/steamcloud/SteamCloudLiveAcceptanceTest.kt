package io.stamethyst.tools.steamcloud

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in acceptance test against the real Steam Cloud account.
 *
 * It is deliberately skipped by default: cloud mutations must never happen as a side effect of
 * the normal build. Set STS_STEAM_CLOUD_LIVE=true and provide the protected login session, then
 * set STS_STEAM_CLOUD_ACCEPT_MUTATIONS=true to exercise the complete round trip.
 */
class SteamCloudLiveAcceptanceTest {
    @Test
    fun authenticatedCloudReadAndDownload() {
        requireAuthenticatedSession()
        val output = createArtifactDirectory("sts-cloud-read-")
        try {
            StsSteamCloudReadOnlySpike.main(arrayOf("--download-all", "--output-dir", output.toString()))
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun authenticatedCloudWriteAndDeleteRoundTrip() {
        requireAuthenticatedSession()

        val mutationsEnabled = System.getenv("STS_STEAM_CLOUD_ACCEPT_MUTATIONS") == "true"
        if (!mutationsEnabled) {
            println(
                "WARNING: Steam Cloud write/delete acceptance skipped; set " +
                    "STS_STEAM_CLOUD_ACCEPT_MUTATIONS=true after authenticating with " +
                    "'python3 tools/steam-cloud-spike/login.py'."
            )
        }
        assumeTrue("Cloud mutations require explicit STS_STEAM_CLOUD_ACCEPT_MUTATIONS=true", mutationsEnabled)

        val root = createArtifactDirectory("sts-cloud-acceptance-")
        val source = root.resolve("round-trip.save")
        val remote = "sts-acceptance/${UUID.randomUUID()}.save"
        Files.writeString(root.resolve("remote-path.txt"), remote)
        Files.writeString(source, "steam-cloud acceptance ${Instant.now()}\n")
        var mutationAttempted = false
        var failure: Throwable? = null
        try {
            val before = root.resolve("before")
            StsSteamCloudReadOnlySpike.main(arrayOf("--output-dir", before.toString(), "--list-limit", "1"))
            val original = readManifestSnapshot(before)
            check(remote !in original) { "Acceptance target already exists; refusing to overwrite it" }
            println("Cloud acceptance target: $remote")
            mutationAttempted = true
            StsSteamCloudReadOnlySpike.main(arrayOf(
                "--upload-path", remote, "--upload-source", source.toString(), "--confirm-cloud-write",
                "--output-dir", root.resolve("upload").toString(), "--list-limit", "1",
            ))
            val downloaded = root.resolve("download")
            StsSteamCloudReadOnlySpike.main(arrayOf(
                "--download-path", remote, "--output-dir", downloaded.toString(), "--list-limit", "1",
            ))
            val downloadedFiles = Files.walk(downloaded.resolve("downloads")).use { paths ->
                paths.filter { Files.isRegularFile(it) }.toList()
            }
            check(downloadedFiles.size == 1) { "Cloud download did not produce exactly one acceptance file" }
            check(Files.mismatch(source, downloadedFiles.single()) == -1L) { "Cloud round-trip content mismatch" }
            println("Cloud acceptance: downloaded bytes match the uploaded source.")

            StsSteamCloudReadOnlySpike.main(arrayOf(
                "--delete-path", remote, "--confirm-cloud-delete",
                "--output-dir", root.resolve("delete").toString(), "--list-limit", "1",
            ))
            mutationAttempted = false
            val after = root.resolve("after")
            StsSteamCloudReadOnlySpike.main(arrayOf("--output-dir", after.toString(), "--list-limit", "1"))
            val restored = readManifestSnapshot(after)
            check(remote !in restored) { "Acceptance file still exists after deletion" }
            check(restored == original) { "Cloud manifest did not return to its pre-test state" }
            println("Cloud acceptance: temporary file deleted; original manifest restored (${original.size} files).")
        } catch (error: Throwable) {
            failure = error
        } finally {
            if (mutationAttempted) {
                try {
                    StsSteamCloudReadOnlySpike.main(arrayOf(
                        "--delete-path", remote, "--confirm-cloud-delete",
                        "--output-dir", root.resolve("cleanup").toString(), "--list-limit", "1",
                    ))
                } catch (cleanupError: Throwable) {
                    val primaryFailure = failure
                    if (primaryFailure == null) failure = cleanupError else primaryFailure.addSuppressed(cleanupError)
                    println("WARNING: acceptance cleanup failed for $remote; inspect artifacts at $root")
                }
            }
            if (failure == null) {
                root.toFile().deleteRecursively()
            } else {
                println("Cloud acceptance failure artifacts retained at $root")
            }
        }
        failure?.let { throw it }
    }

    private fun createArtifactDirectory(prefix: String): Path {
        val base = Path.of("agent-tmp", "steam-cloud-live")
        Files.createDirectories(base)
        return Files.createTempDirectory(base, prefix)
    }

    /** Ignore list indices, which change when the acceptance file is inserted/deleted. */
    private fun readManifestSnapshot(directory: Path): Map<String, List<String>> =
        Files.readAllLines(directory.resolve("cloud-list.tsv")).drop(1).associate { line ->
            val fields = line.split('\t')
            check(fields.size == 9) { "Invalid cloud manifest row" }
            fields[2] to fields.drop(2)
        }

    private fun requireAuthenticatedSession() {
        val authenticated = hasSession()
        if (!authenticated) {
            println(
                "WARNING: Steam Cloud live acceptance skipped; no authenticated session was provided. " +
                    "Run 'python3 tools/steam-cloud-spike/login.py' to authenticate and persist " +
                    "agent-tmp/steam-desktop-session.env, then set STS_STEAM_CLOUD_LIVE=true."
            )
        }
        assumeTrue("Steam Cloud live acceptance requires an authenticated session", authenticated)
    }

    private fun hasSession(): Boolean {
        if (System.getenv("STS_STEAM_CLOUD_LIVE") != "true") return false
        val account = System.getenv("STEAM_ACCOUNT_NAME")
        val token = System.getenv("STEAM_REFRESH_TOKEN")
        if (!account.isNullOrBlank() && !token.isNullOrBlank()) return true
        val path = Path.of(System.getenv("STS_DEPOT_KEY_ENV_FILE") ?: "agent-tmp/steam-desktop-session.env")
        return runCatching {
            val values = SteamSessionFileStore.read(path)
            !values["STEAM_ACCOUNT_NAME"].isNullOrBlank() && !values["STEAM_REFRESH_TOKEN"].isNullOrBlank()
        }.getOrDefault(false)
    }
}
