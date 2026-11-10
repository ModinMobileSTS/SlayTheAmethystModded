package io.stamethyst.backend.workshop

import android.content.Context
import io.stamethyst.ui.preferences.LauncherPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class WorkshopUpdateCheckUiState(
    val checking: Boolean = false,
    val lastCheckedAtMs: Long = 0L,
    val lastCompletedAtMs: Long = 0L,
    val updateCount: Int = 0,
    val failedCount: Int = 0,
    val lastErrorSummary: String? = null,
)

internal data class WorkshopUpdateCheckCompletion(
    val updateCount: Int,
    val failedCount: Int,
    val errorSummary: String? = null,
)

internal object WorkshopUpdateCheckCoordinator {
    private const val CHECK_INTERVAL_MS = 10L * 60L * 1000L
    private const val APP_START_CHECK_DELAY_MS = 60_000L
    private const val APP_START_STORED_COUNT_DELAY_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val _uiState = MutableStateFlow(WorkshopUpdateCheckUiState())
    private val _completionNotices = MutableSharedFlow<WorkshopUpdateCheckCompletion>(extraBufferCapacity = 8)

    @Volatile
    private var checkJob: Job? = null
    @Volatile
    private var periodicJob: Job? = null
    @Volatile
    private var storedCountJob: Job? = null
    @Volatile
    private var notifyNextCompletion = false

    val uiState: StateFlow<WorkshopUpdateCheckUiState> = _uiState.asStateFlow()
    val completionNotices: SharedFlow<WorkshopUpdateCheckCompletion> = _completionNotices.asSharedFlow()

    fun bind(context: Context) {
        val appContext = context.applicationContext
        _uiState.value = _uiState.value.copy(
            lastCheckedAtMs = LauncherPreferences.readLastWorkshopUpdateCheckAtMs(appContext),
        )
        refreshStoredUpdateCountLater(appContext)
    }

    fun checkOnAppStartIfDue(context: Context) {
        val appContext = context.applicationContext
        bind(appContext)
        val initialDelayMs = workshopUpdateCheckInitialDelayMs(
            lastCheckedAtMs = LauncherPreferences.readLastWorkshopUpdateCheckAtMs(appContext),
            nowMs = System.currentTimeMillis(),
            checkIntervalMs = CHECK_INTERVAL_MS,
            appStartDelayMs = APP_START_CHECK_DELAY_MS,
        )
        startPeriodicChecks(appContext, initialDelayMs)
    }

    fun requestCheck(context: Context, force: Boolean = false, notifyResult: Boolean = false, publishedFileIds: Set<ULong>? = null) {
        val appContext = context.applicationContext
        _uiState.value = _uiState.value.copy(
            lastCheckedAtMs = LauncherPreferences.readLastWorkshopUpdateCheckAtMs(appContext),
        )
        if (!force && !isCheckDue(appContext)) {
            return
        }
        synchronized(lock) {
            if (checkJob?.isActive == true) {
                if (notifyResult) {
                    notifyNextCompletion = true
                }
                return
            }
            if (notifyResult) {
                notifyNextCompletion = true
            }
            _uiState.value = _uiState.value.copy(checking = true, lastErrorSummary = null)
            checkJob = scope.launch {
                runCheck(appContext, publishedFileIds)
            }
        }
    }

    private suspend fun runCheck(context: Context, publishedFileIds: Set<ULong>?) {
        runCatching {
            withContext(Dispatchers.IO) {
                WorkshopUpdateChecker(context).checkInstalledMods(publishedFileIds)
            }
        }.onSuccess { report ->
            val completedAtMs = System.currentTimeMillis()
            val updateCount = report.results.count { it.hasUpdate }
            val failedCount = report.failedCount
            if (publishedFileIds == null) LauncherPreferences.saveLastWorkshopUpdateCheckAtMs(context, completedAtMs)
            _uiState.value = WorkshopUpdateCheckUiState(
                checking = false,
                lastCheckedAtMs = LauncherPreferences.readLastWorkshopUpdateCheckAtMs(context),
                lastCompletedAtMs = completedAtMs,
                updateCount = countStoredUpdates(context),
                failedCount = failedCount,
                lastErrorSummary = null,
            )
            emitCompletionNoticeIfRequested(
                WorkshopUpdateCheckCompletion(
                    updateCount = updateCount,
                    failedCount = failedCount,
                )
            )
        }.onFailure { error ->
            val completedAtMs = System.currentTimeMillis()
            val updateCount = countStoredUpdates(context)
            val errorSummary = error.message ?: error.javaClass.simpleName
            if (publishedFileIds == null) LauncherPreferences.saveLastWorkshopUpdateCheckAtMs(context, completedAtMs)
            _uiState.value = WorkshopUpdateCheckUiState(
                checking = false,
                lastCheckedAtMs = LauncherPreferences.readLastWorkshopUpdateCheckAtMs(context),
                lastCompletedAtMs = completedAtMs,
                updateCount = updateCount,
                failedCount = 0,
                lastErrorSummary = errorSummary,
            )
            emitCompletionNoticeIfRequested(
                WorkshopUpdateCheckCompletion(
                    updateCount = updateCount,
                    failedCount = 0,
                    errorSummary = errorSummary,
                )
            )
        }
    }

    private fun emitCompletionNoticeIfRequested(completion: WorkshopUpdateCheckCompletion) {
        val shouldNotify = synchronized(lock) {
            val current = notifyNextCompletion
            notifyNextCompletion = false
            current
        }
        if (shouldNotify) {
            _completionNotices.tryEmit(completion)
        }
    }

    private fun isCheckDue(context: Context): Boolean {
        val lastCheckedAtMs = LauncherPreferences.readLastWorkshopUpdateCheckAtMs(context)
        return isWorkshopUpdateCheckDue(
            lastCheckedAtMs = lastCheckedAtMs,
            nowMs = System.currentTimeMillis(),
            checkIntervalMs = CHECK_INTERVAL_MS,
        )
    }

    private fun startPeriodicChecks(context: Context, initialDelayMs: Long) {
        synchronized(lock) {
            if (periodicJob?.isActive == true) {
                return
            }
            periodicJob = scope.launch {
                if (initialDelayMs > 0L) {
                    delay(initialDelayMs)
                }
                while (true) {
                    val delayMs = millisUntilNextCheck(context)
                    if (delayMs > 0L) {
                        delay(delayMs)
                    }
                    requestCheck(context = context, force = false)
                }
            }
        }
    }

    private fun millisUntilNextCheck(context: Context): Long {
        if (checkJob?.isActive == true) {
            return 5_000L
        }
        val lastCheckedAtMs = LauncherPreferences.readLastWorkshopUpdateCheckAtMs(context)
        val now = System.currentTimeMillis()
        if (lastCheckedAtMs <= 0L || now < lastCheckedAtMs) {
            return 0L
        }
        return (CHECK_INTERVAL_MS - (now - lastCheckedAtMs)).coerceAtLeast(0L)
    }

    private fun refreshStoredUpdateCountLater(context: Context) {
        synchronized(lock) {
            if (storedCountJob?.isActive == true) {
                return
            }
            storedCountJob = scope.launch {
                delay(APP_START_STORED_COUNT_DELAY_MS)
                val updateCount = countStoredUpdates(context)
                _uiState.value = _uiState.value.copy(updateCount = updateCount)
            }
        }
    }

    private fun countStoredUpdates(context: Context): Int {
        return runCatching {
            WorkshopMetadataStore(context).countUpdateAvailable()
        }.getOrDefault(0)
    }
}
