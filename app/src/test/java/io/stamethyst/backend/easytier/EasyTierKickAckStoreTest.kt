package io.stamethyst.backend.easytier

import org.junit.Assert.assertEquals
import org.junit.Test

class EasyTierKickAckStoreTest {
    @Test
    fun readAcknowledgedEventKey_defaultsToBlankWhenNothingWasAcknowledged() {
        val roots = EasyTierTestRoots.create("easytier-kick-ack-empty")
        try {
            assertEquals("", EasyTierKickAckStore.readAcknowledgedEventKey(roots.context))
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun writeAndRead_roundTripsAcknowledgedEventKey() {
        val roots = EasyTierTestRoots.create("easytier-kick-ack-round-trip")
        try {
            EasyTierKickAckStore.writeAcknowledgedEventKey(
                roots.context,
                "room-a|DISCONNECTED|SessionKicked|1000|Removed by owner.",
            )

            assertEquals(
                "room-a|DISCONNECTED|SessionKicked|1000|Removed by owner.",
                EasyTierKickAckStore.readAcknowledgedEventKey(roots.context),
            )

            EasyTierKickAckStore.writeAcknowledgedEventKey(
                roots.context,
                "room-b|DISCONNECTED|SessionKicked|2000|Removed by owner.",
            )
            assertEquals(
                "room-b|DISCONNECTED|SessionKicked|2000|Removed by owner.",
                EasyTierKickAckStore.readAcknowledgedEventKey(roots.context),
            )
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun readAcknowledgedEventKey_recoversFromCorruptedFile() {
        val roots = EasyTierTestRoots.create("easytier-kick-ack-corrupt")
        try {
            EasyTierKickAckStore.writeAcknowledgedEventKey(roots.context, "room-a|key")
            EasyTierStateStore.outputDir(roots.context).resolve("kick-ack.json")
                .writeText("{not json", Charsets.UTF_8)

            assertEquals("", EasyTierKickAckStore.readAcknowledgedEventKey(roots.context))
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }
}
