package io.stamethyst.compatmod.compatibility;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.core.CardCrawlGame;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class TogetherInSpireLanGameStatePatches {
    private static final String MOD_ID = "spireTogether";
    private static final String SPIRE_TOGETHER_MOD_CLASS = "spireTogether.SpireTogetherMod";
    private static final String GAME_STATE_REQUEST_PROPERTY =
        "amethyst.in_game_lan_game_state_request";
    private static final long STATE_POLL_INTERVAL_MS = 500L;
    private static final long GAME_STATE_HEARTBEAT_INTERVAL_MS = 25_000L;

    private static volatile Method isConnectedToGameMethod;
    private static volatile long nextStatePollAtMs;
    private static volatile long lastGameStateReportAtMs;
    private static volatile boolean previouslyConnected;
    private static final AtomicReference<String> PENDING_GAME_STATE_REQUEST =
        new AtomicReference<String>();
    private static final AtomicBoolean GAME_STATE_WRITER_RUNNING = new AtomicBoolean();

    private TogetherInSpireLanGameStatePatches() {
    }

    @SpirePatch2(
        clz = CardCrawlGame.class,
        method = "update",
        requiredModId = MOD_ID,
        optional = true
    )
    public static class CardCrawlGameUpdatePatch {
        @SpirePostfixPatch
        public static void Postfix() {
            reportGameStateIfNeeded();
        }
    }

    private static void reportGameStateIfNeeded() {
        long nowMs = System.currentTimeMillis();
        if (nowMs < nextStatePollAtMs) {
            return;
        }
        nextStatePollAtMs = nowMs + STATE_POLL_INTERVAL_MS;

        boolean connected = isConnectedToTogetherInSpireGame();
        if (connected) {
            if (!previouslyConnected || nowMs - lastGameStateReportAtMs >= GAME_STATE_HEARTBEAT_INTERVAL_MS) {
                enqueueGameStateRequest("game", nowMs);
                lastGameStateReportAtMs = nowMs;
            }
        } else if (previouslyConnected) {
            enqueueGameStateRequest("online", nowMs);
            lastGameStateReportAtMs = 0L;
        }
        previouslyConnected = connected;
    }

    private static boolean isConnectedToTogetherInSpireGame() {
        try {
            Method method = isConnectedToGameMethod;
            if (method == null) {
                method = Class.forName(
                    SPIRE_TOGETHER_MOD_CLASS,
                    false,
                    TogetherInSpireLanGameStatePatches.class.getClassLoader()
                ).getMethod("isConnectedToGame");
                isConnectedToGameMethod = method;
            }
            return Boolean.TRUE.equals(method.invoke(null));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void enqueueGameStateRequest(String state, long nowMs) {
        PENDING_GAME_STATE_REQUEST.set(state + "\n" + nowMs + "\n");
        startGameStateWriterIfNeeded();
    }

    private static void startGameStateWriterIfNeeded() {
        if (!GAME_STATE_WRITER_RUNNING.compareAndSet(false, true)) {
            return;
        }
        Thread writer = new Thread(
            TogetherInSpireLanGameStatePatches::drainGameStateRequests,
            "STS-TogetherInSpireStateWriter"
        );
        writer.setDaemon(true);
        try {
            writer.start();
        } catch (Throwable ignored) {
            GAME_STATE_WRITER_RUNNING.set(false);
        }
    }

    private static void drainGameStateRequests() {
        while (true) {
            String request = PENDING_GAME_STATE_REQUEST.getAndSet(null);
            if (request != null) {
                writeGameStateRequest(request);
            }

            GAME_STATE_WRITER_RUNNING.set(false);
            if (PENDING_GAME_STATE_REQUEST.get() == null
                || !GAME_STATE_WRITER_RUNNING.compareAndSet(false, true)) {
                return;
            }
        }
    }

    private static void writeGameStateRequest(String request) {
        String path = System.getProperty(GAME_STATE_REQUEST_PROPERTY, "").trim();
        if (path.isEmpty()) {
            return;
        }
        File requestFile = new File(path);
        File parent = requestFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return;
        }
        File temporaryFile = new File(requestFile.getParentFile(), "." + requestFile.getName() + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporaryFile, false)) {
                output.write(request.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            if (!temporaryFile.renameTo(requestFile)) {
                try (FileOutputStream output = new FileOutputStream(requestFile, false)) {
                    output.write(request.getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
            }
        } catch (Exception ignored) {
        } finally {
            temporaryFile.delete();
        }
    }
}
