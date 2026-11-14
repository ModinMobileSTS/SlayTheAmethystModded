package io.stamethyst.ui.refresh

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
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
import android.os.Build
import android.view.View
import androidx.compose.ui.viewinterop.AndroidView
import io.stamethyst.backend.diag.refresh.RefreshRatePhase
import io.stamethyst.backend.diag.refresh.RefreshRateProbeView
import io.stamethyst.R
import io.stamethyst.backend.diag.refresh.RefreshRateReportStore
import io.stamethyst.config.LauncherConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
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

    @Test fun allPhaseLabelsUseTheActualPhaseCount() {
        val phases = RefreshRatePhase.entries
        val labels = phases.mapIndexed { index, phase ->
            context.getString(R.string.refresh_diagnostics_phase_label,
                index + 1, phases.size, context.getString(phaseTitle(phase)))
        }
        assertEquals(phases.size, labels.distinct().size)
        labels.forEachIndexed { index, label -> assertTrue(label.startsWith("${index + 1}/${phases.size}")) }
    }

    @Test fun composeProbeHasSeparateBaselineHighAndCleanupPaths() {
        lateinit var probe: RefreshRateProbeView
        compose.setContent {
            AndroidView(factory = { RefreshRateProbeView(it).also { view ->
                probe = view
                view.select(RefreshRatePhase.COMPOSE_BASELINE)
            } }, modifier = Modifier.fillMaxSize())
        }
        compose.runOnIdle {
            assertTrue(probe.ready())
            assertEquals(false, probe.composeHighVoteInstalled())
            if (Build.VERSION.SDK_INT >= 35) probe.requestHighComposeVote()
            else assertTrue(runCatching { probe.requestHighComposeVote() }.isFailure)
        }
        compose.runOnIdle {
            assertEquals(Build.VERSION.SDK_INT >= 35, probe.composeHighVoteInstalled())
            probe.render(1_000_000_000L)
        }
        compose.runOnIdle {
            assertTrue(probe.draws > 0)
            probe.select(RefreshRatePhase.BASELINE)
            assertNull(probe.composeHighVoteInstalled())
            assertTrue(probe.ready())
            probe.release()
        }
    }

    @Test fun viewProbeRestoresItsVoteBeforeStartingANewBaseline() {
        lateinit var probe: RefreshRateProbeView
        compose.setContent {
            AndroidView(factory = { RefreshRateProbeView(it).also { view ->
                probe = view
                view.select(RefreshRatePhase.VIEW_HIGH)
            } }, modifier = Modifier.fillMaxSize())
        }
        compose.runOnIdle {
            val animatedView = probe.getChildAt(0)
            if (Build.VERSION.SDK_INT >= 35) {
                val original = animatedView.requestedFrameRate
                probe.requestHighViewVote()
                assertEquals(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH, animatedView.requestedFrameRate, 0f)
                probe.select(RefreshRatePhase.BASELINE)
                assertEquals(original, animatedView.requestedFrameRate, 0f)
            } else {
                assertTrue(runCatching { probe.requestHighViewVote() }.isFailure)
                probe.select(RefreshRatePhase.BASELINE)
            }
            assertNull(probe.composeHighVoteInstalled())
            probe.release()
        }
    }
}
