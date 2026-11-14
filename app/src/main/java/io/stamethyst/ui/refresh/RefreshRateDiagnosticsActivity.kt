package io.stamethyst.ui.refresh

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import io.stamethyst.R
import io.stamethyst.backend.diag.refresh.RefreshRateDiagnosticsRunner
import io.stamethyst.backend.diag.refresh.RefreshRateOutcome
import io.stamethyst.backend.diag.refresh.RefreshRatePhase
import io.stamethyst.backend.diag.refresh.RefreshRateProbeView
import io.stamethyst.backend.diag.refresh.RefreshRateReport
import io.stamethyst.backend.diag.refresh.RefreshRateReportFormat
import io.stamethyst.backend.diag.refresh.RefreshRateReportStore
import io.stamethyst.ui.preferences.LauncherPreferences
import io.stamethyst.ui.theme.LauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream
import java.io.ByteArrayOutputStream

/** An isolated window: no popup/window vote competes with the measured diagnostic surface. */
class RefreshRateDiagnosticsActivity : ComponentActivity() {
    companion object { const val EXTRA_REVIEW = "review_latest" }
    private val state: RefreshRateDiagnosticsState by viewModels()
    private var testJob: Job? = null
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) state.export(applicationContext, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A fresh ViewModel after process death must not silently rerun a previously interrupted
        // measurement. A configuration recreation keeps the existing ViewModel/partial report.
        if (savedInstanceState != null && !state.started) intent.putExtra(EXTRA_REVIEW, true)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (state.running) testJob?.cancel() else finish()
            }
        })
        val app = applicationContext
        setContent {
            LauncherTheme(LauncherPreferences.readThemeMode(app), LauncherPreferences.readThemeColor(app)) {
                RefreshRateDiagnosticsContent(
                    state,
                    onProbeReady = { probe ->
                        if (!state.started) {
                            if (intent.getBooleanExtra(EXTRA_REVIEW, false)) state.load(app)
                            else start(probe)
                        }
                    },
                    onRetry = { state.started = false; intent.removeExtra(EXTRA_REVIEW); state.clear() },
                    onCancel = { testJob?.cancel() },
                    onClose = { finish() },
                    onExport = { export.launch("refresh-rate-report-${System.currentTimeMillis()}.zip") },
                )
            }
        }
    }

    private fun start(probe: RefreshRateProbeView) {
        if (testJob?.isActive == true) return
        state.started = true
        state.running = true
        testJob = lifecycleScope.launch {
            val report = RefreshRateDiagnosticsRunner(this@RefreshRateDiagnosticsActivity, probe).run { phase, index ->
                state.phase = phase
                state.phaseIndex = index
            }
            // Cancellation must still publish/export the partial report after the runner's finally
            // block restored this Activity's window. Keep Activity/View references out of workers.
            withContext(NonCancellable) { state.complete(applicationContext, report) }
        }
    }

    override fun onPause() {
        if (state.running) testJob?.cancel()
        super.onPause()
    }
}

internal class RefreshRateDiagnosticsState : ViewModel() {
    var started = false
    var running by mutableStateOf(false)
    var reading by mutableStateOf(false)
    var exporting by mutableStateOf(false)
    var phase by mutableStateOf(RefreshRatePhase.BASELINE)
    var phaseIndex by mutableStateOf(0)
    var reportText by mutableStateOf<String?>(null)
    private var reportJson: String? = null
    var message by mutableStateOf<String?>(null)

    fun clear() { reportText = null; reportJson = null; message = null }

    suspend fun complete(context: Context, report: RefreshRateReport) {
        val phases = RefreshRatePhase.entries.associate { it.key to context.getString(
            R.string.refresh_diagnostics_phase_label, it.ordinal + 1, RefreshRatePhase.entries.size, context.getString(phaseTitle(it))) }
        val outcomes = RefreshRateOutcome.entries.associateWith { context.getString(outcomeTitle(it)) }
        val (text, json) = withContext(Dispatchers.Default) {
            RefreshRateReportFormat.text(report, phases, outcomes,
                context.getString(R.string.refresh_diagnostics_scope), context.getString(R.string.refresh_diagnostics_next_steps)) to
                RefreshRateReportFormat.json(report)
        }
        reportText = text
        reportJson = json
        val failure = withContext(Dispatchers.IO) { runCatching { RefreshRateReportStore.save(context, text, json) }.exceptionOrNull() }
        message = failure?.let { context.getString(R.string.refresh_diagnostics_save_failed, it.message.orEmpty()) }
        running = false
    }

    fun load(context: Context) {
        if (started) return
        started = true
        reading = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val file = RefreshRateReportStore.latestFile(context)
                check(file.isFile) { context.getString(R.string.refresh_diagnostics_no_report) }
                val entries = mutableMapOf<String, String>()
                ZipInputStream(file.inputStream().buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name == "report.txt" || entry.name == "report.json") {
                            // InputStream.readNBytes is not available on all supported Android APIs.
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                check(output.size() + count <= 2 * 1024 * 1024) { "Report too large" }
                                output.write(buffer, 0, count)
                            }
                            val bytes = output.toByteArray()
                            entries[entry.name] = bytes.toString(Charsets.UTF_8)
                        }
                        zip.closeEntry()
                    }
                }
                check(entries.keys.containsAll(listOf("report.txt", "report.json"))) { "Incomplete report" }
                entries
            } }
            result.onSuccess { reportText = it["report.txt"]; reportJson = it["report.json"] }
                .onFailure { message = it.message ?: it.javaClass.simpleName }
            reading = false
        }
    }

    fun export(context: Context, uri: Uri) {
        val text = reportText ?: return
        val json = reportJson ?: return
        if (exporting) return
        exporting = true
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) { runCatching {
                val output = checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Cannot open destination" }
                RefreshRateReportStore.writeZip(output, text, json)
            }.exceptionOrNull() }
            message = if (failure == null) context.getString(R.string.refresh_diagnostics_exported)
                else context.getString(R.string.refresh_diagnostics_export_failed, failure.message.orEmpty())
            exporting = false
        }
    }
}

@Composable
internal fun RefreshRateDiagnosticsContent(
    state: RefreshRateDiagnosticsState,
    onProbeReady: (RefreshRateProbeView) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onExport: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!state.reading && state.reportText == null && state.message == null) {
            var probe by remember { mutableStateOf<RefreshRateProbeView?>(null) }
            Box(Modifier.fillMaxSize()) {
                // Cover the whole available window even in landscape. Controls are overlaid,
                // not laid out beside a tiny Surface that evades area-based vendor policies.
                AndroidView(factory = { RefreshRateProbeView(it).also { view -> probe = view } },
                    modifier = Modifier.fillMaxSize().testTag("refresh_diagnostics_probe"))
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.refresh_diagnostics_title), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.refresh_diagnostics_running_scope), style = MaterialTheme.typography.bodySmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.refresh_diagnostics_phase_label,
                                state.phase.ordinal + 1, RefreshRatePhase.entries.size, stringResource(phaseTitle(state.phase))),
                                style = MaterialTheme.typography.labelLarge)
                            LinearProgressIndicator(progress = { state.phaseIndex.toFloat() / RefreshRatePhase.entries.size }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
                        TextButton(onClick = onCancel, enabled = state.running) { Text(stringResource(android.R.string.cancel)) }
                    }
                }
            }
            LaunchedEffect(probe, state.started) { probe?.let(onProbeReady) }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.refresh_diagnostics_title), style = MaterialTheme.typography.titleLarge)
                SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("refresh_diagnostics_report")) {
                    Text(state.reportText ?: state.message.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
                if (state.reportText != null) state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRetry, enabled = !state.running && !state.reading && !state.exporting) { Text(stringResource(R.string.refresh_diagnostics_retry)) }
                    TextButton(onClick = onExport, enabled = !state.running && !state.reading && state.reportText != null && !state.exporting) {
                        Text(stringResource(R.string.refresh_diagnostics_export))
                    }
                    TextButton(onClick = onClose, enabled = !state.running && !state.exporting) { Text(stringResource(R.string.common_action_close)) }
                }
            }
        }
    }
}

internal fun phaseTitle(phase: RefreshRatePhase): Int = when (phase) {
    RefreshRatePhase.BASELINE -> R.string.refresh_diagnostics_phase_baseline
    RefreshRatePhase.MODE -> R.string.refresh_diagnostics_phase_mode
    RefreshRatePhase.WINDOW -> R.string.refresh_diagnostics_phase_window
    RefreshRatePhase.SURFACE -> R.string.refresh_diagnostics_phase_surface
    RefreshRatePhase.TEXTURE -> R.string.refresh_diagnostics_phase_texture
    RefreshRatePhase.VIEW_HIGH -> R.string.refresh_diagnostics_phase_view_high
    RefreshRatePhase.COMPOSE_BASELINE -> R.string.refresh_diagnostics_phase_compose_baseline
    RefreshRatePhase.COMPOSE_HIGH -> R.string.refresh_diagnostics_phase_compose_high
    RefreshRatePhase.SURFACE_PRODUCTION -> R.string.refresh_diagnostics_phase_surface_production
    RefreshRatePhase.TEXTURE_PRODUCTION -> R.string.refresh_diagnostics_phase_texture_production
}

private fun outcomeTitle(outcome: RefreshRateOutcome): Int = when (outcome) {
    RefreshRateOutcome.OBSERVED -> R.string.refresh_diagnostics_observed
    RefreshRateOutcome.CALLBACKS_SLOW -> R.string.refresh_diagnostics_callbacks_slow
    RefreshRateOutcome.BELOW_TARGET -> R.string.refresh_diagnostics_below
    RefreshRateOutcome.VARIABLE -> R.string.refresh_diagnostics_variable
    RefreshRateOutcome.REQUEST_FAILED -> R.string.refresh_diagnostics_request_failed
    RefreshRateOutcome.INSUFFICIENT -> R.string.refresh_diagnostics_insufficient
    RefreshRateOutcome.INTERRUPTED -> R.string.refresh_diagnostics_interrupted
}
