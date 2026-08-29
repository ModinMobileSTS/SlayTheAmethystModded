package io.stamethyst.backend.steamcloud

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudDiagnosticsStoreTest {
    @Test
    fun writeSummary_persistsFailedCredentialLoginInFailureHistory() {
        val roots = TestRoots.create("steam-cloud-diagnostics-failed-login-history")
        try {
            SteamCloudDiagnosticsStore.writeSummary(
                context = roots.context,
                operation = "credentials_login",
                outcome = "FAILED",
                accountName = "test-user",
                startedAtMs = 3_000L,
                completedAtMs = 4_000L,
                diagnostics = null,
                failureSummary = "auth failed",
            )

            val loginHistoryFiles = SteamCloudDiagnosticsStore.loginHistoryDir(roots.context)
                .listFiles()
                ?.toList()
                .orEmpty()
            val failureHistoryFiles = SteamCloudDiagnosticsStore.failureHistoryDir(roots.context)
                .listFiles()
                ?.toList()
                .orEmpty()
            assertEquals(1, loginHistoryFiles.size)
            assertEquals(1, failureHistoryFiles.size)
            assertTrue(loginHistoryFiles.single().name.startsWith("login-failed-"))
            assertTrue(failureHistoryFiles.single().name.startsWith("failure-credentials_login-"))
            assertTrue(
                failureHistoryFiles.single()
                    .readText(StandardCharsets.UTF_8)
                    .contains("Failure Summary: auth failed")
            )
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun writeSummary_includesFullFailureCauseAndStack() {
        val roots = TestRoots.create("steam-cloud-diagnostics-full-error")
        try {
            val error = IllegalStateException(
                "top level failure",
                IOException("root network failure")
            )

            SteamCloudDiagnosticsStore.writeSummary(
                context = roots.context,
                operation = "manual_push",
                outcome = "FAILED",
                accountName = "test-user",
                startedAtMs = 5_000L,
                completedAtMs = 6_000L,
                diagnostics = null,
                error = error,
            )

            val text = SteamCloudDiagnosticsStore.summaryFile(roots.context)
                .readText(StandardCharsets.UTF_8)
            assertTrue(text.contains("Error Type: java.lang.IllegalStateException"))
            assertTrue(text.contains("Error Cause Chain: java.lang.IllegalStateException: top level failure <- java.io.IOException: root network failure"))
            assertTrue(text.contains("Exception Chain:"))
            assertTrue(text.contains("Full Exception Stack:"))
            assertTrue(text.contains("Caused by: java.io.IOException: root network failure"))
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun gamePresenceSummary_usesInternalStorageAndReplacesEarlierStatus() {
        val roots = TestRoots.create("steam-game-presence-diagnostics")
        try {
            SteamGamePresenceDiagnosticsStore.writeSummary(
                roots.context,
                "START_REQUESTED",
                "test-user",
                7_000L,
                7_000L,
                false,
                false,
                null,
                null,
                "foreground_service_start_requested",
            )
            val summary = SteamGamePresenceDiagnosticsStore.summaryFile(roots.context)
            assertEquals(
                File(roots.context.filesDir, "steam-game-presence/last-operation-summary.txt"),
                summary,
            )

            SteamGamePresenceDiagnosticsStore.writeSummary(
                roots.context,
                "STARTED",
                "test-user",
                7_000L,
                8_000L,
                false,
                false,
                null,
                null,
                "foreground_service_started",
            )

            val text = summary.readText(StandardCharsets.UTF_8)
            assertTrue(text.contains("Outcome: STARTED"))
            assertTrue(text.contains("Summary file: ${summary.absolutePath}"))
            assertFalse(text.contains("Outcome: START_REQUESTED"))

            val events = SteamGamePresenceDiagnosticsStore.eventLogFile(roots.context)
                .readText(StandardCharsets.UTF_8)
            assertTrue(events.contains("outcome=START_REQUESTED"))
            assertTrue(events.contains("outcome=STARTED"))
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    private class TestRoots private constructor(
        val rootDir: File,
        val context: Context,
    ) {
        companion object {
            fun create(prefix: String): TestRoots {
                val rootDir = Files.createTempDirectory(prefix).toFile()
                val filesDir = File(rootDir, "internal-files").apply { mkdirs() }
                val externalFilesDir = File(rootDir, "external-files").apply { mkdirs() }
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getExternalFilesDir(type: String?): File = externalFilesDir

                        override fun getPackageName(): String = "io.stamethyst.test"
                    }
                )
            }
        }
    }
}
