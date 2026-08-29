package io.stamethyst.backend.easytier

import java.util.Locale

data class EasyTierIpv4Cidr(val address: String, val prefixLength: Int) {
    val cidr: String get() = "$address/$prefixLength"
}

fun parseEasyTierIpv4Cidr(value: String): EasyTierIpv4Cidr? {
    val parts = value.trim().split('/', limit = 2)
    val ip = parts.firstOrNull()?.trim().orEmpty()
    val prefix = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 32
    if (ip.isEmpty() || prefix !in 0..32) return null
    val number = parseIpv4(ip) ?: return null
    return EasyTierIpv4Cidr(formatIpv4(number), prefix)
}

fun normalizeEasyTierIpv4Route(value: String): EasyTierIpv4Cidr? {
    val parsed = parseEasyTierIpv4Cidr(value) ?: return null
    val mask = if (parsed.prefixLength == 0) 0L else (0xffffffffL shl (32 - parsed.prefixLength)) and 0xffffffffL
    return EasyTierIpv4Cidr(formatIpv4((parseIpv4(parsed.address) ?: return null) and mask), parsed.prefixLength)
}

fun isDefaultEasyTierIpv4Route(value: String): Boolean =
    normalizeEasyTierIpv4Route(value)?.let { it.address == "0.0.0.0" && it.prefixLength == 0 } == true

fun formatEasyTierIpv4Inet(addressValue: Long, networkLength: Int): String? =
    if (networkLength in 0..32) "${formatIpv4(addressValue and 0xffffffffL)}/$networkLength" else null

private fun parseIpv4(value: String): Long? {
    val pieces = value.trim().lowercase(Locale.US).split('.')
    if (pieces.size != 4) return null
    return pieces.fold(0L) { result, piece ->
        val octet = piece.toIntOrNull() ?: return null
        if (octet !in 0..255) return null
        (result shl 8) or octet.toLong()
    }
}

private fun formatIpv4(value: Long): String = listOf(
    (value ushr 24) and 0xff, (value ushr 16) and 0xff, (value ushr 8) and 0xff, value and 0xff,
).joinToString(".")
