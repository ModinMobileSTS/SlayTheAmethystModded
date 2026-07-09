package io.stamethyst.backend.steamcloud;

import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.types.SteamID;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;

public final class SteamCloudClientTest {
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
    public void isRetryableBeginHttpUploadResult_retriesTooManyPending() throws Exception {
        Assert.assertTrue(invokeIsRetryableBeginHttpUploadResult(EResult.TooManyPending));
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
                long.class
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
            1L
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
