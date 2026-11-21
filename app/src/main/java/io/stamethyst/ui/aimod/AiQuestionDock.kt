package io.stamethyst.ui.aimod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.llm.AgentQuestionRequest
import io.stamethyst.backend.llm.AgentUserQuestions
import io.stamethyst.backend.llm.MAX_CUSTOM_ANSWER_LENGTH

@Composable
internal fun pendingAgentQuestion(questions: AgentUserQuestions?): AgentQuestionRequest? = key(questions) {
    questions?.pending?.collectAsState()?.value
}

@Composable
internal fun AiQuestionDock(
    request: AgentQuestionRequest,
    draft: String,
    onReply: (List<List<String>>) -> Boolean,
    onDismiss: () -> Boolean,
) = key(request.id) {
    val questions = request.questions
    var page by rememberSaveable { mutableIntStateOf(0) }
    // One mask per question; the option immediately after the model's options is the custom row.
    var selection by rememberSaveable { mutableStateOf(IntArray(questions.size)) }
    var custom by rememberSaveable { mutableStateOf(ArrayList(List(questions.size) { "" })) }
    var settled by rememberSaveable { mutableStateOf(false) }
    val question = questions[page]
    fun selected(index: Int) = selection[page] and (1 shl index) != 0
    fun answers(index: Int): List<String> = buildList {
        val options = questions[index].options
        options.forEachIndexed { i, option -> if (selection[index] and (1 shl i) != 0) add(option.label) }
        if (selection[index] and (1 shl options.size) != 0 || options.isEmpty()) {
            custom[index].trim().takeIf(String::isNotEmpty)?.let { if (it !in this) add(it) }
        }
    }
    val showCustom = question.options.isEmpty() || selected(question.options.size)
    val complete = answers(page).isNotEmpty() && (!showCustom || custom[page].isNotBlank())
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scroll = rememberScrollState()
    LaunchedEffect(page) { scroll.scrollTo(0) }

    fun finish(accepted: Boolean) {
        if (accepted) {
            settled = true
            focus.clearFocus()
            keyboard?.hide()
        }
    }

    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Column(Modifier.fillMaxWidth().heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            shape = RoundedCornerShape(24.dp),
            color = colors.surfaceContainerLow,
            shadowElevation = 4.dp,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(26.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                            Text("?", color = colors.onPrimaryContainer, style = typography.labelLarge)
                        }
                        Text(stringResource(R.string.ai_question_title), Modifier.weight(1f), style = typography.labelLarge)
                        Text(stringResource(R.string.ai_question_waiting), color = colors.onSurfaceVariant, style = typography.labelSmall)
                    }
                    if (questions.size > 1) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            questions.indices.forEach { index ->
                                Spacer(Modifier.weight(1f).height(3.dp).clip(CircleShape)
                                    .background(if (index <= page) colors.primary else colors.surfaceContainerHighest))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(question.header, Modifier.weight(1f), color = colors.primary, style = typography.labelMedium)
                        if (question.options.isNotEmpty()) {
                            Text(stringResource(if (question.multiple) R.string.ai_question_multiple else R.string.ai_question_single),
                                color = colors.onSurfaceVariant, style = typography.labelSmall)
                        }
                        if (questions.size > 1) {
                            Text(stringResource(R.string.ai_question_progress, page + 1, questions.size),
                                color = colors.onSurfaceVariant, style = typography.labelSmall)
                        }
                    }
                    Text(question.question, Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                        style = typography.titleMedium)
                    if (question.options.isNotEmpty()) {
                        Column(
                            if (question.multiple) Modifier else Modifier.selectableGroup(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val labels = question.options.map { it.label to it.description } +
                                (stringResource(R.string.ai_question_custom) to "")
                            labels.forEachIndexed { index, (label, description) ->
                                val checked = selected(index)
                                AiQuestionOption(label, description, question.multiple, checked, enabled = !settled) {
                                    val bit = 1 shl index
                                    selection = selection.copyOf().apply {
                                        this[page] = if (question.multiple) this[page] xor bit else bit
                                    }
                                }
                            }
                        }
                    }
                    if (showCustom) key(page) {
                        OutlinedTextField(
                            value = custom[page],
                            onValueChange = { value ->
                                custom = ArrayList(custom).apply { this[page] = value.take(MAX_CUSTOM_ANSWER_LENGTH) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.ai_question_custom_hint)) },
                            enabled = !settled,
                            maxLines = 3,
                        )
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { finish(onDismiss()) }, enabled = !settled,
                        contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.ai_question_skip))
                    }
                    if (page > 0) TextButton(onClick = {
                        focus.clearFocus()
                        keyboard?.hide()
                        page--
                    }, enabled = !settled, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.ai_question_previous))
                    }
                    Button(onClick = {
                        if (page == questions.lastIndex) finish(onReply(questions.indices.map(::answers)))
                        else {
                            focus.clearFocus()
                            keyboard?.hide()
                            page++
                        }
                    }, enabled = complete && !settled, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text(stringResource(if (page == questions.lastIndex) R.string.ai_question_submit else R.string.ai_question_next))
                    }
                }
            }
        }
        if (draft.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(draft, Modifier.weight(1f), color = colors.onSurfaceVariant, style = typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.ai_question_draft_saved), color = colors.onSurfaceVariant, style = typography.labelSmall)
            }
        }
    }
}

@Composable
private fun AiQuestionOption(
    label: String, description: String, multiple: Boolean, checked: Boolean, enabled: Boolean, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val selection = if (multiple) Modifier.toggleable(checked, enabled, Role.Checkbox) { onClick() }
        else Modifier.selectable(checked, enabled, Role.RadioButton, onClick = onClick)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (checked) colors.primaryContainer else colors.surfaceContainerHigh)
            .then(selection).heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (multiple) Checkbox(checked, onCheckedChange = null, enabled = enabled)
        else RadioButton(checked, onClick = null, enabled = enabled)
        Column {
            Text(label, color = if (checked) colors.onPrimaryContainer else colors.onSurface, style = MaterialTheme.typography.labelLarge)
            if (description.isNotBlank()) Text(description,
                color = if (checked) colors.onPrimaryContainer else colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
