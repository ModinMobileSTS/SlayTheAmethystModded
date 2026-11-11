package io.stamethyst.ui.refresh

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import android.content.ContextWrapper
import io.stamethyst.R
import io.stamethyst.backend.diag.refresh.RefreshRateReportStore
import io.stamethyst.config.LauncherConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.zip.ZipInputStream

/** Compile-only by default; device execution/deployment requires separate explicit authorization. */
class RefreshRateDiagnosticsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun longReportKeepsExportAndCloseActionsVisibleInLandscapeSizedViewport() {
        val state = RefreshRateDiagnosticsState().apply {
            started = true
            reportText = List(500) { "Observation $it: appHz=60; modeHz=120" }.joinToString("\n")
        }
        var exports = 0
        var closes = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.height(280.dp)) {
                    RefreshRateDiagnosticsContent(state, {}, {}, {}, { closes++ }, { exports++ })
                }
            }
        }
        compose.onNodeWithTag("refresh_diagnostics_report").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.refresh_diagnostics_export)).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.common_action_close)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, exports); assertEquals(1, closes) }
    }

    @Test fun readingPreservesControlsButDisablesRetryAndExport() {
        val state = RefreshRateDiagnosticsState().apply { started = true; reading = true }
        compose.setContent { MaterialTheme { RefreshRateDiagnosticsContent(state, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText(context.getString(R.string.refresh_diagnostics_retry)).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.refresh_diagnostics_export)).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.common_action_close)).assertIsEnabled()
    }

    @Test fun reportSavingKeepsButtonsDisabledUntilPublicationFinishes() {
        val state = RefreshRateDiagnosticsState().apply { started = true; running = true; reportText = "Partial report" }
        compose.setContent { MaterialTheme { RefreshRateDiagnosticsContent(state, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText(context.getString(R.string.refresh_diagnostics_retry)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.refresh_diagnostics_export)).assertIsNotEnabled()
        compose.runOnIdle { state.running = false }
        compose.onNodeWithText(context.getString(R.string.refresh_diagnostics_export)).assertIsEnabled()
    }

    @Test fun atomicStoreProducesReadableReportArchive() {
        // Use the instrumentation package's files, never overwrite the user's latest report.
        val testContext = InstrumentationRegistry.getInstrumentation().context
        RefreshRateReportStore.save(testContext, "诊断\nRead-only report", "{\"schemaVersion\":1}")
        val entries = mutableMapOf<String, String>()
        ZipInputStream(RefreshRateReportStore.latestFile(testContext).inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(setOf("report.txt", "report.json"), entries.keys)
        assertTrue(entries.getValue("report.txt").contains("诊断"))
    }

    @Test fun diagnosticFpsReadDoesNotMigrateMissingPreferences() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val isolated = testContext.getSharedPreferences("refresh_diag_read_only_fixture", Context.MODE_PRIVATE)
        isolated.edit().clear().commit()
        val context = object : ContextWrapper(testContext) {
            override fun getSharedPreferences(name: String, mode: Int) = isolated
        }
        LauncherConfig.readTargetFpsWithoutMigration(context)
        assertTrue(isolated.all.isEmpty())
        isolated.edit().putInt("target_fps", 120).commit()
        val before = isolated.all.toMap()
        assertEquals(120, LauncherConfig.readTargetFpsWithoutMigration(context))
        assertEquals(before, isolated.all)
    }
}
