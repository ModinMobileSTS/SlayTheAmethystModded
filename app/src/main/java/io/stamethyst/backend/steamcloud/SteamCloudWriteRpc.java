package io.stamethyst.backend.steamcloud;

import in.dragonbra.javasteam.enums.EResult;
import top.apricityx.workshop.steam.protocol.SteamServiceMethodException;

/** A missing response, a server-side timeout, or a malformed success is not a rejection. */
enum SteamCloudWriteRpc {
    BEGIN_BATCH("Cloud.BeginAppUploadBatch#1", 7),
    BEGIN_FILE("Cloud.BeginHTTPUpload#1", 7),
    COMMIT_FILE("Cloud.CommitHTTPUpload#1", 4),
    COMPLETE_BATCH("Cloud.CompleteAppUploadBatchBlocking#1", 5);

    final String method;
    private final int maxAttempts;
    private static final long[] PENDING_DELAYS = {10_000, 20_000, 30_000, 60_000, 90_000, 120_000};
    private static final long[] DELAYS = {2_000, 5_000, 10_000};
    private static final long[] COMPLETE_DELAYS = {2_000, 5_000, 10_000, 20_000};

    SteamCloudWriteRpc(String method, int maxAttempts) {
        this.method = method;
        this.maxAttempts = maxAttempts;
    }

    interface Call<T> { T invoke() throws Exception; }
    interface Backoff { void waitForRetry(SteamServiceMethodException rejection, int attempt, long delayMs) throws InterruptedException; }

    <T> T execute(Call<T> call, Backoff backoff) throws Exception {
        for (int attempt = 1; ; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Cloud operation interrupted");
            try {
                return call.invoke();
            } catch (SteamServiceMethodException rejection) {
                EResult result = EResult.from(rejection.getResultCode());
                if (!method.equals(rejection.getMethodName()) || attempt >= maxAttempts) throw rejection;
                long[] delays;
                if ((result == EResult.TooManyPending && this != COMPLETE_BATCH) ||
                    (result == EResult.DuplicateRequest && this == BEGIN_FILE)) {
                    delays = PENDING_DELAYS;
                } else if (result == EResult.Busy || result == EResult.ServiceUnavailable ||
                    // A typed Fail reply from the blocking finalizer for this same batch has
                    // historically required backoff. Never infer this from disconnect/error text.
                    (this == COMPLETE_BATCH && result == EResult.Fail)) {
                    delays = this == COMPLETE_BATCH ? COMPLETE_DELAYS : DELAYS;
                } else {
                    throw rejection;
                }
                backoff.waitForRetry(rejection, attempt, delays[Math.min(attempt - 1, delays.length - 1)]);
            }
        }
    }
}
