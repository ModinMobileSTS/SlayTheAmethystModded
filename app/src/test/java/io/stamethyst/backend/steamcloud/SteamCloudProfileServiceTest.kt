package io.stamethyst.backend.steamcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SteamCloudProfileServiceTest {
    @Test
    fun parseProfileXml_keepsTopLevelAvatar_whenNestedAvatarFieldsExist() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <profile>
                <steamID64>76561198883607238</steamID64>
                <steamID><![CDATA[Apricityx_]]></steamID>
                <avatarIcon><![CDATA[https://avatars.example.com/top-icon.jpg]]></avatarIcon>
                <avatarMedium><![CDATA[https://avatars.example.com/top-medium.jpg]]></avatarMedium>
                <avatarFull><![CDATA[https://avatars.example.com/top-full.jpg]]></avatarFull>
                <favoriteBadge>
                    <avatarIcon><![CDATA[https://avatars.example.com/nested-icon.jpg]]></avatarIcon>
                    <avatarMedium><![CDATA[https://avatars.example.com/nested-medium.jpg]]></avatarMedium>
                    <avatarFull><![CDATA[https://avatars.example.com/nested-full.jpg]]></avatarFull>
                </favoriteBadge>
            </profile>
        """.trimIndent()

        val profile = SteamCloudProfileService.parseProfileXml("76561198883607238", xml)

        assertNotNull(profile)
        assertEquals("76561198883607238", profile?.steamId64)
        assertEquals("Apricityx_", profile?.personaName)
        assertEquals("https://avatars.example.com/top-full.jpg", profile?.avatarUrl)
    }

    @Test
    fun parseProfileXml_rejectsMismatchedSteamId() {
        val xml = """
            <profile>
                <steamID64>76561198000000000</steamID64>
                <steamID><![CDATA[SomeoneElse]]></steamID>
                <avatarFull><![CDATA[https://avatars.example.com/wrong.jpg]]></avatarFull>
            </profile>
        """.trimIndent()

        val profile = SteamCloudProfileService.parseProfileXml("76561198883607238", xml)

        assertNull(profile)
    }
}
