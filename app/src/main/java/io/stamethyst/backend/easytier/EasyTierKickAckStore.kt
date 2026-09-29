package io.stamethyst.backend.easytier

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Remembers which "removed from room" event the player has already seen.
 *
 * A kick is a terminal state, and the kicked snapshot (including `failureCategory = SessionKicked`
 * and its summary) is persisted by [EasyTierStateStore]. Without an on-disk acknowledgement the
 * dialog is re-queued from that persisted snapshot on every cold start, because the in-memory
 * deduplication field in the view model does not survive process death.
 */
internal object EasyTierKickAckStore {
    private const val FILE_NAME = "kick-ack.json"
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun readAcknowledgedEventKey(context: Context): String {
        val file = file(context)
        if (!file.isFile) {
            return ""
        }
        return runCatching {
            json.decodeFromString<EasyTierKickAckSnapshot>(file.readText(Charsets.UTF_8))
                .acknowledgedEventKey
                .trim()
        }.getOrElse { "" }
    }

    @Throws(IOException::class)
    fun writeAcknowledgedEventKey(context: Context, eventKey: String) {
        EasyTierAtomicFileStore.writeText(
            file(context),
            json.encodeToString(EasyTierKickAckSnapshot(acknowledgedEventKey = eventKey.trim())),
            Charsets.UTF_8,
        )
    }

    private fun file(context: Context): File =
        File(EasyTierStateStore.outputDir(context), FILE_NAME)
}

@Serializable
internal data class EasyTierKickAckSnapshot(
    val acknowledgedEventKey: String = "",
)
