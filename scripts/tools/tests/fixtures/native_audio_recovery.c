/* Included after the unmodified repository-native recovery/queue functions.
 * Each scenario runs in a fresh process. This models OpenAL control flow,
 * not Android audio policy, an actual Oboe stream, or audible output.
 */

static int context_token, device_token;
static bool context_available = true;
static bool reset_ok = true, resume_ok = true;
static bool pause_ok = true, query_ok = true;
static bool reset_error, resume_disconnect_only;
static bool connected, output_running;
static int pause_calls, reset_calls, resume_calls, alc_error, injected_error;
static int error_reads, connection_reads;
static float listener_gain = 1.0f;
static uint64_t fake_now_ms = 10000;

static int fake_clock_gettime(clockid_t clock, struct timespec* now) {
    (void)clock;
    now->tv_sec = fake_now_ms / 1000;
    now->tv_nsec = (fake_now_ms % 1000) * 1000000;
    return 0;
}

static void* fake_context(void) { return context_available ? &context_token : NULL; }
static void* fake_device(void* context) { (void)context; return &device_token; }
static void inject_error(int error) {
    alc_error = injected_error = error;
}
static int fake_error(void* device) {
    (void)device;
    ++error_reads;
    int error = alc_error;
    alc_error = ALC_NO_ERROR; /* alcGetError consumes the sticky error. */
    return error;
}
static void fake_integer(void* device, int param, int size, int* values) {
    (void)device;
    ++connection_reads;
    if (!query_ok || param != ALC_CONNECTED || size != 1) {
        inject_error(0xA003); /* ALC_INVALID_ENUM */
        return;
    }
    *values = connected;
}
static void fake_pause(void* device) {
    (void)device;
    ++pause_calls;
    output_running = false;
    if (!pause_ok) inject_error(0xA001);
}
static char fake_reset(void* device, const int* attrs) {
    (void)device; (void)attrs;
    ++reset_calls;
    connected = reset_ok;
    if (!reset_ok || reset_error) inject_error(0xA001); /* ALC_INVALID_DEVICE */
    return reset_ok;
}
static void fake_resume(void* device) {
    (void)device;
    ++resume_calls;
    if (resume_disconnect_only) {
        connected = output_running = false;
    } else if (connected && resume_ok) {
        output_running = true;
    } else {
        /* OpenAL Soft's resume sets this error and disconnects on start failure. */
        connected = output_running = false;
        inject_error(0xA001);
    }
}
static void fake_process(void* context) { (void)context; }
static void fake_gain(int param, float value) { (void)param; listener_gain = value; }
static void fake_get_gain(int param, float* value) { (void)param; *value = listener_gain; }

static void initialize(void) {
    /* Scope starts after symbol resolution, before the first recovery request. */
    pojav_openal_resolved = true;
    pojav_alcGetCurrentContext = fake_context;
    pojav_alcGetContextsDevice = fake_device;
    pojav_alcGetError = fake_error;
    pojav_alcGetIntegerv = fake_integer;
    pojav_alcDevicePauseSOFT = fake_pause;
    pojav_alcResetDeviceSOFT = fake_reset;
    pojav_alcDeviceResumeSOFT = fake_resume;
    pojav_alcProcessContext = fake_process;
    pojav_alListenerf = fake_gain;
    pojav_alGetListenerf = fake_get_gain;
}

static void request_recovery(void) {
    Java_org_lwjgl_glfw_CallbackBridge_nativeRequestAudioRecovery(NULL, NULL);
}

static int poll_recovery(void) {
    return Java_org_lwjgl_glfw_CallbackBridge_nativeRecoverAudioOutput(NULL, NULL);
}

static void request_health(bool renew) {
    Java_org_lwjgl_glfw_CallbackBridge_nativeRequestAudioHealthCheck(NULL, NULL, renew);
}

static void game_audio_tick(void) {
    // The same native guard used by LwjglApplication's context-owning loop.
    if (Java_org_lwjgl_glfw_CallbackBridge_nativeHasQueuedAudioCommands(NULL, NULL)) {
        Java_org_lwjgl_glfw_CallbackBridge_nativeProcessQueuedAudioCommands(NULL, NULL);
    }
}

static void periodic_health_tick(uint64_t advance_ms) {
    fake_now_ms += advance_ms;
    request_health(false);
    game_audio_tick();
}

static void report(const char* phase, int result) {
    printf("{\"phase\":\"%s\",\"reported_success\":%d,\"connected\":%d,"
           "\"running\":%d,\"alc_error\":%d,\"injected_error\":%d,"
           "\"error_reads\":%d,\"connection_reads\":%d,\"pause_calls\":%d,"
           "\"reset_calls\":%d,\"resume_calls\":%d,"
           "\"queued\":%d,\"requested_generation\":%d,\"completed_generation\":%d,"
            "\"reported_generation\":%d,\"health_pending\":%d,\"health_attempts\":%d,"
            "\"next_retry_ms\":%llu,\"muted\":%d,\"listener_gain\":%.2f}\n",
           phase, result, connected, output_running, alc_error, injected_error,
           error_reads, connection_reads, pause_calls, reset_calls,
           resume_calls, pojav_audio_command_count,
           atomic_load(&pojav_audio_recovery_requested_generation),
           atomic_load(&pojav_audio_recovery_completed_generation),
            atomic_load(&pojav_audio_recovery_reported_generation),
            atomic_load(&pojav_audio_health_check_pending), pojav_audio_health_retry_attempts,
            (unsigned long long)pojav_audio_health_next_retry_ms, pojav_audio_force_muted, listener_gain);
}

static void* concurrent_request(void* unused) {
    (void)unused;
    request_recovery();
    return NULL;
}

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    initialize();
    const char* scenario = argv[1];

    if (strcmp(scenario, "healthy") == 0 ||
        strcmp(scenario, "reset_failed") == 0 ||
        strcmp(scenario, "resume_failed") == 0 ||
        strcmp(scenario, "pause_failed") == 0 ||
        strcmp(scenario, "reset_error") == 0 ||
        strcmp(scenario, "resume_disconnected") == 0 ||
        strcmp(scenario, "query_failed") == 0 ||
        strcmp(scenario, "prior_error") == 0 ||
        strcmp(scenario, "missing_reset") == 0 ||
        strcmp(scenario, "missing_resume") == 0 ||
        strcmp(scenario, "missing_error") == 0 ||
        strcmp(scenario, "missing_query") == 0) {
        reset_ok = strcmp(scenario, "reset_failed") != 0;
        resume_ok = strcmp(scenario, "resume_failed") != 0;
        pause_ok = strcmp(scenario, "pause_failed") != 0;
        query_ok = strcmp(scenario, "query_failed") != 0;
        reset_error = strcmp(scenario, "reset_error") == 0;
        resume_disconnect_only = strcmp(scenario, "resume_disconnected") == 0;
        if (strcmp(scenario, "prior_error") == 0) inject_error(0xA001);
        if (strcmp(scenario, "missing_reset") == 0) pojav_alcResetDeviceSOFT = NULL;
        if (strcmp(scenario, "missing_resume") == 0) pojav_alcDeviceResumeSOFT = NULL;
        if (strcmp(scenario, "missing_error") == 0) pojav_alcGetError = NULL;
        if (strcmp(scenario, "missing_query") == 0) pojav_alcGetIntegerv = NULL;
        request_recovery();
        processQueuedAudioCommandsOnCurrentThread();
        report(scenario, poll_recovery());
    } else if (strcmp(scenario, "context_delayed") == 0) {
        context_available = false;
        request_recovery();
        processQueuedAudioCommandsOnCurrentThread();
        report("no_context", poll_recovery());
        context_available = true;
        processQueuedAudioCommandsOnCurrentThread();
        report("context_returned", poll_recovery());
    } else if (strcmp(scenario, "duplicate_pending") == 0) {
        request_recovery();
        request_recovery();
        report("pending", poll_recovery());
        processQueuedAudioCommandsOnCurrentThread();
        report("completed", poll_recovery());
    } else if (strcmp(scenario, "result_consumed_once") == 0) {
        request_recovery();
        processQueuedAudioCommandsOnCurrentThread();
        report("first_poll", poll_recovery());
        report("second_poll", poll_recovery());
    } else if (strcmp(scenario, "stale_success") == 0 ||
               strcmp(scenario, "latest_success") == 0) {
        request_recovery();
        processQueuedAudioCommandsOnCurrentThread(); /* Success not polled yet. */
        /* Another interruption, followed by request -> immediate poll as in
         * GameSessionCoordinator.requestForegroundAudioRecovery(). */
        connected = output_running = false;
        reset_ok = strcmp(scenario, "latest_success") == 0;
        request_recovery();
        report("new_request_pending", poll_recovery());
        processQueuedAudioCommandsOnCurrentThread();
        report("new_request_completed", poll_recovery());
    } else if (strcmp(scenario, "failure_then_retry") == 0) {
        resume_ok = false;
        request_recovery();
        processQueuedAudioCommandsOnCurrentThread();
        report("resume_failed", poll_recovery());
        resume_ok = true;
        request_recovery();
        processQueuedAudioCommandsOnCurrentThread();
        report("retry_success", poll_recovery());
    } else if (strcmp(scenario, "concurrent_requests") == 0) {
        pthread_t threads[8];
        for (int i = 0; i < 8; ++i) {
            if (pthread_create(&threads[i], NULL, concurrent_request, NULL) != 0) return 3;
        }
        for (int i = 0; i < 8; ++i) pthread_join(threads[i], NULL);
        report("pending", poll_recovery());
        processQueuedAudioCommandsOnCurrentThread();
        report("completed", poll_recovery());
    } else if (strcmp(scenario, "queue_full") == 0) {
        PojavAudioCommand command = {.type = AUDIO_COMMAND_SET_MUTED, .muted = false};
        for (int i = 0; i < AUDIO_COMMAND_QUEUE_SIZE; ++i) {
            if (!enqueueAudioCommand(command)) return 3;
        }
        request_recovery();
        report("queue_full", poll_recovery());
        processQueuedAudioCommandsOnCurrentThread();
        request_recovery();
        processQueuedAudioCommandsOnCurrentThread();
        report("retry_success", poll_recovery());
    } else if (strcmp(scenario, "focus_only_disconnect") == 0) {
        listener_gain = 0.65f;
        applyAudioMutedOnCurrentThread(true);
        request_health(true); /* Window focus regained; no Activity/route request exists. */
        report("focus_gained_pending", poll_recovery());
        game_audio_tick();
        report("focus_recovered", poll_recovery());
    } else if (strcmp(scenario, "healthy_focus") == 0) {
        connected = output_running = true;
        request_health(true);
        game_audio_tick();
        for (int i = 0; i < 100; ++i) periodic_health_tick(1000);
        report("healthy_no_reset", poll_recovery());
    } else if (strcmp(scenario, "late_disconnect") == 0 ||
               strcmp(scenario, "disconnect_after_recovery") == 0) {
        connected = output_running = true;
        request_health(true);
        game_audio_tick();
        if (strcmp(scenario, "disconnect_after_recovery") == 0) {
            connected = output_running = false;
            periodic_health_tick(1000);
        }
        report("before_late_disconnect", poll_recovery());
        connected = output_running = false;
        for (int i = 0; i < 100; ++i) game_audio_tick();
        report("idle_no_poll", poll_recovery());
        periodic_health_tick(1000);
        report("late_disconnect_recovered", poll_recovery());
    } else if (strcmp(scenario, "health_retry_exhausted") == 0) {
        resume_ok = false;
        request_health(true);
        game_audio_tick();
        report("first_failed", poll_recovery());
        for (int i = 0; i < 100; ++i) periodic_health_tick(1000);
        report("exhausted", poll_recovery());
        resume_ok = true;
        for (int i = 0; i < 10; ++i) periodic_health_tick(1000);
        report("periodic_does_not_renew", poll_recovery());
        request_health(true); /* New window-focus/audio-mode/foreground event. */
        game_audio_tick();
        report("renewed_recovered", poll_recovery());
    } else if (strcmp(scenario, "health_backoff") == 0) {
        resume_ok = false;
        request_health(true);
        game_audio_tick();
        report("first", poll_recovery());
        periodic_health_tick(249);
        report("before_250ms", poll_recovery());
        periodic_health_tick(1);
        report("second", poll_recovery());
        periodic_health_tick(999);
        report("before_1000ms", poll_recovery());
        periodic_health_tick(1);
        report("third", poll_recovery());
        periodic_health_tick(1999);
        report("before_2000ms", poll_recovery());
        periodic_health_tick(1);
        report("fourth", poll_recovery());
    } else if (strcmp(scenario, "health_context_delayed") == 0) {
        context_available = false;
        request_health(true);
        game_audio_tick();
        report("no_context", poll_recovery());
        context_available = true;
        game_audio_tick();
        report("context_returned", poll_recovery());
    } else if (strcmp(scenario, "health_background") == 0) {
        request_health(true);
        request_recovery();
        applyAudioMutedOnCurrentThread(true);
        atomic_store(&host_environ.runtimeForeground, false);
        game_audio_tick();
        periodic_health_tick(1000);
        report("background", poll_recovery());
        atomic_store(&host_environ.runtimeForeground, true);
        request_health(true);
        game_audio_tick();
        report("foreground", poll_recovery());
    } else if (strcmp(scenario, "health_coalesced") == 0) {
        for (int i = 0; i < 100; ++i) request_health(i == 0);
        report("coalesced", poll_recovery());
        game_audio_tick();
        report("recovered", poll_recovery());
    } else if (strcmp(scenario, "health_connected_but_paused") == 0) {
        reset_error = true;
        request_health(true);
        game_audio_tick();
        report("connected_paused", poll_recovery());
        reset_error = false;
        periodic_health_tick(1000);
        report("retry_started", poll_recovery());
    } else if (strcmp(scenario, "health_query_failed") == 0) {
        query_ok = false;
        request_health(true);
        game_audio_tick();
        report("query_failed", poll_recovery());
    } else if (strcmp(scenario, "health_explicit_coalesced") == 0) {
        request_health(true);
        request_recovery();
        game_audio_tick();
        report("single_recovery", poll_recovery());
    } else {
        fprintf(stderr, "Unknown scenario: %s\n", scenario);
        return 2;
    }
    return 0;
}
