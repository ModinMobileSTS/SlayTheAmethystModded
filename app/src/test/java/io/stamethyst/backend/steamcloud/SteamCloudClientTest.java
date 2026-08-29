package io.stamethyst.backend.steamcloud;

import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesCloudSteamclient;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.types.SteamID;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SteamCloudClientTest {
    @Test
    public void requireFullFileChangelist_rejectsDeltaResponse() {
        IOException error = Assert.assertThrows(
            IOException.class,
            () -> SteamCloudClient.requireFullFileChangelist(true, 42L)
        );

        Assert.assertTrue(error.getMessage().contains("delta"));
        Assert.assertTrue(error.getMessage().contains("42"));
    }

    @Test
    public void requireFullFileChangelist_acceptsFullResponse() throws Exception {
        SteamCloudClient.requireFullFileChangelist(false, 42L);
    }

    @Test
    public void requireMatchingSteamIdentity_rejectsMismatchedAccount() {
        Assert.assertThrows(
            IOException.class,
            () -> SteamCloudClient.requireMatchingSteamIdentity("76561198000000001", 76561198000000002L)
        );
    }

    @Test
    public void requireMatchingSteamIdentity_acceptsExpectedAccount() throws Exception {
        SteamCloudClient.requireMatchingSteamIdentity("76561198000000001", 76561198000000001L);
    }

    @Test
    public void requireMatchingSteamIdentity_allowsLegacyCallerWithoutSavedIdentity() throws Exception {
        SteamCloudClient.requireMatchingSteamIdentity("", 76561198000000001L);
    }

    @Test
    public void buildClientDeleteFileRequest_marksAutosaveDeletionExplicitAndBatched() {
        SteammessagesCloudSteamclient.CCloud_ClientDeleteFile_Request request =
            SteamCloudClient.buildClientDeleteFileRequest(
                646570,
                "%GameInstall%saves/1_IRONCLAD.autosave",
                123L
            );

        Assert.assertEquals(646570, request.getAppid());
        Assert.assertEquals("%GameInstall%saves/1_IRONCLAD.autosave", request.getFilename());
        Assert.assertTrue(request.getIsExplicitDelete());
        Assert.assertEquals(123L, request.getUploadBatchId());
    }

    @Test
    public void buildClientDeleteFileRequest_rejectsMissingBatchId() {
        Assert.assertThrows(
            IllegalArgumentException.class,
            () -> SteamCloudClient.buildClientDeleteFileRequest(
                646570,
                "%GameInstall%saves/1_IRONCLAD.autosave",
                0L
            )
        );
    }

    @Test
    public void requireIndexedChangelistValue_rejectsOutOfRangeIndex() {
        Assert.assertThrows(
            IOException.class,
            () -> SteamCloudClient.requireIndexedChangelistValue("path prefix", 2, 1, index -> "prefix")
        );
    }

    @Test
    public void requireKnownPersistState_acceptsKnownDefaultAndRejectsUnknownWireValue() throws Exception {
        SteammessagesCloudSteamclient.CCloud_AppFileInfo defaultState =
            SteammessagesCloudSteamclient.CCloud_AppFileInfo.newBuilder().build();
        Assert.assertTrue(SteamCloudClient.requireKnownPersistState(defaultState).contains("Persisted"));

        SteammessagesCloudSteamclient.CCloud_AppFileInfo unknownState =
            SteammessagesCloudSteamclient.CCloud_AppFileInfo.parseFrom(new byte[] { 40, 99 });
        Assert.assertThrows(
            IOException.class,
            () -> SteamCloudClient.requireKnownPersistState(unknownState)
        );
    }

    @Test
    public void createUploadSnapshot_usesDedicatedDirectory() throws Exception {
        File root = Files.createTempDirectory("steam-cloud-upload-snapshot-test").toFile();
        File source = new File(root, "preferences/STSPlayer");
        File snapshotDirectory = new File(root, "launcher/steam-cloud/upload-snapshots");
        source.getParentFile().mkdirs();
        Files.write(source.toPath(), "save".getBytes(StandardCharsets.UTF_8));
        File snapshot = null;
        try {
            snapshot = SteamCloudClient.createUploadSnapshot(source, snapshotDirectory);
            Assert.assertEquals(snapshotDirectory.getCanonicalFile(), snapshot.getParentFile().getCanonicalFile());
            Assert.assertArrayEquals(Files.readAllBytes(source.toPath()), Files.readAllBytes(snapshot.toPath()));
        } finally {
            if (snapshot != null) {
                snapshot.delete();
            }
            root.delete();
        }
    }

    @Test
    public void ensureDirectoryExists_toleratesConcurrentCreationRace() throws Exception {
        SteamCloudClient.ensureDirectoryExists(
            new SequencedDirectoryFile("C:/steam-cloud/preferences", false, false, true),
            "output directory"
        );
    }

    @Test
    public void ensureDirectoryExists_throwsWhenDirectoryCannotBeCreated() {
        SequencedDirectoryFile directory = new SequencedDirectoryFile(
            "C:/steam-cloud/preferences",
            false,
            false,
            false
        );
        IOException error = Assert.assertThrows(
            IOException.class,
            () -> SteamCloudClient.ensureDirectoryExists(directory, "output directory")
        );

        Assert.assertEquals(
            "Failed to create output directory: " + directory.getAbsolutePath(),
            error.getMessage()
        );
    }

    @Test
    public void validateDownloadedBytes_acceptsMatchingSizeAndSha1() throws Exception {
        invokeValidateDownloadedBytes(
            "abc".getBytes(StandardCharsets.UTF_8),
            3L,
            "A9993E364706816ABA3E25717850C26C9CD0D89D",
            "%GameInstall%preferences/STSPlayer"
        );
    }

    @Test
    public void validateDownloadedBytes_throwsOnSizeMismatch() throws Exception {
        InvocationTargetException error = Assert.assertThrows(
            InvocationTargetException.class,
            () -> invokeValidateDownloadedBytes(
                "abc".getBytes(StandardCharsets.UTF_8),
                4L,
                "",
                "%GameInstall%preferences/STSPlayer"
            )
        );

        Assert.assertTrue(error.getCause() instanceof IOException);
        Assert.assertTrue(error.getCause().getMessage().contains("expectedRawSize=4 actualRawSize=3"));
    }

    @Test
    public void validateDownloadedBytes_throwsOnSha1Mismatch() throws Exception {
        InvocationTargetException error = Assert.assertThrows(
            InvocationTargetException.class,
            () -> invokeValidateDownloadedBytes(
                "abc".getBytes(StandardCharsets.UTF_8),
                3L,
                "0000000000000000000000000000000000000000",
                "%GameInstall%preferences/STSPlayer"
            )
        );

        Assert.assertTrue(error.getCause() instanceof IOException);
        Assert.assertTrue(error.getCause().getMessage().contains("Steam Cloud download SHA-1 mismatch"));
    }

    @Test
    public void downloadLimits_defaultsToBoundedValues() {
        SteamCloudClient.DownloadLimits limits = SteamCloudClient.DownloadLimits.defaults();

        Assert.assertEquals(
            SteamCloudClient.DEFAULT_MAX_COMPRESSED_DOWNLOAD_BYTES,
            limits.getMaxCompressedDownloadBytes()
        );
        Assert.assertEquals(
            SteamCloudClient.DEFAULT_MAX_RAW_DOWNLOAD_BYTES,
            limits.getMaxRawDownloadBytes()
        );
    }

    @Test
    public void downloadLimits_rejectsNegativeValues() {
        Assert.assertThrows(
            IllegalArgumentException.class,
            () -> new SteamCloudClient.DownloadLimits(-1L, 1L)
        );
        Assert.assertThrows(
            IllegalArgumentException.class,
            () -> new SteamCloudClient.DownloadLimits(1L, -1L)
        );
    }

    @Test
    public void copyToFile_enforcesConfiguredLimitBeforeWritingPastIt() throws Exception {
        File directory = Files.createTempDirectory("steam-cloud-client-test").toFile();
        File target = new File(directory, "download.tmp");
        try {
            InvocationTargetException error = Assert.assertThrows(
                InvocationTargetException.class,
                () -> invokeCopyToFile(
                    "too-large".getBytes(StandardCharsets.UTF_8),
                    target,
                    3L,
                    "test download"
                )
            );

            Assert.assertTrue(error.getCause() instanceof IOException);
            Assert.assertTrue(error.getCause().getMessage().contains("limit=3"));
            Assert.assertTrue(target.length() <= 3L);
        } finally {
            target.delete();
            directory.delete();
        }
    }

    @Test
    public void maybeUnzip_acceptsOneSafeFileEntry() throws Exception {
        File directory = Files.createTempDirectory("steam-cloud-client-test").toFile();
        File compressed = new File(directory, "download.zip");
        File raw = new File(directory, "download.raw");
        try {
            writeZip(compressed, new String[] { "payload.dat" }, new byte[][] { "abc".getBytes(StandardCharsets.UTF_8) });

            Assert.assertEquals(
                3L,
                invokeMaybeUnzip(compressed, raw, "remote/path", 10L)
            );
            Assert.assertArrayEquals(
                "abc".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(raw.toPath())
            );
        } finally {
            compressed.delete();
            raw.delete();
            directory.delete();
        }
    }

    @Test
    public void maybeUnzip_rejectsUnsafeAndMultipleEntries() throws Exception {
        File directory = Files.createTempDirectory("steam-cloud-client-test").toFile();
        File unsafeZip = new File(directory, "unsafe.zip");
        File multipleZip = new File(directory, "multiple.zip");
        File raw = new File(directory, "download.raw");
        try {
            writeZip(
                unsafeZip,
                new String[] { "../escape.dat" },
                new byte[][] { "abc".getBytes(StandardCharsets.UTF_8) }
            );
            InvocationTargetException unsafeError = Assert.assertThrows(
                InvocationTargetException.class,
                () -> invokeMaybeUnzip(unsafeZip, raw, "remote/path", 10L)
            );
            Assert.assertTrue(unsafeError.getCause() instanceof IOException);
            Assert.assertTrue(unsafeError.getCause().getMessage().contains("unsafe entry path"));

            writeZip(
                multipleZip,
                new String[] { "first.dat", "second.dat" },
                new byte[][] { "a".getBytes(StandardCharsets.UTF_8), "b".getBytes(StandardCharsets.UTF_8) }
            );
            InvocationTargetException multipleError = Assert.assertThrows(
                InvocationTargetException.class,
                () -> invokeMaybeUnzip(multipleZip, raw, "remote/path", 10L)
            );
            Assert.assertTrue(multipleError.getCause() instanceof IOException);
            Assert.assertTrue(multipleError.getCause().getMessage().contains("multiple entries"));
        } finally {
            unsafeZip.delete();
            multipleZip.delete();
            raw.delete();
            directory.delete();
        }
    }

    @Test
    public void isRetryableBeginHttpUploadResult_retriesTooManyPending() throws Exception {
        Assert.assertTrue(invokeIsRetryableBeginHttpUploadResult(EResult.TooManyPending));
        Assert.assertTrue(invokeIsRetryableBeginHttpUploadResult(EResult.DuplicateRequest));
        Assert.assertTrue(invokeIsRetryableBeginHttpUploadResult(EResult.Timeout));
        Assert.assertFalse(invokeIsRetryableBeginHttpUploadResult(EResult.AccessDenied));
    }

    @Test
    public void isRetryableBeginAppUploadBatchResult_retriesTooManyPendingAndZeroBatchId() throws Exception {
        Assert.assertTrue(invokeIsRetryableBeginAppUploadBatchResult(EResult.TooManyPending, 0L));
        Assert.assertTrue(invokeIsRetryableBeginAppUploadBatchResult(EResult.Timeout, 0L));
        Assert.assertTrue(invokeIsRetryableBeginAppUploadBatchResult(EResult.OK, 0L));
        Assert.assertFalse(invokeIsRetryableBeginAppUploadBatchResult(EResult.OK, 1L));
        Assert.assertFalse(invokeIsRetryableBeginAppUploadBatchResult(EResult.AccessDenied, 0L));
    }

    @Test
    public void isRetryableBeginAppUploadBatchResult_recognizesSteamCode108AsTooManyPending() throws Exception {
        Assert.assertEquals(108, EResult.TooManyPending.code());
        Assert.assertTrue(invokeIsRetryableBeginAppUploadBatchResult(EResult.from(108), 0L));
    }

    @Test
    public void beginAppUploadBatchRetryDelayMs_usesLongerBackoffForTooManyPendingAndZeroBatchId() throws Exception {
        Assert.assertEquals(10_000L, invokeBeginAppUploadBatchRetryDelayMs(EResult.TooManyPending, 0L, 1));
        Assert.assertEquals(20_000L, invokeBeginAppUploadBatchRetryDelayMs(EResult.OK, 0L, 2));
        Assert.assertEquals(120_000L, invokeBeginAppUploadBatchRetryDelayMs(EResult.OK, 0L, 7));
        Assert.assertEquals(2_000L, invokeBeginAppUploadBatchRetryDelayMs(EResult.Timeout, 0L, 1));
        Assert.assertEquals(10_000L, invokeBeginAppUploadBatchRetryDelayMs(EResult.Timeout, 99L, 4));
    }

    @Test
    public void beginHttpUploadRetryDelayMs_usesLongerBackoffForTooManyPending() throws Exception {
        Assert.assertEquals(10_000L, invokeBeginHttpUploadRetryDelayMs(EResult.TooManyPending, 1));
        Assert.assertEquals(20_000L, invokeBeginHttpUploadRetryDelayMs(EResult.TooManyPending, 2));
        Assert.assertEquals(120_000L, invokeBeginHttpUploadRetryDelayMs(EResult.TooManyPending, 7));
        Assert.assertEquals(10_000L, invokeBeginHttpUploadRetryDelayMs(EResult.DuplicateRequest, 1));
        Assert.assertEquals(20_000L, invokeBeginHttpUploadRetryDelayMs(EResult.DuplicateRequest, 2));
        Assert.assertEquals(2_000L, invokeBeginHttpUploadRetryDelayMs(EResult.Timeout, 1));
        Assert.assertEquals(10_000L, invokeBeginHttpUploadRetryDelayMs(EResult.Timeout, 4));
    }

    @Test
    public void ensureValidUploadBatchId_rejectsZeroBatchId() throws Exception {
        InvocationTargetException error = Assert.assertThrows(
            InvocationTargetException.class,
            () -> invokeEnsureValidUploadBatchId(0L, EResult.OK)
        );

        Assert.assertTrue(error.getCause() instanceof IllegalStateException);
        Assert.assertTrue(error.getCause().getMessage().contains("invalid batchId=0"));
    }

    @Test
    public void ensureValidUploadBatchId_acceptsNonZeroBatchId() throws Exception {
        invokeEnsureValidUploadBatchId(6872875296586793002L, EResult.OK);
    }

    @Test
    public void isRetryableDownloadException_retriesTransientHttpFailuresOnly() throws Exception {
        Assert.assertTrue(invokeIsRetryableDownloadException(newHttpStatusIOException(503)));
        Assert.assertTrue(invokeIsRetryableDownloadException(newHttpStatusIOException(500)));
        Assert.assertTrue(invokeIsRetryableDownloadException(newHttpStatusIOException(429)));
        Assert.assertTrue(invokeIsRetryableDownloadException(new SocketTimeoutException("timeout")));
        Assert.assertFalse(invokeIsRetryableDownloadException(newHttpStatusIOException(404)));
        Assert.assertFalse(invokeIsRetryableDownloadException(new IOException("Steam returned an empty response body")));
        Assert.assertFalse(invokeIsRetryableDownloadException(new InterruptedException()));
    }

    @Test
    public void downloadRetryDelayMs_usesShortTransientBackoff() throws Exception {
        Assert.assertEquals(2_000L, invokeDownloadRetryDelayMs(1));
        Assert.assertEquals(5_000L, invokeDownloadRetryDelayMs(2));
        Assert.assertEquals(10_000L, invokeDownloadRetryDelayMs(3));
        Assert.assertEquals(10_000L, invokeDownloadRetryDelayMs(10));
    }

    @Test
    public void selectPreferredAddress_prefersIpv4AndRejectsIpv6Only() throws Exception {
        InetAddress ipv6 = InetAddress.getByAddress(new byte[] {
            0x24, 0x06, (byte) 0xcb, 0x42,
            0, 0, 0x0f, 0x0e,
            0, 0, 0, 0,
            0, 0, 0x49, (byte) 0xbd,
        });
        InetAddress ipv4 = InetAddress.getByAddress(new byte[] {
            (byte) 185, 25, (byte) 183, 52,
        });

        Assert.assertEquals(
            "185.25.183.52",
            SteamCloudClient.selectPreferredAddress(new InetAddress[] { ipv6, ipv4 })
        );
        Assert.assertEquals(
            "",
            SteamCloudClient.selectPreferredAddress(new InetAddress[] { ipv6 })
        );
    }

    @Test
    public void isWebSocketEndpointParserSafe_rejectsIpv6Literals() {
        Assert.assertTrue(SteamCloudClient.isWebSocketEndpointParserSafe("185.25.183.52:27021"));
        Assert.assertFalse(
            SteamCloudClient.isWebSocketEndpointParserSafe("[2406:cb42:0:f00e::49bd]:27021")
        );
        Assert.assertFalse(
            SteamCloudClient.isWebSocketEndpointParserSafe("2406:cb42:0:f00e::49bd")
        );
    }

    @Test
    public void isJavaSteamTransportAbortLog_detectsWatchdogAndInactiveSession() {
        Assert.assertTrue(SteamCloudClient.isJavaSteamTransportAbortLog(
            "ERROR",
            "in.dragonbra.javasteam.networking.steam3.WebSocketConnection",
            "Watchdog: No response for 30 seconds. Disconnecting from steam",
            null
        ));
        Assert.assertTrue(SteamCloudClient.isJavaSteamTransportAbortLog(
            "ERROR",
            "in.dragonbra.javasteam.networking.steam3.WebSocketConnection",
            "Client or Session is no longer active",
            null
        ));
        Assert.assertFalse(SteamCloudClient.isJavaSteamTransportAbortLog(
            "DEBUG",
            "in.dragonbra.javasteam.networking.steam3.WebSocketConnection",
            "Disconnect called: false",
            null
        ));
    }

    @Test
    public void pushReconnectRetryCandidate_acceptsBeginAppUploadBatchUnexpectedDisconnect() throws Exception {
        SteamCloudClient.DiagnosticsSnapshot diagnostics = newDiagnosticsSnapshot(
            "BeginAppUploadBatch",
            "unexpected",
            Collections.emptyList()
        );

        Assert.assertTrue(invokeIsReconnectRetryCandidate(
            new IllegalStateException("Steam disconnected (unexpected) during BeginAppUploadBatch."),
            diagnostics
        ));
    }

    @Test
    public void pushReconnectRetryCandidate_acceptsHttpPutSocketAbortAfterUploadSlot() throws Exception {
        SteamCloudClient.DiagnosticsSnapshot diagnostics = newDiagnosticsSnapshot(
            "CompleteAppUploadBatch",
            "unexpected",
            Collections.singletonList(
                "upload_file failed remotePath=%GameInstall%preferences/STSSeenCards.backUp "
                    + "batchId=7134522280144150877 startedUpload=true "
                    + "error=java.net.SocketException: Software caused connection abort"
            )
        );

        Assert.assertTrue(invokeIsReconnectRetryCandidate(
            new IllegalStateException(
                "Steam Cloud upload failed for %GameInstall%preferences/STSSeenCards.backUp: "
                    + "SocketException: Software caused connection abort",
                new SocketException("Software caused connection abort")
            ),
            diagnostics
        ));
    }

    @Test
    public void resolveSteamId64FromAuthSession_readsCredentialsAuthSessionField() {
        String steamId64 = "76561198883607238";

        Assert.assertEquals(
            steamId64,
            SteamCloudClient.resolveSteamId64FromAuthSession(new FakeCredentialsAuthSession(steamId64))
        );
    }

    @Test
    public void applySteamId64ToLogOnDetails_setsAccountIdAndInstance() {
        String steamId64 = "76561198883607238";
        SteamID steamID = new SteamID();
        steamID.setFromUInt64String(steamId64);
        LogOnDetails details = new LogOnDetails();

        SteamCloudClient.applySteamId64ToLogOnDetails(details, steamId64);

        Assert.assertEquals(steamID.getAccountID(), details.getAccountID());
        Assert.assertEquals(steamID.getAccountInstance(), details.getAccountInstance());
    }

    private static void invokeValidateDownloadedBytes(
        byte[] rawBytes,
        long expectedRawSize,
        String expectedSha1,
        String remotePath
    ) throws Exception {
        Method method = SteamCloudClient.class.getDeclaredMethod(
            "validateDownloadedBytes",
            byte[].class,
            long.class,
            String.class,
            String.class
        );
        method.setAccessible(true);
        method.invoke(null, rawBytes, expectedRawSize, expectedSha1, remotePath);
    }

    private static long invokeCopyToFile(
        byte[] input,
        File target,
        long maxBytes,
        String description
    ) throws Exception {
        Method method = SteamCloudClient.class.getDeclaredMethod(
            "copyToFile",
            java.io.InputStream.class,
            File.class,
            long.class,
            String.class
        );
        method.setAccessible(true);
        return (long) method.invoke(
            null,
            new ByteArrayInputStream(input),
            target,
            maxBytes,
            description
        );
    }

    private static long invokeMaybeUnzip(
        File compressed,
        File raw,
        String remotePath,
        long maxRawBytes
    ) throws Exception {
        Method method = SteamCloudClient.class.getDeclaredMethod(
            "maybeUnzip",
            File.class,
            File.class,
            String.class,
            long.class
        );
        method.setAccessible(true);
        return (long) method.invoke(null, compressed, raw, remotePath, maxRawBytes);
    }

    private static void writeZip(File target, String[] names, byte[][] contents) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(target))) {
            for (int index = 0; index < names.length; index++) {
                output.putNextEntry(new ZipEntry(names[index]));
                output.write(contents[index]);
                output.closeEntry();
            }
        }
    }

    private static boolean invokeIsRetryableBeginHttpUploadResult(EResult result) throws Exception {
        Method method = SteamCloudClient.class.getDeclaredMethod(
            "isRetryableBeginHttpUploadResult",
            EResult.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(null, result);
    }

    private static long invokeBeginHttpUploadRetryDelayMs(EResult result, int attempt) throws Exception {
        Method method = SteamCloudClient.class.getDeclaredMethod(
            "beginHttpUploadRetryDelayMs",
            EResult.class,
            int.class
        );
        method.setAccessible(true);
        return (long) method.invoke(null, result, attempt);
    }

    private static boolean invokeIsRetryableBeginAppUploadBatchResult(EResult result, long batchId) throws Exception {
        Method method = SteamCloudClient.class.getDeclaredMethod(
            "isRetryableBeginAppUploadBatchResult",
            EResult.class,
            long.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(null, result, batchId);
    }

    private static long invokeBeginAppUploadBatchRetryDelayMs(EResult result, long batchId, int attempt) throws Exception {
        Method method = SteamCloudClient.class.getDeclaredMethod(
            "beginAppUploadBatchRetryDelayMs",
            EResult.class,
            long.class,
            int.class
        );
        method.setAccessible(true);
        return (long) method.invoke(null, result, batchId, attempt);
    }

    private static void invokeEnsureValidUploadBatchId(long batchId, EResult result) throws Exception {
        Method method = SteamCloudClient.class.getDeclaredMethod(
            "ensureValidUploadBatchId",
            long.class,
            EResult.class
        );
        method.setAccessible(true);
        method.invoke(null, batchId, result);
    }

    private static boolean invokeIsRetryableDownloadException(Throwable error) throws Exception {
        Method method = SteamCloudClient.class.getDeclaredMethod(
            "isRetryableDownloadException",
            Throwable.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(null, error);
    }

    private static long invokeDownloadRetryDelayMs(int attempt) throws Exception {
        Method method = SteamCloudClient.class.getDeclaredMethod(
            "downloadRetryDelayMs",
            int.class
        );
        method.setAccessible(true);
        return (long) method.invoke(null, attempt);
    }

    private static IOException newHttpStatusIOException(int statusCode) throws Exception {
        Class<?> type = Class.forName("io.stamethyst.backend.steamcloud.SteamCloudClient$HttpStatusIOException");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class, String.class, String.class);
        constructor.setAccessible(true);
        return (IOException) constructor.newInstance(
            statusCode,
            "downloading",
            "%GameInstall%preferences/STSPlayer"
        );
    }

    private static SteamCloudClient.DiagnosticsSnapshot newDiagnosticsSnapshot(
        String currentStage,
        String disconnectedDescription,
        List<String> diagnosticEventLines
    ) throws Exception {
        Constructor<SteamCloudClient.DiagnosticsSnapshot> constructor =
            SteamCloudClient.DiagnosticsSnapshot.class.getDeclaredConstructor(
                String.class,
                String.class,
                boolean.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                boolean.class,
                boolean.class,
                String.class,
                String.class,
                List.class,
                List.class,
                List.class,
                String.class,
                String.class,
                String.class,
                String.class,
                long.class,
                long.class,
                boolean.class,
                int.class
            );
        constructor.setAccessible(true);
        return constructor.newInstance(
            currentStage,
            "WEB_SOCKET",
            true,
            "OK",
            disconnectedDescription,
            "103.28.54.102:27020 [WEB_SOCKET]",
            "Steam server list",
            "<not evaluated>",
            "<not requested>",
            false,
            false,
            "<none>",
            "<none>",
            Collections.emptyList(),
            Collections.emptyList(),
            diagnosticEventLines,
            "enabled",
            "",
            "76561198883607238",
            "76561198883607238",
            1L,
            1L,
            false,
            0
        );
    }

    private static boolean invokeIsReconnectRetryCandidate(
        Throwable error,
        SteamCloudClient.DiagnosticsSnapshot diagnostics
    ) throws Exception {
        Class<?> coordinatorType = Class.forName("io.stamethyst.backend.steamcloud.SteamCloudPushCoordinator");
        Field instanceField = coordinatorType.getDeclaredField("INSTANCE");
        instanceField.setAccessible(true);
        Object coordinator = instanceField.get(null);
        Method method = coordinatorType.getDeclaredMethod(
            "isReconnectRetryCandidate",
            Throwable.class,
            SteamCloudClient.DiagnosticsSnapshot.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(coordinator, error, diagnostics);
    }

    private static final class FakeCredentialsAuthSession {
        @SuppressWarnings("FieldCanBeLocal")
        private final SteamID steamID;

        private FakeCredentialsAuthSession(String steamId64) {
            this.steamID = new SteamID();
            this.steamID.setFromUInt64String(steamId64);
        }
    }

    private static final class SequencedDirectoryFile extends File {
        private final boolean mkdirsResult;
        private final boolean[] isDirectoryResults;
        private int isDirectoryCallCount = 0;

        private SequencedDirectoryFile(String pathname, boolean mkdirsResult, boolean... isDirectoryResults) {
            super(pathname);
            this.mkdirsResult = mkdirsResult;
            this.isDirectoryResults = isDirectoryResults;
        }

        @Override
        public boolean isDirectory() {
            if (isDirectoryResults.length == 0) {
                return false;
            }
            int index = Math.min(isDirectoryCallCount, isDirectoryResults.length - 1);
            isDirectoryCallCount += 1;
            return isDirectoryResults[index];
        }

        @Override
        public boolean mkdirs() {
            return mkdirsResult;
        }
    }
}
