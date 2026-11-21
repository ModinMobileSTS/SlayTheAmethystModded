package io.stamethyst.backend.steamcloud

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** One physical pass. Service follow-ups reuse the operation ID but get a fresh freeze ID.
 * Only fixed labels, counts and generated IDs cross this diagnostics boundary. */
internal class SteamCloudSyncTrace(
    val operation: UUID = UUID.randomUUID(),
    private val source: Source = Source.SETTINGS,
    private val returnedLaunch: Long? = null,
) {
    enum class Source { SETTINGS, HOME_AUTO, HOME_MANUAL, GAME_RETURN }
    enum class Phase { PRECHECK, FREEZE, CONNECT, SNAPSHOT, LIST, PLAN, CHECK_METADATA, PREPARE_WRITE, WRITE }
    private val started = System.nanoTime()
    private val startedAtMs = System.currentTimeMillis()
    private var since = started
    private var stage = Phase.PRECHECK.name
    private val durations = linkedMapOf<String, Long>()
    private var freeze: UUID? = null
    var localFiles: Int? = null
    var remoteFiles: Int? = null
    private var lists = 0
    var enteredWrite = false
    fun listing() { lists++ }
    fun frozen(id: UUID) { freeze = id }
    fun phase(value: Phase) = transition(value.name)
    fun progress(value: SteamCloudSyncPhase) = transition(value.name)
    private fun transition(next: String) {
        if (next == stage) return
        val now = System.nanoTime()
        durations[stage] = durations.getOrDefault(stage, 0) + now - since
        since = now
        stage = next
    }

    fun record(stateRoot: File, mode: SteamCloudSyncMode, steamId: String?, block: () -> SteamCloudSyncEngine.Result): SteamCloudSyncEngine.Result {
        fun pending() = runCatching { steamId?.let { SteamCloudRemoteCommitStore(stateRoot, it).hasPending() } }.getOrNull()
        val initiallyPending = pending()
        fun save(outcome: String) {
            // Best effort, atomic visibility but no fsync: not a recovery journal. No writes
            // from individual file/progress callbacks. Readers see RUNNING during a new pass.
            runCatching {
                val now = System.nanoTime()
                val times = durations.toMutableMap()
                times[stage] = times.getOrDefault(stage, 0) + now - since
                val text = buildString {
                    appendLine("operationId=$operation")
                    appendLine("startedAtMs=$startedAtMs")
                    appendLine("source=$source")
                    appendLine("mode=$mode")
                    appendLine("freezeId=${freeze ?: "none"}")
                    appendLine("returnedLaunch=${returnedLaunch ?: "none"}")
                    appendLine("localFiles=${localFiles ?: "unknown"}")
                    appendLine("remoteFiles=${remoteFiles ?: "unknown"}")
                    appendLine("manifestCalls=$lists")
                    appendLine("enteredWrite=$enteredWrite")
                    appendLine("pendingBefore=${initiallyPending ?: "unknown"}")
                    // If a rename/unlink wasn't durably synced, presence on this mount cannot
                    // prove the journal state a restarted process will see.
                    val current = if (outcome == "FAILED_JOURNAL_PERSISTENCE") null else pending()
                    appendLine("pendingNow=${current ?: "unknown"}")
                    appendLine("outcome=$outcome")
                    appendLine("durationMs=${(now - started) / 1_000_000}")
                    times.forEach { (label, nanos) -> appendLine("phaseMs.$label=${nanos / 1_000_000}") }
                }
                stateRoot.mkdirs()
                val tmp = File.createTempFile(".sync-trace-", ".tmp", stateRoot)
                try {
                    tmp.writeText(text)
                    Files.move(tmp.toPath(), File(stateRoot, FILE_NAME).toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE)
                } finally { tmp.delete() }
            }
        }
        save("RUNNING")
        try {
            return block().also { save(if (it.plan.conflicts.isNotEmpty()) "CONFLICT" else "COMPLETED") }
        } catch (error: Exception) {
            save(runCatching {
                if (error is SteamCloudLiveSaveInUseException && mode == SteamCloudSyncMode.MERGE) "DEFERRED"
                else "FAILED_${SteamCloudFailureClassifier.classify(error).name}"
            }.getOrDefault("FAILED_UNKNOWN"))
            throw error
        }
    }

    companion object {
        const val FILE_NAME = "last-sync-pass.txt"
        private val number = Regex("[0-9]{1,19}")
        private val uuid = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        private val phases = Phase.entries.map { it.name }.toSet() + SteamCloudSyncPhase.entries.map { it.name }
        private val outcomes = setOf("RUNNING", "COMPLETED", "CONFLICT", "DEFERRED") +
            SteamCloudFailureCategory.entries.map { "FAILED_${it.name}" }
        fun exportable(line: String, push: Boolean): Boolean {
            val key = line.substringBefore('=')
            val value = line.substringAfter('=', "")
            if (key == "operationId") return uuid.matches(value)
            if (key == "freezeId") return (!push && value == "none") || uuid.matches(value)
            if (push) return line == "engine=v2" || key in setOf("completedAtMs", "uploaded", "deleted") && number.matches(value)
            return when (key) {
                "source" -> Source.entries.any { it.name == value }
                "mode" -> SteamCloudSyncMode.entries.any { it.name == value }
                "outcome" -> value in outcomes
                "returnedLaunch" -> value == "none" || number.matches(value)
                "localFiles", "remoteFiles" -> value == "unknown" || number.matches(value)
                "pendingBefore", "pendingNow", "enteredWrite" -> value in setOf("true", "false", "unknown")
                "durationMs", "manifestCalls", "startedAtMs" -> number.matches(value)
                else -> key.startsWith("phaseMs.") && key.removePrefix("phaseMs.") in phases && number.matches(value)
            }
        }
        fun service(id: String, request: SteamCloudServicePass) = SteamCloudSyncTrace(
            runCatching { UUID.fromString(id) }.getOrElse { UUID.randomUUID() },
            when {
                request.returnedLaunch != null -> Source.GAME_RETURN
                request.userInitiated -> Source.HOME_MANUAL
                else -> Source.HOME_AUTO
            }, request.returnedLaunch)
    }
}
