package io.stamethyst.backend.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EasyTierRoomShareCodecTest {
    @Test
    fun buildShareUriRoundTripsThroughParseShareText() {
        val uri = EasyTierRoomShareCodec.buildShareUri(
            roomId = "my room",
            description = "来一起玩 尖塔",
            password = "p@ss word",
        )

        val invite = EasyTierRoomShareCodec.parseShareText("随便写点提示\n$uri")

        assertEquals(
            EasyTierSharedRoomInvite(
                roomId = "my room",
                description = "来一起玩 尖塔",
                password = "p@ss word",
            ),
            invite,
        )
    }

    @Test
    fun parseShareTextKeepsFriendlyWordingAroundTheUri() {
        val uri = EasyTierRoomShareCodec.buildShareUri(roomId = "abc123", password = "secret")

        val invite = EasyTierRoomShareCodec.parseShareText(
            "我在《杀戮尖塔》建了个房间，快来一起玩！\n房间号：abc123\n$uri\n复制后打开启动器即可加入。",
        )

        assertEquals("abc123", invite?.roomId)
        assertEquals("secret", invite?.password)
    }

    @Test
    fun buildShareUriOmitsEmptyPasswordAndDescription() {
        val uri = EasyTierRoomShareCodec.buildShareUri(roomId = "abc123")

        assertEquals("${EasyTierRoomShareCodec.SHARE_URI_PREFIX}room=abc123", uri)
        assertEquals(EasyTierSharedRoomInvite(roomId = "abc123"), EasyTierRoomShareCodec.parseShareText(uri))
    }

    @Test
    fun parseShareTextRejectsUnrelatedClipboardContent() {
        assertNull(EasyTierRoomShareCodec.parseShareText(null))
        assertNull(EasyTierRoomShareCodec.parseShareText(""))
        assertNull(EasyTierRoomShareCodec.parseShareText("hello world"))
        assertNull(EasyTierRoomShareCodec.parseShareText("https://example.com/join?room=abc"))
        assertNull(EasyTierRoomShareCodec.parseShareText(EasyTierRoomShareCodec.SHARE_URI_PREFIX))
        assertNull(
            EasyTierRoomShareCodec.parseShareText("${EasyTierRoomShareCodec.SHARE_URI_PREFIX}password=only"),
        )
    }

    @Test
    fun parseShareTextStopsAtTheFirstWhitespaceAfterTheUri() {
        val uri = EasyTierRoomShareCodec.buildShareUri(roomId = "abc123", password = "secret")

        val invite = EasyTierRoomShareCodec.parseShareText("$uri 后面还有别的文字")

        assertEquals("abc123", invite?.roomId)
        assertEquals("secret", invite?.password)
    }
}
