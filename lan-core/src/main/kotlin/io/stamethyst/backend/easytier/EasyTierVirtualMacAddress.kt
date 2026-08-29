package io.stamethyst.backend.easytier

import java.security.MessageDigest

/** Builds a stable, locally administered MAC for server-side room address allocation. */
object EasyTierVirtualMacAddress {
    fun fromDeviceId(deviceId: String, fallback: String = "sts-device"): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(deviceId.trim().ifBlank { fallback }.toByteArray(Charsets.UTF_8))
        val bytes = digest.copyOfRange(0, 6)
        bytes[0] = ((bytes[0].toInt() and 0xfe) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }
}
