package io.stamethyst.ui.aimod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.stamethyst.R
import io.stamethyst.backend.llm.AgentCacheStats
import io.stamethyst.backend.llm.AgentContextState
import io.stamethyst.backend.llm.LlmSettings
import io.stamethyst.ui.LauncherTopAppBar
import io.stamethyst.ui.LauncherAlertDialog
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.ArrowBack
import io.stamethyst.ui.settings.core.SettingsScreenViewModel
import io.stamethyst.ui.settings.llm.LlmSettingsContent
import io.stamethyst.ui.settings.llm.LlmSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val aiEditorMenuTabs = listOf(
    R.string.ai_editor_sessions, R.string.ai_editor_statistics,
    R.string.ai_editor_model_config, R.string.ai_editor_trajectory,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiEditorMenuScreen(
    initialPage: Int,
    modName: String,
    editor: AiModEditorViewModel,
    speed: Double?,
    onBack: () -> Unit,
    onTutorial: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val settingsEditor: LlmSettingsViewModel = viewModel(key = "ai-editor-model-settings",
        factory = LlmSettingsViewModel.factory(context))
    // The same settings editor survives closing/reopening this menu. Reload composer edits too.
    DisposableEffect(settingsEditor) {
        settingsEditor.refreshSettings()
        onDispose { }
    }
    val settings = settingsEditor.settings
    LaunchedEffect(settings) { editor.acceptSettings(settings) }
    val builder = remember(editor.currentSessionId) { AiHistoryBuilder() }
    val history by produceState<AiHistorySnapshot?>(null, editor.currentSessionId, editor.transcriptRevision) {
        val messages = editor.messages.toList()
        value = withContext(Dispatchers.Default) { builder.build(messages) }
    }
    val current = editor.sessions.firstOrNull { it.id == editor.currentSessionId }
    var budgetRevision by remember { mutableIntStateOf(0) }
    AiEditorMenuLayout(initialPage, modName, onBack, modifier) { page ->
        when (page) {
            0 -> AiEditorSessionList(editor.history, editor.currentSessionId,
                enabled = editor.storageReady && !editor.switchingConversation && !editor.busy,
                onNew = { editor.newConversation(); onBack() },
                onSelect = { editor.switchConversation(it); onBack() },
                onDelete = editor::deleteConversation)
            1 -> AiEditorStatistics(history, current?.context, editor.llmSettings,
                current?.displayTitle().orEmpty(), speed, editor.busy, budgetRevision)
            2 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LlmSettingsContent(uiState = SettingsScreenViewModel.UiState(), embedded = true,
                    onOpenTutorial = onTutorial, settingsViewModel = settingsEditor)
                AiEditorBudgetFields(settingsEditor.settings) { budgetRevision++ }
            }
            3 -> key(editor.currentSessionId) { AiExecutionHistoryContent(history, Modifier.fillMaxSize()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiEditorMenuLayout(
    initialPage: Int,
    modName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    pageContent: @Composable (Int) -> Unit,
) {
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, 3)) { aiEditorMenuTabs.size }
    val scope = rememberCoroutineScope()
    Scaffold(modifier = modifier.testTag("ai-editor-menu"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LauncherTopAppBar(title = {
                Column {
                    Text(stringResource(R.string.ai_editor_menu), style = MaterialTheme.typography.titleMedium)
                    Text(modName, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.ArrowBack, stringResource(R.string.common_content_desc_back))
                }
            })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 8.dp,
                containerColor = MaterialTheme.colorScheme.background) {
                aiEditorMenuTabs.forEachIndexed { index, label ->
                    Tab(selected = pager.currentPage == index,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        text = { Text(stringResource(label), maxLines = 1) },
                        modifier = Modifier.testTag("ai-editor-tab-$index"))
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth().testTag("ai-editor-pager"),
                verticalAlignment = Alignment.Top) { page ->
                pageContent(page)
            }
        }
    }
}

@Composable
internal fun AiEditorSessionList(
    sessions: List<AiEditorSession>, currentId: String, enabled: Boolean,
    onNew: () -> Unit, onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var menuId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(enabled) { if (!enabled) { menuId = null; deleteId = null } }
    LazyColumn(Modifier.fillMaxSize().testTag("ai-editor-sessions"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.ai_editor_sessions), style = MaterialTheme.typography.titleMedium)
                FilledTonalButton(onClick = onNew, enabled = enabled) {
                    Text(stringResource(R.string.ai_mod_editor_new_chat))
                }
            }
        }
        if (sessions.isEmpty()) item {
            Text(stringResource(R.string.ai_mod_editor_history_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(sessions.asReversed(), key = { it.id }) { session ->
            val selected = session.id == currentId
            Surface(onClick = { onSelect(session.id) }, enabled = enabled,
                shape = MaterialTheme.shapes.large,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.ic_ai_execution_history), null, Modifier.size(20.dp))
                    Text(session.displayTitle(), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (selected) Icon(painterResource(R.drawable.ic_check_circle),
                        stringResource(R.string.ai_editor_current_session), Modifier.size(20.dp))
                    Box {
                        IconButton(onClick = { menuId = session.id }, enabled = enabled,
                            modifier = Modifier.testTag("ai-session-actions-${session.id}")) {
                            Icon(painterResource(R.drawable.ic_more_vert),
                                stringResource(R.string.ai_editor_session_actions, session.displayTitle()))
                        }
                        DropdownMenu(expanded = menuId == session.id,
                            onDismissRequest = { menuId = null }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ai_editor_delete)) },
                                leadingIcon = {
                                    Icon(painterResource(R.drawable.ic_delete), null, Modifier.size(20.dp))
                                },
                                enabled = enabled,
                                onClick = { menuId = null; deleteId = session.id },
                                modifier = Modifier.testTag("ai-session-delete-${session.id}"),
                            )
                        }
                    }
                }
            }
        }
    }
    sessions.firstOrNull { it.id == deleteId }?.let { session ->
        LauncherAlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.ai_editor_delete_title)) },
            text = { Text(stringResource(R.string.ai_editor_delete_message, session.displayTitle())) },
            dismissButton = {
                TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.common_action_close)) }
            },
            confirmButton = {
                TextButton(onClick = { deleteId = null; onDelete(session.id) }, enabled = enabled,
                    modifier = Modifier.testTag("ai-session-delete-confirm")) {
                    Icon(painterResource(R.drawable.ic_delete), null, Modifier.size(18.dp))
                    Text(stringResource(R.string.ai_editor_delete), Modifier.padding(start = 6.dp))
                }
            },
        )
    }
}

@Composable
private fun AiEditorStatistics(
    history: AiHistorySnapshot?, state: AgentContextState?, settings: LlmSettings,
    title: String, speed: Double?, busy: Boolean, budgetRevision: Int,
) {
    val context = LocalContext.current
    val key = AiContextLimits.key(settings.baseUrl, settings.modelName)
    val budget = remember(key, budgetRevision) { AiContextLimits.budget(context, key) }
    val matching = state?.takeIf { it.modelKey == key }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        .testTag("ai-editor-statistics"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ai_editor_total_usage), style = MaterialTheme.typography.labelMedium)
                Text(history?.usage?.let { (if (it.partial && it.tokens != null) "≥ " else "") + formatAiTokenCount(it.tokens) } ?: "—",
                    style = MaterialTheme.typography.headlineMedium)
                Text("tokens", style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.ai_editor_report_coverage, history?.usage?.reported ?: 0,
                    history?.usage?.requests ?: 0), style = MaterialTheme.typography.bodySmall)
                val breakdown = history?.let(::aiEditorTokenBreakdown)
                Text(stringResource(R.string.ai_editor_usage_breakdown,
                    formatAiTokenCount(breakdown?.first), formatAiTokenCount(breakdown?.second)),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ai_editor_output_speed), style = MaterialTheme.typography.labelSmall)
                Text(speed?.let { String.format(java.util.Locale.ROOT, "%.1f token/s", it) } ?: "— token/s")
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ai_history_duration), style = MaterialTheme.typography.labelSmall)
                Text(formatAiHistoryDuration(history?.elapsedMs))
            }
        }
        Text(stringResource(R.string.ai_editor_turns_steps, history?.turns?.size ?: 0, history?.stepCount ?: 0),
            style = MaterialTheme.typography.bodySmall)
        AiContextUsagePanel(if (history?.turns?.isEmpty() == true) 0 else matching?.estimatedTokens,
            budget.inputLimit, busy, matching,
            matching?.cacheStats?.takeIf { it.endpoint == settings.endpoint.name } ?: AgentCacheStats())
    }
}

@Composable
private fun AiEditorBudgetFields(settings: LlmSettings, onSaved: () -> Unit) {
    val context = LocalContext.current
    val key = AiContextLimits.key(settings.baseUrl, settings.modelName)
    var input by rememberSaveable(key) { mutableStateOf(AiContextLimits.explicitInputLimit(context, key)?.toString().orEmpty()) }
    var output by rememberSaveable(key) { mutableStateOf(AiContextLimits.explicitOutputTarget(context, key)?.toString().orEmpty()) }
    var saved by rememberSaveable(key) { mutableStateOf(false) }
    fun valid(value: String) = value.isBlank() || (value.toIntOrNull() ?: 0) > 0
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ai_editor_budget), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(input, { input = it.filter(Char::isDigit); saved = false }, Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                label = { Text(stringResource(R.string.ai_agent_budget_input_target)) }, isError = !valid(input))
            OutlinedTextField(output, { output = it.filter(Char::isDigit); saved = false }, Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                label = { Text(stringResource(R.string.ai_agent_budget_output_target)) }, isError = !valid(output))
            Text(stringResource(R.string.ai_agent_budget_input_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled = valid(input) && valid(output), onClick = {
                AiContextLimits.set(context, key, input.toIntOrNull(), output.toIntOrNull())
                saved = true
                onSaved()
            }) { Text(stringResource(R.string.common_action_save)) }
            if (saved) Text(stringResource(R.string.ai_editor_budget_saved), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Only sum complete reported requests, matching the total's lower-bound accounting. */
internal fun aiEditorTokenBreakdown(snapshot: AiHistorySnapshot): Pair<Long?, Long?> {
    val reported = snapshot.turns.flatMap { it.steps }.filter { it.billable && it.totalTokens != null }
    return if (reported.isEmpty()) null to null else
        reported.sumOf { it.inputTokens!! } to reported.sumOf { it.outputTokens!! }
}
