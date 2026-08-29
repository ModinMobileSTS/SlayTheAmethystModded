package io.stamethyst.backend.easytier

import android.content.Context
import android.os.ResultReceiver
import android.util.Log
import io.stamethyst.R

object EasyTierSessionController {
    private const val LOGCAT_TAG = "STS-EasyTierSession"
    private const val CONFIG_MISSING_FALLBACK = "EasyTier cloud-control config is unavailable."
    private const val PERMISSION_REQUIRED_FALLBACK = "Return to the launcher to grant VPN permission."
    private const val VPN_REVOKED_FALLBACK =
        "EasyTier VPN permission was revoked. Grant permission again to reconnect."

    fun currentSnapshot(context: Context): EasyTierConnectionSnapshot {
        val config = EasyTierConfigRepository.current()
        val nowMs = System.currentTimeMillis()
        val stored = EasyTierStateStore.readSnapshot(context)
        val storedSnapshot = stored ?: buildInitialSnapshot(context, config, nowMs)
        // The foreground service and VPN are process-bound. A persisted active snapshot from a
        // previous process cannot prove that the tunnel still exists after a reboot/force-stop.
        val processCheckedSnapshot = if (
            storedSnapshot.isConnectionActive && !EasyTierProcessService.isRunning(context)
        ) {
            buildDisconnectedSnapshot(storedSnapshot, nowMs = nowMs).also { snapshot ->
                runCatching { EasyTierStateStore.writeSnapshot(context, snapshot) }
            }
        } else {
            storedSnapshot
        }
        val base = if (
            config.canConnect &&
            !processCheckedSnapshot.isConnectionActive &&
            processCheckedSnapshot.failureCategory == EasyTierFailureCategory.ConfigMissing
        ) {
            buildInitialSnapshot(context, config, nowMs)
        } else {
            processCheckedSnapshot
        }
        if (!config.canConnect && !base.isConnectionActive) {
            return buildInitialSnapshot(context, config, nowMs)
        }

        val shouldRefreshConfigFields = !base.isConnectionActive
        return base.copy(
            enabled = config.enabled,
            canConnect = config.canConnect,
            mode = if (shouldRefreshConfigFields) config.defaultMode else base.mode,
            entryNodeUrl = if (shouldRefreshConfigFields) {
                config.entryNodeUrl
            } else {
                base.entryNodeUrl.ifBlank { config.entryNodeUrl }
            },
            lastUpdatedAtMs = base.lastUpdatedAtMs.takeIf { it > 0L } ?: nowMs,
        )
    }

    fun requestConnect(
        context: Context,
        mode: EasyTierNetworkMode? = null,
        roomId: String = "",
        userInitiated: Boolean = true,
        receiver: ResultReceiver? = null,
        roomDescriptionWhenCreating: String = "",
        allowNewJoinsWhenCreating: Boolean? = null,
        createOnly: Boolean = false,
        password: String = "",
    ): Boolean = EasyTierProcessService.startConnect(
        context,
        mode,
        roomId,
        userInitiated,
        receiver,
        roomDescriptionWhenCreating,
        allowNewJoinsWhenCreating,
        createOnly,
        password,
    )

    fun requestDisconnect(
        context: Context,
        receiver: ResultReceiver? = null,
    ) {
        EasyTierProcessService.disconnect(context, receiver)
    }

    internal fun buildInitialSnapshot(
        context: Context,
        config: EasyTierResolvedConfig,
        nowMs: Long = System.currentTimeMillis(),
    ): EasyTierConnectionSnapshot {
        val ready = config.canConnect
        return EasyTierConnectionSnapshot(
            enabled = config.enabled,
            canConnect = config.canConnect,
            status = if (ready) {
                EasyTierConnectionStatus.DISCONNECTED
            } else {
                EasyTierConnectionStatus.FAILED
            },
            mode = config.defaultMode,
            failureCategory = if (ready) {
                EasyTierFailureCategory.None
            } else {
                EasyTierFailureCategory.ConfigMissing
            },
            entryNodeUrl = config.entryNodeUrl,
            configServerUrl = config.configServerUrl,
            lastUpdatedAtMs = nowMs,
            lastErrorSummary = if (ready) {
                ""
            } else {
                resolveString(
                    context,
                    R.string.main_easytier_config_missing,
                    CONFIG_MISSING_FALLBACK,
                )
            },
        )
    }

    internal fun buildConnectingSnapshot(
        config: EasyTierResolvedConfig,
        mode: EasyTierNetworkMode,
        roomId: String,
        userInitiated: Boolean,
        currentPlayerId: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): EasyTierConnectionSnapshot {
        return EasyTierConnectionSnapshot(
            enabled = config.enabled,
            canConnect = config.canConnect,
            status = EasyTierConnectionStatus.CONNECTING,
            mode = mode,
            failureCategory = EasyTierFailureCategory.None,
            roomId = roomId,
            entryNodeUrl = config.entryNodeUrl,
            configServerUrl = config.configServerUrl,
            startedAtMs = nowMs,
            lastUpdatedAtMs = nowMs,
            currentPlayerId = currentPlayerId,
            userInitiated = userInitiated,
        )
    }

    internal fun buildSessionReadySnapshot(
        previous: EasyTierConnectionSnapshot,
        sessionConfig: EasyTierRoomSessionConfig,
        sessionStatus: EasyTierSessionStatusSnapshot? = null,
        currentPlayerId: String = previous.currentPlayerId,
        nowMs: Long = System.currentTimeMillis(),
    ): EasyTierConnectionSnapshot {
        return previous.copy(
            status = EasyTierConnectionStatus.SESSION_READY,
            mode = sessionConfig.mode,
            failureCategory = EasyTierFailureCategory.None,
            sessionId = sessionConfig.sessionId,
            roomId = sessionConfig.roomId,
            entryNodeUrl = sessionConfig.entryNodeUrl.ifBlank { previous.entryNodeUrl },
            configServerUrl = sessionConfig.configServerUrl.ifBlank { previous.configServerUrl },
            aclGroup = sessionConfig.aclGroup,
            expiresAtEpochSeconds = sessionConfig.expiresAtEpochSeconds,
            connectedAtMs = null,
            lastUpdatedAtMs = nowMs,
            lastErrorSummary = "",
            assignedIpv4Cidr = sessionStatus?.assignedIpv4Cidr.orEmpty(),
            currentPlayerId = currentPlayerId,
            peerCount = sessionStatus?.peerCount,
            relayServerDescription = sessionStatus?.relayServerDescription.orEmpty(),
            lastSessionState = sessionStatus?.sessionState.orEmpty(),
            lastRoomState = sessionStatus?.roomState.orEmpty(),
        )
    }

    internal fun buildPermissionRequiredSnapshot(
        context: Context,
        previous: EasyTierConnectionSnapshot,
        nowMs: Long = System.currentTimeMillis(),
    ): EasyTierConnectionSnapshot {
        return previous.copy(
            status = EasyTierConnectionStatus.PERMISSION_REQUIRED,
            failureCategory = EasyTierFailureCategory.VpnPermissionRequired,
            startedAtMs = previous.startedAtMs ?: nowMs,
            connectedAtMs = null,
            lastUpdatedAtMs = nowMs,
            lastErrorSummary = resolveString(
                context,
                R.string.main_easytier_notification_permission_required,
                PERMISSION_REQUIRED_FALLBACK,
            ),
            assignedIpv4Cidr = "",
            peerCount = null,
            relayServerDescription = "",
            lastSessionState = previous.lastSessionState,
            lastRoomState = previous.lastRoomState,
        )
    }

    internal fun buildPermissionRevokedSnapshot(
        context: Context,
        previous: EasyTierConnectionSnapshot,
        nowMs: Long = System.currentTimeMillis(),
    ): EasyTierConnectionSnapshot {
        return previous.copy(
            status = EasyTierConnectionStatus.PERMISSION_REQUIRED,
            failureCategory = EasyTierFailureCategory.VpnPermissionRevoked,
            connectedAtMs = null,
            lastUpdatedAtMs = nowMs,
            lastErrorSummary = resolveString(
                context,
                R.string.main_easytier_vpn_revoked,
                VPN_REVOKED_FALLBACK,
            ),
            assignedIpv4Cidr = "",
            peerCount = null,
            relayServerDescription = "",
            lastSessionState = previous.lastSessionState,
            lastRoomState = previous.lastRoomState,
        )
    }

    internal fun buildDisconnectedSnapshot(
        previous: EasyTierConnectionSnapshot,
        summary: String = "",
        failureCategory: EasyTierFailureCategory = EasyTierFailureCategory.None,
        terminalSessionState: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): EasyTierConnectionSnapshot {
        return previous.copy(
            status = EasyTierConnectionStatus.DISCONNECTED,
            failureCategory = failureCategory,
            startedAtMs = null,
            connectedAtMs = null,
            sessionId = "",
            lastUpdatedAtMs = nowMs,
            lastErrorSummary = summary,
            assignedIpv4Cidr = "",
            roomOwnerPlayerId = "",
            roomOwnerIpv4Cidr = "",
            peerCount = null,
            relayServerDescription = "",
            aclGroup = "",
            expiresAtEpochSeconds = null,
            lastSessionState = terminalSessionState,
            lastRoomState = "",
        )
    }

    /**
     * Marks the connection as failed while preserving everything needed to recover from it.
     *
     * [assignedIpv4Cidr] and [connectedAtMs] are deliberately carried over. The Room API only
     * renews a session lease from a runtime report, and that report is skipped when the snapshot has
     * no address, so blanking the address here stopped lease renewal at exactly the moment the
     * connection was struggling. The server then expired the session after its TTL and the next poll
     * got a 404, which turned a single transient request failure into a permanent disconnect.
     *
     * [connectedAtMs] is kept for the same reason in reverse: it is the only evidence that this
     * session ever reached a working tunnel, which is what separates "never connected, give up" from
     * "already connected, keep retrying" in [hasEasyTierConnectionTimedOut].
     *
     * [peerCount] and [relayServerDescription] are still cleared because they are point-in-time
     * observations of the room that must not be shown as current while the connection is broken.
     */
    internal fun buildFailureSnapshot(
        previous: EasyTierConnectionSnapshot,
        summary: String,
        failureCategory: EasyTierFailureCategory = EasyTierErrorClassifier.classifyFromSummary(summary),
        nowMs: Long = System.currentTimeMillis(),
    ): EasyTierConnectionSnapshot {
        return previous.copy(
            status = EasyTierConnectionStatus.FAILED,
            failureCategory = failureCategory,
            lastUpdatedAtMs = nowMs,
            lastErrorSummary = summary,
            peerCount = null,
            relayServerDescription = "",
        )
    }

    internal fun persistSnapshot(
        context: Context,
        snapshot: EasyTierConnectionSnapshot,
        extraLines: List<String> = emptyList(),
        error: Throwable? = null,
    ): EasyTierConnectionSnapshot {
        val persisted = snapshot.copy(
            diagnosticsSummaryPath = EasyTierDiagnosticsStore.summaryFile(context).absolutePath,
        )
        runCatching {
            EasyTierStateStore.writeSnapshot(context, persisted)
        }.onFailure { failure ->
            Log.w(LOGCAT_TAG, "Failed to persist EasyTier state snapshot", failure)
        }
        runCatching {
            EasyTierDiagnosticsStore.recordStateTransition(context, persisted, extraLines, error)
        }.onFailure { failure ->
            Log.w(LOGCAT_TAG, "Failed to persist EasyTier diagnostics summary", failure)
        }
        return persisted
    }

    private fun resolveString(
        context: Context,
        resId: Int,
        fallback: String,
    ): String = runCatching { context.getString(resId) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: fallback

    @JvmStatic
    fun resolveRequestedRoomId(
        mode: EasyTierNetworkMode,
        requestedRoomId: String,
    ): String {
        val normalized = requestedRoomId.trim()
        if (normalized.isNotEmpty()) {
            return normalized
        }
        return when (mode) {
            EasyTierNetworkMode.Room -> DEFAULT_EASYTIER_SHARED_ROOM_ID
            EasyTierNetworkMode.Community -> DEFAULT_EASYTIER_SHARED_ROOM_ID
        }
    }
}
