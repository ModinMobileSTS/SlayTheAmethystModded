package io.stamethyst.backend.diag.refresh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class RefreshRateReportTest {
    @Test fun emptyAndInvalidIntervalsDoNotInventAFrameRate() {
        assertEquals(RefreshRateFrameStats(), RefreshRateFrameStats.fromIntervals(listOf(0L, -1L), 120f))
    }

    @Test fun statisticsUseArrivalIntervalsAndTargetBudget() {
        val stats = RefreshRateFrameStats.fromIntervals(List(99) { 8_333_333L } + 25_000_000L, 120f)
        assertEquals(100, stats.intervals)
        assertEquals(8.333333, stats.medianMs, .00001)
        assertEquals(8.333333, stats.p95Ms, .00001)
        assertEquals(25.0, stats.maxMs, .00001)
        assertEquals(1.0, stats.slowIntervalPercent, .00001)
        assertEquals(117.647, stats.callbackRateHz, .01)
    }

    @Test fun returnFromSetterDoesNotByItselfMeanTargetObserved() {
        val stage = stage(appHz = 60f)
        assertEquals(RefreshRateOutcome.BELOW_TARGET, stage.outcome())
        assertEquals(RefreshRateOutcome.INSUFFICIENT, stage.copy(observations = emptyList()).outcome())
    }

    @Test fun distinguishesReportedRateFromSlowCallbacksAndSelectedMode() {
        assertEquals(RefreshRateOutcome.CALLBACKS_SLOW, stage(appHz = 120f).outcome())
        assertEquals(RefreshRateOutcome.OBSERVED,
            stage(appHz = 119.88f).copy(frames = RefreshRateFrameStats(100, 119.0)).outcome())
    }

    @Test fun partialAndFailedTestsRemainExplicit() {
        assertEquals(RefreshRateOutcome.INTERRUPTED, stage(120f).copy(completed = false).outcome())
        assertEquals(RefreshRateOutcome.REQUEST_FAILED, stage(120f).copy(requestError = "SecurityException").outcome())
        assertEquals(RefreshRateOutcome.INSUFFICIENT, stage(120f).copy(drawErrors = 1).outcome())
        assertEquals(RefreshRateOutcome.INSUFFICIENT, stage(120f).copy(drawCount = 0).outcome())
        assertEquals(RefreshRateOutcome.INSUFFICIENT, stage(120f).copy(frames = RefreshRateFrameStats()).outcome())
    }

    @Test fun sparseOrUnfocusedSamplesCannotProduceSuccess() {
        val stage = stage(120f).copy(frames = RefreshRateFrameStats(100, 120.0))
        assertEquals(RefreshRateOutcome.INSUFFICIENT, stage.copy(observations = stage.observations.take(1)).outcome())
        assertEquals(RefreshRateOutcome.INSUFFICIENT,
            stage.copy(observations = stage.observations.map { it.copy(focused = false) }).outcome())
    }

    @Test fun onlyStableSampleObservationsCountTowardsVerdict() {
        val low = stage(60f)
        val high = low.observations.first().copy(appRateHz = 120f)
        assertEquals(RefreshRateOutcome.BELOW_TARGET,
            low.copy(observations = low.observations + high.copy(reason = "after_request")).outcome())
        assertEquals(RefreshRateOutcome.VARIABLE,
            low.copy(observations = low.observations + high).outcome())
    }

    @Test fun reportRoundTripsAndExportContainsBothFormats() {
        val report = RefreshRateReport(startedAt = "start", finishedAt = "end", interruption = "cancelled",
            environment = mapOf("firmware" to "test"), stages = listOf(stage(60f)), cleanup = "restored")
        val json = RefreshRateReportFormat.json(report)
        assertEquals(report, RefreshRateReportFormat.json.decodeFromString<RefreshRateReport>(json))
        assertTrue(json.contains("\"outcomeCode\": \"BELOW_TARGET\""))
        val text = RefreshRateReportFormat.text(report, mapOf("window_120" to "Window"),
            RefreshRateOutcome.entries.associateWith { it.name }, "LIMITATIONS", "NEXT STEPS")
        assertTrue(text.contains("INTERRUPTED: cancelled"))
        assertTrue(text.contains("BELOW_TARGET"))
        assertTrue(text.contains("appHz=60.0"))
        assertTrue(text.contains("mode=2@120.0"))
        assertTrue(text.contains("LIMITATIONS"))
        assertTrue(text.contains("NEXT STEPS"))
        val output = ByteArrayOutputStream()
        RefreshRateReportStore.writeZip(output, text, json)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        assertEquals(setOf("report.txt", "report.json"), entries.keys)
        assertEquals(text, entries["report.txt"])
        assertEquals(json, entries["report.json"])
        assertFalse(json.contains("physicalPanelRate"))
    }

    @Test fun schemaOneReportsAndObservationsRemainReadableWithoutVoteMetadata() {
        val legacyReport = """{"schemaVersion":1,"startedAt":"start","finishedAt":"end","interruption":null,"environment":{},"stages":[],"cleanup":"restored"}"""
        assertEquals(1, RefreshRateReportFormat.json.decodeFromString<RefreshRateReport>(legacyReport).schemaVersion)
        val legacyObservation = """{"elapsedMs":1,"reason":"sample","displayId":0,"appRateHz":60.0,"modeId":6,"modeRateHz":60.0,"resolution":"1080x2344","preferredModeId":4,"preferredRateHz":0.0,"focused":true,"powerSave":false,"thermalStatus":1}"""
        val decoded = RefreshRateReportFormat.json.decodeFromString<RefreshRateObservation>(legacyObservation)
        assertNull(decoded.viewRequestedFrameRate)
        assertNull(decoded.composeHighVoteInstalled)
    }

    @Test fun schemaTwoExportsRawViewCategoryAndInstalledComposeHintWithoutInventingAcceptance() {
        val original = stage(60f)
        val report = RefreshRateReport(startedAt = "start", finishedAt = "end", interruption = null,
            environment = emptyMap(), cleanup = "restored", stages = listOf(original.copy(
                phase = "compose_high", observations = original.observations.map {
                    it.copy(viewRequestedFrameRate = "NaN", composeHighVoteInstalled = true)
                })))
        assertEquals(2, report.schemaVersion)
        val json = RefreshRateReportFormat.json(report)
        assertEquals(report, RefreshRateReportFormat.json.decodeFromString<RefreshRateReport>(json))
        val text = RefreshRateReportFormat.text(report, emptyMap(),
            RefreshRateOutcome.entries.associateWith { it.name }, "scope", "next")
        assertTrue(text.contains("viewRequestedFrameRate=NaN; composeHighVoteInstalled=true"))
        assertTrue(text.contains("BELOW_TARGET"))
        assertFalse(json.contains("physicalPanelRate"))
    }

    private fun stage(appHz: Float) = RefreshRateStage(
        phase = "window_120", targetHz = 120f, durationMs = 3200, completed = true,
        request = "setter returned", requestError = null,
        observations = List(10) { RefreshRateObservation(1000L + it * 250, "sample", 0, appHz, 2, 120f,
            "1216x2640", 0, 120f, true, false, 0) },
        frames = RefreshRateFrameStats(100, 60.0, 16.67, 16.67, 16.67, 100.0),
        drawCount = 100, drawErrors = 0, lastDrawError = null, probeSize = "1216x2400", events = emptyList(),
    )
}
