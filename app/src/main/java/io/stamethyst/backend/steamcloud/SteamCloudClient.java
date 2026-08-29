package io.stamethyst.backend.steamcloud;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.google.protobuf.GeneratedMessage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.security.MessageDigest;

import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.enums.EMsg;
import in.dragonbra.javasteam.enums.EOSType;
import in.dragonbra.javasteam.base.ClientMsgProtobuf;
import in.dragonbra.javasteam.base.IPacketMsg;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver2;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesCloudSteamclient;
import in.dragonbra.javasteam.protobufs.steamclient.Enums;
import in.dragonbra.javasteam.networking.steam3.ProtocolTypes;
import in.dragonbra.javasteam.rpc.service.Cloud;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.EAuthSessionGuardType;
import in.dragonbra.javasteam.steam.authentication.AuthenticationException;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSession;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.steam.discovery.FileServerListProvider;
import in.dragonbra.javasteam.steam.discovery.ServerRecord;
import in.dragonbra.javasteam.steam.discovery.SmartCMServerList;
import in.dragonbra.javasteam.steam.handlers.steamcloud.AppFileChangeList;
import in.dragonbra.javasteam.steam.handlers.steamcloud.AppFileInfo;
import in.dragonbra.javasteam.steam.handlers.steamcloud.FileDownloadInfo;
import in.dragonbra.javasteam.steam.handlers.steamcloud.HttpHeaders;
import in.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud;
import in.dragonbra.javasteam.steam.handlers.ClientMsgHandler;
import in.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages;
import in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.callback.ServiceMethodResponse;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.PlayingSessionStateCallback;
import in.dragonbra.javasteam.enums.EPersonaState;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration;
import in.dragonbra.javasteam.types.AsyncJobSingle;
import in.dragonbra.javasteam.types.SteamID;
import in.dragonbra.javasteam.types.JobID;
import in.dragonbra.javasteam.types.KeyValue;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverUserstats;
import in.dragonbra.javasteam.util.log.LogListener;
import in.dragonbra.javasteam.util.log.LogManager;
import io.stamethyst.config.RuntimePaths;
import okhttp3.RequestBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class SteamCloudClient implements AutoCloseable {
    private static final String TAG = "SteamCloudClient";
    private static final long CONNECT_TIMEOUT_MS = 40_000L;
    private static final long AUTH_START_TIMEOUT_MS = 60_000L;
    private static final long AUTH_POLL_TIMEOUT_MS = 4L * 60L * 1000L;
    private static final long LOGON_TIMEOUT_MS = 45_000L;
    private static final long RPC_TIMEOUT_MS = 90_000L;
    private static final long DOWNLOAD_TIMEOUT_MS = 60_000L;
    private static final long CALLBACK_POLL_TIMEOUT_MS = 250L;
    private static final int DOWNLOAD_MAX_ATTEMPTS = 4;
    private static final long[] DOWNLOAD_RETRY_DELAYS_MS = new long[] { 2_000L, 5_000L, 10_000L };
    private static final int BEGIN_UPLOAD_BATCH_MAX_ATTEMPTS = 7;
    private static final int BEGIN_HTTP_UPLOAD_MAX_ATTEMPTS = 7;
    private static final long[] BEGIN_HTTP_UPLOAD_RETRY_DELAYS_MS = new long[] { 2_000L, 5_000L, 10_000L };
    private static final long[] BEGIN_HTTP_UPLOAD_PENDING_RETRY_DELAYS_MS =
        new long[] { 10_000L, 20_000L, 30_000L, 60_000L, 90_000L, 120_000L };
    private static final int TRANSIENT_RPC_MAX_ATTEMPTS = 4;
    private static final long[] TRANSIENT_RPC_RETRY_DELAYS_MS = new long[] { 2_000L, 5_000L, 10_000L };
    private static final int COMPLETE_UPLOAD_BATCH_MAX_ATTEMPTS = 5;
    private static final long[] COMPLETE_UPLOAD_BATCH_RETRY_DELAYS_MS = new long[] { 2_000L, 5_000L, 10_000L, 20_000L };
    private static final int JAVA_STEAM_LOG_TAIL_LIMIT = 12;
    private static final int JAVA_STEAM_STACKTRACE_LINE_LIMIT = 24;
    private static final int DIAGNOSTIC_EVENT_LIMIT = 96;
    public static final long DEFAULT_MAX_COMPRESSED_DOWNLOAD_BYTES = 256L * 1024L * 1024L;
    public static final long DEFAULT_MAX_RAW_DOWNLOAD_BYTES = 512L * 1024L * 1024L;
    private static final int IO_BUFFER_SIZE = 8192;
    private static final String OUTPUT_DIR_NAME = "steam-cloud";
    private static final String UPLOAD_SNAPSHOT_DIR_NAME = "upload-snapshots";
    private static final String LAST_CM_ENDPOINT_FILE_NAME = "last-websocket-cm-endpoint.txt";
    private static final String CM_SERVER_LIST_FILE_NAME = "steam-cm-server-list.bin";

    private final SteamClient steamClient;
    private final CallbackManager callbackManager;
    private final SteamUser steamUser;
    private final SteamFriends steamFriends;
    private final SteamCloud steamCloud;
    private final Cloud cloudService;
    private final OkHttpClient httpClient;
    private final OkHttpClient protocolHttpClient;
    private final SteamCloudProtocolClient protocolClient;
    private final File lastCmEndpointFile;
    private final File uploadSnapshotDir;
    private final DownloadLimits downloadLimits;
    private final boolean wattAccelerationEnabled;
    private final EnumSet<ProtocolTypes> protocolTypes = EnumSet.of(ProtocolTypes.WEB_SOCKET);
    private final JavaSteamLogCollector javaSteamLogCollector;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final CompletableFuture<Void> connectedFuture = new CompletableFuture<>();
    private final CompletableFuture<Void> disconnectedFuture = new CompletableFuture<>();
    private final CompletableFuture<LoggedOnCallback> loggedOnFuture = new CompletableFuture<>();
    private volatile String currentStage = "startup";
    private volatile boolean connectedCallbackReceived = false;
    private volatile String loggedOnResultDescription = "<not received>";
    private volatile String disconnectedDescription = "<not observed>";
    private volatile String resolvedServerDescription = "<not resolved>";
    private volatile String candidateSourceDescription = "<not selected>";
    private volatile String allowedChallengesDescription = "<not evaluated>";
    private volatile String lastAuthPromptDescription = "<not requested>";
    private volatile boolean guardDataConfigured = false;
    private volatile boolean guardDataUpdated = false;
    private volatile String currentSteamId64 = "";
    private volatile String credentialsAuthSteamId64 = "";
    private volatile String loggedOnCallbackSteamId64 = "";
    private volatile String steamClientSteamId64 = "";
    private volatile long cmServerSelectionMs = -1L;
    private volatile long cmConnectWaitMs = -1L;
    private volatile boolean playingSessionBlocked;
    private volatile int playingSessionAppId;
    private final Object diagnosticEventsLock = new Object();
    private final ArrayDeque<String> diagnosticEvents = new ArrayDeque<>();
    private Thread callbackThread;

    private static final class PreparedServerRecord {
        private final ServerRecord serverRecord;
        private final String candidateSourceDescription;

        private PreparedServerRecord(ServerRecord serverRecord, String candidateSourceDescription) {
            this.serverRecord = serverRecord;
            this.candidateSourceDescription = candidateSourceDescription;
        }
    }

    private void reportTransportAbortFromJavaSteamLog(JavaSteamLogEntry entry) {
        if (entry == null || shuttingDown.get() || !running.get() || disconnectedFuture.isDone()) {
            return;
        }
        disconnectedDescription = "unexpected transport abort";
        recordDiagnosticEvent("transport_abort_log " + entry.describe());
        IllegalStateException error = new IllegalStateException(
            buildDisconnectFailureMessage(disconnectedDescription)
                + " JavaSteam log: "
                + entry.describe()
        );
        disconnectedFuture.completeExceptionally(error);
        connectedFuture.completeExceptionally(error);
        loggedOnFuture.completeExceptionally(error);
    }

    public SteamCloudClient(Context context) {
        this(context, DownloadLimits.defaults());
    }

    public SteamCloudClient(Context context, DownloadLimits downloadLimits) {
        this(context, downloadLimits, true);
    }

    /**
     * @param useSharedCmSession when true (the default), the CM transport is the
     *     process-wide shared session from {@code SharedSteamCmSessions}; closing
     *     this client then only releases the borrowed handle. The credential
     *     login flow passes {@code false} to keep its dedicated diagnostics
     *     transport isolated from the shared connection.
     */
    public SteamCloudClient(Context context, DownloadLimits downloadLimits, boolean useSharedCmSession) {
        applyProxySystemProperties();
        this.downloadLimits = Objects.requireNonNull(downloadLimits, "downloadLimits");

        File outputDir = new File(RuntimePaths.storageRoot(context), OUTPUT_DIR_NAME);
        if (!outputDir.isDirectory()) {
            outputDir.mkdirs();
        }
        lastCmEndpointFile = new File(outputDir, LAST_CM_ENDPOINT_FILE_NAME);
        uploadSnapshotDir = new File(outputDir, UPLOAD_SNAPSHOT_DIR_NAME);

        wattAccelerationEnabled = SteamCloudAcceleratedHttp.isEnabled(context);
        httpClient = SteamCloudAcceleratedHttp.createClient(
            context,
            DOWNLOAD_TIMEOUT_MS,
            DOWNLOAD_TIMEOUT_MS,
            DOWNLOAD_TIMEOUT_MS
        );
        protocolHttpClient = httpClient;
        protocolClient = new SteamCloudProtocolClient(
            httpClient,
            SteamCloudAcceleratedHttp.createWebSocketFactory(context, httpClient),
            useSharedCmSession
                ? io.stamethyst.backend.workshop.SharedSteamCmSessions.forProcess(context).asCmSession()
                : null
        );
        Log.i(TAG, "Steam Cloud Watt acceleration: " + (wattAccelerationEnabled ? "enabled" : "disabled") + '.');

        // JavaSteam's internal Ktor websocket cannot be given the accelerated OkHttp transport.
        // Keep all CM traffic on the protocol client created above.
        steamClient = null;
        callbackManager = null;
        steamUser = null;
        steamFriends = null;
        steamCloud = null;
        cloudService = null;
        javaSteamLogCollector = null;
    }

    public void beginOperationDiagnostics(String operation, String accountName, boolean hasGuardData) {
        currentStage = operation == null || operation.trim().isEmpty() ? "startup" : operation.trim();
        guardDataConfigured = hasGuardData;
        guardDataUpdated = false;
        allowedChallengesDescription = "<not evaluated>";
        lastAuthPromptDescription = "<not requested>";
        loggedOnResultDescription = "<not received>";
        currentSteamId64 = "";
        credentialsAuthSteamId64 = "";
        loggedOnCallbackSteamId64 = "";
        steamClientSteamId64 = "";
        disconnectedDescription = "<not observed>";
        cmServerSelectionMs = -1L;
        cmConnectWaitMs = -1L;
        playingSessionBlocked = false;
        playingSessionAppId = 0;
        synchronized (diagnosticEventsLock) {
            diagnosticEvents.clear();
        }
        recordDiagnosticEvent(
            "operation_begin operation="
                + currentStage
                + " account="
                + (isBlank(accountName) ? "<unknown>" : accountName.trim())
                + " guardDataConfigured="
                + hasGuardData
                + " protocolTypes="
                + describeProtocolTypes(protocolTypes)
                + " wattAcceleration="
                + (wattAccelerationEnabled ? "enabled" : "disabled")
        );
        Log.i(
            TAG,
            "Beginning Steam Cloud operation="
                + currentStage
                + " account="
                + (isBlank(accountName) ? "<unknown>" : accountName.trim())
                + " guardDataConfigured="
                + hasGuardData
        );
    }

    public void start() throws Exception {
        try {
            running.set(true);
            candidateSourceDescription = "Steam directory via accelerated OkHttp";
            recordDiagnosticEvent("cm_protocol_ready transport=OkHttp accelerated=" + wattAccelerationEnabled);
        } catch (Exception error) {
            recordDiagnosticEvent("cm_connect failed " + describeThrowable(error));
            Log.e(TAG, "Steam connect failed during " + currentStage + '.', error);
            throw error;
        }
    }

    private void startSteamConnection(ServerRecord serverRecord) {
        throw new UnsupportedOperationException("JavaSteam CM transport is disabled; use the accelerated protocol client.");
    }

    public AuthMaterial authenticateWithCredentials(
        String username,
        String password,
        String guardData,
        AuthPrompt prompt
    ) throws Exception {
        throw new UnsupportedOperationException(
            "Credential authentication uses SteamCloudAuthCoordinator's accelerated protocol flow."
        );
    }

    public void logOnWithRefreshToken(String accountName, String refreshToken) throws Exception {
        logOnWithRefreshToken(accountName, refreshToken, "");
    }

    public void logOnWithRefreshToken(String accountName, String refreshToken, String steamId64) throws Exception {
        try {
            recordDiagnosticEvent(
                "refresh_token_logon begin account="
                    + (isBlank(accountName) ? "<unknown>" : accountName.trim())
                    + " tokenProvided="
                    + !isBlank(refreshToken)
                    + " tokenLength="
                    + (refreshToken == null ? 0 : refreshToken.length())
                    + " steamIdProvided="
                    + !isBlank(steamId64)
            );
            Log.i(
                TAG,
                "Logging on with refresh token for account="
                    + (isBlank(accountName) ? "<unknown>" : accountName.trim())
            );
            long startedAtNs = System.nanoTime();
            long resolvedSteamId = protocolClient.logOn(accountName, refreshToken, steamId64);
            requireMatchingSteamIdentity(steamId64, resolvedSteamId);
            cmConnectWaitMs = elapsedMillis(startedAtNs);
            currentSteamId64 = String.valueOf(resolvedSteamId);
            loggedOnCallbackSteamId64 = currentSteamId64;
            steamClientSteamId64 = currentSteamId64;
            connectedCallbackReceived = true;
            loggedOnResultDescription = "OK";
            resolvedServerDescription = "Steam directory websocket CM";
            recordDiagnosticEvent(
                "refresh_token_logon completed steamIdResolved="
                    + !isBlank(currentSteamId64)
                    + " clientSteamIdResolved="
                    + !isBlank(steamClientSteamId64)
            );
        } catch (Exception error) {
            recordDiagnosticEvent("refresh_token_logon failed " + describeThrowable(error));
            Log.e(TAG, "Refresh-token logon failed during " + currentStage + '.', error);
            throw error;
        }
    }

    public String getCurrentSteamId64() {
        return currentSteamId64;
    }

    static void requireMatchingSteamIdentity(String expectedSteamId64, long resolvedSteamId) throws IOException {
        String resolved = String.valueOf(resolvedSteamId);
        if (resolvedSteamId <= 0L ||
            (!isBlank(expectedSteamId64) && !resolved.equals(expectedSteamId64.trim()))) {
            throw new IOException(
                "Authenticated Steam account does not match the saved Steam Cloud account identity."
            );
        }
    }

    public static final class UserStatsResult {
        public static final class AchievementDefinition {
            public final int achievementId;
            public final String apiName;
            public final String displayName;
            public final String description;
            public final String icon;
            public final String iconGray;

            AchievementDefinition(
                int achievementId,
                String apiName,
                String displayName,
                String description,
                String icon,
                String iconGray
            ) {
                this.achievementId = achievementId;
                this.apiName = apiName;
                this.displayName = displayName;
                this.description = description;
                this.icon = icon;
                this.iconGray = iconGray;
            }
        }

        public static final class AchievementStatTarget {
            public final int statId;
            public final int bitIndex;
            public final int mask;

            AchievementStatTarget(int statId, int bitIndex) {
                this.statId = statId;
                this.bitIndex = bitIndex;
                this.mask = 1 << bitIndex;
            }
        }

        public final List<AchievementDefinition> definitions;
        public final int crcStats;
        public final Map<Integer, Integer> statValues;
        public final Map<String, AchievementStatTarget> achievementStatTargets;

        UserStatsResult(
            List<AchievementDefinition> definitions,
            int crcStats,
            Map<Integer, Integer> statValues,
            Map<String, AchievementStatTarget> achievementStatTargets
        ) {
            this.definitions = definitions;
            this.crcStats = crcStats;
            this.statValues = statValues;
            this.achievementStatTargets = achievementStatTargets;
        }
    }

    /** Reads current-user achievement state directly from the logged-in CM session. */
    public UserStatsResult getUserStats(long appId, long steamId64, long timeoutMs) throws Exception {
        try {
            SteammessagesClientserverUserstats.CMsgClientGetUserStatsResponse response =
                protocolClient.getUserStats(appId, steamId64, timeoutMs);
            if (response.hasEresult() && response.getEresult() != EResult.OK.code()) {
                throw new IllegalStateException("Steam CM GetUserStats failed: " + response.getEresult());
            }
            KeyValue schema = parseSchema(response.hasSchema() ? response.getSchema().toByteArray() : null);
            Map<Integer, Integer> statValues = new LinkedHashMap<>();
            for (SteammessagesClientserverUserstats.CMsgClientGetUserStatsResponse.Stats stat : response.getStatsList()) {
                statValues.put(stat.getStatId(), stat.getStatValue());
            }
            return new UserStatsResult(
                schema == null ? Collections.emptyList() : parseAchievementSchema(schema),
                response.getCrcStats(),
                Collections.unmodifiableMap(statValues),
                schema == null ? Collections.emptyMap() : parseAchievementStatTargets(schema)
            );
        } catch (Exception error) { throw error; }
    }

    /**
     * Sends exactly one client-stat mutation through the authenticated CM session.
     * Callers must derive the stat ID from the server-provided schema and verify the result by
     * reading the state again; Steam can reject a stat as invalid or out of date.
     */
    public void storeUserStat(
        long appId,
        long steamId64,
        int crcStats,
        int statId,
        int statValue,
        long timeoutMs
    ) throws Exception {
        SteammessagesClientserverUserstats.CMsgClientStoreUserStatsResponse response =
            protocolClient.storeUserStat(appId, steamId64, crcStats, statId, statValue, timeoutMs);
        if (response.hasEresult() && response.getEresult() != EResult.OK.code()) {
            throw new IllegalStateException("Steam CM StoreUserStats failed: " + response.getEresult());
        }
        if (response.getStatsOutOfDate()) {
            throw new IllegalStateException("Steam CM StoreUserStats rejected stale statistics.");
        }
        if (response.getStatsFailedValidationCount() > 0) {
            throw new IllegalStateException(
                "Steam CM StoreUserStats validation failed for stat "
                    + response.getStatsFailedValidation(0).getStatId()
            );
        }
    }

    private static List<UserStatsResult.AchievementDefinition> parseAchievementSchema(byte[] bytes) {
        KeyValue root = parseSchema(bytes);
        return root == null ? Collections.emptyList() : parseAchievementSchema(root);
    }

    private static KeyValue parseSchema(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        KeyValue root = new KeyValue();
        try {
            return root.tryReadAsBinary(new ByteArrayInputStream(bytes)) ? root : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static List<UserStatsResult.AchievementDefinition> parseAchievementSchema(KeyValue root) {
        List<UserStatsResult.AchievementDefinition> result = new ArrayList<>();
        collectAchievementDefinitions(root, false, result);
        return result;
    }

    static Map<String, UserStatsResult.AchievementStatTarget> parseAchievementStatTargets(KeyValue root) {
        Map<String, UserStatsResult.AchievementStatTarget> result = new LinkedHashMap<>();
        collectAchievementStatContainers(root, result);
        return Collections.unmodifiableMap(result);
    }

    private static void collectAchievementStatContainers(
        KeyValue node,
        Map<String, UserStatsResult.AchievementStatTarget> result
    ) {
        if ("stats".equalsIgnoreCase(node.getName())) {
            for (KeyValue stat : node.getChildren()) {
                int statId = parseNonNegativeInt(stat.getName());
                KeyValue bits = stat.get("bits");
                if (statId < 0 || bits == KeyValue.INVALID) continue;
                for (KeyValue bit : bits.getChildren()) {
                    int bitIndex = parseNonNegativeInt(bit.getName());
                    String apiName = firstNonBlank(
                        value(bit, "name"),
                        value(bit, "display", "name", "english")
                    );
                    if (bitIndex >= 0 && bitIndex <= 30 && !apiName.isEmpty()) {
                        result.put(
                            apiName.toLowerCase(Locale.ROOT),
                            new UserStatsResult.AchievementStatTarget(statId, bitIndex)
                        );
                    }
                }
            }
        }
        for (KeyValue child : node.getChildren()) {
            collectAchievementStatContainers(child, result);
        }
    }

    private static void collectAchievementDefinitions(
        KeyValue node,
        boolean insideAchievements,
        List<UserStatsResult.AchievementDefinition> result
    ) {
        boolean isAchievementsContainer = "achievements".equalsIgnoreCase(node.getName());
        if ("achievement".equalsIgnoreCase(node.getName()) || insideAchievements) {
            KeyValue id = node.get("id");
            KeyValue apiName = node.get("name");
            KeyValue displayName = node.get("displayName");
            int achievementId = id == KeyValue.INVALID
                ? parseNonNegativeInt(node.getName())
                : id.asInteger(-1);
            String apiNameValue = apiName == KeyValue.INVALID ? "" : safeTrim(apiName.asString());
            if (achievementId >= 0 && !apiNameValue.isEmpty()) {
                result.add(new UserStatsResult.AchievementDefinition(
                    achievementId,
                    apiNameValue,
                    displayName == KeyValue.INVALID ? "" : displayName.asString(),
                    value(node, "description"),
                    value(node, "icon"),
                    value(node, "icongray")
                ));
            }
        }
        for (KeyValue child : node.getChildren()) {
            collectAchievementDefinitions(child, isAchievementsContainer, result);
        }
    }

    private static int parseNonNegativeInt(String value) {
        if (value == null) return -1;
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 ? parsed : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String value(KeyValue node, String name) {
        KeyValue child = node.get(name);
        return child == KeyValue.INVALID ? "" : nullToEmpty(child.getValue());
    }

    private static String value(KeyValue node, String... path) {
        KeyValue current = node;
        for (String part : path) {
            current = current.get(part);
            if (current == KeyValue.INVALID) return "";
        }
        return nullToEmpty(current.getValue());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = safeTrim(value);
            if (!normalized.isEmpty()) return normalized;
        }
        return "";
    }

    private static String safeTrim(String value) {
        return nullToEmpty(value).trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Sends the undocumented CM game-state message used by third-party Steam clients.
     * A zero AppID clears the current game state.
     */
    public void setGamePlayedAppId(long appId) {
        protocolClient.sendGamesPlayed(appId);
        playingSessionAppId = (int) appId;
        recordDiagnosticEvent("games_played_sent appId=" + appId + " emsg=5410");
        Log.i(TAG, "Published Steam games-played state AppID=" + appId + ".");
    }

    public void setPersonaOnline() {
        protocolClient.sendPersonaOnline();
        recordDiagnosticEvent("persona_state_sent state=Online");
        Log.i(TAG, "Published Steam persona state Online.");
    }

    public boolean isCmSessionActive() {
        return protocolClient.isSessionActive();
    }

    public void setRichPresence(Map<String, String> kvPairs) {
        protocolClient.sendRichPresence(kvPairs);
        recordDiagnosticEvent("rich_presence_sent keys=" + kvPairs.size());
        Log.i(TAG, "Published Steam rich presence (" + kvPairs.size() + " keys).");
    }

    private static EOSType androidOsType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return EOSType.Android9;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return EOSType.Android8;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return EOSType.Android7;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return EOSType.Android6;
        }
        return EOSType.AndroidUnknown;
    }

    static String resolveSteamId64FromAuthSession(Object authSession) {
        if (authSession == null) {
            return "";
        }
        try {
            Field field = findField(authSession.getClass(), "steamID");
            if (field == null) {
                return "";
            }
            field.setAccessible(true);
            Object value = field.get(authSession);
            if (!(value instanceof SteamID)) {
                return "";
            }
            SteamID steamID = (SteamID) value;
            return steamID.isValid() ? String.valueOf(steamID.convertToUInt64()) : "";
        } catch (Throwable error) {
            Log.w(TAG, "Unable to read SteamID64 from credentials auth session.", error);
            return "";
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    static void applySteamId64ToLogOnDetails(LogOnDetails details, String steamId64) {
        if (details == null || isBlank(steamId64)) {
            return;
        }
        try {
            SteamID steamID = new SteamID();
            steamID.setFromUInt64String(steamId64.trim());
            if (!steamID.isValid()) {
                return;
            }
            details.setAccountID(steamID.getAccountID());
            details.setAccountInstance(steamID.getAccountInstance());
        } catch (Throwable error) {
            Log.w(TAG, "Unable to apply SteamID64 to refresh-token logon details.", error);
        }
    }

    public List<RemoteFileRecord> listFiles(int appId) throws Exception {
        try {
            Log.i(TAG, "Requesting Steam Cloud manifest for AppID " + appId + '.');
            SteammessagesCloudSteamclient.CCloud_GetAppFileChangelist_Response changeList =
                protocolClient.getAppFileChangelist(appId);
            requireFullFileChangelist(
                changeList.getIsOnlyDelta(),
                changeList.getCurrentChangeNumber()
            );

            List<RemoteFileRecord> entries = new ArrayList<>();
            for (SteammessagesCloudSteamclient.CCloud_AppFileInfo file : changeList.getFilesList()) {
                String pathPrefix = requireIndexedChangelistValue(
                    "path prefix",
                    file.getPathPrefixIndex(),
                    changeList.getPathPrefixesCount(),
                    index -> changeList.getPathPrefixes(index)
                );
                String machineName = "";
                if (file.hasMachineNameIndex()) {
                    machineName = requireIndexedChangelistValue(
                        "machine name",
                        file.getMachineNameIndex(),
                        changeList.getMachineNamesCount(),
                        index -> changeList.getMachineNames(index)
                    );
                }
                String persistState = requireKnownPersistState(file);

                String remotePath = joinRemotePath(pathPrefix, file.getFileName());
                entries.add(new RemoteFileRecord(
                    remotePath,
                    file.getRawFileSize(),
                    TimeUnit.SECONDS.toMillis(file.getTimeStamp()),
                    machineName,
                    persistState,
                    bytesToHex(file.getShaFile().toByteArray())
                ));
            }

            entries.sort(Comparator.comparing(entry -> entry.remotePath.toLowerCase(Locale.ROOT)));
            Log.i(TAG, "Steam Cloud manifest request completed. files=" + entries.size());
            return entries;
        } catch (Exception error) {
            Log.e(TAG, "Steam Cloud manifest request failed during " + currentStage + '.', error);
            throw error;
        }
    }

    static void requireFullFileChangelist(boolean isOnlyDelta, long currentChangeNumber) throws IOException {
        if (isOnlyDelta) {
            throw new IOException(
                "Steam Cloud returned a delta file changelist (changeNumber="
                    + currentChangeNumber
                    + "); a full manifest is required for synchronization."
            );
        }
    }

    static String requireIndexedChangelistValue(
        String fieldName,
        int index,
        int valueCount,
        java.util.function.IntFunction<String> valueAt
    ) throws IOException {
        if (index < 0 || index >= valueCount) {
            throw new IOException(
                "Steam Cloud manifest is incomplete: invalid " + fieldName + " index " + index + "."
            );
        }
        return valueAt.apply(index);
    }

    static String requireKnownPersistState(
        SteammessagesCloudSteamclient.CCloud_AppFileInfo file
    ) throws IOException {
        if (!file.hasPersistState() &&
            file.getUnknownFields().hasField(
                SteammessagesCloudSteamclient.CCloud_AppFileInfo.PERSIST_STATE_FIELD_NUMBER
            )) {
            throw new IOException("Steam Cloud manifest is incomplete: file persist state is unknown.");
        }
        Enums.ECloudStoragePersistState persistState = file.getPersistState();
        if (persistState == null ||
            Enums.ECloudStoragePersistState.forNumber(persistState.getNumber()) != persistState) {
            throw new IOException("Steam Cloud manifest is incomplete: file persist state is unknown.");
        }
        return persistState.name();
    }

    public DownloadResult downloadFile(int appId, String remotePath, File outputFile) throws Exception {
        return downloadFile(appId, remotePath, outputFile, -1L, "");
    }

    public DownloadResult downloadFile(
        int appId,
        String remotePath,
        File outputFile,
        long expectedRawSize,
        String expectedSha1
    ) throws Exception {
        outputFile = outputFile.getAbsoluteFile();
        for (int attempt = 1; attempt <= DOWNLOAD_MAX_ATTEMPTS; attempt++) {
            try {
                return downloadFileOnce(appId, remotePath, outputFile, expectedRawSize, expectedSha1);
            } catch (Exception error) {
                if (!isRetryableDownloadException(error) || attempt >= DOWNLOAD_MAX_ATTEMPTS) {
                    Log.e(TAG, "Steam Cloud file download failed for " + remotePath + '.', error);
                    throw error;
                }

                long delayMs = downloadRetryDelayMs(attempt);
                Log.w(
                    TAG,
                    "Steam Cloud download failed transiently for "
                        + remotePath
                        + ": "
                        + sanitizeSingleLine(error.getMessage())
                        + "; retrying attempt "
                        + (attempt + 1)
                        + "/"
                        + DOWNLOAD_MAX_ATTEMPTS
                        + " after "
                        + delayMs
                        + "ms.",
                    error
                );
                sleepBeforeRetry(delayMs);
            }
        }
        throw new IllegalStateException("Steam Cloud download failed without completing: " + remotePath);
    }

    private DownloadResult downloadFileOnce(
        int appId,
        String remotePath,
        File outputFile,
        long expectedRawSize,
        String expectedSha1
    ) throws Exception {
        long startedAtNs = System.nanoTime();
        long rpcMs = 0L;
        long httpMs = 0L;
        long unzipMs = 0L;
        long writeMs = 0L;
        long compressedBytesCount = 0L;
        long rawBytesCount = 0L;
        boolean decompressed = false;
        File compressedTempFile = null;
        File rawTempFile = null;
        Log.i(TAG, "Downloading Steam Cloud file: " + remotePath);
        long rpcStartedAtNs = System.nanoTime();
        SteammessagesCloudSteamclient.CCloud_ClientFileDownload_Response info =
            protocolClient.clientFileDownload(appId, remotePath);
        rpcMs = elapsedMillis(rpcStartedAtNs);

        if (info.getUrlHost().isEmpty()) {
            throw new IllegalStateException("Steam returned an empty download host for " + remotePath);
        }
        if (info.getFileSize() > downloadLimits.getMaxCompressedDownloadBytes()) {
            throw new DownloadLimitIOException(
                "Steam Cloud compressed download exceeds the configured limit for " + remotePath
                    + ": declared=" + info.getFileSize()
                    + " limit=" + downloadLimits.getMaxCompressedDownloadBytes()
            );
        }
        if (info.getRawFileSize() > downloadLimits.getMaxRawDownloadBytes()) {
            throw new DownloadLimitIOException(
                "Steam Cloud raw download exceeds the configured limit for " + remotePath
                    + ": declared=" + info.getRawFileSize()
                    + " limit=" + downloadLimits.getMaxRawDownloadBytes()
            );
        }

        String scheme = info.getUseHttps() ? "https://" : "http://";
        String url = scheme + info.getUrlHost() + info.getUrlPath();
        Request.Builder requestBuilder = new Request.Builder().url(url);
        for (SteammessagesCloudSteamclient.CCloud_ClientFileDownload_Response.HTTPHeaders header : info.getRequestHeadersList()) {
            requestBuilder.addHeader(header.getName(), header.getValue());
        }

        File parent = outputFile.getParentFile();
        ensureDirectoryExists(parent, "output directory");
        try {
            compressedTempFile = File.createTempFile("steam-cloud-", ".compressed", parent);
            rawTempFile = File.createTempFile("steam-cloud-", ".raw", parent);

            long httpStartedAtNs = System.nanoTime();
            try {
                try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                    if (!response.isSuccessful()) {
                        throw new HttpStatusIOException(response.code(), "downloading", remotePath);
                    }
                    ResponseBody responseBody = response.body();
                    if (responseBody == null) {
                        throw new IOException("Steam returned an empty response body for " + remotePath);
                    }
                    long contentLength = responseBody.contentLength();
                    if (contentLength > downloadLimits.getMaxCompressedDownloadBytes()) {
                        throw new DownloadLimitIOException(
                            "Steam Cloud compressed download exceeds the configured limit for " + remotePath
                                + ": declared=" + contentLength
                                + " limit=" + downloadLimits.getMaxCompressedDownloadBytes()
                        );
                    }
                    compressedBytesCount = copyToFile(
                        responseBody.byteStream(),
                        compressedTempFile,
                        downloadLimits.getMaxCompressedDownloadBytes(),
                        "compressed download for " + remotePath
                    );
                }
            } catch (IOException error) {
                if (error instanceof HttpStatusIOException || error instanceof DownloadLimitIOException) {
                    throw error;
                }
                throw new HttpTransferIOException("HTTP transfer failed when downloading " + remotePath, error);
            }

            httpMs = elapsedMillis(httpStartedAtNs);
            decompressed = info.getRawFileSize() != info.getFileSize();
            long unzipStartedAtNs = System.nanoTime();
            if (decompressed) {
                rawBytesCount = maybeUnzip(
                    compressedTempFile,
                    rawTempFile,
                    remotePath,
                    downloadLimits.getMaxRawDownloadBytes()
                );
            } else {
                rawBytesCount = copyFileWithLimit(
                    compressedTempFile,
                    rawTempFile,
                    downloadLimits.getMaxRawDownloadBytes(),
                    "raw download for " + remotePath
                );
            }
            unzipMs = elapsedMillis(unzipStartedAtNs);
            validateDownloadedFile(rawTempFile, expectedRawSize, expectedSha1, remotePath);

            long writeStartedAtNs = System.nanoTime();
            SteamCloudAtomicFileStore.replaceFile(rawTempFile, outputFile);
            writeMs = elapsedMillis(writeStartedAtNs);
            rawTempFile = null;
            long totalMs = elapsedMillis(startedAtNs);
            Log.i(
                TAG,
                "Downloaded Steam Cloud file: "
                    + remotePath
                    + " totalMs="
                    + totalMs
                    + " rpcMs="
                    + rpcMs
                    + " httpMs="
                    + httpMs
                    + " unzipMs="
                    + unzipMs
                    + " writeMs="
                    + writeMs
                    + " compressedBytes="
                    + compressedBytesCount
                    + " rawBytes="
                    + rawBytesCount
                    + " output="
                    + outputFile.getAbsolutePath()
            );
            return new DownloadResult(
                remotePath,
                outputFile.getAbsolutePath(),
                compressedBytesCount,
                rawBytesCount,
                decompressed,
                rpcMs,
                httpMs,
                unzipMs,
                writeMs,
                totalMs
            );
        } finally {
            if (compressedTempFile != null) {
                compressedTempFile.delete();
            }
            if (rawTempFile != null) {
                rawTempFile.delete();
            }
        }
    }

    public UploadBatch beginUploadBatch(int appId, List<String> remotePaths) throws Exception {
        return beginUploadBatch(appId, remotePaths, Collections.emptyList());
    }

    public UploadBatch beginUploadBatch(
        int appId,
        List<String> remotePathsToUpload,
        List<String> remotePathsToDelete
    ) throws Exception {
        try {
            Log.i(
                TAG,
                "Beginning Steam Cloud upload batch. uploads="
                    + remotePathsToUpload.size()
                    + " deletes="
                    + remotePathsToDelete.size()
            );
            SteammessagesCloudSteamclient.CCloud_BeginAppUploadBatch_Request request =
                SteammessagesCloudSteamclient.CCloud_BeginAppUploadBatch_Request.newBuilder()
                    .setAppid(appId)
                    .setMachineName(buildUploadMachineName())
                    .addAllFilesToUpload(remotePathsToUpload)
                    .addAllFilesToDelete(remotePathsToDelete)
                    .setClientId(0L)
                    .setAppBuildId(0L)
                    .build();
            SteammessagesCloudSteamclient.CCloud_BeginAppUploadBatch_Response response =
                beginAppUploadBatchWithRetries(
                    request,
                    remotePathsToUpload.size(),
                    remotePathsToDelete.size()
                );
            SteammessagesCloudSteamclient.CCloud_BeginAppUploadBatch_Response body = response;
            long batchId = body.getBatchId();
            if (batchId == 0L) {
                recordDiagnosticEvent("begin_app_upload_batch invalid_batch_id batchId=0");
            }
            ensureValidUploadBatchId(batchId, EResult.OK);
            Log.i(
                TAG,
                "Steam Cloud upload batch started. batchId="
                    + batchId
                    + " appChangeNumber="
                    + body.getAppChangeNumber()
            );
            return new UploadBatch(batchId, body.getAppChangeNumber());
        } catch (Exception error) {
            Log.e(TAG, "Steam Cloud upload batch start failed during " + currentStage + '.', error);
            throw error;
        }
    }

    public UploadedFile uploadFile(int appId, String remotePath, File sourceFile, long uploadBatchId) throws Exception {
        if (!sourceFile.isFile()) {
            throw new IOException("Steam Cloud upload source file is missing: " + sourceFile.getAbsolutePath());
        }

        File uploadSnapshot = createUploadSnapshot(sourceFile, uploadSnapshotDir);
        boolean startedUpload = false;
        long fileSizeLong = 0L;
        String sha1Hex = "";

        try {
            fileSizeLong = uploadSnapshot.length();
            if (fileSizeLong > Integer.MAX_VALUE) {
                throw new IOException("Steam Cloud upload does not support files larger than 2 GiB: " + remotePath);
            }
            int fileSize = (int) fileSizeLong;
            sha1Hex = sha1Hex(uploadSnapshot);
            Log.i(
                TAG,
                "Beginning HTTP upload for Steam Cloud file: "
                    + remotePath
                    + " bytes="
                    + fileSize
                    + " batchId="
                    + uploadBatchId
                    + " sha1="
                    + sha1Hex
            );
            recordDiagnosticEvent(
                "begin_http_upload request remotePath="
                    + remotePath
                    + " bytes="
                    + fileSize
                    + " batchId="
                    + uploadBatchId
                    + " sha1="
                    + sha1Hex
            );
            SteammessagesCloudSteamclient.CCloud_BeginHTTPUpload_Request request =
                SteammessagesCloudSteamclient.CCloud_BeginHTTPUpload_Request.newBuilder()
                    .setAppid(appId)
                    .setFileSize(fileSize)
                    .setFilename(remotePath)
                    .setFileSha(sha1Hex)
                    .setIsPublic(false)
                    .addPlatformsToSync("all")
                    .setUploadBatchId(uploadBatchId)
                    .build();
            SteammessagesCloudSteamclient.CCloud_BeginHTTPUpload_Response beginResponse =
                beginHttpUploadWithRetries(request, remotePath);
            startedUpload = true;

            SteammessagesCloudSteamclient.CCloud_BeginHTTPUpload_Response body = beginResponse;
            String url = (body.getUseHttps() ? "https://" : "http://") + body.getUrlHost() + body.getUrlPath();
            recordDiagnosticEvent(
                "begin_http_upload response remotePath="
                    + remotePath
                    + " host="
                    + body.getUrlHost()
                    + " pathLength="
                    + body.getUrlPath().length()
                    + " headers="
                    + body.getRequestHeadersCount()
            );
            Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .put(RequestBody.create(uploadSnapshot, null));
            for (int index = 0; index < body.getRequestHeadersCount(); index++) {
                SteammessagesCloudSteamclient.CCloud_BeginHTTPUpload_Response.HTTPHeaders header =
                    body.getRequestHeaders(index);
                String name = header.getName();
                if ("host".equalsIgnoreCase(name) || "content-length".equalsIgnoreCase(name)) {
                    continue;
                }
                requestBuilder.addHeader(name, header.getValue());
            }

            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code() + " when uploading " + remotePath);
                }
                recordDiagnosticEvent(
                    "http_upload transfer_success remotePath="
                        + remotePath
                        + " httpCode="
                        + response.code()
                );
            }

            boolean committed = commitHttpUpload(true, appId, sha1Hex, remotePath);
            if (!committed) {
                throw new IllegalStateException("Steam did not commit uploaded file: " + remotePath);
            }
            Log.i(TAG, "Steam Cloud upload committed: " + remotePath);
            recordDiagnosticEvent("commit_http_upload committed remotePath=" + remotePath);
            return new UploadedFile(remotePath, fileSizeLong, sha1Hex);
        } catch (Exception error) {
            recordDiagnosticEvent(
                "upload_file failed remotePath="
                    + remotePath
                    + " batchId="
                    + uploadBatchId
                    + " startedUpload="
                    + startedUpload
                    + " error="
                    + describeThrowable(error)
            );
            if (startedUpload) {
                try {
                    commitHttpUpload(false, appId, sha1Hex, remotePath);
                } catch (Throwable commitError) {
                    Log.w(TAG, "Failed to report Steam Cloud upload failure for " + remotePath + '.', commitError);
                }
            }
            Log.e(TAG, "Steam Cloud upload failed for " + remotePath + '.', error);
            throw error;
        } finally {
            if (!uploadSnapshot.delete() && uploadSnapshot.exists()) {
                Log.w(TAG, "Failed to remove temporary Steam Cloud upload snapshot: " + uploadSnapshot);
            }
        }
    }

    public void deleteFile(int appId, String remotePath, long uploadBatchId) throws Exception {
        SteammessagesCloudSteamclient.CCloud_ClientDeleteFile_Request request =
            buildClientDeleteFileRequest(appId, remotePath, uploadBatchId);
        try {
            protocolClient.clientDeleteFile(request);
            recordDiagnosticEvent(
                "client_delete_file acknowledged remotePath=" + remotePath + " batchId=" + uploadBatchId
            );
        } catch (Exception error) {
            recordDiagnosticEvent(
                "client_delete_file failed remotePath=" + remotePath + " batchId=" + uploadBatchId +
                    " error=" + describeThrowable(error)
            );
            throw error;
        }
    }

    static SteammessagesCloudSteamclient.CCloud_ClientDeleteFile_Request buildClientDeleteFileRequest(
        int appId,
        String remotePath,
        long uploadBatchId
    ) {
        if (appId <= 0) {
            throw new IllegalArgumentException("Steam Cloud AppID must be positive.");
        }
        if (isBlank(remotePath)) {
            throw new IllegalArgumentException("Steam Cloud delete path must not be blank.");
        }
        if (uploadBatchId <= 0L) {
            throw new IllegalArgumentException("Steam Cloud delete requires a valid upload batch ID.");
        }
        return SteammessagesCloudSteamclient.CCloud_ClientDeleteFile_Request.newBuilder()
            .setAppid(appId)
            .setFilename(remotePath.trim())
            .setIsExplicitDelete(true)
            .setUploadBatchId(uploadBatchId)
            .build();
    }

    public void completeUploadBatch(int appId, long batchId, EResult batchResult) throws Exception {
        // CompleteAppUploadBatch is susceptible to transient EResult.Fail (2) responses from the
        // Steam backend when the batch finalization is still in-flight server-side even though all
        // individual file commits succeeded.  Retry with back-off before giving up.
        SteammessagesCloudSteamclient.CCloud_CompleteAppUploadBatch_Request request =
            SteammessagesCloudSteamclient.CCloud_CompleteAppUploadBatch_Request.newBuilder()
                .setAppid(appId)
                .setBatchId(batchId)
                .setBatchEresult(batchResult.code())
                .build();
        for (int attempt = 1; attempt <= COMPLETE_UPLOAD_BATCH_MAX_ATTEMPTS; attempt++) {
            try {
                protocolClient.completeAppUploadBatch(request);
                Log.i(TAG, "Steam Cloud upload batch completed. batchId=" + batchId
                    + " result=" + batchResult + " attempt=" + attempt);
                recordDiagnosticEvent(
                    "completeappuploadbatch success batchId=" + batchId
                        + " result=" + batchResult + " attempt=" + attempt
                );
                return;
            } catch (Exception error) {
                boolean isRetryable = isRetryableCompleteUploadBatchException(error);
                recordDiagnosticEvent(
                    "completeappuploadbatch failed batchId=" + batchId
                        + " attempt=" + attempt + " retryable=" + isRetryable
                        + " error=" + describeThrowable(error)
                );
                if (!isRetryable || attempt >= COMPLETE_UPLOAD_BATCH_MAX_ATTEMPTS) {
                    Log.e(TAG, "Steam Cloud upload batch completion failed during " + currentStage
                        + " batchId=" + batchId + " attempt=" + attempt + '.', error);
                    throw error;
                }
                long delayMs = COMPLETE_UPLOAD_BATCH_RETRY_DELAYS_MS[
                    Math.min(attempt - 1, COMPLETE_UPLOAD_BATCH_RETRY_DELAYS_MS.length - 1)
                ];
                Log.w(TAG, "Steam Cloud upload batch completion failed transiently for batchId=" + batchId
                    + ": " + sanitizeSingleLine(error.getMessage())
                    + "; retrying attempt " + (attempt + 1) + "/" + COMPLETE_UPLOAD_BATCH_MAX_ATTEMPTS
                    + " after " + delayMs + "ms.", error);
                sleepBeforeRetry(delayMs);
            }
        }
        throw new IllegalStateException("CompleteAppUploadBatch failed without completing. batchId=" + batchId);
    }

    private static boolean isRetryableCompleteUploadBatchException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            // SteamServiceMethodException carries the raw EResult code; EResult.Fail (2) is the
            // most common transient result during CompleteAppUploadBatch.
            if (current instanceof top.apricityx.workshop.steam.protocol.SteamServiceMethodException) {
                int code = ((top.apricityx.workshop.steam.protocol.SteamServiceMethodException) current).getResultCode();
                return isRetryableCompleteUploadBatchResultCode(code);
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("completeappuploadbatch failed: fail")
                    || normalized.contains("eresult=2")
                    || normalized.contains("eresult = 2")
                    || normalized.contains("busy")
                    || normalized.contains("timeout")
                    || normalized.contains("timed out")
                    || normalized.contains("serviceunavailable")
                    || normalized.contains("service unavailable")
                    || normalized.contains("remotecallfailed")
                    || normalized.contains("remote call failed")
                ) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isRetryableCompleteUploadBatchResultCode(int code) {
        // EResult.Fail=2, Busy=10, ServiceUnavailable=15, Timeout=16, RemoteCallFailed=71
        return code == 2 || code == 10 || code == 15 || code == 16 || code == 71;
    }

    @Override
    public void close() {
        shuttingDown.set(true);
        running.set(false);

        protocolClient.close();
        recordDiagnosticEvent("cm_disconnect completed");
        applyProxySystemProperties();
    }

    public DiagnosticsSnapshot snapshotDiagnostics() {
        return new DiagnosticsSnapshot(
            currentStage,
            describeProtocolTypes(protocolTypes),
            connectedCallbackReceived,
            loggedOnResultDescription,
            disconnectedDescription,
            resolvedServerDescription,
            candidateSourceDescription,
            allowedChallengesDescription,
            lastAuthPromptDescription,
            guardDataConfigured,
            guardDataUpdated,
            "<not applicable: accelerated protocol CM>",
            "<not applicable: accelerated protocol CM>",
            Collections.emptyList(),
            Collections.emptyList(),
            snapshotDiagnosticEvents(),
            wattAccelerationEnabled ? "enabled" : "disabled",
            credentialsAuthSteamId64,
            loggedOnCallbackSteamId64,
            steamClientSteamId64,
            cmServerSelectionMs,
            cmConnectWaitMs,
            playingSessionBlocked,
            playingSessionAppId
        );
    }

    void recordProtocolAuthDiagnostic(String message) {
        recordDiagnosticEvent("protocol_auth " + message);
    }

    void applyProtocolAuthDiagnostics(
        String steamId64,
        String allowedChallenges,
        String lastPrompt,
        boolean updatedGuardData
    ) {
        credentialsAuthSteamId64 = sanitizeSingleLine(steamId64);
        currentSteamId64 = credentialsAuthSteamId64;
        allowedChallengesDescription = sanitizeSingleLine(allowedChallenges);
        lastAuthPromptDescription = sanitizeSingleLine(lastPrompt);
        guardDataUpdated = updatedGuardData;
    }

    private <T> T waitForStage(CompletableFuture<T> future, long timeoutMs, String stage) throws Exception {
        currentStage = stage;
        recordDiagnosticEvent("stage_begin name=" + stage + " timeoutMs=" + timeoutMs);
        long startedAtNs = System.nanoTime();
        try {
            T value = waitForEither(future, disconnectedFuture, timeoutMs, stage);
            recordDiagnosticEvent("stage_success name=" + stage + " durationMs=" + elapsedMillis(startedAtNs));
            return value;
        } catch (Exception error) {
            recordDiagnosticEvent(
                "stage_failed name="
                    + stage
                    + " durationMs="
                    + elapsedMillis(startedAtNs)
                    + " error="
                    + describeThrowable(error)
            );
            throw error;
        }
    }

    private <T> T waitForStageWithRetries(
        Supplier<CompletableFuture<T>> futureSupplier,
        long timeoutMs,
        String stage
    ) throws Exception {
        for (int attempt = 1; attempt <= TRANSIENT_RPC_MAX_ATTEMPTS; attempt++) {
            try {
                return waitForStage(futureSupplier.get(), timeoutMs, stage);
            } catch (Exception error) {
                if (!isRetryableSteamCloudException(error) || attempt >= TRANSIENT_RPC_MAX_ATTEMPTS) {
                    throw error;
                }
                sleepBeforeTransientRetry(stage, error, attempt);
            }
        }
        throw new IllegalStateException(stage + " failed without completing.");
    }

    private PreparedServerRecord selectWebSocketServerRecord() throws IOException {
        List<PreparedServerRecord> candidates = new ArrayList<>();

        String defaultAddress = SmartCMServerList.getDefaultServerWebSocket();
        addWebSocketAddressCandidate(candidates, defaultAddress, "JavaSteam default websocket CM");

        String cachedAddress = readOptionalTextFile(lastCmEndpointFile);
        addWebSocketAddressCandidate(candidates, cachedAddress, "Cached websocket CM fallback");
        recordDiagnosticEvent("cm_server_list skipped using_cached_or_default_websocket");

        IOException lastResolutionError = null;
        List<String> attemptedKeys = new ArrayList<>();
        for (PreparedServerRecord candidate : candidates) {
            String dedupeKey = buildServerRecordKey(candidate.serverRecord);
            if (attemptedKeys.contains(dedupeKey)) {
                continue;
            }
            attemptedKeys.add(dedupeKey);
            try {
                return materializeWebSocketServerRecord(candidate);
            } catch (IOException error) {
                lastResolutionError = error;
                Log.w(
                    TAG,
                    "Skipping Steam websocket CM candidate source="
                        + candidate.candidateSourceDescription
                        + " because endpoint pre-resolution failed: "
                        + sanitizeSingleLine(error.getMessage()),
                    error
                );
            }
        }

        if (lastResolutionError != null) {
            throw lastResolutionError;
        }
        return null;
    }

    private PreparedServerRecord materializeWebSocketServerRecord(PreparedServerRecord candidate) throws IOException {
        ServerRecord serverRecord = candidate.serverRecord;
        if (serverRecord == null || serverRecord.getEndpoint() == null) {
            return candidate;
        }
        if (!serverRecord.getProtocolTypes().contains(ProtocolTypes.WEB_SOCKET)) {
            return candidate;
        }

        InetSocketAddress endpoint = serverRecord.getEndpoint();
        String host = sanitizeSingleLine(endpoint.getHostString());
        int port = endpoint.getPort();
        if (isBlank(host)) {
            return candidate;
        }

        InetAddress resolvedAddress = endpoint.getAddress();
        if (resolvedAddress != null) {
            String literalAddress = sanitizeSingleLine(resolvedAddress.getHostAddress());
            if (!isBlank(literalAddress)) {
                if (isIpv6Literal(literalAddress)) {
                    String preferredAddress = !isIpLiteral(host)
                        ? selectPreferredAddress(InetAddress.getAllByName(host))
                        : "";
                    if (!isBlank(preferredAddress)) {
                        return createWebSocketServerCandidate(
                            formatHostPort(preferredAddress, port),
                            candidate.candidateSourceDescription + " (pre-resolved " + host + " -> " + preferredAddress + ")"
                        );
                    }
                    throw new IOException(
                        "Skipping IPv6-only Steam websocket CM endpoint because JavaSteam cannot parse IPv6 websocket literals on Android: "
                            + formatHostPort(literalAddress, port)
                    );
                }
                return createWebSocketServerCandidate(
                    formatHostPort(literalAddress, port),
                    candidate.candidateSourceDescription + " (pre-resolved " + host + " -> " + literalAddress + ")"
                );
            }
            return candidate;
        }

        if (isIpLiteral(host)) {
            if (isIpv6Literal(host)) {
                throw new IOException(
                    "Skipping IPv6 Steam websocket CM endpoint because JavaSteam cannot parse IPv6 websocket literals on Android: "
                        + formatHostPort(host, port)
                );
            }
            return candidate;
        }

        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses == null || addresses.length == 0) {
            throw new IOException("Failed to resolve Steam websocket CM hostname: " + host);
        }
        String preferredAddress = selectPreferredAddress(addresses);
        if (isBlank(preferredAddress)) {
            throw new IOException(
                "Resolved Steam websocket CM hostname had no IPv4 address usable by JavaSteam websocket parsing: " + host
            );
        }

        Log.i(TAG, "Pre-resolved Steam websocket CM hostname " + host + " -> " + preferredAddress + '.');
        return createWebSocketServerCandidate(
            formatHostPort(preferredAddress, port),
            candidate.candidateSourceDescription + " (pre-resolved " + host + " -> " + preferredAddress + ")"
        );
    }

    private void addWebSocketAddressCandidate(
        List<PreparedServerRecord> candidates,
        String address,
        String source
    ) {
        if (isBlank(address)) {
            return;
        }
        try {
            candidates.add(createWebSocketServerCandidate(address, source));
        } catch (IOException error) {
            recordDiagnosticEvent(
                "cm_candidate_skipped source="
                    + sanitizeSingleLine(source)
                    + " reason="
                    + sanitizeSingleLine(error.getMessage())
            );
            Log.w(
                TAG,
                "Skipping Steam websocket CM candidate source="
                    + source
                    + ": "
                    + sanitizeSingleLine(error.getMessage()),
                error
            );
        }
    }

    private static PreparedServerRecord createWebSocketServerCandidate(
        String address,
        String source
    ) throws IOException {
        String normalizedAddress = sanitizeSingleLine(address);
        if (!isWebSocketEndpointParserSafe(normalizedAddress)) {
            throw new IOException(
                "Steam websocket CM endpoint is not safe for JavaSteam parser: " + normalizedAddress
            );
        }
        try {
            return new PreparedServerRecord(
                ServerRecord.createWebSocketServer(normalizedAddress),
                source
            );
        } catch (RuntimeException error) {
            throw new IOException("Invalid Steam websocket CM endpoint: " + normalizedAddress, error);
        }
    }

    private void persistResolvedWebSocketEndpoint(ServerRecord serverRecord) {
        if (serverRecord == null || !serverRecord.getProtocolTypes().contains(ProtocolTypes.WEB_SOCKET)) {
            return;
        }
        InetSocketAddress endpoint = serverRecord.getEndpoint();
        if (endpoint == null) {
            return;
        }
        String address = endpoint.getHostString();
        if (endpoint.getAddress() != null && !isBlank(endpoint.getAddress().getHostAddress())) {
            address = endpoint.getAddress().getHostAddress();
        }
        if (isIpv6Literal(address)) {
            recordDiagnosticEvent(
                "cm_connect endpoint_not_persisted reason=ipv6_literal endpoint=" + formatHostPort(address, endpoint.getPort())
            );
            return;
        }
        address = formatHostPort(address, endpoint.getPort());
        try {
            writeTextFile(lastCmEndpointFile, address + "\n");
        } catch (IOException ignored) {
            // Best effort cache.
        }
    }

    private void maybeValidateSupportedChallenges(AuthSession authSession) {
        List<String> challengeDescriptions = new ArrayList<>();
        for (int index = 0; index < authSession.getAllowedConfirmations().size(); index++) {
            EAuthSessionGuardType type = authSession.getAllowedConfirmations().get(index).getConfirmationType();
            challengeDescriptions.add(describeGuardType(type));
            Log.i(TAG, "Steam auth challenge candidate index=" + index + " type=" + describeGuardType(type));
            if (type == EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode
                || type == EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode
                || type == EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceConfirmation
            ) {
                allowedChallengesDescription = challengeDescriptions.isEmpty()
                    ? "<none>"
                    : String.join(", ", challengeDescriptions);
                recordDiagnosticEvent("auth_challenges supported=" + allowedChallengesDescription);
                Log.i(TAG, "Steam auth allowed challenges: " + allowedChallengesDescription);
                return;
            }
        }
        allowedChallengesDescription = challengeDescriptions.isEmpty()
            ? "<none>"
            : String.join(", ", challengeDescriptions);
        recordDiagnosticEvent("auth_challenges unsupported=" + allowedChallengesDescription);
        Log.w(TAG, "Steam auth exposed no supported challenge flow. allowed=" + allowedChallengesDescription);
    }

    private void recordDiagnosticEvent(String message) {
        String line = Instant.now() + " | " + sanitizeSingleLine(message);
        synchronized (diagnosticEventsLock) {
            while (diagnosticEvents.size() >= DIAGNOSTIC_EVENT_LIMIT) {
                diagnosticEvents.removeFirst();
            }
            diagnosticEvents.addLast(line);
        }
        Log.i(TAG, "diag " + line);
    }

    private List<String> snapshotDiagnosticEvents() {
        synchronized (diagnosticEventsLock) {
            return new ArrayList<>(diagnosticEvents);
        }
    }

    private String buildDisconnectFailureMessage(String reason) {
        StringBuilder message = new StringBuilder()
            .append("Steam disconnected (")
            .append(reason)
            .append(") during ")
            .append(currentStage)
            .append('.');
        message.append(" JavaSteam 1.6.0 websocket transport has a watchdog for stalled auth flows.");
        return message.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T> T waitForEither(
        CompletableFuture<T> future,
        CompletableFuture<?> abortFuture,
        long timeoutMs,
        String stage
    ) throws Exception {
        CompletableFuture<Object> combined = new CompletableFuture<>();
        future.whenComplete((value, error) -> {
            if (error != null) {
                combined.completeExceptionally(error);
                return;
            }
            combined.complete(value);
        });

        if (abortFuture != null) {
            abortFuture.whenComplete((value, error) -> {
                if (error != null) {
                    combined.completeExceptionally(error);
                    return;
                }
                combined.completeExceptionally(new IllegalStateException(stage + " was aborted before completion."));
            });
        }

        long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!combined.isDone()) {
            long remainingNs = deadlineNs - System.nanoTime();
            if (remainingNs <= 0L) {
                throw new TimeoutException(stage + " timed out after " + (timeoutMs / 1000L) + "s.");
            }
            long sleepMs = Math.min(
                250L,
                Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNs))
            );
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw error;
            }
        }

        try {
            return (T) combined.get();
        } catch (ExecutionException error) {
            Throwable cause = unwrapAsyncThrowable(error);
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(stage + " failed.", cause);
        }
    }

    private static Throwable unwrapAsyncThrowable(Throwable error) {
        Throwable current = error;
        while (current instanceof ExecutionException && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String sanitizeSingleLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String describeThrowable(Throwable error) {
        if (error == null) {
            return "";
        }
        String message = sanitizeSingleLine(error.getMessage());
        if (message.isEmpty()) {
            return error.getClass().getName();
        }
        return error.getClass().getName() + ": " + message;
    }

    private static String describeAuthenticationResult(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AuthenticationException) {
                AuthenticationException authError = (AuthenticationException) current;
                EResult result = authError.getResult();
                return "authResult=" + (result == null ? "<none>" : result);
            }
            current = current.getCause();
        }
        return "authResult=<none>";
    }

    private static List<String> buildStackTraceLines(Throwable error, int limit) {
        List<String> lines = new ArrayList<>();
        if (error == null || limit <= 0) {
            return lines;
        }
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        error.printStackTrace(printWriter);
        printWriter.flush();
        for (String line : stringWriter.toString().split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            lines.add(trimmed);
            if (lines.size() >= limit) {
                break;
            }
        }
        return lines;
    }

    private static String describeServerRecord(ServerRecord serverRecord) {
        if (serverRecord == null || serverRecord.getEndpoint() == null) {
            return "<not resolved>";
        }
        return serverRecord.getEndpoint().getHostString()
            + ':'
            + serverRecord.getEndpoint().getPort()
            + " ["
            + describeProtocolTypes(serverRecord.getProtocolTypes())
            + ']';
    }

    private static String describeProtocolTypes(EnumSet<ProtocolTypes> protocolTypes) {
        if (protocolTypes == null || protocolTypes.isEmpty()) {
            return "<none>";
        }
        List<String> values = new ArrayList<>();
        for (ProtocolTypes type : protocolTypes) {
            values.add(String.valueOf(type));
        }
        return String.join(", ", values);
    }

    private static String describeGuardType(EAuthSessionGuardType type) {
        if (type == null) {
            return "<unknown>";
        }
        switch (type) {
            case k_EAuthSessionGuardType_DeviceCode:
                return "device_code";
            case k_EAuthSessionGuardType_EmailCode:
                return "email_code";
            case k_EAuthSessionGuardType_DeviceConfirmation:
                return "device_confirmation";
            default:
                return String.valueOf(type);
        }
    }

    private static String joinRemotePath(String prefix, String filename) {
        if (isBlank(prefix)) {
            return filename;
        }
        if (isBlank(filename)) {
            return prefix;
        }
        char separator = prefix.indexOf('\\') >= 0 && prefix.indexOf('/') < 0 ? '\\' : '/';
        if (prefix.endsWith("/") || prefix.endsWith("\\")) {
            return prefix + filename;
        }
        return prefix + separator + filename;
    }

    private static String buildServerRecordKey(ServerRecord serverRecord) {
        if (serverRecord == null || serverRecord.getEndpoint() == null) {
            return "<null>";
        }
        return sanitizeSingleLine(serverRecord.getEndpoint().getHostString()).toLowerCase(Locale.ROOT)
            + ':'
            + serverRecord.getEndpoint().getPort()
            + '|'
            + describeProtocolTypes(serverRecord.getProtocolTypes());
    }

    static String selectPreferredAddress(InetAddress[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "";
        }
        for (InetAddress address : addresses) {
            String literal = address == null ? "" : sanitizeSingleLine(address.getHostAddress());
            if (address instanceof Inet4Address && !isBlank(literal)) {
                return literal;
            }
        }
        return "";
    }

    private static String formatHostPort(String host, int port) {
        String sanitizedHost = sanitizeSingleLine(host);
        if (sanitizedHost.indexOf(':') >= 0 && !sanitizedHost.startsWith("[") && !sanitizedHost.endsWith("]")) {
            sanitizedHost = '[' + sanitizedHost + ']';
        }
        return sanitizedHost + ':' + port;
    }

    static boolean isWebSocketEndpointParserSafe(String endpoint) {
        String host = extractHostFromEndpoint(endpoint);
        return !isBlank(host) && !isIpv6Literal(host);
    }

    private static String extractHostFromEndpoint(String endpoint) {
        String value = sanitizeSingleLine(endpoint);
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 1 ? value.substring(1, end) : value;
        }
        int separator = value.lastIndexOf(':');
        return separator > 0 ? value.substring(0, separator) : value;
    }

    private static boolean isIpLiteral(String host) {
        String value = sanitizeSingleLine(host);
        if (value.isEmpty()) {
            return false;
        }
        if (value.indexOf(':') >= 0) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if ((c < '0' || c > '9') && c != '.') {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv6Literal(String host) {
        String value = sanitizeSingleLine(host);
        if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        return value.indexOf(':') >= 0;
    }

    private boolean commitHttpUpload(
        boolean transferSucceeded,
        int appId,
        String sha1Hex,
        String remotePath
    ) throws Exception {
        SteammessagesCloudSteamclient.CCloud_CommitHTTPUpload_Request request =
            SteammessagesCloudSteamclient.CCloud_CommitHTTPUpload_Request.newBuilder()
                .setTransferSucceeded(transferSucceeded)
                .setAppid(appId)
                .setFileSha(sha1Hex)
                .setFilename(remotePath)
                .build();
        SteammessagesCloudSteamclient.CCloud_CommitHTTPUpload_Response response = null;
        for (int attempt = 1; attempt <= TRANSIENT_RPC_MAX_ATTEMPTS; attempt++) {
            try {
                response = protocolClient.commitHttpUpload(request);
                return response.getFileCommitted();
            } catch (Exception error) {
                if (!isRetryableSteamCloudException(error) || attempt >= TRANSIENT_RPC_MAX_ATTEMPTS) {
                    throw error;
                }
                sleepBeforeTransientRetry("CommitHTTPUpload", error, attempt);
            }
        }
        throw new IllegalStateException("CommitHTTPUpload failed without a response result.");
    }

    private <T extends GeneratedMessage.Builder<T>> ServiceMethodResponse<T> waitForServiceJob(
        AsyncJobSingle<ServiceMethodResponse<T>> job,
        long timeoutMs,
        String stage
    ) throws Exception {
        return waitForStage(job.toFuture(), timeoutMs, stage);
    }

    private <T extends GeneratedMessage.Builder<T>> ServiceMethodResponse<T> waitForServiceJobWithRetries(
        Supplier<AsyncJobSingle<ServiceMethodResponse<T>>> jobSupplier,
        long timeoutMs,
        String stage
    ) throws Exception {
        ServiceMethodResponse<T> response = null;
        for (int attempt = 1; attempt <= TRANSIENT_RPC_MAX_ATTEMPTS; attempt++) {
            try {
                response = waitForServiceJob(jobSupplier.get(), timeoutMs, stage);
            } catch (Exception error) {
                if (!isRetryableSteamCloudException(error) || attempt >= TRANSIENT_RPC_MAX_ATTEMPTS) {
                    throw error;
                }
                sleepBeforeTransientRetry(stage, error, attempt);
                continue;
            }

            EResult result = response.getResult();
            if (result == EResult.OK) {
                return response;
            }
            if (!isRetryableSteamCloudResult(result) || attempt >= TRANSIENT_RPC_MAX_ATTEMPTS) {
                return response;
            }
            sleepBeforeTransientRetry(stage, result, "", attempt);
        }

        ensureServiceResult(response, stage);
        throw new IllegalStateException(stage + " failed without a response result.");
    }

    private SteammessagesCloudSteamclient.CCloud_BeginHTTPUpload_Response
    beginHttpUploadWithRetries(
        SteammessagesCloudSteamclient.CCloud_BeginHTTPUpload_Request request,
        String remotePath
    ) throws Exception {
        SteammessagesCloudSteamclient.CCloud_BeginHTTPUpload_Response response = null;
        for (int attempt = 1; attempt <= BEGIN_HTTP_UPLOAD_MAX_ATTEMPTS; attempt++) {
            try {
                response = protocolClient.beginHttpUpload(request);
                recordDiagnosticEvent("begin_http_upload success remotePath=" + remotePath + " attempt=" + attempt);
                return response;
            } catch (Exception error) {
                if (!isRetryableSteamCloudException(error) || attempt >= BEGIN_HTTP_UPLOAD_MAX_ATTEMPTS) {
                    throw error;
                }
                sleepBeforeTransientRetry("BeginHTTPUpload", error, attempt);
            }
        }
        throw new IllegalStateException("BeginHTTPUpload failed without a response result.");
    }

    private SteammessagesCloudSteamclient.CCloud_BeginAppUploadBatch_Response
    beginAppUploadBatchWithRetries(
        SteammessagesCloudSteamclient.CCloud_BeginAppUploadBatch_Request request,
        int uploadCount,
        int deleteCount
    ) throws Exception {
        SteammessagesCloudSteamclient.CCloud_BeginAppUploadBatch_Response response = null;
        for (int attempt = 1; attempt <= BEGIN_UPLOAD_BATCH_MAX_ATTEMPTS; attempt++) {
            try {
                response = protocolClient.beginAppUploadBatch(request);
                long batchId = response.getBatchId();
                recordDiagnosticEvent(
                    "begin_app_upload_batch success uploads=" + uploadCount + " deletes=" + deleteCount
                        + " attempt=" + attempt + " batchId=" + batchId
                );
                ensureValidUploadBatchId(batchId, EResult.OK);
                return response;
            } catch (Exception error) {
                EResult typedResult = steamCloudResultFromException(error);
                long batchId = response == null ? 0L : response.getBatchId();
                boolean retryable = typedResult != null
                    ? isRetryableBeginAppUploadBatchResult(typedResult, batchId)
                    : isRetryableSteamCloudException(error);
                if (!retryable || attempt >= BEGIN_UPLOAD_BATCH_MAX_ATTEMPTS) {
                    throw error;
                }
                if (typedResult != null) {
                    long delayMs = beginAppUploadBatchRetryDelayMs(typedResult, batchId, attempt);
                    Log.w(
                        TAG,
                        "BeginAppUploadBatch returned " + typedResult +
                            beginAppUploadBatchRetryHint(typedResult, batchId) +
                            "; retrying attempt " + (attempt + 1) + "/" +
                            BEGIN_UPLOAD_BATCH_MAX_ATTEMPTS + " after " + delayMs + "ms."
                    );
                    sleepBeforeRetry(delayMs);
                } else {
                    sleepBeforeTransientRetry("BeginAppUploadBatch", error, attempt);
                }
            }
        }
        throw new IllegalStateException("BeginAppUploadBatch failed without a response result.");
    }

    private static void sleepBeforeTransientRetry(
        String stage,
        EResult result,
        String remotePath,
        int attempt
    ) throws InterruptedException {
        long delayMs = transientRetryDelayMs(attempt);
        Log.w(
            TAG,
            stage
                + " returned "
                + result
                + (isBlank(remotePath) ? "" : " for " + remotePath)
                + "; retrying attempt "
                + (attempt + 1)
                + "/"
                + TRANSIENT_RPC_MAX_ATTEMPTS
                + " after "
                + delayMs
                + "ms."
        );
        sleepBeforeRetry(delayMs);
    }

    private static void sleepBeforeTransientRetry(
        String stage,
        Exception error,
        int attempt
    ) throws InterruptedException {
        long delayMs = transientRetryDelayMs(attempt);
        Log.w(
            TAG,
            stage
                + " failed transiently: "
                + sanitizeSingleLine(error.getMessage())
                + "; retrying attempt "
                + (attempt + 1)
                + "/"
                + TRANSIENT_RPC_MAX_ATTEMPTS
                + " after "
                + delayMs
                + "ms.",
            error
        );
        sleepBeforeRetry(delayMs);
    }

    private static long transientRetryDelayMs(int attempt) {
        return TRANSIENT_RPC_RETRY_DELAYS_MS[
            Math.min(attempt - 1, TRANSIENT_RPC_RETRY_DELAYS_MS.length - 1)
        ];
    }

    private static boolean isRetryableSteamCloudResult(EResult result) {
        return result == EResult.Busy
            || result == EResult.ServiceUnavailable
            || result == EResult.Timeout
            || result == EResult.RemoteCallFailed;
    }

    private static boolean isRetryableSteamCloudException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            // SteamServiceMethodException carries a typed result code; check it directly instead
            // of relying on string parsing which misses codes like EResult.Fail (2).
            if (current instanceof top.apricityx.workshop.steam.protocol.SteamServiceMethodException) {
                int code = ((top.apricityx.workshop.steam.protocol.SteamServiceMethodException) current).getResultCode();
                // Busy=10, ServiceUnavailable=15, Timeout=16, RemoteCallFailed=71
                if (code == 10 || code == 15 || code == 16 || code == 71 || code == 108) {
                    return true;
                }
                // Do not retry other typed results (e.g. Fail=2 for generic cloud calls,
                // auth errors, etc.) — let the caller decide based on context.
                return false;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("busy")
                    || normalized.contains("timeout")
                    || normalized.contains("timed out")
                    || normalized.contains("serviceunavailable")
                    || normalized.contains("service unavailable")
                    || normalized.contains("remotecallfailed")
                    || normalized.contains("remote call failed")
                ) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static EResult steamCloudResultFromException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof top.apricityx.workshop.steam.protocol.SteamServiceMethodException) {
                return EResult.from(
                    ((top.apricityx.workshop.steam.protocol.SteamServiceMethodException) current).getResultCode()
                );
            }
            current = current.getCause();
        }
        return null;
    }

    private static boolean isRetryableDownloadException(Throwable error) {
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return false;
            }
            if (current instanceof HttpStatusIOException) {
                return isRetryableHttpStatus(((HttpStatusIOException) current).statusCode);
            }
            if (current instanceof HttpTransferIOException || current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isRetryableHttpStatus(int statusCode) {
        return statusCode == 408
            || statusCode == 429
            || (statusCode >= 500 && statusCode <= 599);
    }

    private static long downloadRetryDelayMs(int attempt) {
        return DOWNLOAD_RETRY_DELAYS_MS[Math.min(attempt - 1, DOWNLOAD_RETRY_DELAYS_MS.length - 1)];
    }

    private static boolean isRetryableBeginHttpUploadResult(EResult result) {
        return isRetryableSteamCloudResult(result)
            || result == EResult.TooManyPending
            || result == EResult.DuplicateRequest;
    }

    private static boolean isRetryableBeginAppUploadBatchResult(EResult result, long batchId) {
        return result == EResult.OK
            ? batchId == 0L
            : isRetryableSteamCloudResult(result) || result == EResult.TooManyPending;
    }

    private static long beginAppUploadBatchRetryDelayMs(EResult result, long batchId, int attempt) {
        long[] delays = (result == EResult.TooManyPending || (result == EResult.OK && batchId == 0L))
            ? BEGIN_HTTP_UPLOAD_PENDING_RETRY_DELAYS_MS
            : BEGIN_HTTP_UPLOAD_RETRY_DELAYS_MS;
        return delays[Math.min(attempt - 1, delays.length - 1)];
    }

    private static String beginAppUploadBatchRetryHint(EResult result, long batchId) {
        if (result == EResult.TooManyPending || (result == EResult.OK && batchId == 0L)) {
            return " Steam may still be clearing an earlier unfinished upload batch after repeated uploads or cancellations.";
        }
        return "";
    }

    private static long beginHttpUploadRetryDelayMs(EResult result, int attempt) {
        long[] delays = isPendingUploadSlotResult(result)
            ? BEGIN_HTTP_UPLOAD_PENDING_RETRY_DELAYS_MS
            : BEGIN_HTTP_UPLOAD_RETRY_DELAYS_MS;
        return delays[Math.min(attempt - 1, delays.length - 1)];
    }

    private static String beginHttpUploadRetryHint(EResult result) {
        if (!isPendingUploadSlotResult(result)) {
            return "";
        }
        return " Steam may still be clearing an earlier unfinished upload batch.";
    }

    private static boolean isPendingUploadSlotResult(EResult result) {
        return result == EResult.TooManyPending || result == EResult.DuplicateRequest;
    }

    private static void sleepBeforeRetry(long delayMs) throws InterruptedException {
        Thread.sleep(delayMs);
    }

    private static <T extends GeneratedMessage.Builder<T>> void ensureServiceResult(
        ServiceMethodResponse<T> response,
        String operation
    ) {
        if (response.getResult() != EResult.OK) {
            throw new IllegalStateException(operation + " failed: " + response.getResult());
        }
    }

    private static void ensureValidUploadBatchId(long batchId, EResult result) {
        if (batchId != 0L) {
            return;
        }
        throw new IllegalStateException(
            "BeginAppUploadBatch returned invalid batchId=0 (result=" + result
                + "). Steam may still be clearing an earlier unfinished upload batch."
        );
    }

    private static String sha1Hex(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (Exception error) {
            throw new IOException("Failed to initialize SHA-1 digest.", error);
        }
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[IO_BUFFER_SIZE];
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                digest.update(buffer, 0, read);
            }
        } catch (IOException error) {
            throw new IOException("Failed to read Steam Cloud upload source file.", error);
        }
        return bytesToHex(digest.digest());
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value & 0xFF));
        }
        return builder.toString();
    }

    private static String buildUploadMachineName() {
        String manufacturer = sanitizeSingleLine(Build.MANUFACTURER);
        String model = sanitizeSingleLine(Build.MODEL);
        String base = manufacturer.isEmpty() ? model : manufacturer + ' ' + model;
        if (base.trim().isEmpty()) {
            return "Android (Steam Cloud)";
        }
        return base.trim() + " (Steam Cloud)";
    }

    public static final class DownloadLimits {
        private final long maxCompressedDownloadBytes;
        private final long maxRawDownloadBytes;

        public DownloadLimits(long maxCompressedDownloadBytes, long maxRawDownloadBytes) {
            if (maxCompressedDownloadBytes < 0L || maxRawDownloadBytes < 0L) {
                throw new IllegalArgumentException("Download limits must not be negative.");
            }
            this.maxCompressedDownloadBytes = maxCompressedDownloadBytes;
            this.maxRawDownloadBytes = maxRawDownloadBytes;
        }

        public static DownloadLimits defaults() {
            return new DownloadLimits(
                DEFAULT_MAX_COMPRESSED_DOWNLOAD_BYTES,
                DEFAULT_MAX_RAW_DOWNLOAD_BYTES
            );
        }

        public long getMaxCompressedDownloadBytes() {
            return maxCompressedDownloadBytes;
        }

        public long getMaxRawDownloadBytes() {
            return maxRawDownloadBytes;
        }
    }

    static File createUploadSnapshot(File sourceFile, File snapshotDirectory) throws IOException {
        File source = sourceFile.getAbsoluteFile();
        ensureDirectoryExists(snapshotDirectory, "Steam Cloud upload snapshot directory");
        File snapshot = File.createTempFile("steam-cloud-upload-", ".snapshot", snapshotDirectory);
        boolean completed = false;
        try {
            copyFileWithLimit(source, snapshot, Long.MAX_VALUE, "Steam Cloud upload snapshot");
            completed = true;
            return snapshot;
        } finally {
            if (!completed) {
                snapshot.delete();
            }
        }
    }

    private static long maybeUnzip(
        File compressedFile,
        File rawFile,
        String remotePath,
        long maxRawBytes
    ) throws IOException {
        if (!hasZipLocalFileHeader(compressedFile)) {
            return copyFileWithLimit(compressedFile, rawFile, maxRawBytes, "raw download for " + remotePath);
        }

        try (
            InputStream input = new FileInputStream(compressedFile);
            ZipInputStream zipStream = new ZipInputStream(input)
        ) {
            ZipEntry entry = zipStream.getNextEntry();
            if (entry == null) {
                throw new IOException("Downloaded ZIP for " + remotePath + " had no entries.");
            }
            validateZipEntry(entry, remotePath);
            long rawBytes = copyToFile(
                zipStream,
                rawFile,
                maxRawBytes,
                "decompressed download for " + remotePath
            );
            zipStream.closeEntry();
            if (zipStream.getNextEntry() != null) {
                throw new IOException("Downloaded ZIP for " + remotePath + " contained multiple entries.");
            }
            return rawBytes;
        }
    }

    private static boolean hasZipLocalFileHeader(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return input.read() == 0x50
                && input.read() == 0x4B
                && input.read() == 0x03
                && input.read() == 0x04;
        }
    }

    private static void validateZipEntry(ZipEntry entry, String remotePath) throws IOException {
        if (entry.isDirectory()) {
            throw new IOException("Downloaded ZIP for " + remotePath + " contained a directory entry.");
        }
        String name = entry.getName();
        if (isUnsafeZipEntryName(name)) {
            throw new IOException("Downloaded ZIP for " + remotePath + " contained an unsafe entry path.");
        }
    }

    private static boolean isUnsafeZipEntryName(String name) {
        if (isBlank(name) || name.indexOf('\0') >= 0) {
            return true;
        }
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.endsWith("/") || normalized.indexOf(':') >= 0) {
            return true;
        }
        for (String segment : normalized.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static long copyFileWithLimit(
        File source,
        File target,
        long maxBytes,
        String description
    ) throws IOException {
        try (InputStream input = new FileInputStream(source)) {
            return copyToFile(input, target, maxBytes, description);
        }
    }

    private static long copyToFile(
        InputStream input,
        File target,
        long maxBytes,
        String description
    ) throws IOException {
        if (maxBytes < 0L) {
            throw new IllegalArgumentException("maxBytes must not be negative");
        }
        long copied = 0L;
        try (FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[IO_BUFFER_SIZE];
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                if (copied > maxBytes - read) {
                    throw new DownloadLimitIOException(
                        "Steam Cloud " + description + " exceeds the configured limit: limit=" + maxBytes
                    );
                }
                output.write(buffer, 0, read);
                copied += read;
            }
            output.getFD().sync();
        }
        return copied;
    }

    private static void validateDownloadedFile(
        File rawFile,
        long expectedRawSize,
        String expectedSha1,
        String remotePath
    ) throws IOException {
        long actualSize = rawFile.length();
        if (expectedRawSize >= 0L && actualSize != expectedRawSize) {
            throw new IOException(
                "Steam Cloud download size mismatch for "
                    + remotePath
                    + ": expectedRawSize="
                    + expectedRawSize
                    + " actualRawSize="
                    + actualSize
            );
        }
        if (isBlank(expectedSha1)) {
            return;
        }
        String actualSha1 = sha1Hex(rawFile);
        if (!actualSha1.equalsIgnoreCase(expectedSha1.trim())) {
            throw new IOException(
                "Steam Cloud download SHA-1 mismatch for "
                    + remotePath
                    + ": expectedSha1="
                    + expectedSha1.trim()
                    + " actualSha1="
                    + actualSha1
            );
        }
    }

    private static long elapsedMillis(long startedAtNs) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNs);
    }

    private static void validateDownloadedBytes(
        byte[] rawBytes,
        long expectedRawSize,
        String expectedSha1,
        String remotePath
    ) throws IOException {
        if (expectedRawSize >= 0L && rawBytes.length != expectedRawSize) {
            throw new IOException(
                "Steam Cloud download size mismatch for "
                    + remotePath
                    + ": expectedRawSize="
                    + expectedRawSize
                    + " actualRawSize="
                    + rawBytes.length
            );
        }
        if (isBlank(expectedSha1)) {
            return;
        }
        String actualSha1 = sha1Hex(rawBytes);
        if (!actualSha1.equalsIgnoreCase(expectedSha1.trim())) {
            throw new IOException(
                "Steam Cloud download SHA-1 mismatch for "
                    + remotePath
                    + ": expectedSha1="
                    + expectedSha1.trim()
                    + " actualSha1="
                    + actualSha1
            );
        }
    }

    private static String sha1Hex(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(bytes);
            return bytesToHex(digest.digest());
        } catch (Exception error) {
            throw new IOException("Failed to calculate Steam Cloud download SHA-1.", error);
        }
    }

    private static void applyProxySystemProperties() {
        System.setProperty("java.net.useSystemProxies", "true");
        clearProxySystemProperty("http.proxyHost");
        clearProxySystemProperty("http.proxyPort");
        clearProxySystemProperty("https.proxyHost");
        clearProxySystemProperty("https.proxyPort");
        clearProxySystemProperty("socksProxyHost");
        clearProxySystemProperty("socksProxyPort");
    }

    private static void clearProxySystemProperty(String key) {
        System.clearProperty(key);
    }

    private static String readOptionalTextFile(File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    static void ensureDirectoryExists(File directory, String description) throws IOException {
        if (directory == null || directory.isDirectory()) {
            return;
        }
        if (directory.mkdirs() || directory.isDirectory()) {
            return;
        }
        throw new IOException("Failed to create " + description + ": " + directory.getAbsolutePath());
    }

    private static void writeTextFile(File target, String content) throws IOException {
        File parent = target.getParentFile();
        ensureDirectoryExists(parent, "parent directory for " + target.getAbsolutePath());
        File tempFile = new File(parent, "." + target.getName() + "." + System.nanoTime() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(tempFile)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Failed to replace existing file: " + target.getAbsolutePath());
        }
        if (!tempFile.renameTo(target)) {
            throw new IOException("Failed to move temp file into place: " + target.getAbsolutePath());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String buildCredentialsInputDiagnostics(String username, String password, String guardData) {
        return "usernamePresent="
            + (username != null)
            + " usernameLength="
            + safeLength(username)
            + " usernameTrimmedChanged="
            + trimmedChanged(username)
            + " usernameLeadingWhitespace="
            + hasLeadingWhitespace(username)
            + " usernameTrailingWhitespace="
            + hasTrailingWhitespace(username)
            + " usernameNonAsciiCount="
            + countNonAscii(username)
            + " passwordPresent="
            + (password != null)
            + " passwordLength="
            + safeLength(password)
            + " passwordLeadingWhitespace="
            + hasLeadingWhitespace(password)
            + " passwordTrailingWhitespace="
            + hasTrailingWhitespace(password)
            + " passwordNonAsciiCount="
            + countNonAscii(password)
            + " guardDataConfigured="
            + !isBlank(guardData)
            + " guardDataLength="
            + safeLength(guardData);
    }

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private static boolean trimmedChanged(String value) {
        return value != null && !value.equals(value.trim());
    }

    private static boolean hasLeadingWhitespace(String value) {
        return value != null && !value.isEmpty() && Character.isWhitespace(value.charAt(0));
    }

    private static boolean hasTrailingWhitespace(String value) {
        return value != null && !value.isEmpty() && Character.isWhitespace(value.charAt(value.length() - 1));
    }

    private static int countNonAscii(String value) {
        if (value == null) {
            return 0;
        }
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7F) {
                count++;
            }
        }
        return count;
    }

    private static <T> T requireNonNull(T value, String label) {
        return Objects.requireNonNull(value, label + " was not available.");
    }

    public interface AuthPrompt {
        CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect);

        CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect);

        CompletableFuture<Boolean> acceptDeviceConfirmation();
    }

    public static final class AuthMaterial {
        private final String accountName;
        private final String refreshToken;
        private final String guardData;
        private final String steamId64;

        AuthMaterial(String accountName, String refreshToken, String guardData, String steamId64) {
            this.accountName = accountName;
            this.refreshToken = refreshToken;
            this.guardData = guardData;
            this.steamId64 = steamId64;
        }

        public String getAccountName() {
            return accountName;
        }

        public String getRefreshToken() {
            return refreshToken;
        }

        public String getGuardData() {
            return guardData;
        }

        public String getSteamId64() {
            return steamId64;
        }
    }

    public static final class DiagnosticsSnapshot {
        private final String currentStage;
        private final String protocolTypesDescription;
        private final boolean connectedCallbackReceived;
        private final String loggedOnResultDescription;
        private final String disconnectedDescription;
        private final String resolvedServerDescription;
        private final String candidateSourceDescription;
        private final String allowedChallengesDescription;
        private final String lastAuthPromptDescription;
        private final boolean guardDataConfigured;
        private final boolean guardDataUpdated;
        private final String javaSteamLastLogDescription;
        private final String javaSteamLastErrorDescription;
        private final List<String> javaSteamLogTailLines;
        private final List<String> javaSteamErrorStackLines;
        private final List<String> diagnosticEventLines;
        private final String wattAccelerationDescription;
        private final String credentialsAuthSteamId64;
        private final String loggedOnCallbackSteamId64;
        private final String steamClientSteamId64;
        private final long cmServerSelectionMs;
        private final long cmConnectWaitMs;
        private final boolean playingSessionBlocked;
        private final int playingSessionAppId;

        private DiagnosticsSnapshot(
            String currentStage,
            String protocolTypesDescription,
            boolean connectedCallbackReceived,
            String loggedOnResultDescription,
            String disconnectedDescription,
            String resolvedServerDescription,
            String candidateSourceDescription,
            String allowedChallengesDescription,
            String lastAuthPromptDescription,
            boolean guardDataConfigured,
            boolean guardDataUpdated,
            String javaSteamLastLogDescription,
            String javaSteamLastErrorDescription,
            List<String> javaSteamLogTailLines,
            List<String> javaSteamErrorStackLines,
            List<String> diagnosticEventLines,
            String wattAccelerationDescription,
            String credentialsAuthSteamId64,
            String loggedOnCallbackSteamId64,
            String steamClientSteamId64,
            long cmServerSelectionMs,
            long cmConnectWaitMs,
            boolean playingSessionBlocked,
            int playingSessionAppId
        ) {
            this.currentStage = currentStage;
            this.protocolTypesDescription = protocolTypesDescription;
            this.connectedCallbackReceived = connectedCallbackReceived;
            this.loggedOnResultDescription = loggedOnResultDescription;
            this.disconnectedDescription = disconnectedDescription;
            this.resolvedServerDescription = resolvedServerDescription;
            this.candidateSourceDescription = candidateSourceDescription;
            this.allowedChallengesDescription = allowedChallengesDescription;
            this.lastAuthPromptDescription = lastAuthPromptDescription;
            this.guardDataConfigured = guardDataConfigured;
            this.guardDataUpdated = guardDataUpdated;
            this.javaSteamLastLogDescription = javaSteamLastLogDescription;
            this.javaSteamLastErrorDescription = javaSteamLastErrorDescription;
            this.javaSteamLogTailLines = Collections.unmodifiableList(new ArrayList<>(javaSteamLogTailLines));
            this.javaSteamErrorStackLines = Collections.unmodifiableList(new ArrayList<>(javaSteamErrorStackLines));
            this.diagnosticEventLines = Collections.unmodifiableList(new ArrayList<>(diagnosticEventLines));
            this.wattAccelerationDescription = wattAccelerationDescription;
            this.credentialsAuthSteamId64 = credentialsAuthSteamId64;
            this.loggedOnCallbackSteamId64 = loggedOnCallbackSteamId64;
            this.steamClientSteamId64 = steamClientSteamId64;
            this.cmServerSelectionMs = cmServerSelectionMs;
            this.cmConnectWaitMs = cmConnectWaitMs;
            this.playingSessionBlocked = playingSessionBlocked;
            this.playingSessionAppId = playingSessionAppId;
        }

        public String getCurrentStage() {
            return currentStage;
        }

        public String getProtocolTypesDescription() {
            return protocolTypesDescription;
        }

        public boolean getConnectedCallbackReceived() {
            return connectedCallbackReceived;
        }

        public String getLoggedOnResultDescription() {
            return loggedOnResultDescription;
        }

        public String getDisconnectedDescription() {
            return disconnectedDescription;
        }

        public String getResolvedServerDescription() {
            return resolvedServerDescription;
        }

        public String getCandidateSourceDescription() {
            return candidateSourceDescription;
        }

        public String getAllowedChallengesDescription() {
            return allowedChallengesDescription;
        }

        public String getLastAuthPromptDescription() {
            return lastAuthPromptDescription;
        }

        public boolean getGuardDataConfigured() {
            return guardDataConfigured;
        }

        public boolean getGuardDataUpdated() {
            return guardDataUpdated;
        }

        public String getJavaSteamLastLogDescription() {
            return javaSteamLastLogDescription;
        }

        public String getJavaSteamLastErrorDescription() {
            return javaSteamLastErrorDescription;
        }

        public List<String> getJavaSteamLogTailLines() {
            return javaSteamLogTailLines;
        }

        public List<String> getJavaSteamErrorStackLines() {
            return javaSteamErrorStackLines;
        }

        public List<String> getDiagnosticEventLines() {
            return diagnosticEventLines;
        }

        public String getWattAccelerationDescription() {
            return wattAccelerationDescription;
        }

        public String getCredentialsAuthSteamId64() {
            return credentialsAuthSteamId64;
        }

        public String getLoggedOnCallbackSteamId64() {
            return loggedOnCallbackSteamId64;
        }

        public String getSteamClientSteamId64() {
            return steamClientSteamId64;
        }

        public long getCmServerSelectionMs() {
            return cmServerSelectionMs;
        }

        public long getCmConnectWaitMs() {
            return cmConnectWaitMs;
        }

        public boolean getPlayingSessionBlocked() {
            return playingSessionBlocked;
        }

        public int getPlayingSessionAppId() {
            return playingSessionAppId;
        }
    }

    public static final class RemoteFileRecord {
        private final String remotePath;
        private final long rawFileSize;
        private final long timestampMs;
        private final String machineName;
        private final String persistState;
        private final String sha1;

        public RemoteFileRecord(
            String remotePath,
            long rawFileSize,
            long timestampMs,
            String machineName,
            String persistState,
            String sha1
        ) {
            this.remotePath = remotePath;
            this.rawFileSize = rawFileSize;
            this.timestampMs = timestampMs;
            this.machineName = machineName;
            this.persistState = persistState;
            this.sha1 = sha1 == null ? "" : sha1;
        }

        public String getRemotePath() {
            return remotePath;
        }

        public long getRawFileSize() {
            return rawFileSize;
        }

        public long getTimestampMs() {
            return timestampMs;
        }

        public String getMachineName() {
            return machineName;
        }

        public String getPersistState() {
            return persistState;
        }

        public String getSha1() {
            return sha1;
        }
    }

    public static final class DownloadResult {
        private final String remotePath;
        private final String outputPath;
        private final long compressedBytes;
        private final long rawBytes;
        private final boolean decompressed;
        private final long rpcMs;
        private final long httpMs;
        private final long unzipMs;
        private final long writeMs;
        private final long totalMs;

        private DownloadResult(
            String remotePath,
            String outputPath,
            long compressedBytes,
            long rawBytes,
            boolean decompressed,
            long rpcMs,
            long httpMs,
            long unzipMs,
            long writeMs,
            long totalMs
        ) {
            this.remotePath = remotePath;
            this.outputPath = outputPath;
            this.compressedBytes = compressedBytes;
            this.rawBytes = rawBytes;
            this.decompressed = decompressed;
            this.rpcMs = rpcMs;
            this.httpMs = httpMs;
            this.unzipMs = unzipMs;
            this.writeMs = writeMs;
            this.totalMs = totalMs;
        }

        public String getRemotePath() {
            return remotePath;
        }

        public String getOutputPath() {
            return outputPath;
        }

        public long getCompressedBytes() {
            return compressedBytes;
        }

        public long getRawBytes() {
            return rawBytes;
        }

        public boolean getDecompressed() {
            return decompressed;
        }

        public long getRpcMs() {
            return rpcMs;
        }

        public long getHttpMs() {
            return httpMs;
        }

        public long getUnzipMs() {
            return unzipMs;
        }

        public long getWriteMs() {
            return writeMs;
        }

        public long getTotalMs() {
            return totalMs;
        }
    }

    private static final class HttpStatusIOException extends IOException {
        private final int statusCode;

        private HttpStatusIOException(int statusCode, String operation, String remotePath) {
            super("HTTP " + statusCode + " when " + operation + " " + remotePath);
            this.statusCode = statusCode;
        }
    }

    private static final class HttpTransferIOException extends IOException {
        private HttpTransferIOException(String message, IOException cause) {
            super(message, cause);
        }
    }

    private static final class DownloadLimitIOException extends IOException {
        private DownloadLimitIOException(String message) {
            super(message);
        }
    }

    public static final class UploadBatch {
        private final long batchId;
        private final long appChangeNumber;

        private UploadBatch(long batchId, long appChangeNumber) {
            this.batchId = batchId;
            this.appChangeNumber = appChangeNumber;
        }

        public long getBatchId() {
            return batchId;
        }

        public long getAppChangeNumber() {
            return appChangeNumber;
        }
    }

    public static final class UploadedFile {
        private final String remotePath;
        private final long fileSize;
        private final String sha1Hex;

        private UploadedFile(String remotePath, long fileSize, String sha1Hex) {
            this.remotePath = remotePath;
            this.fileSize = fileSize;
            this.sha1Hex = sha1Hex;
        }

        public String getRemotePath() {
            return remotePath;
        }

        public long getFileSize() {
            return fileSize;
        }

        public String getSha1Hex() {
            return sha1Hex;
        }
    }

    private final class PromptAuthenticator implements IAuthenticator {
        private final AuthPrompt prompt;

        private PromptAuthenticator(AuthPrompt prompt) {
            this.prompt = prompt;
        }

        @Override
        public CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect) {
            lastAuthPromptDescription = "device_code"
                + (previousCodeWasIncorrect ? " (retry)" : " (initial)");
            recordDiagnosticEvent("auth_prompt device_code retry=" + previousCodeWasIncorrect);
            Log.i(TAG, "Steam auth requested device code. retry=" + previousCodeWasIncorrect);
            return prompt.getDeviceCode(previousCodeWasIncorrect);
        }

        @Override
        public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect) {
            lastAuthPromptDescription = "email_code"
                + (isBlank(email) ? "" : " (" + email.trim() + ")")
                + (previousCodeWasIncorrect ? " (retry)" : " (initial)");
            recordDiagnosticEvent(
                "auth_prompt email_code hint="
                    + (isBlank(email) ? "<none>" : email.trim())
                    + " retry="
                    + previousCodeWasIncorrect
            );
            Log.i(
                TAG,
                "Steam auth requested email code. hint="
                    + (isBlank(email) ? "<none>" : email.trim())
                    + " retry="
                    + previousCodeWasIncorrect
            );
            return prompt.getEmailCode(email, previousCodeWasIncorrect);
        }

        @Override
        public CompletableFuture<Boolean> acceptDeviceConfirmation() {
            lastAuthPromptDescription = "device_confirmation";
            recordDiagnosticEvent("auth_prompt device_confirmation");
            Log.i(TAG, "Steam auth requested mobile device confirmation.");
            return prompt.acceptDeviceConfirmation();
        }
    }

    private final class JavaSteamLogCollector implements LogListener {
        private static final int MAX_ENTRIES = 48;

        private final Object lock = new Object();
        private final ArrayDeque<JavaSteamLogEntry> entries = new ArrayDeque<>();
        private JavaSteamLogEntry lastErrorEntry;

        @Override
        public void onLog(Class<?> clazz, String message, Throwable error) {
            record("DEBUG", clazz, message, error);
        }

        @Override
        public void onError(Class<?> clazz, String message, Throwable error) {
            record("ERROR", clazz, message, error);
        }

        private void record(String level, Class<?> clazz, String message, Throwable error) {
            JavaSteamLogEntry entry = new JavaSteamLogEntry(
                level,
                clazz == null ? "<unknown>" : clazz.getName(),
                sanitizeSingleLine(message),
                error,
                buildStackTraceLines(error, JAVA_STEAM_STACKTRACE_LINE_LIMIT)
            );

            synchronized (lock) {
                while (entries.size() >= MAX_ENTRIES) {
                    entries.removeFirst();
                }
                entries.addLast(entry);
                if ("ERROR".equals(level) || error != null) {
                    lastErrorEntry = entry;
                }
            }

            if ("ERROR".equals(level) || error != null) {
                Log.e(TAG, "JavaSteam [" + level + "] " + entry.describe(), error);
            } else {
                Log.d(TAG, "JavaSteam [" + level + "] " + entry.describe());
            }
            if (isJavaSteamTransportAbortLog(level, entry.sourceClass, entry.message, error)) {
                reportTransportAbortFromJavaSteamLog(entry);
            }
        }

        private String describeLastLog() {
            synchronized (lock) {
                return entries.isEmpty() ? "<none>" : entries.getLast().describe();
            }
        }

        private String describeLastError() {
            synchronized (lock) {
                return lastErrorEntry == null ? "<none>" : lastErrorEntry.describe();
            }
        }

        private List<String> snapshotTailLines() {
            synchronized (lock) {
                if (entries.isEmpty()) {
                    return Collections.emptyList();
                }
                List<String> lines = new ArrayList<>();
                int index = 0;
                int start = Math.max(0, entries.size() - JAVA_STEAM_LOG_TAIL_LIMIT);
                for (JavaSteamLogEntry entry : entries) {
                    if (index++ < start) {
                        continue;
                    }
                    lines.add(entry.describe());
                }
                return lines;
            }
        }

        private List<String> snapshotErrorStackLines() {
            synchronized (lock) {
                if (lastErrorEntry == null || lastErrorEntry.stackTraceLines.isEmpty()) {
                    return Collections.emptyList();
                }
                return new ArrayList<>(lastErrorEntry.stackTraceLines);
            }
        }
    }

    static boolean isJavaSteamTransportAbortLog(
        String level,
        String sourceClass,
        String message,
        Throwable error
    ) {
        String normalized = (
            sanitizeSingleLine(level)
                + ' '
                + sanitizeSingleLine(sourceClass)
                + ' '
                + sanitizeSingleLine(message)
                + ' '
                + describeThrowable(error)
        ).toLowerCase(Locale.ROOT);
        if (!normalized.contains("javasteam")) {
            return false;
        }
        return normalized.contains("watchdog: no response")
            || normalized.contains("client or session is no longer active")
            || normalized.contains("an error occurred while receiving data");
    }

    private static final class JavaSteamLogEntry {
        private final String level;
        private final String sourceClass;
        private final String message;
        private final String throwableSummary;
        private final List<String> stackTraceLines;

        private JavaSteamLogEntry(
            String level,
            String sourceClass,
            String message,
            Throwable throwable,
            List<String> stackTraceLines
        ) {
            this.level = level;
            this.sourceClass = sourceClass;
            this.message = message == null ? "" : message;
            this.throwableSummary = describeThrowable(throwable);
            this.stackTraceLines = stackTraceLines;
        }

        private String describe() {
            StringBuilder builder = new StringBuilder();
            builder.append(level).append(' ').append(sourceClass);
            if (!message.isEmpty()) {
                builder.append(" - ").append(message);
            }
            if (!throwableSummary.isEmpty()) {
                builder.append(" | ").append(throwableSummary);
            }
            return builder.toString();
        }
    }
}
