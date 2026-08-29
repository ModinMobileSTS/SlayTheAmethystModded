package io.stamethyst.backend.easytier

import java.security.MessageDigest
import java.util.Locale

data class EasyTierRuntimeConfig(
    val instanceName: String,
    val networkName: String,
    val peerUrls: List<String>,
    val toml: String,
)

object EasyTierRuntimeConfigBuilder {
    fun build(
        sessionConfig: EasyTierRoomSessionConfig,
        playerId: String,
        instancePrefix: String = "sts-android",
    ): EasyTierRuntimeConfig {
        val peers = listOf(sessionConfig.entryNodeUrl.trim()).filter(String::isNotEmpty).distinct()
        val assignedCidr = parseEasyTierIpv4Cidr(sessionConfig.assignedIpv4Cidr)?.cidr.orEmpty()
        val instanceName = stableName(instancePrefix, sessionConfig.sessionId, "session", 96, 12)
        val networkName = buildNetworkName(sessionConfig.roomId)
        val hostname = stableName("sts", playerId, "player", 63, 8)
        val toml = buildString {
            append("instance_name = ").toml(instanceName).append('\n')
            append("hostname = ").toml(hostname).append('\n')
            if (assignedCidr.isBlank()) append("dhcp = true\n") else {
                append("ipv4 = ").toml(assignedCidr).append('\n')
                append("dhcp = false\n")
            }
            append("listeners = []\n\n[network_identity]\n")
            append("network_name = ").toml(networkName).append('\n')
            append("network_secret = ").toml(sessionConfig.networkSecret).append("\n\n")
            peers.forEach { append("[[peer]]\nuri = ").toml(it).append("\n\n") }
        }
        return EasyTierRuntimeConfig(instanceName, networkName, peers, toml)
    }

    fun buildNetworkName(roomId: String): String = stableName("sts", roomId, "default-room", 96, 12)

    private fun stableName(prefix: String, value: String, fallback: String, maxLength: Int, hashLength: Int): String {
        val input = value.trim().ifBlank { fallback }
        val hash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }.take(hashLength)
        val length = (maxLength - prefix.length - hash.length - 2).coerceAtLeast(1)
        val body = value.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-")
            .trim('-').ifBlank { fallback }.take(length).trimEnd('-').ifBlank { fallback.take(length) }
        return "$prefix-$body-$hash"
    }

    private fun StringBuilder.toml(value: String): StringBuilder = append('"').apply {
        value.forEach { append(when (it) { '\\' -> "\\\\"; '"' -> "\\\""; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"; else -> it }) }
    }.append('"')
}
