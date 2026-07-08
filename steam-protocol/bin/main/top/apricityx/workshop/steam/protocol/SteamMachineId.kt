package top.apricityx.workshop.steam.protocol

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

fun buildSteamMachineId(
    machineGuidSource: ByteArray,
    macAddressSource: ByteArray,
    diskIdSource: ByteArray,
): ByteArray =
    ByteArrayOutputStream().use { output ->
        writeKeyValueNodeStart(output, "MessageObject")
        writeKeyValueString(output, "BB3", sha1Hex(machineGuidSource))
        writeKeyValueString(output, "FF2", sha1Hex(macAddressSource))
        writeKeyValueString(output, "3B3", sha1Hex(diskIdSource))
        output.write(KEY_VALUE_TYPE_END)
        output.write(KEY_VALUE_TYPE_END)
        output.toByteArray()
    }

internal fun defaultSteamMachineId(): ByteArray =
    buildSteamMachineId(
        machineGuidSource = "android-workshop-demo".toByteArray(Charsets.UTF_8),
        macAddressSource = "android-workshop-demo-mac".toByteArray(Charsets.UTF_8),
        diskIdSource = "android-workshop-demo-disk".toByteArray(Charsets.UTF_8),
    )

private fun sha1Hex(value: ByteArray): String =
    MessageDigest.getInstance("SHA-1")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

private fun writeKeyValueNodeStart(
    output: ByteArrayOutputStream,
    name: String,
) {
    output.write(KEY_VALUE_TYPE_NONE)
    writeNullTerminatedUtf8(output, name)
}

private fun writeKeyValueString(
    output: ByteArrayOutputStream,
    name: String,
    value: String,
) {
    output.write(KEY_VALUE_TYPE_STRING)
    writeNullTerminatedUtf8(output, name)
    writeNullTerminatedUtf8(output, value)
}

private fun writeNullTerminatedUtf8(
    output: ByteArrayOutputStream,
    value: String,
) {
    output.write(value.toByteArray(Charsets.UTF_8))
    output.write(0)
}

private const val KEY_VALUE_TYPE_NONE = 0
private const val KEY_VALUE_TYPE_STRING = 1
private const val KEY_VALUE_TYPE_END = 8
