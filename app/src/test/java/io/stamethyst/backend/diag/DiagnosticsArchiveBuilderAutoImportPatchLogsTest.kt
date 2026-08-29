package io.stamethyst.backend.diag

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.stamethyst.backend.workshop.WorkshopAutoImportPatchLogStore
import io.stamethyst.backend.easytier.EasyTierConnectionSnapshot
import io.stamethyst.backend.easytier.EasyTierConnectionStatus
import io.stamethyst.backend.easytier.EasyTierDiagnosticsStore
import io.stamethyst.backend.easytier.EasyTierFailureCategory
import io.stamethyst.backend.easytier.EasyTierNetworkMode
import io.stamethyst.backend.easytier.EasyTierStateStore
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsArchiveBuilderAutoImportPatchLogsTest {
    @Test
    fun writeWorkshopAutoImportPatchLogsForArchive_includesPatchLogs() {
        val roots = TestRoots.create("diag-auto-import-patch-logs")
        val logFile = WorkshopAutoImportPatchLogStore.createLogFile(roots.context)
        WorkshopAutoImportPatchLogStore.appendLine(logFile, "修补完成：module=mod.downfall.mobile_layout")
        val archive = File(roots.rootDir, "diagnostics.zip")

        FileOutputStream(archive, false).use { output ->
            ZipOutputStream(output).use { zipOutput ->
                DiagnosticsArchiveBuilder.writeWorkshopAutoImportPatchLogsForArchive(zipOutput, roots.context)
            }
        }

        ZipFile(archive).use { zipFile ->
            val indexEntry = zipFile.getEntry("sts/workshop/auto_import_patch_logs/index.txt")
            assertNotNull(indexEntry)
            val logEntry = zipFile.getEntry("sts/workshop/auto_import_patch_logs/${logFile.name}")
            assertNotNull(logEntry)
            val logText = zipFile.getInputStream(logEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertTrue(logText.contains("mod.downfall.mobile_layout"))
        }
    }

    @Test
    fun writeWindowDiagnosticsForArchive_includesCurrentAndRotatedLogs() {
        val roots = TestRoots.create("diag-window")
        val windowDir = RuntimePaths.windowDiagnosticsDir(roots.context).apply { mkdirs() }
        File(windowDir, "window_diagnostics.log").writeText(
            "RenderSurfaceDiag: resync, windowBounds=0,0,2400,1080",
            Charsets.UTF_8
        )
        File(windowDir, "window_diagnostics.log.1").writeText(
            "RenderSurfaceInput: raw=120.0,80.0",
            Charsets.UTF_8
        )
        File(windowDir, "unrelated.log").writeText("ignored", Charsets.UTF_8)
        val archive = File(roots.rootDir, "diagnostics-window.zip")

        FileOutputStream(archive, false).use { output ->
            ZipOutputStream(output).use { zipOutput ->
                DiagnosticsArchiveBuilder.writeWindowDiagnosticsForArchive(zipOutput, roots.context)
            }
        }

        ZipFile(archive).use { zipFile ->
            val current = zipFile.getEntry("sts/window/window_diagnostics.log")
            val rotated = zipFile.getEntry("sts/window/window_diagnostics.log.1")
            assertNotNull(current)
            assertNotNull(rotated)
            assertNull(zipFile.getEntry("sts/window/unrelated.log"))
            val text = zipFile.getInputStream(current).bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertTrue(text.contains("windowBounds=0,0,2400,1080"))
        }
    }

    @Test
    fun writeAchievementSyncLogsForArchive_includesAchievementSyncLogs() {
        val roots = TestRoots.create("diag-achievement-sync")
        RuntimePaths.achievementSyncLog(roots.context).apply {
            parentFile?.mkdirs()
            writeText("event=request_parsed detail=ids=shrug_it_off", Charsets.UTF_8)
        }
        val archive = File(roots.rootDir, "diagnostics-achievement.zip")

        FileOutputStream(archive, false).use { output ->
            ZipOutputStream(output).use { zipOutput ->
                DiagnosticsArchiveBuilder.writeAchievementSyncLogsForArchive(zipOutput, roots.context)
            }
        }

        ZipFile(archive).use { zipFile ->
            val entry = zipFile.getEntry("sts/achievement_sync/achievement_sync.log")
            assertNotNull(entry)
            val text = zipFile.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertTrue(text.contains("shrug_it_off"))
        }
    }

    @Test
    fun writeLauncherCrashReportsForArchive_includesCrashReportsFromAndroidDirectory() {
        val roots = TestRoots.create("diag-launcher-crash-reports")
        val reportDir = RuntimePaths.launcherCrashReportsDir(roots.context).apply { mkdirs() }
        val reportFile = File(
            reportDir,
            "sts-launcher-crash-uncaught-20260528-120000-000-io.stamethyst.test-pid123.txt"
        ).apply {
            writeText("launcher stack trace", Charsets.UTF_8)
        }
        File(reportDir, "notes.txt").writeText("not a crash report", Charsets.UTF_8)
        val archive = File(roots.rootDir, "diagnostics-crash.zip")

        FileOutputStream(archive, false).use { output ->
            ZipOutputStream(output).use { zipOutput ->
                DiagnosticsArchiveBuilder.writeLauncherCrashReportsForArchive(zipOutput, roots.context)
            }
        }

        ZipFile(archive).use { zipFile ->
            val indexEntry = zipFile.getEntry("sts/launcher_crash_reports/index.txt")
            assertNotNull(indexEntry)
            val reportEntry = zipFile.getEntry("sts/launcher_crash_reports/${reportFile.name}")
            assertNotNull(reportEntry)
            assertNull(zipFile.getEntry("sts/launcher_crash_reports/notes.txt"))
            val reportText = zipFile.getInputStream(reportEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertEquals("launcher stack trace", reportText)
        }
    }

    @Test
    fun writeEasyTierDiagnosticsForArchive_includesSnapshotSummaryAndHistory() {
        val roots = TestRoots.create("diag-easytier")
        val snapshot = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.FAILED,
            mode = EasyTierNetworkMode.Room,
            failureCategory = EasyTierFailureCategory.RuntimeBridgePending,
            roomId = "room-e2e",
            entryNodeUrl = "tcp://online.example.com:11010",
            lastUpdatedAtMs = 9_000L,
            lastErrorSummary = "EasyTier runtime bridge is not wired into this build yet."
        )
        EasyTierStateStore.writeSnapshot(roots.context, snapshot)
        EasyTierDiagnosticsStore.recordStateTransition(
            context = roots.context,
            snapshot = snapshot,
            extraLines = listOf("runtime_bridge_integrated=false")
        )
        val archive = File(roots.rootDir, "diagnostics-easytier.zip")

        FileOutputStream(archive, false).use { output ->
            ZipOutputStream(output).use { zipOutput ->
                DiagnosticsArchiveBuilder.writeEasyTierDiagnosticsForArchive(zipOutput, roots.context)
            }
        }

        ZipFile(archive).use { zipFile ->
            val configEntry = zipFile.getEntry("sts/easytier/config_snapshot.txt")
            assertNotNull(configEntry)
            val configText = zipFile.getInputStream(configEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertTrue(configText.contains("entryNodeUrl="))

            val stateEntry = zipFile.getEntry("sts/easytier/connection-state.json")
            assertNotNull(stateEntry)
            val stateText = zipFile.getInputStream(stateEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertTrue(stateText.contains("RuntimeBridgePending"))

            val summaryEntry = zipFile.getEntry("sts/easytier/last-session-summary.txt")
            assertNotNull(summaryEntry)
            val summaryText = zipFile.getInputStream(summaryEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertTrue(summaryText.contains("Failure Category: RuntimeBridgePending"))

            val eventEntry = zipFile.entries().asSequence()
                .firstOrNull { it.name.startsWith("sts/easytier/event-failed-") }
            assertNotNull(eventEntry)
        }
    }

    @Test
    fun writeEasyTierDiagnosticsForArchive_includesDisconnectEventsNotOnlyFailures() {
        // A mid-game drop lands on DISCONNECTED. The export filter used to match "-failed-" only, so
        // these records were archived on disk and then silently omitted from the bundle.
        val roots = TestRoots.create("diag-easytier-disconnect")
        EasyTierDiagnosticsStore.resetArchivedStatusForTest()
        val snapshot = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.DISCONNECTED,
            mode = EasyTierNetworkMode.Room,
            failureCategory = EasyTierFailureCategory.None,
            roomId = "room-drop",
            entryNodeUrl = "tcp://online.example.com:11010",
            lastUpdatedAtMs = 12_000L,
        )
        EasyTierStateStore.writeSnapshot(roots.context, snapshot)
        EasyTierDiagnosticsStore.recordStateTransition(
            context = roots.context,
            snapshot = snapshot,
            extraLines = listOf("terminal_session_state_source=room_api_session_missing"),
        )
        val archive = File(roots.rootDir, "diagnostics-easytier-disconnect.zip")

        FileOutputStream(archive, false).use { output ->
            ZipOutputStream(output).use { zipOutput ->
                DiagnosticsArchiveBuilder.writeEasyTierDiagnosticsForArchive(zipOutput, roots.context)
            }
        }

        ZipFile(archive).use { zipFile ->
            val eventEntry = zipFile.entries().asSequence()
                .firstOrNull { it.name.startsWith("sts/easytier/event-disconnected-") }
            assertNotNull("Disconnect events must reach the exported bundle", eventEntry)

            val summaryEntry = zipFile.getEntry("sts/easytier/last-session-summary.txt")
            assertNotNull(summaryEntry)
            val summaryText = zipFile.getInputStream(summaryEntry)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            // The section that tells a reader whether :easytier was killed must always be present.
            assertTrue(summaryText.contains("Recent :easytier Process Exits:"))
            assertTrue(summaryText.contains("terminal_session_state_source=room_api_session_missing"))
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
                val cacheDir = File(rootDir, "cache").apply { mkdirs() }
                val externalFilesDir = File(rootDir, "external-files").apply { mkdirs() }
                val prefs = LinkedHashMap<String, InMemorySharedPreferences>()
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getCacheDir(): File = cacheDir

                        override fun getExternalFilesDir(type: String?): File = externalFilesDir

                        override fun getApplicationContext(): Context = this

                        override fun getPackageName(): String = "io.stamethyst.test"

                        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                            prefs.getOrPut(name) { InMemorySharedPreferences() }
                    }
                )
            }
        }
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = synchronized(values) { LinkedHashMap(values) }

        override fun getString(key: String, defValue: String?): String? =
            synchronized(values) { values[key] as? String ?: defValue }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            synchronized(values) { (values[key] as? Set<String>)?.toMutableSet() ?: defValues }

        override fun getInt(key: String, defValue: Int): Int =
            synchronized(values) { values[key] as? Int ?: defValue }

        override fun getLong(key: String, defValue: Long): Long =
            synchronized(values) { values[key] as? Long ?: defValue }

        override fun getFloat(key: String, defValue: Float): Float =
            synchronized(values) { values[key] as? Float ?: defValue }

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            synchronized(values) { values[key] as? Boolean ?: defValue }

        override fun contains(key: String): Boolean = synchronized(values) { values.containsKey(key) }

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private val removals = LinkedHashSet<String>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
                apply { pending[key] = values?.toMutableSet() }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }

            override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }

            override fun clear(): SharedPreferences.Editor = apply { clear = true }

            override fun commit(): Boolean {
                synchronized(values) {
                    if (clear) values.clear()
                    removals.forEach(values::remove)
                    pending.forEach { (key, value) ->
                        if (value == null) values.remove(key) else values[key] = value
                    }
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
