package io.stamethyst.ui.workshop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.roundToInt
import io.stamethyst.R
import io.stamethyst.backend.llm.LlmSettingsRepository
import io.stamethyst.backend.llm.LlmSettings
import io.stamethyst.backend.llm.LlmReasoningEffort
import io.stamethyst.backend.workshop.*
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.Search
import io.stamethyst.ui.aimod.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object WorkshopChatTranscriptHazeKey

@Composable
internal fun WorkshopSearchTabs(
    selected: Boolean, motion: WorkshopModeMotion = rememberWorkshopModeMotion(selected),
    onSelect: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tabSize = 48.dp
    Box(Modifier.background(colors.surfaceContainerHigh, CircleShape)) {
        Box(Modifier.offset { IntOffset((tabSize.toPx() * motion.progress).roundToInt(), 0) }
            .padding(5.dp).size(38.dp).background(colors.secondaryContainer, CircleShape))
        Row(Modifier.selectableGroup()) {
            listOf(false, true).forEach { ai ->
                val isSelected = ai == selected
                IconButton(
                    onClick = { onSelect(ai) },
                    modifier = Modifier.size(tabSize).semantics {
                        role = Role.Tab
                        this.selected = isSelected
                    },
                ) {
                    WorkshopSearchModeIcon(ai)
                }
            }
        }
    }
}

@Composable
private fun WorkshopSearchModeIcon(ai: Boolean) {
    if (ai) {
        Icon(painterResource(R.drawable.ic_market_ai), stringResource(R.string.market_ai_title), Modifier.size(21.dp))
    } else {
        Icon(Icons.Search, stringResource(R.string.market_ai_ordinary), Modifier.size(21.dp))
    }
}

@Composable
internal fun WorkshopSearchChat(
    sessions: WorkshopSearchSessions, listState: LazyListState, onSettings: () -> Unit,
    modifier: Modifier = Modifier, composerModifier: Modifier = Modifier, active: Boolean,
    titleInset: Dp = 0.dp, haze: HazeState = rememberHazeState(),
    renderItem: @Composable (WorkshopItemSummary) -> Unit,
) {
    val context = LocalContext.current
    val chat = sessions.current
    val keyboard = LocalSoftwareKeyboardController.current
    val settings by produceState<LlmSettings?>(null, active, context) {
        if (active) value = withContext(Dispatchers.IO) { LlmSettingsRepository(context).get() }
    }
    // A re-mounted panel already has its retained scroll position. Follow new turns only.
    val lastTurn = chat.turns.lastOrNull()?.id
    var displayedTurn by remember { mutableStateOf(lastTurn) }
    LaunchedEffect(lastTurn) {
        if (lastTurn != displayedTurn && lastTurn != null) listState.scrollToItem(chat.turns.lastIndex)
        displayedTurn = lastTurn
    }
    val density = LocalDensity.current
    var bottomInset by remember { mutableStateOf(200.dp) }
    Box(modifier.fillMaxSize()) {
        AiConversationTranscript(listState, Modifier.fillMaxSize().hazeSource(haze, key = WorkshopChatTranscriptHazeKey),
            PaddingValues(start = 16.dp, end = 16.dp, top = titleInset + 16.dp, bottom = bottomInset + 8.dp)) {
            if (chat.turns.isEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.market_ai_welcome), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.market_ai_intro))
                    listOf(R.string.market_ai_example_map, R.string.market_ai_example_touch).forEach { res ->
                        val question = stringResource(res)
                        OutlinedButton(onClick = { chat.draft = question }) { Text(question) }
                    }
                }
            }
            itemsIndexed(chat.turns, key = { _, turn -> turn.id }) { index, turn ->
                val streaming = chat.busy && index == chat.turns.lastIndex
                Column(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MarketMessage(AiEditorMessage(turn.id, true, turn.question))
                    Text(stringResource(R.string.market_ai_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (streaming || turn.activityParts.isNotEmpty()) {
                        MarketMessage(AiEditorMessage(
                            id = turn.id,
                            fromUser = false,
                            text = "",
                            streaming = streaming,
                            tools = turn.tools,
                            parts = turn.activityParts,
                        ))
                        // Some providers don't stream thinking. Keep showing activity while waiting
                        // for the next model response after a tool has finished.
                        if (streaming && turn.activityParts.lastOrNull()?.kind == AiMessagePartKind.TOOL &&
                            turn.tools.lastOrNull()?.result != null
                        ) {
                            AiAssistantLoading()
                        }
                    }
                    turn.blocks.forEach { block -> when (block) {
                        is WorkshopAnswerBlock.Text -> MarketMessage(AiEditorMessage(turn.id, false, block.value))
                        is WorkshopAnswerBlock.Card -> {
                            val citation = block.citation
                            renderItem(citation.item)
                            var expanded by rememberSaveable(turn.id, citation.item.publishedFileId.toString()) { mutableStateOf(false) }
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(stringResource(if (citation.partial) R.string.market_ai_partial else R.string.market_ai_source))
                            }
                            if (expanded) Text("“${citation.quote}”\nSteam Workshop · ${citation.item.publishedFileId}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    } }
                    if (turn.blocks.isNotEmpty() && turn.blocks.none { it is WorkshopAnswerBlock.Card }) {
                        Text(stringResource(R.string.market_ai_uncited), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (turn.stopped || turn.error != null) {
                        Text(turn.error ?: stringResource(R.string.market_ai_stopped))
                        if (index == chat.turns.lastIndex) TextButton(onClick = { chat.retry(context) }, enabled = !chat.busy) {
                            Text(stringResource(R.string.ai_mod_editor_retry))
                        }
                    }
                }
            }
        }
        val config = settings
        val models = config?.models.orEmpty()
        val options = config?.availableReasoningEfforts ?: LlmReasoningEffort.defaults
        AiChatComposer(
            modifier = Modifier.align(Alignment.BottomCenter).then(composerModifier)
                .onSizeChanged { bottomInset = with(density) { it.height.toDp() } }.navigationBarsPadding().imePadding(),
            draft = chat.draft, onDraftChange = { chat.draft = it.take(4000) },
            placeholder = stringResource(R.string.market_ai_hint), draftEnabled = true,
            attachments = emptyList(), onRemoveAttachment = {}, onAttach = null,
            configured = config?.isConfigured() == true, busy = chat.busy, allowQueue = false,
            modelName = chat.modelOverride?.takeIf { it in models } ?: config?.modelName.orEmpty(),
            models = models, onModelSelect = { chat.modelOverride = it },
            reasoningOptions = options,
            effort = chat.effortOverride?.takeIf { it in options } ?: config?.reasoningEffort ?: LlmReasoningEffort.LOW,
            onEffortChange = { chat.effortOverride = it }, onConfigureEfforts = onSettings,
            onSend = { keyboard?.hide(); chat.send(context) }, onStop = chat::stop,
            hazeState = haze, onComposerHeightChanged = {},
            // The shared header can sample both panes while they overlap; the composer only samples the transcript.
            hazeSourceKey = WorkshopChatTranscriptHazeKey,
            leadingAction = {
                IconButton(onClick = { keyboard?.hide(); sessions.newChat() }) {
                    Icon(painterResource(R.drawable.ic_chat_add), stringResource(R.string.ai_mod_editor_new_chat))
                }
            },
            contextCounter = {
                if (config != null && !config.isConfigured()) TextButton(onClick = onSettings) {
                    Text(stringResource(R.string.settings_llm_title))
                }
                Box {
                    var expanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { expanded = true }) {
                        Icon(painterResource(R.drawable.ic_market_ai_sessions), stringResource(R.string.ai_editor_sessions))
                    }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                        sessions.sessions.forEach { other ->
                            DropdownMenuItem(
                                text = { Text((other.turns.firstOrNull()?.question ?: other.draft)
                                    .ifBlank { stringResource(R.string.ai_mod_editor_new_chat) },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingIcon = if (other === chat) { { Icon(painterResource(R.drawable.ic_check_circle),
                                    stringResource(R.string.ai_editor_current_session)) } } else null,
                                onClick = { expanded = false; keyboard?.hide(); sessions.select(other) },
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun MarketMessage(message: AiEditorMessage) = AiMessageCard(message, {}, {}, {}, {}, enabled = false, showFooter = false)
