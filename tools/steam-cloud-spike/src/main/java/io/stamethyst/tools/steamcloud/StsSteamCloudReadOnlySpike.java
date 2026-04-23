package io.stamethyst.tools.steamcloud;

import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.networking.steam3.ProtocolTypes;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSession;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.EAuthSessionGuardType;
import in.dragonbra.javasteam.steam.handlers.steamcloud.AppFileChangeList;
import in.dragonbra.javasteam.steam.handlers.steamcloud.AppFileInfo;
import in.dragonbra.javasteam.steam.handlers.steamcloud.FileDownloadInfo;
import in.dragonbra.javasteam.steam.handlers.steamcloud.HttpHeaders;
import in.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Read-only spike for validating the Steam Cloud chain for Slay the Spire (AppID 646570).
 *
 * <p>This tool intentionally lives outside the Android app/runtime path. It validates whether
 * we can authenticate, enumerate the cloud files for an app, and download selected files without
 * touching the launcher's production code yet.
 */
public final class StsSteamCloudReadOnlySpike {
    private static final int DEFAULT_APP_ID = 646570;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration AUTH_START_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration AUTH_POLL_TIMEOUT = Duration.ofMinutes(4);
    private static final Duration LOGON_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    private StsSteamCloudReadOnlySpike() {}

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.help) {
            printUsage();
            return;
        }

        options.validate();

        Files.createDirectories(options.outputDir);

        try (Runner runner = new Runner(options)) {
            runner.start();
            if (options.connectOnly) {
                System.out.println("Connect-only mode completed. Steam transport is reachable.");
                return;
            }

            AuthMaterial authMaterial = runner.resolveAuthMaterial(options);
            if (options.writeAuthFile != null && authMaterial.shouldWriteSecretsFile()) {
                writeAuthFile(options.writeAuthFile, authMaterial);
            }

            runner.logOn(authMaterial);

            List<RemoteFileEntry> entries = runner.listFiles(options.appId);
            writeListing(options.outputDir.resolve("cloud-list.tsv"), entries, options.appId);
            printListing(entries, options.listLimit, options.appId);

            List<RemoteFileEntry> selectedEntries = selectDownloads(entries, options);
            if (selectedEntries.isEmpty()) {
                System.out.println();
                System.out.println("No download selection requested. Listing only.");
                System.out.println("Listing manifest written to " + options.outputDir.resolve("cloud-list.tsv"));
                return;
            }

            Path downloadRoot = options.outputDir.resolve("downloads");
            Files.createDirectories(downloadRoot);

            List<DownloadResult> downloadResults = new ArrayList<>();
            for (RemoteFileEntry entry : selectedEntries) {
                System.out.println();
                System.out.println("Downloading [" + entry.index + "] " + entry.displayPath);
                downloadResults.add(runner.download(options.appId, entry, downloadRoot));
            }

            writeDownloads(options.outputDir.resolve("downloads.tsv"), downloadResults);

            System.out.println();
            System.out.println("Downloaded " + downloadResults.size() + " file(s).");
            System.out.println("Download manifest written to " + options.outputDir.resolve("downloads.tsv"));
        }
    }

    private static void printUsage() {
        String usage = ""
            + "Steam Cloud read-only spike for Slay the Spire (default AppID 646570)\n"
            + "\n"
            + "Recommended usage:\n"
            + "  PowerShell env vars:\n"
            + "    $env:STEAM_USERNAME='your_steam_account'\n"
            + "    $env:STEAM_PASSWORD='your_password'\n"
            + "    .\\gradlew :tools:steam-cloud-spike:run --args=\"--write-auth-file .tmp/sts-steam-cloud-spike/auth.env\"\n"
            + "\n"
            + "  Refresh token mode:\n"
            + "    $env:STEAM_ACCOUNT_NAME='your_steam_account'\n"
            + "    $env:STEAM_REFRESH_TOKEN='your_refresh_token'\n"
            + "    .\\gradlew :tools:steam-cloud-spike:run\n"
            + "\n"
            + "Options:\n"
            + "  --help                             Show this help.\n"
            + "  --app-id <id>                      Steam AppID. Default: 646570.\n"
            + "  --output-dir <path>                Output root. Default: .tmp/sts-steam-cloud-spike.\n"
            + "  --list-limit <n>                   Max rows printed to stdout. Default: 200.\n"
            + "  --write-auth-file <path>           Optional file to store STEAM_ACCOUNT_NAME / STEAM_REFRESH_TOKEN / STEAM_GUARD_DATA.\n"
            + "  --accept-device-confirmation <b>   Whether to accept Steam mobile confirmation prompts. Default: true.\n"
            + "  --protocol <auto|websocket|tcp>    Network transport. Default: auto.\n"
            + "  --proxy-url <url>                  Optional proxy URL, e.g. http://127.0.0.1:7897.\n"
            + "  --connect-only                     Stop after Steam transport connect succeeds.\n"
            + "  --download-all                     Download every listed cloud file.\n"
            + "  --download-index <n>               Download one listed row by index. Repeatable.\n"
            + "  --download-path <remote-path>      Download one exact remote path. Repeatable.\n"
            + "  --download-match <substring>       Download files whose remote path contains this text. Repeatable.\n"
            + "\n"
            + "Credential sources:\n"
            + "  Refresh token mode:\n"
            + "    STEAM_ACCOUNT_NAME, STEAM_REFRESH_TOKEN\n"
            + "  Credentials mode:\n"
            + "    STEAM_USERNAME, STEAM_PASSWORD, optional STEAM_GUARD_DATA\n"
            + "  Optional 2FA helpers:\n"
            + "    STEAM_2FA_CODE, STEAM_EMAIL_CODE, STEAM_ACCEPT_DEVICE_CONFIRMATION\n"
            + "  Misc:\n"
            + "    STS_STEAM_CLOUD_APP_ID, STS_STEAM_CLOUD_OUTPUT_DIR\n"
            + "  Connection helpers:\n"
            + "    STEAM_CONNECTION_PROTOCOL, STEAM_PROXY_URL, HTTPS_PROXY, HTTP_PROXY\n";

        System.out.println(usage);
    }

    private static void printListing(List<RemoteFileEntry> entries, int listLimit, int appId) {
        System.out.println("Enumerated " + entries.size() + " cloud file(s) for AppID " + appId + ".");
        if (entries.isEmpty()) {
            return;
        }

        System.out.println();
        System.out.println("Idx | Raw Size | Timestamp           | Machine           | Remote Path");
        System.out.println("----+----------+---------------------+-------------------+------------");

        int printed = 0;
        for (RemoteFileEntry entry : entries) {
            if (printed >= listLimit) {
                break;
            }
            System.out.printf(
                Locale.ROOT,
                "%3d | %8d | %-19s | %-17s | %s%n",
                entry.index,
                entry.rawFileSize,
                TS_FORMATTER.format(entry.timestamp),
                truncate(entry.machineName, 17),
                entry.displayPath
            );
            printed++;
        }

        if (entries.size() > printed) {
            System.out.println();
            System.out.println("... " + (entries.size() - printed) + " more row(s) omitted from stdout.");
        }
    }

    private static void writeListing(Path target, List<RemoteFileEntry> entries, int appId) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("appId\tindex\tremotePath\tfilename\tpathPrefix\tmachineName\trawFileSize\ttimestamp\tpersistState");
        for (RemoteFileEntry entry : entries) {
            lines.add(
                appId + "\t"
                    + entry.index + "\t"
                    + sanitizeCell(entry.displayPath) + "\t"
                    + sanitizeCell(entry.fileName) + "\t"
                    + sanitizeCell(entry.pathPrefix) + "\t"
                    + sanitizeCell(entry.machineName) + "\t"
                    + entry.rawFileSize + "\t"
                    + TS_FORMATTER.format(entry.timestamp) + "\t"
                    + sanitizeCell(entry.persistState)
            );
        }
        Files.createDirectories(target.getParent());
        Files.write(target, lines, StandardCharsets.UTF_8);
    }

    private static void writeDownloads(Path target, List<DownloadResult> results) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("index\tremotePath\tcompressedBytes\trawBytes\tencrypted\toutputPath");
        for (DownloadResult result : results) {
            lines.add(
                result.entry.index + "\t"
                    + sanitizeCell(result.entry.displayPath) + "\t"
                    + result.compressedBytes + "\t"
                    + result.rawBytes + "\t"
                    + result.encrypted + "\t"
                    + sanitizeCell(result.outputPath.toString())
            );
        }
        Files.createDirectories(target.getParent());
        Files.write(target, lines, StandardCharsets.UTF_8);
    }

    private static void writeAuthFile(Path target, AuthMaterial authMaterial) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# Sensitive file generated by the Steam Cloud spike.");
        lines.add("STEAM_ACCOUNT_NAME=" + authMaterial.accountName);
        lines.add("STEAM_REFRESH_TOKEN=" + authMaterial.refreshToken);
        if (authMaterial.guardData != null && !authMaterial.guardData.isBlank()) {
            lines.add("STEAM_GUARD_DATA=" + authMaterial.guardData);
        }
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Files.write(target, lines, StandardCharsets.UTF_8);
        System.out.println("Wrote auth material to " + target + " (contains secrets).");
    }

    private static List<RemoteFileEntry> selectDownloads(List<RemoteFileEntry> entries, Options options) {
        if (!options.downloadAll
            && options.downloadIndices.isEmpty()
            && options.downloadPaths.isEmpty()
            && options.downloadMatches.isEmpty()
        ) {
            return Collections.emptyList();
        }

        Map<Integer, RemoteFileEntry> byIndex = new LinkedHashMap<>();
        Map<String, RemoteFileEntry> byPath = new LinkedHashMap<>();
        for (RemoteFileEntry entry : entries) {
            byIndex.put(entry.index, entry);
            byPath.put(entry.displayPath, entry);
        }

        Set<RemoteFileEntry> selected = new LinkedHashSet<>();
        if (options.downloadAll) {
            selected.addAll(entries);
        }

        for (Integer index : options.downloadIndices) {
            RemoteFileEntry entry = byIndex.get(index);
            if (entry == null) {
                throw new IllegalArgumentException("Unknown --download-index: " + index);
            }
            selected.add(entry);
        }

        for (String path : options.downloadPaths) {
            RemoteFileEntry entry = byPath.get(path);
            if (entry == null) {
                throw new IllegalArgumentException("Unknown --download-path: " + path);
            }
            selected.add(entry);
        }

        for (String needle : options.downloadMatches) {
            boolean matched = false;
            String loweredNeedle = needle.toLowerCase(Locale.ROOT);
            for (RemoteFileEntry entry : entries) {
                if (entry.displayPath.toLowerCase(Locale.ROOT).contains(loweredNeedle)) {
                    selected.add(entry);
                    matched = true;
                }
            }
            if (!matched) {
                throw new IllegalArgumentException("No file matched --download-match: " + needle);
            }
        }

        return new ArrayList<>(selected);
    }

    private static String sanitizeCell(String value) {
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    private static byte[] readAll(InputStream inputStream) throws IOException {
        return inputStream.readAllBytes();
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
            byte[] unzipped = readAll(zipStream);
            if (zipStream.getNextEntry() != null) {
                System.out.println("Warning: ZIP for " + remotePath + " had more than one entry; only the first entry was used.");
            }
            return unzipped;
        }
    }

    private static Path buildOutputPath(Path downloadRoot, String displayPath) {
        String normalized = displayPath.replace('\\', '/');
        Path current = downloadRoot;
        for (String rawSegment : normalized.split("/")) {
            if (rawSegment == null || rawSegment.isBlank()) {
                continue;
            }
            current = current.resolve(sanitizePathSegment(rawSegment));
        }
        return current;
    }

    private static String sanitizePathSegment(String rawSegment) {
        String sanitized = rawSegment
            .replace(':', '_')
            .replace('*', '_')
            .replace('?', '_')
            .replace('"', '_')
            .replace('<', '_')
            .replace('>', '_')
            .replace('|', '_');

        sanitized = sanitized.replaceAll("[\\u0000-\\u001F]", "_").trim();
        if (sanitized.isEmpty()) {
            sanitized = "_";
        }
        while (sanitized.endsWith(".")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1) + "_";
        }
        return sanitized;
    }

    private static final class Runner implements AutoCloseable {
        private final Options options;
        private final SteamConfiguration steamConfiguration;
        private final SteamClient steamClient;
        private final CallbackManager callbackManager;
        private final SteamUser steamUser;
        private final SteamCloud steamCloud;
        private final OkHttpClient httpClient;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
        private final CompletableFuture<Void> connectedFuture = new CompletableFuture<>();
        private final CompletableFuture<Void> disconnectedFuture = new CompletableFuture<>();
        private final CompletableFuture<LoggedOnCallback> loggedOnFuture = new CompletableFuture<>();
        private final CompletableFuture<LoggedOffCallback> loggedOffFuture = new CompletableFuture<>();
        private volatile String currentStage = "startup";
        private Thread callbackThread;

        private Runner(Options options) {
            this.options = options;
            applyProxySystemProperties(options.proxySettings);
            OkHttpClient sharedHttpClient = buildSharedHttpClient(options.proxySettings);
            this.steamConfiguration = buildSteamConfiguration(options, sharedHttpClient);
            this.steamClient = new SteamClient(steamConfiguration);
            this.callbackManager = new CallbackManager(steamClient);
            this.steamUser = requireNonNull(steamClient.getHandler(SteamUser.class), "SteamUser handler");
            this.steamCloud = requireNonNull(steamClient.getHandler(SteamCloud.class), "SteamCloud handler");
            this.httpClient = sharedHttpClient.newBuilder()
                .callTimeout(DOWNLOAD_TIMEOUT)
                .connectTimeout(DOWNLOAD_TIMEOUT)
                .readTimeout(DOWNLOAD_TIMEOUT)
                .writeTimeout(DOWNLOAD_TIMEOUT)
                .build();

            callbackManager.subscribe(ConnectedCallback.class, callback -> {
                System.out.println("Connected to Steam.");
                connectedFuture.complete(null);
            });

            callbackManager.subscribe(DisconnectedCallback.class, callback -> {
                String reason = callback.isUserInitiated() ? "user initiated" : "unexpected";
                if (!callback.isUserInitiated()) {
                    System.out.println();
                }
                System.out.println("Disconnected from Steam (" + reason + ").");
                if (!shuttingDown.get()) {
                    IllegalStateException error = new IllegalStateException(buildDisconnectFailureMessage(reason));
                    disconnectedFuture.completeExceptionally(error);
                    connectedFuture.completeExceptionally(error);
                    loggedOnFuture.completeExceptionally(error);
                }
            });

            callbackManager.subscribe(LoggedOnCallback.class, callback -> {
                System.out.println("Steam logon result: " + callback.getResult());
                loggedOnFuture.complete(callback);
            });

            callbackManager.subscribe(LoggedOffCallback.class, callback -> {
                System.out.println("Steam logged off: " + callback.getResult());
                loggedOffFuture.complete(callback);
            });
        }

        void start() throws Exception {
            running.set(true);
            callbackThread = new Thread(() -> {
                while (running.get()) {
                    callbackManager.runWaitCallbacks(1000L);
                }
            }, "steam-cloud-spike-callbacks");
            callbackThread.setDaemon(true);
            callbackThread.start();

            System.out.println(
                "Connecting to Steam... protocol="
                    + options.connectionMode.displayName
                    + ", proxy="
                    + (options.proxySettings == null ? "none" : options.proxySettings.describe())
            );
            steamClient.connect();
            waitForStage(connectedFuture, CONNECT_TIMEOUT, "Steam connect");
        }

        AuthMaterial resolveAuthMaterial(Options options) throws Exception {
            if (options.refreshToken != null) {
                System.out.println("Using refresh token mode for account '" + options.accountName + "'.");
                return new AuthMaterial(options.accountName, options.refreshToken, options.guardData, false);
            }

            System.out.println("Using credentials mode for account '" + options.username + "'.");
            AuthSessionDetails details = new AuthSessionDetails();
            details.username = options.username;
            details.password = options.password;
            details.guardData = options.guardData;
            details.persistentSession = true;
            details.authenticator = new ConsoleAuthenticator(options);

            var authSession = waitForStage(
                steamClient.getAuthentication().beginAuthSessionViaCredentials(details),
                AUTH_START_TIMEOUT,
                "Steam auth session start"
            );
            maybeWarnAboutInteractiveGuard(authSession);

            AuthPollResult pollResult = waitForStage(
                authSession.pollingWaitForResult(),
                AUTH_POLL_TIMEOUT,
                "Steam auth completion"
            );

            String effectiveGuardData = pollResult.getNewGuardData() != null
                ? pollResult.getNewGuardData()
                : options.guardData;

            System.out.println("Authentication succeeded for '" + pollResult.getAccountName() + "'.");
            System.out.println("Refresh token length: " + pollResult.getRefreshToken().length());
            if (pollResult.getNewGuardData() != null) {
                System.out.println("Received updated guard data.");
            }

            return new AuthMaterial(
                pollResult.getAccountName(),
                pollResult.getRefreshToken(),
                effectiveGuardData,
                true
            );
        }

        void logOn(AuthMaterial authMaterial) throws Exception {
            LogOnDetails details = new LogOnDetails();
            details.setUsername(authMaterial.accountName);
            details.setAccessToken(authMaterial.refreshToken);
            details.setShouldRememberPassword(true);
            details.setLoginID(149);

            System.out.println("Logging on to Steam network with refresh token...");
            steamUser.logOn(details);

            LoggedOnCallback callback = waitForStage(loggedOnFuture, LOGON_TIMEOUT, "Steam logon");
            if (callback.getResult() != EResult.OK) {
                throw new IllegalStateException("Steam logon failed: " + callback.getResult());
            }
        }

        List<RemoteFileEntry> listFiles(int appId) throws Exception {
            AppFileChangeList changeList = waitForStage(
                steamCloud.getAppFileListChange(appId),
                RPC_TIMEOUT,
                "GetAppFileChangelist"
            );

            List<RemoteFileEntry> entries = new ArrayList<>();
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
                entries.add(
                    new RemoteFileEntry(
                        -1,
                        remotePath,
                        remotePath.replace('\\', '/'),
                        file.getFilename(),
                        pathPrefix,
                        machineName,
                        file.getRawFileSize(),
                        file.getTimestamp().toInstant(),
                        file.getPersistState().name()
                    )
                );
            }

            entries.sort(Comparator.comparing(entry -> entry.displayPath.toLowerCase(Locale.ROOT)));

            List<RemoteFileEntry> indexed = new ArrayList<>(entries.size());
            int index = 1;
            for (RemoteFileEntry entry : entries) {
                indexed.add(entry.withIndex(index));
                index++;
            }
            return indexed;
        }

        DownloadResult download(int appId, RemoteFileEntry entry, Path downloadRoot) throws Exception {
            FileDownloadInfo info = waitForStage(
                steamCloud.clientFileDownload(appId, entry.remotePath),
                RPC_TIMEOUT,
                "ClientFileDownload"
            );

            if (info.getUrlHost().isEmpty()) {
                throw new IllegalStateException("Steam returned an empty download host for " + entry.displayPath);
            }

            String scheme = info.getUseHttps() ? "https://" : "http://";
            String url = scheme + info.getUrlHost() + info.getUrlPath();

            Request.Builder requestBuilder = new Request.Builder().url(url);
            for (HttpHeaders header : info.getRequestHeaders()) {
                requestBuilder.addHeader(header.getName(), header.getValue());
            }

            byte[] compressedBytes;
            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code() + " when downloading " + entry.displayPath);
                }
                if (response.body() == null) {
                    throw new IOException("Steam returned an empty response body for " + entry.displayPath);
                }
                compressedBytes = response.body().bytes();
            }

            byte[] rawBytes = compressedBytes;
            if (info.getRawFileSize() != info.getFileSize()) {
                rawBytes = maybeUnzip(compressedBytes, entry.displayPath);
            }

            Path outputPath = buildOutputPath(downloadRoot, entry.displayPath);
            Files.createDirectories(requireNonNull(outputPath.getParent(), "download output parent"));
            Files.write(outputPath, rawBytes);

            System.out.println(
                "Saved to " + outputPath
                    + " (compressed=" + compressedBytes.length
                    + " bytes, raw=" + rawBytes.length
                    + " bytes, encrypted=" + info.getEncrypted() + ")"
            );

            return new DownloadResult(entry, outputPath, compressedBytes.length, rawBytes.length, info.getEncrypted());
        }

        @Override
        public void close() throws Exception {
            shuttingDown.set(true);
            running.set(false);
            try {
                steamUser.logOff();
            } catch (Throwable ignored) {
                // Best effort shutdown.
            }
            try {
                steamClient.disconnect();
            } catch (Throwable ignored) {
                // Best effort shutdown.
            }
            if (callbackThread != null) {
                callbackThread.join(2000L);
            }
            try {
                waitFor(loggedOffFuture, Duration.ofSeconds(5), "Steam logoff");
            } catch (Exception ignored) {
                // A clean logoff is not guaranteed during shutdown.
            }
        }

        private <T> T waitForStage(CompletableFuture<T> future, Duration timeout, String stage)
            throws InterruptedException, ExecutionException, TimeoutException {
            currentStage = stage;
            return waitForEither(future, disconnectedFuture, timeout, stage);
        }

        private void maybeWarnAboutInteractiveGuard(AuthSession authSession) {
            if (!usesWebSocketTransport() || hasPreSuppliedSteamGuardCode()) {
                return;
            }
            if (!requiresInteractiveCode(authSession)) {
                return;
            }

            System.out.println(
                "Warning: this login flow requires a Steam Guard code. JavaSteam 1.6.0's websocket transport "
                    + "has a ~30s no-response watchdog and may disconnect while waiting for interactive input. "
                    + "Prefer setting STEAM_2FA_CODE / STEAM_EMAIL_CODE before launching, or use --protocol tcp "
                    + "if direct TCP is reachable."
            );
        }

        private boolean usesWebSocketTransport() {
            return options.connectionMode == ConnectionMode.AUTO || options.connectionMode == ConnectionMode.WEBSOCKET;
        }

        private boolean hasPreSuppliedSteamGuardCode() {
            return firstNonBlank(options.twoFactorCode, options.emailCode) != null;
        }

        private String buildDisconnectFailureMessage(String reason) {
            StringBuilder message = new StringBuilder()
                .append("Steam disconnected (")
                .append(reason)
                .append(") during ")
                .append(currentStage)
                .append(".");

            if (options.refreshToken == null && usesWebSocketTransport()) {
                message.append(" JavaSteam 1.6.0's websocket transport has a ~30s no-response watchdog.");
                if (!hasPreSuppliedSteamGuardCode()) {
                    message.append(" Interactive Steam Guard entry can trigger this.");
                }
                message.append(" Retry with STEAM_2FA_CODE / STEAM_EMAIL_CODE pre-set, or use --protocol tcp if direct TCP is reachable.");
            }

            return message.toString();
        }
    }

    private static String joinRemotePath(String prefix, String filename) {
        if (prefix == null || prefix.isBlank()) {
            return filename;
        }
        if (filename == null || filename.isBlank()) {
            return prefix;
        }
        char separator = prefix.indexOf('\\') >= 0 && prefix.indexOf('/') < 0 ? '\\' : '/';
        if (prefix.endsWith("/") || prefix.endsWith("\\")) {
            return prefix + filename;
        }
        return prefix + separator + filename;
    }

    private static <T> T waitFor(CompletableFuture<T> future, Duration timeout, String stage)
        throws InterruptedException, ExecutionException, TimeoutException {
        return waitForEither(future, null, timeout, stage);
    }

    @SuppressWarnings("unchecked")
    private static <T> T waitForEither(
        CompletableFuture<T> future,
        CompletableFuture<?> abortFuture,
        Duration timeout,
        String stage
    ) throws InterruptedException, ExecutionException, TimeoutException {
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
                combined.completeExceptionally(
                    new IllegalStateException(stage + " was aborted before completion.")
                );
            });
        }

        try {
            return (T) combined.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            throw new TimeoutException(stage + " timed out after " + timeout.toSeconds() + "s.");
        }
    }

    private static boolean requiresInteractiveCode(AuthSession authSession) {
        for (var confirmation : authSession.getAllowedConfirmations()) {
            EAuthSessionGuardType type = confirmation.getConfirmationType();
            if (type == EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode
                || type == EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode
            ) {
                return true;
            }
        }
        return false;
    }

    private static <T> T requireNonNull(T value, String label) {
        return Objects.requireNonNull(value, label + " was not available.");
    }

    private static final class ConsoleAuthenticator implements IAuthenticator {
        private final Options options;

        private ConsoleAuthenticator(Options options) {
            this.options = options;
        }

        @Override
        public CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect) {
            String code = firstNonBlank(options.twoFactorCode, env("STEAM_2FA_CODE"));
            if (code != null) {
                return CompletableFuture.completedFuture(code);
            }
            return CompletableFuture.completedFuture(
                prompt(
                    previousCodeWasIncorrect
                        ? "Previous 2FA code was incorrect. Enter a new Steam Guard 2FA code: "
                        : "Enter Steam Guard 2FA code: ",
                    false
                )
            );
        }

        @Override
        public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect) {
            String code = firstNonBlank(options.emailCode, env("STEAM_EMAIL_CODE"));
            if (code != null) {
                return CompletableFuture.completedFuture(code);
            }
            String prompt = previousCodeWasIncorrect
                ? "Previous email code was incorrect. Enter a new Steam email code"
                : "Enter Steam email code";
            if (email != null && !email.isBlank()) {
                prompt += " for " + email;
            }
            prompt += ": ";
            return CompletableFuture.completedFuture(prompt(prompt, false));
        }

        @Override
        public CompletableFuture<Boolean> acceptDeviceConfirmation() {
            return CompletableFuture.completedFuture(options.acceptDeviceConfirmation);
        }
    }

    private static String prompt(String message, boolean allowEmpty) {
        Console console = System.console();
        if (console != null) {
            while (true) {
                String value = console.readLine("%s", message);
                if (value == null) {
                    throw new IllegalStateException(buildClosedInputMessage("console"));
                }
                if (allowEmpty || (value != null && !value.isBlank())) {
                    return value == null ? "" : value.trim();
                }
            }
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            try {
                System.out.print(message);
                String value = reader.readLine();
                if (value == null) {
                    throw new IllegalStateException(buildClosedInputMessage("stdin"));
                }
                if (allowEmpty || (value != null && !value.isBlank())) {
                    return value == null ? "" : value.trim();
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read input from stdin.", e);
            }
        }
    }

    private static String buildClosedInputMessage(String inputKind) {
        return "Interactive Steam Guard input is unavailable because " + inputKind + " is closed. "
            + "If you are launching through Gradle, upgrade to the latest spike code and rerun. "
            + "As a fallback, provide STEAM_2FA_CODE or STEAM_EMAIL_CODE through environment variables.";
    }

    private static SteamConfiguration buildSteamConfiguration(Options options, OkHttpClient sharedHttpClient) {
        return SteamConfiguration.create(builder -> {
            builder.withHttpClient(sharedHttpClient);
            builder.withConnectionTimeout(CONNECT_TIMEOUT.toMillis());
            builder.withProtocolTypes(options.protocolTypes);
        });
    }

    private static OkHttpClient buildSharedHttpClient(ProxySettings proxySettings) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(DOWNLOAD_TIMEOUT)
            .readTimeout(DOWNLOAD_TIMEOUT)
            .writeTimeout(DOWNLOAD_TIMEOUT)
            .callTimeout(DOWNLOAD_TIMEOUT)
            .retryOnConnectionFailure(true);

        if (proxySettings != null) {
            builder.proxy(proxySettings.proxy);
        }

        return builder.build();
    }

    private static void applyProxySystemProperties(ProxySettings proxySettings) {
        System.setProperty("java.net.useSystemProxies", "true");

        clearProxySystemProperty("http.proxyHost");
        clearProxySystemProperty("http.proxyPort");
        clearProxySystemProperty("https.proxyHost");
        clearProxySystemProperty("https.proxyPort");
        clearProxySystemProperty("socksProxyHost");
        clearProxySystemProperty("socksProxyPort");

        if (proxySettings == null) {
            return;
        }

        String host = proxySettings.uri.getHost();
        String port = Integer.toString(proxySettings.uri.getPort());
        if (proxySettings.proxy.type() == Proxy.Type.SOCKS) {
            System.setProperty("socksProxyHost", host);
            System.setProperty("socksProxyPort", port);
            return;
        }

        System.setProperty("http.proxyHost", host);
        System.setProperty("http.proxyPort", port);
        System.setProperty("https.proxyHost", host);
        System.setProperty("https.proxyPort", port);
    }

    private static void clearProxySystemProperty(String key) {
        if (System.getProperty(key) != null) {
            System.clearProperty(key);
        }
    }

    private static String env(String key) {
        String value = System.getenv(key);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static final class Options {
        private boolean help;
        private int appId = parseIntOrDefault(env("STS_STEAM_CLOUD_APP_ID"), DEFAULT_APP_ID);
        private Path outputDir = Paths.get(firstNonBlank(env("STS_STEAM_CLOUD_OUTPUT_DIR"), ".tmp/sts-steam-cloud-spike"));
        private int listLimit = 200;
        private Path writeAuthFile;
        private boolean acceptDeviceConfirmation =
            parseBooleanOrDefault(firstNonBlank(env("STEAM_ACCEPT_DEVICE_CONFIRMATION"), "true"), true);
        private String protocolName = firstNonBlank(env("STEAM_CONNECTION_PROTOCOL"), "auto");
        private String proxyUrl = firstNonBlank(
            env("STEAM_PROXY_URL"),
            env("HTTPS_PROXY"),
            env("https_proxy"),
            env("HTTP_PROXY"),
            env("http_proxy")
        );
        private ConnectionMode connectionMode = ConnectionMode.AUTO;
        private EnumSet<ProtocolTypes> protocolTypes = EnumSet.of(ProtocolTypes.TCP, ProtocolTypes.WEB_SOCKET);
        private ProxySettings proxySettings;
        private boolean connectOnly;

        private String accountName = env("STEAM_ACCOUNT_NAME");
        private String refreshToken = env("STEAM_REFRESH_TOKEN");
        private String username = env("STEAM_USERNAME");
        private String password = env("STEAM_PASSWORD");
        private String guardData = env("STEAM_GUARD_DATA");
        private String twoFactorCode = env("STEAM_2FA_CODE");
        private String emailCode = env("STEAM_EMAIL_CODE");

        private final List<Integer> downloadIndices = new ArrayList<>();
        private final List<String> downloadPaths = new ArrayList<>();
        private final List<String> downloadMatches = new ArrayList<>();
        private boolean downloadAll;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String rawArg = args[i];
                if (!rawArg.startsWith("--")) {
                    throw new IllegalArgumentException("Unknown argument: " + rawArg);
                }

                String key;
                String inlineValue = null;
                int equalsIndex = rawArg.indexOf('=');
                if (equalsIndex >= 0) {
                    key = rawArg.substring(2, equalsIndex);
                    inlineValue = rawArg.substring(equalsIndex + 1);
                } else {
                    key = rawArg.substring(2);
                }

                switch (key) {
                    case "help":
                        options.help = true;
                        break;
                    case "app-id":
                        options.appId = Integer.parseInt(requireValue(key, inlineValue, args, ++i));
                        break;
                    case "output-dir":
                        options.outputDir = Paths.get(requireValue(key, inlineValue, args, ++i));
                        break;
                    case "list-limit":
                        options.listLimit = Integer.parseInt(requireValue(key, inlineValue, args, ++i));
                        break;
                    case "write-auth-file":
                        options.writeAuthFile = Paths.get(requireValue(key, inlineValue, args, ++i));
                        break;
                    case "accept-device-confirmation":
                        options.acceptDeviceConfirmation =
                            parseBooleanOrDefault(requireValue(key, inlineValue, args, ++i), true);
                        break;
                    case "protocol":
                        options.protocolName = requireValue(key, inlineValue, args, ++i);
                        break;
                    case "proxy-url":
                        options.proxyUrl = requireValue(key, inlineValue, args, ++i);
                        break;
                    case "connect-only":
                        options.connectOnly = true;
                        break;
                    case "account-name":
                        options.accountName = requireValue(key, inlineValue, args, ++i);
                        break;
                    case "refresh-token":
                        options.refreshToken = requireValue(key, inlineValue, args, ++i);
                        break;
                    case "username":
                        options.username = requireValue(key, inlineValue, args, ++i);
                        break;
                    case "password":
                        options.password = requireValue(key, inlineValue, args, ++i);
                        break;
                    case "guard-data":
                        options.guardData = requireValue(key, inlineValue, args, ++i);
                        break;
                    case "download-all":
                        options.downloadAll = true;
                        break;
                    case "download-index":
                        options.downloadIndices.add(Integer.parseInt(requireValue(key, inlineValue, args, ++i)));
                        break;
                    case "download-path":
                        options.downloadPaths.add(requireValue(key, inlineValue, args, ++i));
                        break;
                    case "download-match":
                        options.downloadMatches.add(requireValue(key, inlineValue, args, ++i));
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown flag: --" + key);
                }
            }
            return options;
        }

        void validate() {
            if (appId <= 0) {
                throw new IllegalArgumentException("--app-id must be > 0.");
            }
            if (listLimit <= 0) {
                throw new IllegalArgumentException("--list-limit must be > 0.");
            }

            connectionMode = ConnectionMode.parse(protocolName);
            protocolTypes = connectionMode.toProtocolTypes();
            proxySettings = ProxySettings.parse(proxyUrl);
            if (proxySettings != null && connectionMode == ConnectionMode.TCP) {
                System.out.println("Warning: proxy is configured, but TCP mode does not currently tunnel through the configured proxy.");
            }

            if (connectOnly) {
                return;
            }

            boolean hasRefreshTokenMode = refreshToken != null;
            boolean hasCredentialsMode = username != null || password != null;

            if (hasRefreshTokenMode) {
                if (accountName == null) {
                    throw new IllegalArgumentException("Refresh token mode requires STEAM_ACCOUNT_NAME or --account-name.");
                }
                return;
            }

            if (!hasCredentialsMode) {
                throw new IllegalArgumentException(
                    "Provide either STEAM_ACCOUNT_NAME + STEAM_REFRESH_TOKEN, or STEAM_USERNAME + STEAM_PASSWORD."
                );
            }

            if (username == null || password == null) {
                throw new IllegalArgumentException(
                    "Credentials mode requires both STEAM_USERNAME and STEAM_PASSWORD."
                );
            }

        }

        private static String requireValue(String key, String inlineValue, String[] args, int index) {
            if (inlineValue != null) {
                return inlineValue;
            }
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for --" + key);
            }
            return args[index];
        }

        private static int parseIntOrDefault(String value, int defaultValue) {
            if (value == null) {
                return defaultValue;
            }
            return Integer.parseInt(value);
        }

        private static boolean parseBooleanOrDefault(String value, boolean defaultValue) {
            if (value == null) {
                return defaultValue;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                return defaultValue;
            }
            return normalized.equals("1")
                || normalized.equals("true")
                || normalized.equals("yes")
                || normalized.equals("y");
        }
    }

    private enum ConnectionMode {
        AUTO("auto"),
        WEBSOCKET("websocket"),
        TCP("tcp");

        private final String displayName;

        ConnectionMode(String displayName) {
            this.displayName = displayName;
        }

        private EnumSet<ProtocolTypes> toProtocolTypes() {
            if (this == AUTO) {
                return EnumSet.of(ProtocolTypes.TCP, ProtocolTypes.WEB_SOCKET);
            }
            if (this == WEBSOCKET) {
                return EnumSet.of(ProtocolTypes.WEB_SOCKET);
            }
            return EnumSet.of(ProtocolTypes.TCP);
        }

        private static ConnectionMode parse(String rawValue) {
            String normalized = rawValue == null ? "auto" : rawValue.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty() || normalized.equals("auto")) {
                return AUTO;
            }
            if (normalized.equals("ws")
                || normalized.equals("websocket")
                || normalized.equals("web_socket")
                || normalized.equals("wss")
            ) {
                return WEBSOCKET;
            }
            if (normalized.equals("tcp")) {
                return TCP;
            }
            throw new IllegalArgumentException("Unsupported --protocol value: " + rawValue);
        }
    }

    private static final class ProxySettings {
        private final URI uri;
        private final Proxy proxy;

        private ProxySettings(URI uri, Proxy proxy) {
            this.uri = uri;
            this.proxy = proxy;
        }

        private static ProxySettings parse(String rawUrl) {
            if (rawUrl == null || rawUrl.isBlank()) {
                return null;
            }

            URI uri;
            try {
                uri = URI.create(rawUrl.trim());
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Invalid proxy URL: " + rawUrl, error);
            }

            String scheme = uri.getScheme() == null ? "" : uri.getScheme().trim().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || host.isBlank() || port <= 0) {
                throw new IllegalArgumentException("Proxy URL must include a host and port: " + rawUrl);
            }

            Proxy.Type proxyType;
            if (scheme.equals("http") || scheme.equals("https")) {
                proxyType = Proxy.Type.HTTP;
            } else if (scheme.equals("socks") || scheme.equals("socks5")) {
                proxyType = Proxy.Type.SOCKS;
            } else {
                throw new IllegalArgumentException(
                    "Unsupported proxy scheme '" + scheme + "'. Use http:// or socks5://."
                );
            }

            Proxy proxy = new Proxy(proxyType, new InetSocketAddress(host, port));
            return new ProxySettings(uri, proxy);
        }

        private String describe() {
            return proxy.type().name().toLowerCase(Locale.ROOT) + "://" + uri.getHost() + ":" + uri.getPort();
        }
    }

    private static final class AuthMaterial {
        private final String accountName;
        private final String refreshToken;
        private final String guardData;
        private final boolean writeSecretsFile;

        private AuthMaterial(String accountName, String refreshToken, String guardData, boolean writeSecretsFile) {
            this.accountName = accountName;
            this.refreshToken = refreshToken;
            this.guardData = guardData;
            this.writeSecretsFile = writeSecretsFile;
        }

        boolean shouldWriteSecretsFile() {
            return writeSecretsFile;
        }
    }

    private static final class RemoteFileEntry {
        private final int index;
        private final String remotePath;
        private final String displayPath;
        private final String fileName;
        private final String pathPrefix;
        private final String machineName;
        private final int rawFileSize;
        private final Instant timestamp;
        private final String persistState;

        private RemoteFileEntry(
            int index,
            String remotePath,
            String displayPath,
            String fileName,
            String pathPrefix,
            String machineName,
            int rawFileSize,
            Instant timestamp,
            String persistState
        ) {
            this.index = index;
            this.remotePath = remotePath;
            this.displayPath = displayPath;
            this.fileName = fileName;
            this.pathPrefix = pathPrefix;
            this.machineName = machineName == null || machineName.isBlank() ? "-" : machineName;
            this.rawFileSize = rawFileSize;
            this.timestamp = timestamp;
            this.persistState = persistState;
        }

        private RemoteFileEntry withIndex(int nextIndex) {
            return new RemoteFileEntry(
                nextIndex,
                remotePath,
                displayPath,
                fileName,
                pathPrefix,
                machineName,
                rawFileSize,
                timestamp,
                persistState
            );
        }
    }

    private static final class DownloadResult {
        private final RemoteFileEntry entry;
        private final Path outputPath;
        private final int compressedBytes;
        private final int rawBytes;
        private final boolean encrypted;

        private DownloadResult(
            RemoteFileEntry entry,
            Path outputPath,
            int compressedBytes,
            int rawBytes,
            boolean encrypted
        ) {
            this.entry = entry;
            this.outputPath = outputPath;
            this.compressedBytes = compressedBytes;
            this.rawBytes = rawBytes;
            this.encrypted = encrypted;
        }
    }

}
