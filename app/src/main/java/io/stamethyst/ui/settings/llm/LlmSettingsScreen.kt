package io.stamethyst.ui.settings.llm

import android.content.Context
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.stamethyst.R
import io.stamethyst.navigation.Route
import io.stamethyst.backend.llm.LlmEndpoint
import io.stamethyst.backend.llm.LlmSettings
import io.stamethyst.backend.llm.LlmSettingsRepository
import io.stamethyst.backend.llm.LlmReasoningEffort
import io.stamethyst.backend.llm.LlmModelTestService
import io.stamethyst.backend.llm.OpenAiCompatibleModelConfig
import io.stamethyst.backend.llm.NewApiModel
import io.stamethyst.backend.llm.NewApiModelService
import io.stamethyst.backend.llm.DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS
import io.stamethyst.backend.llm.MAX_LLM_REQUEST_TIMEOUT_SECONDS
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.settings.common.SettingsRouteScaffold
import io.stamethyst.ui.settings.common.SettingsLlmRouteSpec
import io.stamethyst.ui.settings.common.SettingsSectionCard
import io.stamethyst.ui.settings.common.SettingsActionListItem
import io.stamethyst.ui.settings.common.SettingsDropdownField
import io.stamethyst.ui.settings.core.SettingsScreenViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LlmSettingsViewModel(private val repository: LlmSettingsRepository) : ViewModel() {
    var settings by mutableStateOf(repository.get())
        private set
    var remoteModels by mutableStateOf<List<NewApiModel>>(emptyList())
        private set
    var refreshingModels by mutableStateOf(false)
        private set
    var modelFetchError by mutableStateOf<String?>(null)
        private set
    var remoteModelsEmpty by mutableStateOf(false)
        private set
    var saveError by mutableStateOf(false)
        private set
    var testingModel by mutableStateOf(false)
        private set
    var modelTestResult by mutableStateOf<String?>(null)
        private set
    var modelTestError by mutableStateOf<String?>(null)
        private set
    var showModelTestResult by mutableStateOf(false)
        private set

    // Drain queued edits even when navigation clears this ViewModel.
    private val writes = Channel<LlmSettings>(Channel.CONFLATED)
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        writeScope.launch {
            try {
                for (queued in writes) {
                    delay(350)
                    var draft = queued
                    while (true) {
                        draft = writes.tryReceive().getOrNull() ?: break
                    }
                    if (draft == settings) {
                        saveError = false
                        continue
                    }
                    runCatching {
                        withContext(Dispatchers.IO) {
                            repository.set(draft)
                            repository.get()
                        }
                    }.onSuccess {
                        settings = it
                        saveError = false
                    }.onFailure { saveError = true }
                }
            } finally {
                writeScope.cancel()
            }
        }
    }

    fun save(settings: LlmSettings) {
        writes.trySend(settings)
    }

    override fun onCleared() {
        writes.close()
        super.onCleared()
    }

    fun consumeRemoteModels() {
        remoteModels = emptyList()
    }

    fun refreshModels(baseUrl: String, apiKey: String) {
        if (refreshingModels || baseUrl.isBlank() || apiKey.isBlank()) return
        refreshingModels = true
        modelFetchError = null
        remoteModelsEmpty = false
        remoteModels = emptyList()
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                NewApiModelService().fetchModels(baseUrl, apiKey)
            } }
                .onSuccess { models -> remoteModels = models; remoteModelsEmpty = models.isEmpty() }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    modelFetchError = error.javaClass.simpleName
                }
            refreshingModels = false
        }
    }

    fun testModel(config: OpenAiCompatibleModelConfig) {
        if (testingModel) return
        testingModel = true
        modelTestResult = null
        modelTestError = null
        showModelTestResult = true
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { LlmModelTestService().test(config) } }
                .onSuccess { modelTestResult = it }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    modelTestError = error.message?.takeIf(String::isNotBlank)
                        ?: error.javaClass.simpleName
                }
            testingModel = false
        }
    }

    fun dismissModelTestResult() {
        showModelTestResult = false
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LlmSettingsViewModel(LlmSettingsRepository(context.applicationContext)) as T
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LauncherLlmSettingsScreen(
    modifier: Modifier = Modifier,
    uiState: SettingsScreenViewModel.UiState,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val navigator = currentNavigator
    val viewModel: LlmSettingsViewModel = viewModel(factory = LlmSettingsViewModel.factory(context))
    val current = viewModel.settings
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf(current.baseUrl) }
    var modelName by rememberSaveable { mutableStateOf(current.modelName) }
    var endpoint by rememberSaveable { mutableStateOf(current.endpoint) }
    var requestTimeoutSeconds by rememberSaveable {
        mutableStateOf(current.requestTimeoutSeconds)
    }
    var reasoningEffort by rememberSaveable { mutableStateOf(current.reasoningEffort) }
    var models by rememberSaveable {
        mutableStateOf(current.models)
    }
    var customModelInput by rememberSaveable { mutableStateOf("") }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showAddCustomModel by rememberSaveable { mutableStateOf(false) }
    var modelQuery by rememberSaveable { mutableStateOf("") }
    val effectiveKey = apiKey.trim().ifEmpty { current.apiKey }
    val validUrl = baseUrl.trim().toHttpUrlOrNull()?.let {
        it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    } == true
    val draft = LlmSettings(
        apiKey = effectiveKey,
        baseUrl = if (validUrl) baseUrl.trim().trimEnd('/') else current.baseUrl,
        modelName = modelName,
        endpoint = endpoint,
        requestTimeoutSeconds = requestTimeoutSeconds,
        reasoningEffort = reasoningEffort,
        models = models,
    )
    LaunchedEffect(draft) { viewModel.save(draft) }
    val latestDraft by rememberUpdatedState(draft)
    DisposableEffect(viewModel) {
        onDispose { viewModel.save(latestDraft) }
    }
    LaunchedEffect(viewModel.remoteModels) {
        if (viewModel.remoteModels.isNotEmpty()) {
            models = (models + viewModel.remoteModels.map { it.id }).distinct()
            if (modelName.isBlank()) modelName = models.firstOrNull().orEmpty()
            viewModel.consumeRemoteModels()
        }
    }

    SettingsRouteScaffold(
        modifier = modifier.imePadding(),
        uiState = uiState,
        spec = SettingsLlmRouteSpec,
        onGoBack = navigator::goBack,
    ) {
        item {
            SettingsActionListItem(
                title = stringResource(R.string.llm_tutorial_title),
                supportingText = stringResource(R.string.llm_tutorial_entry_hint),
                enabled = true,
                onClick = { navigator.push(Route.SettingsLlmTutorial) },
            )
        }
        if (viewModel.saveError) {
            item {
                Text(stringResource(R.string.llm_save_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { viewModel.save(draft) }) { Text(stringResource(R.string.llm_retry)) }
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_connection), iconResId = R.drawable.ic_link) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_llm_base_url)) },
                    supportingText = {
                        Text(stringResource(if (!validUrl) R.string.llm_invalid_url else R.string.settings_llm_base_url_hint))
                    },
                    isError = !validUrl,
                    singleLine = true,
                )
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_credentials), iconResId = R.drawable.ic_lan_room_key) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_llm_api_key)) },
                    supportingText = { Text(stringResource(R.string.llm_key_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_models), iconResId = R.drawable.ic_llm_support) {
                SettingsActionListItem(
                    title = stringResource(R.string.llm_manage_models, models.size),
                    supportingText = if (modelName.isBlank()) stringResource(R.string.llm_models_empty)
                        else stringResource(R.string.llm_active_model, modelName),
                    enabled = true,
                    onClick = { showModels = true },
                )
                OutlinedButton(
                    onClick = {
                        viewModel.testModel(
                            OpenAiCompatibleModelConfig(
                                baseUrl = draft.baseUrl,
                                apiKey = draft.apiKey,
                                modelName = draft.modelName,
                                endpoint = draft.endpoint,
                                requestTimeoutSeconds = draft.requestTimeoutSeconds,
                                reasoningEffort = draft.reasoningEffort,
                            ),
                        )
                    },
                    enabled = !viewModel.testingModel && effectiveKey.isNotBlank() && validUrl && modelName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(painterResource(R.drawable.ic_play_arrow), null, Modifier.size(18.dp))
                    Text(stringResource(R.string.llm_test_model), Modifier.padding(start = 8.dp))
                }
                if (viewModel.refreshingModels) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_request_options), iconResId = R.drawable.ic_settings_other) {
                SettingsDropdownField(
                    label = stringResource(R.string.settings_llm_endpoint),
                    valueText = stringResource(if (endpoint == LlmEndpoint.CHAT_COMPLETIONS) R.string.settings_llm_chat_completions else R.string.settings_llm_responses),
                    enabled = true,
                    options = LlmEndpoint.entries,
                    optionLabel = { stringResource(if (it == LlmEndpoint.CHAT_COMPLETIONS) R.string.settings_llm_chat_completions else R.string.settings_llm_responses) },
                    onOptionSelected = { endpoint = it },
                )
                SettingsDropdownField(
                    label = stringResource(R.string.settings_llm_timeout),
                    valueText = stringResource(R.string.settings_llm_timeout_value, requestTimeoutSeconds / 60),
                    enabled = true,
                    options = listOf(60, 180, DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS, 600, MAX_LLM_REQUEST_TIMEOUT_SECONDS),
                    optionLabel = { stringResource(R.string.settings_llm_timeout_value, it / 60) },
                    onOptionSelected = { requestTimeoutSeconds = it },
                )
                SettingsDropdownField(
                    label = stringResource(R.string.settings_llm_reasoning),
                    valueText = stringResource(reasoningEffort.labelRes),
                    enabled = true,
                    options = LlmReasoningEffort.entries,
                    optionLabel = { stringResource(it.labelRes) },
                    onOptionSelected = { reasoningEffort = it },
                )
            }
        }
    }

    if (showModels) {
        AlertDialog(
            onDismissRequest = { showModels = false },
            title = { Text(stringResource(R.string.llm_manage_models, models.size)) },
            confirmButton = {
                TextButton(onClick = { showModels = false }) { Text(stringResource(R.string.llm_done)) }
            },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { showAddCustomModel = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(painterResource(R.drawable.ic_add_circle), null, Modifier.size(18.dp))
                                Text(
                                    stringResource(R.string.llm_add_custom_model),
                                    Modifier.padding(start = 8.dp),
                                )
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { viewModel.refreshModels(baseUrl, effectiveKey) },
                                    enabled = !viewModel.refreshingModels && effectiveKey.isNotBlank() && validUrl,
                                ) {
                                    Icon(painterResource(R.drawable.ic_cloud_sync), null, Modifier.size(18.dp))
                                    Text(stringResource(R.string.llm_fetch_models), Modifier.padding(start = 8.dp))
                                }
                                TextButton(enabled = models.isNotEmpty(), onClick = { models = emptyList(); modelName = "" }) {
                                    Icon(painterResource(R.drawable.ic_delete), null, Modifier.size(18.dp))
                                    Text(stringResource(R.string.llm_clear_models), Modifier.padding(start = 8.dp))
                                }
                            }
                            if (viewModel.refreshingModels) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (viewModel.remoteModelsEmpty) Text(stringResource(R.string.llm_remote_empty))
                            viewModel.modelFetchError?.let {
                                Text(stringResource(R.string.llm_fetch_failed, it), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = modelQuery,
                            onValueChange = { modelQuery = it },
                            label = { Text(stringResource(R.string.llm_search_models)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                    val visibleModels = models.filter { it.contains(modelQuery.trim(), ignoreCase = true) }
                    if (visibleModels.isEmpty()) {
                        item { Text(stringResource(if (models.isEmpty()) R.string.llm_models_empty else R.string.llm_no_matches)) }
                    }
                    items(visibleModels, key = { it }) { model ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .selectable(selected = modelName == model, role = Role.RadioButton, onClick = { modelName = model }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = modelName == model, onClick = null)
                                Text(model, Modifier.weight(1f).padding(start = 8.dp))
                            }
                            IconButton(onClick = {
                                models = models - model
                                if (modelName == model) modelName = models.firstOrNull().orEmpty()
                            }) {
                                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.llm_remove_model, model))
                            }
                        }
                    }
                }
            }
        )
    }

    if (showAddCustomModel) {
        AlertDialog(
            onDismissRequest = {
                showAddCustomModel = false
                customModelInput = ""
            },
            title = { Text(stringResource(R.string.llm_add_custom_model)) },
            text = {
                OutlinedTextField(
                    value = customModelInput,
                    onValueChange = { customModelInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.llm_custom_model_id)) },
                    singleLine = true,
                )
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddCustomModel = false
                    customModelInput = ""
                }) { Text(stringResource(R.string.common_action_close)) }
            },
            confirmButton = {
                TextButton(
                    enabled = customModelInput.isNotBlank(),
                    onClick = {
                        val model = customModelInput.trim()
                        if (model.isNotEmpty()) {
                            models = (models + model).distinct()
                            if (modelName.isBlank()) modelName = model
                        }
                        showAddCustomModel = false
                        customModelInput = ""
                    },
                ) { Text(stringResource(R.string.common_action_save)) }
            },
        )
    }

    if (viewModel.showModelTestResult) {
        AlertDialog(
            onDismissRequest = { if (!viewModel.testingModel) viewModel.dismissModelTestResult() },
            title = { Text(stringResource(R.string.llm_test_model)) },
            confirmButton = {
                TextButton(
                    enabled = !viewModel.testingModel,
                    onClick = { viewModel.dismissModelTestResult() },
                ) { Text(stringResource(R.string.llm_done)) }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        viewModel.testingModel -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.llm_test_model_running))
                        }
                        viewModel.modelTestError != null -> {
                            Text(
                                stringResource(R.string.llm_test_model_failed, viewModel.modelTestError.orEmpty()),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        else -> {
                            Text(stringResource(R.string.llm_test_model_success))
                            Text(viewModel.modelTestResult.orEmpty())
                        }
                    }
                }
            },
        )
    }
}

private val LlmReasoningEffort.labelRes: Int
    get() = when (this) {
        LlmReasoningEffort.OFF -> R.string.settings_llm_reasoning_off
        LlmReasoningEffort.LOW -> R.string.settings_llm_reasoning_low
        LlmReasoningEffort.MEDIUM -> R.string.settings_llm_reasoning_medium
        LlmReasoningEffort.HIGH -> R.string.settings_llm_reasoning_high
    }
