package io.stamethyst.backend.easytier

import org.json.JSONArray
import org.json.JSONObject

internal data class EasyTierRuntimeInfo(
    val instanceName: String,
    val running: Boolean,
    val errorMessage: String,
    val virtualIpv4Cidr: String,
    val routeCidrs: List<String>,
    val peerCount: Int?,
    val peerConnections: List<EasyTierPeerConnection> = emptyList(),
)

internal object EasyTierRuntimeInfoParser {
    fun parse(
        rawJson: String?,
        instanceName: String,
    ): EasyTierRuntimeInfo? {
        val root = rawJson?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val mapObject = runCatching { JSONObject(root).optJSONObjectCompat("map") }.getOrNull()
            ?: return null
        // Never reuse another instance's routes during a room/session change.
        val instanceObject = mapObject.optJSONObjectCompat(instanceName) ?: return null

        val myNodeInfo = instanceObject.optJSONObjectCompat("my_node_info", "myNodeInfo")
        val virtualIpv4Cidr = parseIpv4Inet(
            myNodeInfo?.optAny("virtual_ipv4", "virtualIpv4")
        ).orEmpty()
        val routeCidrs = buildList {
            if (virtualIpv4Cidr.isNotBlank()) {
                normalizeEasyTierIpv4Route(virtualIpv4Cidr)?.cidr?.let(::add)
            }
            instanceObject.optJSONArrayCompat("routes")?.forEachObject { route ->
                route.optStringArray("proxy_cidrs", "proxyCidrs").forEach { cidr ->
                    normalizeEasyTierIpv4Route(cidr)?.cidr
                        ?.takeUnless(::isDefaultEasyTierIpv4Route)
                        ?.let(::add)
                }
            }
        }.distinct()

        return EasyTierRuntimeInfo(
            instanceName = instanceName,
            running = instanceObject.optBooleanCompat("running"),
            errorMessage = instanceObject.optStringCompat(
                "error_msg",
                "errorMsg",
                "error_message",
                "errorMessage",
            ),
            virtualIpv4Cidr = virtualIpv4Cidr,
            routeCidrs = routeCidrs,
            peerCount = instanceObject.optJSONArrayCompat("peers")?.length()
                ?: instanceObject.optJSONArrayCompat("routes")?.length(),
            peerConnections = parsePeerConnections(instanceObject, virtualIpv4Cidr),
        )
    }

    private fun parsePeerConnections(instance: JSONObject, localCidr: String): List<EasyTierPeerConnection> {
        val peers = mutableMapOf<Long, List<JSONObject>>()
        instance.optJSONArrayCompat("peers")?.forEachObject { peer ->
            val id = peer.optLongCompat("peer_id", "peerId") ?: return@forEachObject
            peers[id] = buildList {
                peer.optJSONArrayCompat("conns")?.forEachObject { conn ->
                    if (!conn.optBooleanCompat("is_closed") && !conn.optBooleanCompat("isClosed")) add(conn)
                }
            }
        }
        return buildList {
            instance.optJSONArrayCompat("routes")?.forEachObject { route ->
                val cidr = parseIpv4Inet(route.optAny("ipv4_addr", "ipv4Addr")) ?: return@forEachObject
                val host = cidr.substringBefore('/')
                if (host == localCidr.substringBefore('/') || host == "0.0.0.0") return@forEachObject
                val flags = route.optJSONObjectCompat("feature_flag", "featureFlag")
                if (flags?.optBooleanCompat("is_public_server") == true ||
                    flags?.optBooleanCompat("isPublicServer") == true) return@forEachObject
                val cost = route.optIntCompat("cost") ?: return@forEachObject
                val id = route.optLongCompat("peer_id", "peerId") ?: return@forEachObject
                val conns = peers[id].orEmpty()
                val kind = when {
                    cost > 1 -> EasyTierPeerConnectionKind.Relay
                    cost == 1 && conns.isNotEmpty() -> EasyTierPeerConnectionKind.Direct
                    else -> return@forEachObject
                }
                val protocols = if (kind == EasyTierPeerConnectionKind.Direct) {
                    conns.mapNotNull { conn ->
                        conn.optJSONObjectCompat("tunnel")
                            ?.optStringCompat("tunnel_type", "tunnelType")
                            ?.lowercase()?.takeIf { it in setOf("udp", "tcp", "quic", "ws", "wss", "wg") }
                    }.distinct().sorted().joinToString("/")
                } else ""
                add(EasyTierPeerConnection(host, kind, protocols))
            }
        }.distinctBy { it.virtualIpv4 }
    }

    private fun parseIpv4Inet(value: Any?): String? {
        return when (value) {
            is String -> parseEasyTierIpv4Cidr(value)?.cidr
            is JSONObject -> {
                val address = value.optAny("address")
                val networkLength = value.optIntCompat("network_length", "networkLength")
                    ?: value.optIntCompat("prefix_length", "prefixLength")
                    ?: 32
                val addressValue = when (address) {
                    is JSONObject -> address.optLongCompat("addr")
                    is Number -> address.toLong()
                    is String -> address.toLongOrNull()
                    else -> null
                } ?: return null
                formatEasyTierIpv4Inet(addressValue, networkLength)
            }
            else -> null
        }
    }

    private fun JSONObject.optJSONObjectCompat(vararg names: String): JSONObject? {
        for (name in names) {
            val value = opt(name)
            if (value is JSONObject) return value
        }
        return null
    }

    private fun JSONObject.optJSONArrayCompat(vararg names: String): JSONArray? {
        for (name in names) {
            val value = opt(name)
            if (value is JSONArray) return value
        }
        return null
    }

    private fun JSONObject.optAny(vararg names: String): Any? {
        for (name in names) {
            if (has(name)) {
                return opt(name)
            }
        }
        return null
    }

    private fun JSONObject.optStringCompat(vararg names: String): String {
        for (name in names) {
            val value = optString(name, "").trim()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    private fun JSONObject.optBooleanCompat(name: String): Boolean {
        val value = opt(name)
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            else -> false
        }
    }

    private fun JSONObject.optIntCompat(vararg names: String): Int? {
        for (name in names) {
            if (!has(name)) continue
            val value = opt(name)
            val parsed = when (value) {
                is Number -> value.toInt()
                is String -> value.trim().toIntOrNull()
                else -> null
            }
            if (parsed != null) return parsed
        }
        return null
    }

    private fun JSONObject.optLongCompat(vararg names: String): Long? {
        for (name in names) {
            if (!has(name)) continue
            val value = opt(name)
            val parsed = when (value) {
                is Number -> value.toLong()
                is String -> value.trim().toLongOrNull()
                else -> null
            }
            if (parsed != null) return parsed
        }
        return null
    }

    private fun JSONObject.optStringArray(vararg names: String): List<String> {
        val array = optJSONArrayCompat(*names) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
            }
        }
    }

    private fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
        for (index in 0 until length()) {
            val value = opt(index)
            if (value is JSONObject) block(value)
        }
    }
}
