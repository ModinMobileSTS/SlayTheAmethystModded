package io.stamethyst.backend.steamcloud;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.google.protobuf.GeneratedMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.security.MessageDigest;

import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesCloudSteamclient;
import in.dragonbra.javasteam.networking.steam3.ProtocolTypes;
import in.dragonbra.javasteam.rpc.service.Cloud;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.EAuthSessionGuardType;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSession;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.steam.discovery.FileServerListProvider;
import in.dragonbra.javasteam.steam.discovery.ServerRecord;
import in.dragonbra.javasteam.steam.discovery.SmartCMServerList;
import in.dragonbra.javasteam.steam.handlers.steamcloud.AppFileChangeList;
import in.dragonbra.javasteam.steam.handlers.steamcloud.AppFileInfo;
import in.dragonbra.javasteam.steam.handlers.steamcloud.AppUploadBatchResponse;
import in.dragonbra.javasteam.steam.handlers.steamcloud.FileDownloadInfo;
import in.dragonbra.javasteam.steam.handlers.steamcloud.HttpHeaders;
import in.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages;
import in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.callback.ServiceMethodResponse;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration;
import in.dragonbra.javasteam.types.AsyncJobSingle;
import in.dragonbra.javasteam.types.SteamID;
import in.dragonbra.javasteam.util.log.LogListener;
import in.dragonbra.javasteam.util.log.LogManager;
import io.stamethyst.config.RuntimePaths;
import okhttp3.RequestBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class SteamCloudClient implements AutoCloseable {
    private static final String TAG = "SteamCloudClient";
    private static final long CONNECT_TIMEOUT_MS = 40_000L;
    private static final long AUTH_START_TIMEOUT_MS = 60_000L;
    private static final long AUTH_POLL_TIMEOUT_MS = 4L * 60L * 1000L;
    private static final long LOGON_TIMEOUT_MS = 45_000L;
    private static final long RPC_TIMEOUT_MS = 90_000L;
    private static final long DOWNLOAD_TIMEOUT_MS = 60_000L;
    private static final long CALLBACK_POLL_TIMEOUT_MS = 250L;
    private static final int JAVA_STEAM_LOG_TAIL_LIMIT = 12;
    private static final int JAVA_STEAM_STACKTRACE_LINE_LIMIT = 24;
    private static final String OUTPUT_DIR_NAME = "steam-cloud";
    private static final String LAST_CM_ENDPOINT_FILE_NAME = "last-websocket-cm-endpoint.txt";
    private static final String CM_SERVER_LIST_FILE_NAME = "steam-cm-server-list.bin";

    private final SteamClient steamClient;
    private final CallbackManager callbackManager;
    private final SteamUser steamUser;
    private final SteamCloud steamCloud;
    private final Cloud cloudService;
    private final OkHttpClient httpClient;
    private final File lastCmEndpointFile;
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
    private volatile String loggedOnCallbackSteamId64 = "";
    private volatile String steamClientSteamId64 = "";
    private volatile long cmServerSelectionMs = -1L;
    private volatile long cmConnectWaitMs = -1L;
    private Thread callbackThread;

    private static final class PreparedServerRecord {
        private final ServerRecord serverRecord;
        private final String candidateSourceDescription;

        private PreparedServerRecord(ServerRecord serverRecord, String candidateSourceDescription) {
            this.serverRecord = serverRecord;
            this.candidateSourceDescription = candidateSourceDescription;
        }
    }

    public SteamCloudClient(Context context) {
        applyProxySystemProperties();

        File outputDir = new File(RuntimePaths.storageRoot(context), OUTPUT_DIR_NAME);
        if (!outputDir.isDirectory()) {
            outputDir.mkdirs();
        }
        lastCmEndpointFile = new File(outputDir, LAST_CM_ENDPOINT_FILE_NAME);

        wattAccelerationEnabled = SteamCloudAcceleratedHttp.isEnabled(context);
        httpClient = SteamCloudAcceleratedHttp.createClient(
            context,
            DOWNLOAD_TIMEOUT_MS,
            DOWNLOAD_TIMEOUT_MS,
            DOWNLOAD_TIMEOUT_MS
        );
        Log.i(TAG, "Steam Cloud Watt acceleration: " + (wattAccelerationEnabled ? "enabled" : "disabled") + '.');

        File cmServerListFile = new File(outputDir, CM_SERVER_LIST_FILE_NAME);
        SteamConfiguration steamConfiguration = SteamConfiguration.create(builder -> {
            builder.withHttpClient(httpClient);
            builder.withConnectionTimeout(CONNECT_TIMEOUT_MS);
            builder.withProtocolTypes(protocolTypes);
            builder.withServerListProvider(new FileServerListProvider(cmServerListFile));
        });

        javaSteamLogCollector = new JavaSteamLogCollector();
        LogManager.addListener(javaSteamLogCollector);
        steamClient = new SteamClient(steamConfiguration);
        callbackManager = new CallbackManager(steamClient);
        steamUser = requireNonNull(steamClient.getHandler(SteamUser.class), "SteamUser handler");
        steamCloud = requireNonNull(steamClient.getHandler(SteamCloud.class), "SteamCloud handler");
        SteamUnifiedMessages unifiedMessages = requireNonNull(
            steamClient.getHandler(SteamUnifiedMessages.class),
            "SteamUnifiedMessages handler"
        );
        cloudService = unifiedMessages.createService(Cloud.class);

        callbackManager.subscribe(ConnectedCallback.class, callback -> {
            connectedCallbackReceived = true;
            Log.i(TAG, "Connected to Steam.");
            connectedFuture.complete(null);
        });
        callbackManager.subscribe(DisconnectedCallback.class, callback -> {
            String reason = callback.isUserInitiated() ? "user initiated" : "unexpected";
            disconnectedDescription = reason;
            Log.i(TAG, "Disconnected from Steam (" + reason + ") during " + currentStage + '.');
            if (!shuttingDown.get()) {
                IllegalStateException error = new IllegalStateException(buildDisconnectFailureMessage(reason));
                disconnectedFuture.completeExceptionally(error);
                connectedFuture.completeExceptionally(error);
                loggedOnFuture.completeExceptionally(error);
            }
        });
        callbackManager.subscribe(LoggedOnCallback.class, callback -> {
            loggedOnResultDescription = String.valueOf(callback.getResult());
            SteamID steamID = callback.getClientSteamID();
            currentSteamId64 = steamID == null ? "" : String.valueOf(steamID.convertToUInt64());
            loggedOnCallbackSteamId64 = currentSteamId64;
            SteamID clientSteamId = steamClient.getSteamID();
            steamClientSteamId64 = clientSteamId == null ? "" : String.valueOf(clientSteamId.convertToUInt64());
            Log.i(TAG, "Steam logon result: " + callback.getResult());
            loggedOnFuture.complete(callback);
        });
    }

    public void beginOperationDiagnostics(String operation, String accountName, boolean hasGuardData) {
        currentStage = operation == null || operation.trim().isEmpty() ? "startup" : operation.trim();
        guardDataConfigured = hasGuardData;
        guardDataUpdated = false;
        allowedChallengesDescription = "<not evaluated>";
        lastAuthPromptDescription = "<not requested>";
        loggedOnResultDescription = "<not received>";
        currentSteamId64 = "";
        loggedOnCallbackSteamId64 = "";
        steamClientSteamId64 = "";
        disconnectedDescription = "<not observed>";
        cmServerSelectionMs = -1L;
        cmConnectWaitMs = -1L;
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
            callbackThread = new Thread(() -> {
                while (running.get()) {
                    try {
                        callbackManager.runWaitCallbacks(CALLBACK_POLL_TIMEOUT_MS);
                    } catch (Throwable error) {
                        if (shuttingDown.get() || !running.get()) {
                            break;
                        }
                        Log.e(TAG, "Steam callback loop failed unexpectedly.", error);
                        connectedFuture.completeExceptionally(error);
                        loggedOnFuture.completeExceptionally(error);
                        disconnectedFuture.completeExceptionally(error);
                        break;
                    }
                }
            }, "steam-cloud-client-callbacks");
            callbackThread.setDaemon(true);
            callbackThread.start();

            PreparedServerRecord preparedServerRecord;
            long serverSelectionStartedAtNs = System.nanoTime();
            try {
                preparedServerRecord = selectWebSocketServerRecord();
            } finally {
                cmServerSelectionMs = elapsedMillis(serverSelectionStartedAtNs);
            }
            ServerRecord serverRecord = preparedServerRecord == null ? null : preparedServerRecord.serverRecord;
            if (serverRecord == null) {
                throw new IllegalStateException(
                    "Steam server list returned no websocket CM candidate, and no fallback websocket endpoint was available."
                );
            }

            candidateSourceDescription = preparedServerRecord.candidateSourceDescription;
            resolvedServerDescription = describeServerRecord(serverRecord);
            Log.i(
                TAG,
                "Connecting to Steam websocket CM endpoint="
                    + resolvedServerDescription
                    + " source="
                    + candidateSourceDescription
            );
            long connectStartedAtNs = System.nanoTime();
            try {
                steamClient.connect(serverRecord);
                waitForStage(connectedFuture, CONNECT_TIMEOUT_MS, "Steam connect");
            } finally {
                cmConnectWaitMs = elapsedMillis(connectStartedAtNs);
            }
            persistResolvedWebSocketEndpoint(serverRecord);
        } catch (Exception error) {
            Log.e(TAG, "Steam connect failed during " + currentStage + '.', error);
            throw error;
        }
    }

    public AuthMaterial authenticateWithCredentials(
        String username,
        String password,
        String guardData,
        AuthPrompt prompt
    ) throws Exception {
        try {
            Log.i(
                TAG,
                "Starting credentials auth for account="
                    + (isBlank(username) ? "<unknown>" : username.trim())
                    + " guardDataConfigured="
                    + !isBlank(guardData)
            );
            AuthSessionDetails details = new AuthSessionDetails();
            details.username = username;
            details.password = password;
            details.guardData = guardData;
            details.persistentSession = true;
            details.authenticator = new PromptAuthenticator(prompt);

            AuthSession authSession = waitForStage(
                steamClient.getAuthentication().beginAuthSessionViaCredentials(details),
                AUTH_START_TIMEOUT_MS,
                "Steam auth session start"
            );
            maybeValidateSupportedChallenges(authSession);

            AuthPollResult pollResult = waitForStage(
                authSession.pollingWaitForResult(),
                AUTH_POLL_TIMEOUT_MS,
                "Steam auth completion"
            );

            String effectiveGuardData = pollResult.getNewGuardData() != null
                ? pollResult.getNewGuardData()
                : guardData;
            guardDataUpdated = !isBlank(pollResult.getNewGuardData());
            Log.i(
                TAG,
                "Credentials auth completed for account="
                    + pollResult.getAccountName()
                    + " refreshTokenLength="
                    + (pollResult.getRefreshToken() == null ? 0 : pollResult.getRefreshToken().length())
                    + " guardDataUpdated="
                    + guardDataUpdated
            );

            return new AuthMaterial(
                pollResult.getAccountName(),
                pollResult.getRefreshToken(),
                effectiveGuardData
            );
        } catch (Exception error) {
            Log.e(TAG, "Credentials auth failed during " + currentStage + '.', error);
            throw error;
        }
    }

    public void logOnWithRefreshToken(String accountName, String refreshToken) throws Exception {
        try {
            Log.i(
                TAG,
                "Logging on with refresh token for account="
                    + (isBlank(accountName) ? "<unknown>" : accountName.trim())
            );
            LogOnDetails details = new LogOnDetails();
            details.setUsername(accountName);
            details.setAccessToken(refreshToken);
            details.setShouldRememberPassword(true);
            details.setLoginID(149);
            steamUser.logOn(details);

            LoggedOnCallback callback = waitForStage(loggedOnFuture, LOGON_TIMEOUT_MS, "Steam logon");
            if (callback.getResult() != EResult.OK) {
                throw new IllegalStateException("Steam logon failed: " + callback.getResult());
            }
            SteamID steamID = callback.getClientSteamID();
            if (steamID != null) {
                currentSteamId64 = String.valueOf(steamID.convertToUInt64());
            } else if (steamClient.getSteamID() != null) {
                currentSteamId64 = String.valueOf(steamClient.getSteamID().convertToUInt64());
            }
            SteamID clientSteamId = steamClient.getSteamID();
            steamClientSteamId64 = clientSteamId == null ? "" : String.valueOf(clientSteamId.convertToUInt64());
        } catch (Exception error) {
            Log.e(TAG, "Refresh-token logon failed during " + currentStage + '.', error);
            throw error;
        }
    }

    public String getCurrentSteamId64() {
        return currentSteamId64;
    }

    public List<RemoteFileRecord> listFiles(int appId) throws Exception {
        try {
            Log.i(TAG, "Requesting Steam Cloud manifest for AppID " + appId + '.');
            AppFileChangeList changeList = waitForStage(
                steamCloud.getAppFileListChange(appId),
                RPC_TIMEOUT_MS,
                "GetAppFileChangelist"
            );

            List<RemoteFileRecord> entries = new ArrayList<>();
            for (AppFileInfo file : changeList.getFiles()) {
                String pathPrefix = "";
                if (file.getPathPrefixIndex() >= 0 && file.getPathPrefixIndex() < changeList.getPathPrefixes().size()) {
                    pathPrefix = changeList.getPathPrefixes().get(file.getPathPrefixIndex());
                }

                String machineName = "";
                if (file.getMachineNameIndex() >= 0 && file.getMachineNameIndex() < changeList.getMachineNames().size()) {
                    machineName = changeList.getMachineNames().get(file.getMachineNameIndex());
                }

                String remotePath = joinRemotePath(pathPrefix, file.getFilename());
                entries.add(new RemoteFileRecord(
                    remotePath,
                    file.getRawFileSize(),
                    file.getTimestamp().toInstant().toEpochMilli(),
                    machineName,
                    file.getPersistState().name(),
                    bytesToHex(file.getShaFile())
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

    public DownloadResult downloadFile(int appId, String remotePath, File outputFile) throws Exception {
        long startedAtNs = System.nanoTime();
        long rpcMs = 0L;
        long httpMs = 0L;
        long unzipMs = 0L;
        long writeMs = 0L;
        long compressedBytesCount = 0L;
        long rawBytesCount = 0L;
        boolean decompressed = false;
        try {
            Log.i(TAG, "Downloading Steam Cloud file: " + remotePath);
            long rpcStartedAtNs = System.nanoTime();
            FileDownloadInfo info = waitForStage(
                steamCloud.clientFileDownload(appId, remotePath),
                RPC_TIMEOUT_MS,
                "ClientFileDownload"
            );
            rpcMs = elapsedMillis(rpcStartedAtNs);

            if (info.getUrlHost().isEmpty()) {
                throw new IllegalStateException("Steam returned an empty download host for " + remotePath);
            }

            String scheme = info.getUseHttps() ? "https://" : "http://";
            String url = scheme + info.getUrlHost() + info.getUrlPath();
            Request.Builder requestBuilder = new Request.Builder().url(url);
            for (HttpHeaders header : info.getRequestHeaders()) {
                requestBuilder.addHeader(header.getName(), header.getValue());
            }

            byte[] compressedBytes;
            long httpStartedAtNs = System.nanoTime();
            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code() + " when downloading " + remotePath);
                }
                if (response.body() == null) {
                    throw new IOException("Steam returned an empty response body for " + remotePath);
                }
                compressedBytes = response.body().bytes();
            }
            httpMs = elapsedMillis(httpStartedAtNs);
            compressedBytesCount = compressedBytes.length;

            byte[] rawBytes = compressedBytes;
            if (info.getRawFileSize() != info.getFileSize()) {
                long unzipStartedAtNs = System.nanoTime();
                rawBytes = maybeUnzip(compressedBytes, remotePath);
                unzipMs = elapsedMillis(unzipStartedAtNs);
                decompressed = true;
            }
            rawBytesCount = rawBytes.length;

            File parent = outputFile.getParentFile();
            ensureDirectoryExists(parent, "output directory");
            long writeStartedAtNs = System.nanoTime();
            try (FileOutputStream outputStream = new FileOutputStream(outputFile, false)) {
                outputStream.write(rawBytes);
            }
            writeMs = elapsedMillis(writeStartedAtNs);
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
        } catch (Exception error) {
            Log.e(TAG, "Steam Cloud file download failed for " + remotePath + '.', error);
            throw error;
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
            AppUploadBatchResponse response = waitForStage(
                steamCloud.beginAppUploadBatch(
                    appId,
                    buildUploadMachineName(),
                    remotePathsToUpload,
                    remotePathsToDelete,
                    0L,
                    0L
                ),
                RPC_TIMEOUT_MS,
                "BeginAppUploadBatch"
            );
            Log.i(
                TAG,
                "Steam Cloud upload batch started. batchId="
                    + response.getBatchID()
                    + " appChangeNumber="
                    + response.getAppChangeNumber()
            );
            return new UploadBatch(response.getBatchID(), response.getAppChangeNumber());
        } catch (Exception error) {
            Log.e(TAG, "Steam Cloud upload batch start failed during " + currentStage + '.', error);
            throw error;
        }
    }

    public UploadedFile uploadFile(int appId, String remotePath, File sourceFile, long uploadBatchId) throws Exception {
        if (!sourceFile.isFile()) {
            throw new IOException("Steam Cloud upload source file is missing: " + sourceFile.getAbsolutePath());
        }

        long fileSizeLong = sourceFile.length();
        if (fileSizeLong > Integer.MAX_VALUE) {
            throw new IOException("Steam Cloud upload does not support files larger than 2 GiB: " + remotePath);
        }
        int fileSize = (int) fileSizeLong;
        String sha1Hex = sha1Hex(sourceFile);
        boolean startedUpload = false;

        try {
            Log.i(
                TAG,
                "Beginning HTTP upload for Steam Cloud file: "
                    + remotePath
                    + " bytes="
                    + fileSize
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
            ServiceMethodResponse<SteammessagesCloudSteamclient.CCloud_BeginHTTPUpload_Response.Builder> beginResponse =
                waitForServiceJob(
                    cloudService.beginHTTPUpload(request),
                    RPC_TIMEOUT_MS,
                    "BeginHTTPUpload"
                );
            ensureServiceResult(beginResponse, "BeginHTTPUpload");
            startedUpload = true;

            SteammessagesCloudSteamclient.CCloud_BeginHTTPUpload_Response.Builder body = beginResponse.getBody();
            String url = (body.getUseHttps() ? "https://" : "http://") + body.getUrlHost() + body.getUrlPath();
            Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .put(RequestBody.create(sourceFile, null));
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
            }

            boolean committed = commitHttpUpload(true, appId, sha1Hex, remotePath);
            if (!committed) {
                throw new IllegalStateException("Steam did not commit uploaded file: " + remotePath);
            }
            Log.i(TAG, "Steam Cloud upload committed: " + remotePath);
            return new UploadedFile(remotePath, fileSizeLong, sha1Hex);
        } catch (Exception error) {
            if (startedUpload) {
                try {
                    commitHttpUpload(false, appId, sha1Hex, remotePath);
                } catch (Throwable commitError) {
                    Log.w(TAG, "Failed to report Steam Cloud upload failure for " + remotePath + '.', commitError);
                }
            }
            Log.e(TAG, "Steam Cloud upload failed for " + remotePath + '.', error);
            throw error;
        }
    }

    public void completeUploadBatch(int appId, long batchId, EResult batchResult) throws Exception {
        try {
            waitForStage(
                steamCloud.completeAppUploadBatch(appId, batchId, batchResult),
                RPC_TIMEOUT_MS,
                "CompleteAppUploadBatch"
            );
            Log.i(TAG, "Steam Cloud upload batch completed. batchId=" + batchId + " result=" + batchResult);
        } catch (Exception error) {
            Log.e(TAG, "Steam Cloud upload batch completion failed during " + currentStage + '.', error);
            throw error;
        }
    }

    @Override
    public void close() {
        shuttingDown.set(true);
        running.set(false);

        Thread thread = callbackThread;
        if (thread != null) {
            thread.interrupt();
        }
        try {
            steamClient.disconnect();
        } catch (Throwable ignored) {
            // Best effort.
        }
        LogManager.removeListener(javaSteamLogCollector);
        if (thread != null) {
            try {
                thread.join(1000L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
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
            javaSteamLogCollector.describeLastLog(),
            javaSteamLogCollector.describeLastError(),
            javaSteamLogCollector.snapshotTailLines(),
            javaSteamLogCollector.snapshotErrorStackLines(),
            wattAccelerationEnabled ? "enabled" : "disabled",
            loggedOnCallbackSteamId64,
            steamClientSteamId64,
            cmServerSelectionMs,
            cmConnectWaitMs
        );
    }

    private <T> T waitForStage(CompletableFuture<T> future, long timeoutMs, String stage) throws Exception {
        currentStage = stage;
        return waitForEither(future, disconnectedFuture, timeoutMs, stage);
    }

    private PreparedServerRecord selectWebSocketServerRecord() throws IOException {
        List<PreparedServerRecord> candidates = new ArrayList<>();

        ServerRecord serverListRecord = steamClient.getServers().getNextServerCandidate(protocolTypes);
        if (serverListRecord != null) {
            candidates.add(new PreparedServerRecord(serverListRecord, "Steam server list"));
        }

        String cachedAddress = readOptionalTextFile(lastCmEndpointFile);
        if (!isBlank(cachedAddress)) {
            candidates.add(
                new PreparedServerRecord(
                    ServerRecord.createWebSocketServer(cachedAddress),
                    "Cached websocket CM fallback"
                )
            );
        }

        String defaultAddress = SmartCMServerList.getDefaultServerWebSocket();
        if (!isBlank(defaultAddress)) {
            candidates.add(
                new PreparedServerRecord(
                    ServerRecord.createWebSocketServer(defaultAddress),
                    "JavaSteam default websocket CM"
                )
            );
        }

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
                return new PreparedServerRecord(
                    ServerRecord.createWebSocketServer(formatHostPort(literalAddress, port)),
                    candidate.candidateSourceDescription + " (pre-resolved " + host + " -> " + literalAddress + ")"
                );
            }
            return candidate;
        }

        if (isIpLiteral(host)) {
            return candidate;
        }

        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses == null || addresses.length == 0) {
            throw new IOException("Failed to resolve Steam websocket CM hostname: " + host);
        }
        String preferredAddress = selectPreferredAddress(addresses);
        if (isBlank(preferredAddress)) {
            throw new IOException("Resolved Steam websocket CM hostname had no usable address: " + host);
        }

        Log.i(TAG, "Pre-resolved Steam websocket CM hostname " + host + " -> " + preferredAddress + '.');
        return new PreparedServerRecord(
            ServerRecord.createWebSocketServer(formatHostPort(preferredAddress, port)),
            candidate.candidateSourceDescription + " (pre-resolved " + host + " -> " + preferredAddress + ")"
        );
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
            if (type == EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode
                || type == EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode
                || type == EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceConfirmation
            ) {
                allowedChallengesDescription = challengeDescriptions.isEmpty()
                    ? "<none>"
                    : String.join(", ", challengeDescriptions);
                Log.i(TAG, "Steam auth allowed challenges: " + allowedChallengesDescription);
                return;
            }
        }
        allowedChallengesDescription = challengeDescriptions.isEmpty()
            ? "<none>"
            : String.join(", ", challengeDescriptions);
        Log.w(TAG, "Steam auth exposed no supported challenge flow. allowed=" + allowedChallengesDescription);
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

        try {
            return (T) combined.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException error) {
            Throwable cause = unwrapAsyncThrowable(error);
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(stage + " failed.", cause);
        } catch (TimeoutException error) {
            throw new TimeoutException(stage + " timed out after " + (timeoutMs / 1000L) + "s.");
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

    private static String selectPreferredAddress(InetAddress[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "";
        }
        for (InetAddress address : addresses) {
            String literal = address == null ? "" : sanitizeSingleLine(address.getHostAddress());
            if (!isBlank(literal) && literal.indexOf(':') < 0) {
                return literal;
            }
        }
        for (InetAddress address : addresses) {
            String literal = address == null ? "" : sanitizeSingleLine(address.getHostAddress());
            if (!isBlank(literal)) {
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
        ServiceMethodResponse<SteammessagesCloudSteamclient.CCloud_CommitHTTPUpload_Response.Builder> response =
            waitForServiceJob(
                cloudService.commitHTTPUpload(request),
                RPC_TIMEOUT_MS,
                "CommitHTTPUpload"
            );
        ensureServiceResult(response, "CommitHTTPUpload");
        return response.getBody().getFileCommitted();
    }

    private <T extends GeneratedMessage.Builder<T>> ServiceMethodResponse<T> waitForServiceJob(
        AsyncJobSingle<ServiceMethodResponse<T>> job,
        long timeoutMs,
        String stage
    ) throws Exception {
        return waitForStage(job.toFuture(), timeoutMs, stage);
    }

    private static <T extends GeneratedMessage.Builder<T>> void ensureServiceResult(
        ServiceMethodResponse<T> response,
        String operation
    ) {
        if (response.getResult() != EResult.OK) {
            throw new IllegalStateException(operation + " failed: " + response.getResult());
        }
    }

    private static String sha1Hex(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (Exception error) {
            throw new IOException("Failed to initialize SHA-1 digest.", error);
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            digest.update(bytes);
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

    private static byte[] maybeUnzip(byte[] bytes, String remotePath) throws IOException {
        if (bytes.length < 4
            || bytes[0] != 0x50
            || bytes[1] != 0x4B
            || bytes[2] != 0x03
            || bytes[3] != 0x04
        ) {
            return bytes;
        }

        try (ZipInputStream zipStream = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry = zipStream.getNextEntry();
            if (entry == null) {
                throw new IOException("Downloaded ZIP for " + remotePath + " had no entries.");
            }
            return readAll(zipStream);
        }
    }

    private static long elapsedMillis(long startedAtNs) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNs);
    }

    private static byte[] readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (true) {
            int read = inputStream.read(buffer);
            if (read < 0) {
                break;
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
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

        private AuthMaterial(String accountName, String refreshToken, String guardData) {
            this.accountName = accountName;
            this.refreshToken = refreshToken;
            this.guardData = guardData;
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
        private final String wattAccelerationDescription;
        private final String loggedOnCallbackSteamId64;
        private final String steamClientSteamId64;
        private final long cmServerSelectionMs;
        private final long cmConnectWaitMs;

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
            String wattAccelerationDescription,
            String loggedOnCallbackSteamId64,
            String steamClientSteamId64,
            long cmServerSelectionMs,
            long cmConnectWaitMs
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
            this.wattAccelerationDescription = wattAccelerationDescription;
            this.loggedOnCallbackSteamId64 = loggedOnCallbackSteamId64;
            this.steamClientSteamId64 = steamClientSteamId64;
            this.cmServerSelectionMs = cmServerSelectionMs;
            this.cmConnectWaitMs = cmConnectWaitMs;
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

        public String getWattAccelerationDescription() {
            return wattAccelerationDescription;
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
            Log.i(TAG, "Steam auth requested device code. retry=" + previousCodeWasIncorrect);
            return prompt.getDeviceCode(previousCodeWasIncorrect);
        }

        @Override
        public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect) {
            lastAuthPromptDescription = "email_code"
                + (isBlank(email) ? "" : " (" + email.trim() + ")")
                + (previousCodeWasIncorrect ? " (retry)" : " (initial)");
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
            Log.i(TAG, "Steam auth requested mobile device confirmation.");
            return prompt.acceptDeviceConfirmation();
        }
    }

    private static final class JavaSteamLogCollector implements LogListener {
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
