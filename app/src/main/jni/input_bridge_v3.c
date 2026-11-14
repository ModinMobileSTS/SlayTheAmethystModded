/*
 * V3 input bridge implementation.
 *
 * Status:
 * - Active development
 * - Works with some bugs:
 *  + Modded versions gives broken stuff..
 *
 *
 * - Implements glfwSetCursorPos() to handle grab camera pos correctly.
 */

#include <assert.h>
#include <dlfcn.h>
#include <jni.h>
#include <libgen.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <stdatomic.h>
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <time.h>
#include <limits.h>

#define TAG __FILE_NAME__
#include "utils.h"
#include "environ/environ.h"
#include "jvm_hooks/jvm_hooks.h"
#include "ctxbridges/gl_bridge.h"
#include "ctxbridges/swappy_bridge.h"

#define EVENT_TYPE_CHAR 1000
#define EVENT_TYPE_CHAR_MODS 1001
#define EVENT_TYPE_CURSOR_ENTER 1002
#define EVENT_TYPE_KEY 1005
#define EVENT_TYPE_MOUSE_BUTTON 1006
#define EVENT_TYPE_SCROLL 1007

/* Keep synchronization and buffer metadata outside the shared environment ABI. */
static pthread_mutex_t input_event_lock = PTHREAD_MUTEX_INITIALIZER;
static GLFWInputEvent input_event_snapshot[EVENT_WINDOW_SIZE];
static size_t input_snapshot_count;
static bool input_queue_full_logged;
static jlong key_buffer_capacity;
static jlong mouse_buffer_capacity;

#define TRY_ATTACH_ENV(env_name, vm, error_message, then) JNIEnv* env_name;\
do {                                                                       \
    env_name = get_attached_env(vm);                                       \
    if(env_name == NULL) {                                                 \
        printf(error_message);                                             \
        then                                                               \
    }                                                                      \
} while(0)

#define AL_GAIN 0x100A
#define ALC_NO_ERROR 0
#define ALC_CONNECTED 0x313
#define MIN_VALID_SCREEN_SIZE 1
#define MAX_VALID_SCREEN_SIZE 32768
#define AUDIO_ROUTE_LOG_PREFIX "[amethyst-audio-route-native]"
#define AUDIO_COMMAND_QUEUE_SIZE 64
#define AUDIO_COMMAND_RECOVER_OUTPUT 2
#define AUDIO_MUTE_PENDING 1
#define AUDIO_MUTE_REQUESTED 2
#define AUDIO_HEALTH_CHECK_PENDING 1
#define AUDIO_HEALTH_RENEW_BUDGET 2
#define AUDIO_HEALTH_ROUTE_CHANGED 4
#define AUDIO_HEALTH_MAX_RETRIES 4

static bool isValidScreenSize(int width, int height) {
    return width >= MIN_VALID_SCREEN_SIZE &&
           height >= MIN_VALID_SCREEN_SIZE &&
           width <= MAX_VALID_SCREEN_SIZE &&
           height <= MAX_VALID_SCREEN_SIZE;
}

typedef void* (*POJAV_alcGetCurrentContext_fn)(void);
typedef void* (*POJAV_alcGetContextsDevice_fn)(void* context);
typedef int (*POJAV_alcGetError_fn)(void* device);
typedef void (*POJAV_alcGetIntegerv_fn)(void* device, int param, int size, int* values);
typedef char (*POJAV_alcResetDeviceSOFT_fn)(void* device, const int* attrlist);
typedef void (*POJAV_alcDevicePauseSOFT_fn)(void* device);
typedef void (*POJAV_alcDeviceResumeSOFT_fn)(void* device);
typedef void (*POJAV_alcProcessContext_fn)(void* context);
typedef void (*POJAV_alListenerf_fn)(int param, float value);
typedef void (*POJAV_alGetListenerf_fn)(int param, float* value);

typedef struct {
    int type;
    int recoveryGeneration;
    bool healthRecovery;
} PojavAudioCommand;

static POJAV_alcGetCurrentContext_fn pojav_alcGetCurrentContext = NULL;
static POJAV_alcGetContextsDevice_fn pojav_alcGetContextsDevice = NULL;
static POJAV_alcGetError_fn pojav_alcGetError = NULL;
static POJAV_alcGetIntegerv_fn pojav_alcGetIntegerv = NULL;
static POJAV_alcResetDeviceSOFT_fn pojav_alcResetDeviceSOFT = NULL;
static POJAV_alcDevicePauseSOFT_fn pojav_alcDevicePauseSOFT = NULL;
static POJAV_alcDeviceResumeSOFT_fn pojav_alcDeviceResumeSOFT = NULL;
static POJAV_alcProcessContext_fn pojav_alcProcessContext = NULL;
static POJAV_alListenerf_fn pojav_alListenerf = NULL;
static POJAV_alGetListenerf_fn pojav_alGetListenerf = NULL;
static void* pojav_openal_handle = NULL;
static bool pojav_openal_resolved = false;
static bool pojav_openal_no_context_logged = false;
static bool pojav_openal_symbols_unavailable_logged = false;
static bool pojav_openal_recovery_failed_logged = false;
static bool pojav_audio_force_muted = false;
static float pojav_saved_listener_gain = 1.0f;
static bool pojav_saved_listener_gain_valid = false;
static pthread_mutex_t pojav_audio_command_lock = PTHREAD_MUTEX_INITIALIZER;
static PojavAudioCommand pojav_audio_command_queue[AUDIO_COMMAND_QUEUE_SIZE];
static int pojav_audio_command_head = 0;
static int pojav_audio_command_tail = 0;
static int pojav_audio_command_count = 0;
static atomic_bool pojav_audio_command_pending = false;
// Latest desired gain state, independent of the bounded recovery command ring.
// Consuming PENDING leaves REQUESTED intact so recovery cannot override a later mute.
static atomic_int pojav_audio_mute_request = 0;
static bool pojav_audio_command_context_unavailable_logged = false;
static bool pojav_audio_command_queue_full_logged = false;
static atomic_int pojav_audio_recovery_requested_generation = 0;
static atomic_int pojav_audio_recovery_completed_generation = 0;
static atomic_int pojav_audio_recovery_reported_generation = 0;
static atomic_bool pojav_audio_recovery_last_result = false;
// Serialize request generations and their result as one coherent snapshot.
static pthread_mutex_t pojav_audio_recovery_lock = PTHREAD_MUTEX_INITIALIZER;
// Coalesced separately from the command ring: an idle game must still check for disconnects.
static atomic_int pojav_audio_health_check_pending = 0;
// Bridge readiness is not OpenAL readiness: 0=initializing, 1=ready, 2=no device, 3=disabled/failed.
static atomic_int pojav_audio_runtime_state = 0;
static atomic_bool pojav_audio_recovery_allowed = true;
// Independent diagnostics: readers never acquire an OpenAL, command or recovery mutex.
static atomic_uint_fast64_t pojav_audio_operation_snapshot = 0;
static uint64_t pojav_audio_operation_sequence = 0; // game-thread owned

static void audioOperationStage(int stage) {
    atomic_store_explicit(&pojav_audio_operation_snapshot,
                          (++pojav_audio_operation_sequence << 8) | (unsigned)stage,
                          memory_order_release);
    if (stage != 0 && stage < 20) {
        printf("%s event=operation_begin stage=%d thread=%lu\n", AUDIO_ROUTE_LOG_PREFIX,
               stage, (unsigned long)pthread_self());
        fflush(stdout);
    }
}

static void audioOperationEnd(void) {
    uint64_t previous = atomic_load_explicit(&pojav_audio_operation_snapshot, memory_order_acquire);
    if ((previous & 255) != 0 && (previous & 255) < 20) {
        printf("%s event=operation_end stage=%d thread=%lu\n", AUDIO_ROUTE_LOG_PREFIX,
               (int)(previous & 255), (unsigned long)pthread_self());
        fflush(stdout);
    }
    audioOperationStage(0);
}
// The following health/retry state is owned exclusively by the OpenAL/game thread.
static bool pojav_audio_health_recovery_needed = false;
static bool pojav_audio_health_exhausted_logged = false;
static bool pojav_audio_health_query_failed_logged = false;
static int pojav_audio_health_retry_attempts = 0;
static uint64_t pojav_audio_health_next_retry_ms = 0;

static uint64_t audioMonotonicMillis(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (uint64_t) now.tv_sec * 1000 + (uint64_t) now.tv_nsec / 1000000;
}

static bool audioHealthChecksAllowed(void) {
    return pojav_environ != NULL &&
           atomic_load_explicit(&pojav_environ->runtimeForeground, memory_order_acquire) &&
           atomic_load_explicit(&pojav_audio_recovery_allowed, memory_order_acquire);
}

static void resolveOpenalSymbolsFromHandle(void* handle) {
    if (handle == NULL) {
        return;
    }
    if (pojav_alcGetCurrentContext == NULL) {
        pojav_alcGetCurrentContext = (POJAV_alcGetCurrentContext_fn) dlsym(handle, "alcGetCurrentContext");
    }
    if (pojav_alcGetContextsDevice == NULL) {
        pojav_alcGetContextsDevice = (POJAV_alcGetContextsDevice_fn) dlsym(handle, "alcGetContextsDevice");
    }
    if (pojav_alcGetError == NULL) {
        pojav_alcGetError = (POJAV_alcGetError_fn) dlsym(handle, "alcGetError");
    }
    if (pojav_alcGetIntegerv == NULL) {
        pojav_alcGetIntegerv = (POJAV_alcGetIntegerv_fn) dlsym(handle, "alcGetIntegerv");
    }
    if (pojav_alcResetDeviceSOFT == NULL) {
        pojav_alcResetDeviceSOFT = (POJAV_alcResetDeviceSOFT_fn) dlsym(handle, "alcResetDeviceSOFT");
    }
    if (pojav_alcDevicePauseSOFT == NULL) {
        pojav_alcDevicePauseSOFT = (POJAV_alcDevicePauseSOFT_fn) dlsym(handle, "alcDevicePauseSOFT");
    }
    if (pojav_alcDeviceResumeSOFT == NULL) {
        pojav_alcDeviceResumeSOFT = (POJAV_alcDeviceResumeSOFT_fn) dlsym(handle, "alcDeviceResumeSOFT");
    }
    if (pojav_alcProcessContext == NULL) {
        pojav_alcProcessContext = (POJAV_alcProcessContext_fn) dlsym(handle, "alcProcessContext");
    }
    if (pojav_alListenerf == NULL) {
        pojav_alListenerf = (POJAV_alListenerf_fn) dlsym(handle, "alListenerf");
    }
    if (pojav_alGetListenerf == NULL) {
        pojav_alGetListenerf = (POJAV_alGetListenerf_fn) dlsym(handle, "alGetListenerf");
    }
}

static bool resolveOpenalSymbols(void) {
    if (pojav_openal_resolved) {
        return true;
    }
    resolveOpenalSymbolsFromHandle(RTLD_DEFAULT);

    if (pojav_alcGetCurrentContext == NULL ||
        pojav_alcGetContextsDevice == NULL ||
        pojav_alcGetError == NULL ||
        pojav_alcGetIntegerv == NULL ||
        pojav_alcResetDeviceSOFT == NULL ||
        pojav_alcDevicePauseSOFT == NULL ||
        pojav_alcDeviceResumeSOFT == NULL ||
        pojav_alcProcessContext == NULL ||
        pojav_alListenerf == NULL ||
        pojav_alGetListenerf == NULL) {
        if (pojav_openal_handle == NULL) {
            pojav_openal_handle = dlopen("libopenal.so", RTLD_NOW | RTLD_GLOBAL);
        }
        resolveOpenalSymbolsFromHandle(pojav_openal_handle);
    }

    if (pojav_alcGetCurrentContext == NULL || pojav_alListenerf == NULL) {
        if (!pojav_openal_symbols_unavailable_logged) {
            printf("%s event=openal_symbols_unavailable\n", AUDIO_ROUTE_LOG_PREFIX);
            fflush(stdout);
            pojav_openal_symbols_unavailable_logged = true;
        }
        return false;
    }

    pojav_openal_resolved = true;
    return true;
}

static bool enqueueAudioCommand(PojavAudioCommand command) {
    bool queued = false;
    pthread_mutex_lock(&pojav_audio_command_lock);
    if (pojav_audio_command_count < AUDIO_COMMAND_QUEUE_SIZE) {
        pojav_audio_command_queue[pojav_audio_command_tail] = command;
        pojav_audio_command_tail = (pojav_audio_command_tail + 1) % AUDIO_COMMAND_QUEUE_SIZE;
        pojav_audio_command_count++;
        atomic_store_explicit(&pojav_audio_command_pending, true, memory_order_release);
        queued = true;
    }
    pthread_mutex_unlock(&pojav_audio_command_lock);
    return queued;
}

static bool dequeueAudioCommand(PojavAudioCommand* command) {
    bool hasCommand = false;
    pthread_mutex_lock(&pojav_audio_command_lock);
    if (pojav_audio_command_count > 0) {
        *command = pojav_audio_command_queue[pojav_audio_command_head];
        pojav_audio_command_head = (pojav_audio_command_head + 1) % AUDIO_COMMAND_QUEUE_SIZE;
        pojav_audio_command_count--;
        if (pojav_audio_command_count == 0) {
            atomic_store_explicit(&pojav_audio_command_pending, false, memory_order_release);
        }
        hasCommand = true;
    }
    pthread_mutex_unlock(&pojav_audio_command_lock);
    return hasCommand;
}

// Caller holds pojav_audio_recovery_lock.
static void completeAudioRecoveryCommandLocked(int generation, bool result) {
    int completedGeneration = atomic_load_explicit(
            &pojav_audio_recovery_completed_generation,
            memory_order_acquire
    );
    if (generation >= completedGeneration) {
        atomic_store_explicit(&pojav_audio_recovery_last_result, result, memory_order_release);
        atomic_store_explicit(
                &pojav_audio_recovery_completed_generation,
                generation,
                memory_order_release
        );
    }
}

static void completeAudioRecoveryCommand(int generation, bool result) {
    pthread_mutex_lock(&pojav_audio_recovery_lock);
    completeAudioRecoveryCommandLocked(generation, result);
    pthread_mutex_unlock(&pojav_audio_recovery_lock);
}

static bool queueAudioRecoveryCommandWithTrigger(bool healthRecovery) {
    pthread_mutex_lock(&pojav_audio_recovery_lock);
    int requestedGeneration = atomic_load_explicit(
            &pojav_audio_recovery_requested_generation,
            memory_order_acquire
    );
    int completedGeneration = atomic_load_explicit(
            &pojav_audio_recovery_completed_generation,
            memory_order_acquire
    );
    if (requestedGeneration > completedGeneration) {
        pthread_mutex_unlock(&pojav_audio_recovery_lock);
        return true;
    }
    int generation = atomic_fetch_add_explicit(
            &pojav_audio_recovery_requested_generation,
            1,
            memory_order_acq_rel
    ) + 1;
    PojavAudioCommand command = {
            .type = AUDIO_COMMAND_RECOVER_OUTPUT,
            .recoveryGeneration = generation,
            .healthRecovery = healthRecovery
    };
    if (!enqueueAudioCommand(command)) {
        if (!pojav_audio_command_queue_full_logged) {
            printf("%s event=audio_command_queue_full\n", AUDIO_ROUTE_LOG_PREFIX);
            fflush(stdout);
            pojav_audio_command_queue_full_logged = true;
        }
        completeAudioRecoveryCommandLocked(generation, false);
        pthread_mutex_unlock(&pojav_audio_recovery_lock);
        return false;
    }
    printf("%s event=audio_recovery_requested generation=%d trigger=%s\n",
           AUDIO_ROUTE_LOG_PREFIX, generation, healthRecovery ? "health_check" : "explicit");
    fflush(stdout);
    pthread_mutex_unlock(&pojav_audio_recovery_lock);
    return true;
}

static bool queueAudioRecoveryCommand(void) {
    return queueAudioRecoveryCommandWithTrigger(false);
}

static void logNoOpenalContextOnce(void) {
    if (pojav_openal_no_context_logged) {
        return;
    }
    printf("%s event=openal_context_unavailable\n", AUDIO_ROUTE_LOG_PREFIX);
    fflush(stdout);
    pojav_openal_no_context_logged = true;
}

static void applyAudioMutedOnCurrentThread(bool muted) {
    audioOperationStage(11); // mute symbol/context lookup, separate from gain reads/writes
    bool hasContext = resolveOpenalSymbols() && pojav_alcGetCurrentContext() != NULL;
    audioOperationEnd();
    if (!hasContext) {
        logNoOpenalContextOnce();
        return;
    }
    pojav_openal_no_context_logged = false;

    if (muted) {
        if (pojav_audio_force_muted) {
            return;
        }
        if (pojav_alGetListenerf != NULL) {
            float currentGain = 1.0f;
            audioOperationStage(1); // mute: read gain
            pojav_alGetListenerf(AL_GAIN, &currentGain);
            audioOperationEnd();
            if (currentGain >= 0.0f && currentGain <= 16.0f) {
                pojav_saved_listener_gain = currentGain;
                pojav_saved_listener_gain_valid = true;
            }
        }
        audioOperationStage(2); // mute: write gain
        pojav_alListenerf(AL_GAIN, 0.0f);
        audioOperationEnd();
        pojav_audio_force_muted = true;
        return;
    }

    if (!pojav_audio_force_muted) {
        return;
    }
    float restoredGain = pojav_saved_listener_gain_valid ? pojav_saved_listener_gain : 1.0f;
    audioOperationStage(2);
    pojav_alListenerf(AL_GAIN, restoredGain);
    audioOperationEnd();
    pojav_audio_force_muted = false;
}

static void applyRequestedAudioMutedOnCurrentThread(void) {
    int request = atomic_fetch_and_explicit(&pojav_audio_mute_request,
                                           ~AUDIO_MUTE_PENDING, memory_order_acq_rel);
    bool background = pojav_environ == NULL ||
            !atomic_load_explicit(&pojav_environ->runtimeForeground, memory_order_acquire);
    applyAudioMutedOnCurrentThread(background || (request & AUDIO_MUTE_REQUESTED));
}

static bool failAudioOutputRecovery(const char* stage, int error) {
    if (!pojav_openal_recovery_failed_logged) {
        printf("%s event=openal_device_recovery_failed stage=%s alc_error=0x%x\n",
               AUDIO_ROUTE_LOG_PREFIX, stage, error);
        fflush(stdout);
        pojav_openal_recovery_failed_logged = true;
    }
    return false;
}

static bool recoverAudioOutputOnCurrentThread(void) {
    if (!resolveOpenalSymbols()) {
        return false;
    }

    // Never pause the device unless we can reset, resume and verify it.
    if (pojav_alcGetCurrentContext == NULL || pojav_alcGetContextsDevice == NULL ||
        pojav_alcResetDeviceSOFT == NULL || pojav_alcDevicePauseSOFT == NULL ||
        pojav_alcDeviceResumeSOFT == NULL || pojav_alcGetError == NULL ||
        pojav_alcGetIntegerv == NULL) {
        return failAudioOutputRecovery("symbols", ALC_NO_ERROR);
    }

    audioOperationStage(3);
    void* currentContext = pojav_alcGetCurrentContext();
    audioOperationEnd();
    if (currentContext == NULL) {
        logNoOpenalContextOnce();
        return false;
    }

    audioOperationStage(4);
    void* device = pojav_alcGetContextsDevice(currentContext);
    audioOperationEnd();
    if (device == NULL) {
        return false;
    }

    // ALC errors are sticky. Discard errors from before this recovery attempt.
    audioOperationStage(5);
    pojav_alcGetError(device);
    audioOperationEnd();
    audioOperationStage(6);
    pojav_alcDevicePauseSOFT(device);
    audioOperationEnd();
    audioOperationStage(5);
    int error = pojav_alcGetError(device);
    audioOperationEnd();
    if (error != ALC_NO_ERROR) {
        return failAudioOutputRecovery("pause", error);
    }

    audioOperationStage(7);
    bool resetSucceeded = pojav_alcResetDeviceSOFT(device, NULL) != 0;
    audioOperationEnd();
    audioOperationStage(5);
    error = pojav_alcGetError(device);
    audioOperationEnd();
    if (!resetSucceeded || error != ALC_NO_ERROR) {
        return failAudioOutputRecovery("reset", error);
    }

    // Resetting a paused device doesn't start its backend. Resume can still fail.
    audioOperationStage(8);
    pojav_alcDeviceResumeSOFT(device);
    audioOperationEnd();
    audioOperationStage(5);
    error = pojav_alcGetError(device);
    audioOperationEnd();
    if (error != ALC_NO_ERROR) {
        return failAudioOutputRecovery("resume", error);
    }
    if (pojav_alcProcessContext != NULL) {
        audioOperationStage(9);
        pojav_alcProcessContext(currentContext);
        audioOperationEnd();
        audioOperationStage(5);
        error = pojav_alcGetError(device);
        audioOperationEnd();
        if (error != ALC_NO_ERROR) {
            return failAudioOutputRecovery("process_context", error);
        }
    }

    int connected = 0;
    audioOperationStage(10);
    pojav_alcGetIntegerv(device, ALC_CONNECTED, 1, &connected);
    audioOperationEnd();
    audioOperationStage(5);
    error = pojav_alcGetError(device);
    audioOperationEnd();
    if (error != ALC_NO_ERROR) {
        return failAudioOutputRecovery("connection_check", error);
    }
    if (!connected) {
        return failAudioOutputRecovery("disconnected", ALC_NO_ERROR);
    }
    pojav_openal_no_context_logged = false;
    pojav_openal_recovery_failed_logged = false;
    return true;
}

static void processAudioHealthCheckOnCurrentThread(int flags) {
    if (!flags || !audioHealthChecksAllowed()) {
        return;
    }
    if (pojav_alcGetContextsDevice == NULL || pojav_alcGetIntegerv == NULL || pojav_alcGetError == NULL) {
        return;
    }
    audioOperationStage(20); // health query includes context/device/error/connected (may lock in OpenAL)
    void* device = pojav_alcGetContextsDevice(pojav_alcGetCurrentContext());
    audioOperationEnd();
    if (device == NULL) return;
    // Isolate this query from pre-existing sticky errors, just like recovery itself.
    audioOperationStage(20);
    pojav_alcGetError(device);
    int connected = 0;
    pojav_alcGetIntegerv(device, ALC_CONNECTED, 1, &connected);
    int error = pojav_alcGetError(device);
    audioOperationEnd();
    if (error != ALC_NO_ERROR) {
        if (!pojav_audio_health_query_failed_logged) {
            printf("%s event=audio_health_query_failed alc_error=0x%x\n", AUDIO_ROUTE_LOG_PREFIX, error);
            fflush(stdout);
            pojav_audio_health_query_failed_logged = true;
        }
        return;
    }
    pojav_audio_health_query_failed_logged = false;
    // A failed reset can leave a connected-but-paused backend. Only a completed recovery
    // clears recovery_needed; ALC_CONNECTED alone must not abandon that retry window.
    if (connected && !pojav_audio_health_recovery_needed) return;
    if (!pojav_audio_health_recovery_needed) {
        printf("%s event=audio_health_disconnected connected=%d muted=%d requested=%d completed=%d\n",
               AUDIO_ROUTE_LOG_PREFIX, connected, pojav_audio_force_muted,
               atomic_load(&pojav_audio_recovery_requested_generation),
               atomic_load(&pojav_audio_recovery_completed_generation));
        fflush(stdout);
        pojav_audio_health_recovery_needed = true;
    }
    if (pojav_audio_health_retry_attempts >= AUDIO_HEALTH_MAX_RETRIES) {
        if (!pojav_audio_health_exhausted_logged) {
            printf("%s event=audio_health_retry_exhausted attempts=%d connected=%d muted=%d\n",
                   AUDIO_ROUTE_LOG_PREFIX, pojav_audio_health_retry_attempts,
                   connected, pojav_audio_force_muted);
            fflush(stdout);
            pojav_audio_health_exhausted_logged = true;
        }
        return;
    }
    if (audioMonotonicMillis() < pojav_audio_health_next_retry_ms) return;
    queueAudioRecoveryCommandWithTrigger(true);
}

static void drainAudioCommandsOnCurrentThread(void) {
    PojavAudioCommand command;
    while (dequeueAudioCommand(&command)) {
        if (command.type == AUDIO_COMMAND_RECOVER_OUTPUT) {
            // A request can outlive onStop/exit while waiting for the game thread.
            if (!audioHealthChecksAllowed()) {
                completeAudioRecoveryCommand(command.recoveryGeneration, false);
                printf("%s event=audio_recovery_skipped generation=%d reason=background\n",
                       AUDIO_ROUTE_LOG_PREFIX, command.recoveryGeneration);
                fflush(stdout);
                continue;
            }
            // Both explicit route recovery and health recovery belong to the same interruption.
            // Polls/focus callbacks cannot bypass the budget or its monotonic backoff.
            pojav_audio_health_recovery_needed = true;
            if (pojav_audio_health_retry_attempts >= AUDIO_HEALTH_MAX_RETRIES ||
                audioMonotonicMillis() < pojav_audio_health_next_retry_ms) {
                completeAudioRecoveryCommand(command.recoveryGeneration, false);
                printf("%s event=audio_recovery_skipped generation=%d reason=retry_budget_or_backoff\n",
                       AUDIO_ROUTE_LOG_PREFIX, command.recoveryGeneration);
                fflush(stdout);
                continue;
            }
            ++pojav_audio_health_retry_attempts;
            printf("%s event=audio_recovery_executing generation=%d trigger=%s attempt=%d muted=%d\n",
                   AUDIO_ROUTE_LOG_PREFIX, command.recoveryGeneration,
                   command.healthRecovery ? "health_check" : "explicit",
                   pojav_audio_health_retry_attempts, pojav_audio_force_muted);
            fflush(stdout);
            pojav_openal_recovery_failed_logged = false;
            bool recovered = recoverAudioOutputOnCurrentThread();
            completeAudioRecoveryCommand(command.recoveryGeneration, recovered);
            pojav_audio_health_recovery_needed = !recovered;
            if (recovered) {
                pojav_audio_health_retry_attempts = 0;
                pojav_audio_health_next_retry_ms = 0;
                pojav_audio_health_exhausted_logged = false;
                if (command.healthRecovery && audioHealthChecksAllowed()) {
                    applyRequestedAudioMutedOnCurrentThread();
                }
            } else {
                static const uint64_t retryDelaysMs[] = {250, 1000, 2000, 4000};
                pojav_audio_health_next_retry_ms = audioMonotonicMillis() +
                        retryDelaysMs[pojav_audio_health_retry_attempts - 1];
            }
            printf("%s event=audio_recovery_completed generation=%d success=%d muted=%d requested=%d completed=%d\n",
                   AUDIO_ROUTE_LOG_PREFIX, command.recoveryGeneration, recovered, pojav_audio_force_muted,
                   atomic_load(&pojav_audio_recovery_requested_generation),
                   atomic_load(&pojav_audio_recovery_completed_generation));
            fflush(stdout);
        }
    }
}

static void processQueuedAudioCommandsOnCurrentThread(void) {
    if (!audioHealthChecksAllowed()) {
        // Drop only the disposable poll. A route/budget event survives a pause or call.
        atomic_fetch_and_explicit(&pojav_audio_health_check_pending,
                                  ~AUDIO_HEALTH_CHECK_PENDING, memory_order_acq_rel);
    }
    if (!atomic_load_explicit(&pojav_audio_command_pending, memory_order_acquire) &&
        !(atomic_load_explicit(&pojav_audio_mute_request, memory_order_acquire) & AUDIO_MUTE_PENDING) &&
        !(audioHealthChecksAllowed() &&
          atomic_load_explicit(&pojav_audio_health_check_pending, memory_order_acquire))) {
        return;
    }
    if (atomic_load_explicit(&pojav_audio_runtime_state, memory_order_acquire) != 1) return;
    audioOperationStage(21); // symbol resolution / current-context guard
    bool hasContext = resolveOpenalSymbols() && pojav_alcGetCurrentContext != NULL &&
                      pojav_alcGetCurrentContext() != NULL;
    audioOperationEnd();
    if (!hasContext) {
        if (!pojav_audio_command_context_unavailable_logged) {
            printf("%s event=queued_audio_commands_skipped reason=no_current_context\n", AUDIO_ROUTE_LOG_PREFIX);
            fflush(stdout);
            pojav_audio_command_context_unavailable_logged = true;
        }
        return;
    }
    pojav_audio_command_context_unavailable_logged = false;
    if (atomic_load_explicit(&pojav_audio_mute_request, memory_order_acquire) & AUDIO_MUTE_PENDING) {
        applyRequestedAudioMutedOnCurrentThread();
    }
    int flags = 0;
    if (audioHealthChecksAllowed()) {
        flags = atomic_exchange_explicit(&pojav_audio_health_check_pending, 0, memory_order_acq_rel);
        // Transfer the interruption to game-thread state BEFORE any fallible query or recovery.
        // Query errors, a closing gate, backoff and queue saturation must not lose the route.
        if (flags & AUDIO_HEALTH_RENEW_BUDGET) {
            pojav_audio_health_retry_attempts = 0;
            pojav_audio_health_next_retry_ms = 0;
            pojav_audio_health_exhausted_logged = false;
        }
        if (flags & AUDIO_HEALTH_ROUTE_CHANGED) pojav_audio_health_recovery_needed = true;
    }
    drainAudioCommandsOnCurrentThread();
    processAudioHealthCheckOnCurrentThread(flags);
    // A disconnected idle device gets a new generation and executes on this context-owning thread.
    drainAudioCommandsOnCurrentThread();
    // A pause can arrive while an OpenAL recovery is blocked. Apply its latest gain on return.
    if (atomic_load_explicit(&pojav_audio_mute_request, memory_order_acquire) & AUDIO_MUTE_PENDING) {
        applyRequestedAudioMutedOnCurrentThread();
    }
}

static void registerFunctions(JNIEnv *env);

JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeHasQueuedAudioCommands(
        JNIEnv* env,
        jclass clazz
);
JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeProcessQueuedAudioCommands(
        JNIEnv* env,
        jclass clazz
);
JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetAudioRuntimeState(
        JNIEnv* env, jclass clazz, jint state);
JNIEXPORT jlong JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeGetAudioOperationSnapshot(
        JNIEnv* env, jclass clazz);

static const JNINativeMethod jvm_audio_bridge_fcns[] = {
        {"nativeGetAudioOperationSnapshot", "()J",
         Java_org_lwjgl_glfw_CallbackBridge_nativeGetAudioOperationSnapshot},
        {"nativeSetAudioRuntimeState", "(I)V",
         Java_org_lwjgl_glfw_CallbackBridge_nativeSetAudioRuntimeState},
        {
                "nativeHasQueuedAudioCommands",
                "()Z",
                Java_org_lwjgl_glfw_CallbackBridge_nativeHasQueuedAudioCommands
        },
        {
                "nativeProcessQueuedAudioCommands",
                "()V",
                Java_org_lwjgl_glfw_CallbackBridge_nativeProcessQueuedAudioCommands
        }
};

static bool registerJvmAudioBridgeFunctions(JNIEnv *env) {
    jclass bridgeClass = (*env)->FindClass(env, "org/lwjgl/glfw/CallbackBridge");
    if (bridgeClass == NULL) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        printf("%s event=game_jvm_audio_bridge_registration_failed reason=callback_bridge_not_found\n", AUDIO_ROUTE_LOG_PREFIX);
        fflush(stdout);
        return false;
    }

    jint registrationResult = (*env)->RegisterNatives(
            env,
            bridgeClass,
            jvm_audio_bridge_fcns,
            sizeof(jvm_audio_bridge_fcns) / sizeof(jvm_audio_bridge_fcns[0])
    );
    (*env)->DeleteLocalRef(env, bridgeClass);
    if (registrationResult != JNI_OK) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        printf(
                "%s event=game_jvm_audio_bridge_registration_failed reason=register_natives result=%d\n",
                AUDIO_ROUTE_LOG_PREFIX,
                registrationResult
        );
        fflush(stdout);
        return false;
    }

    return true;
}

static bool inputJniFailed(JNIEnv* env, const char* operation) {
    if (!(*env)->ExceptionCheck(env)) return false;
    fprintf(stderr, "input_bridge: JNI failure during %s\n", operation);
    (*env)->ExceptionDescribe(env);
    (*env)->ExceptionClear(env);
    return true;
}

static jclass inputGlobalClass(JNIEnv* env, const char* name) {
    jclass local = (*env)->FindClass(env, name);
    if (inputJniFailed(env, name) || local == NULL) return NULL;
    jclass global = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    inputJniFailed(env, "NewGlobalRef");
    return global;
}

static jmethodID inputStaticMethod(JNIEnv* env, jclass clazz, const char* name, const char* signature) {
    if (clazz == NULL) return NULL;
    jmethodID method = (*env)->GetStaticMethodID(env, clazz, name, signature);
    if (inputJniFailed(env, name)) return NULL;
    return method;
}

static void inputDirectBuffer(JNIEnv* env, jclass clazz, const char* name, jbyte** address, jlong* capacity) {
    *address = NULL;
    *capacity = 0;
    if (clazz == NULL) return;
    jfieldID field = (*env)->GetStaticFieldID(env, clazz, name, "Ljava/nio/ByteBuffer;");
    if (inputJniFailed(env, name) || field == NULL) return;
    jobject buffer = (*env)->GetStaticObjectField(env, clazz, field);
    if (inputJniFailed(env, name) || buffer == NULL) return;
    jlong size = (*env)->GetDirectBufferCapacity(env, buffer);
    void* bytes = (*env)->GetDirectBufferAddress(env, buffer);
    if (!inputJniFailed(env, name) && size > 0 && bytes != NULL) {
        *capacity = size;
        *address = bytes;
    }
    (*env)->DeleteLocalRef(env, buffer);
}

jint JNI_OnLoad(JavaVM* vm, __attribute__((unused)) void* reserved) {
    JNIEnv* loadEnv = NULL;
    if ((*vm)->GetEnv(vm, (void**)&loadEnv, JNI_VERSION_1_4) != JNI_OK || loadEnv == NULL) {
        fprintf(stderr, "input_bridge: JNI_OnLoad GetEnv failed\n");
        return JNI_VERSION_1_4;
    }
    if (pojav_environ->dalvikJavaVMPtr == NULL) {
        ;
        //Save dalvik global JavaVM pointer
        pojav_environ->dalvikJavaVMPtr = vm;
        JNIEnv *dvEnv = loadEnv;
        pojav_environ->bridgeClazz = inputGlobalClass(dvEnv, "org/lwjgl/glfw/CallbackBridge");
        pojav_environ->method_accessAndroidClipboard = inputStaticMethod(dvEnv, pojav_environ->bridgeClazz, "accessAndroidClipboard", "(ILjava/lang/String;)Ljava/lang/String;");
        pojav_environ->method_onGrabStateChanged = inputStaticMethod(dvEnv, pojav_environ->bridgeClazz, "onGrabStateChanged", "(Z)V");
        pojav_environ->method_onDirectInputEnable = inputStaticMethod(dvEnv, pojav_environ->bridgeClazz, "onDirectInputEnable", "()V");
        pojav_environ->isUseStackQueueCall = JNI_FALSE;
    } else if (pojav_environ->dalvikJavaVMPtr != vm) {
        ;
        pojav_environ->runtimeJavaVMPtr = vm;
        JNIEnv *vmEnv = loadEnv;
        registerJvmAudioBridgeFunctions(vmEnv);
        pojav_environ->vmGlfwClass = inputGlobalClass(vmEnv, "org/lwjgl/glfw/GLFW");
        pojav_environ->method_glftSetWindowAttrib = inputStaticMethod(vmEnv, pojav_environ->vmGlfwClass, "glfwSetWindowAttrib", "(JII)V");
        pojav_environ->method_glfwSetWindowShouldClose = inputStaticMethod(vmEnv, pojav_environ->vmGlfwClass, "glfwSetWindowShouldClose", "(JZ)V");
        pojav_environ->method_internalChangeMonitorSize = inputStaticMethod(vmEnv, pojav_environ->vmGlfwClass, "internalChangeMonitorSize", "(II)V");
        inputDirectBuffer(vmEnv, pojav_environ->vmGlfwClass, "keyDownBuffer", &pojav_environ->keyDownBuffer, &key_buffer_capacity);
        inputDirectBuffer(vmEnv, pojav_environ->vmGlfwClass, "mouseDownBuffer", &pojav_environ->mouseDownBuffer, &mouse_buffer_capacity);
        hookExec(vmEnv);
        installLwjglDlopenHook(vmEnv);
        installEMUIIteratorMititgation(vmEnv);
    }

    if(pojav_environ->dalvikJavaVMPtr == vm) {
        //perform in all DVM instances, not only during first ever set up
        registerFunctions(loadEnv);
    }
    pojav_environ->isGrabbing = JNI_FALSE;

    return JNI_VERSION_1_4;
}

#define ADD_CALLBACK_WWIN(NAME) \
JNIEXPORT jlong JNICALL Java_org_lwjgl_glfw_GLFW_nglfwSet##NAME##Callback(JNIEnv * env, jclass cls, jlong window, jlong callbackptr) { \
    GLFW_invoke_##NAME##_func* oldCallback = pojav_environ->GLFW_invoke_##NAME; \
    pojav_environ->GLFW_invoke_##NAME = (GLFW_invoke_##NAME##_func*) (uintptr_t) callbackptr; \
    return (jlong) (uintptr_t) oldCallback; \
}

ADD_CALLBACK_WWIN(Char)
ADD_CALLBACK_WWIN(CharMods)
ADD_CALLBACK_WWIN(CursorEnter)
ADD_CALLBACK_WWIN(CursorPos)
ADD_CALLBACK_WWIN(Key)
ADD_CALLBACK_WWIN(MouseButton)
ADD_CALLBACK_WWIN(Scroll)

#undef ADD_CALLBACK_WWIN

void updateMonitorSize(int width, int height) {
    if (!isValidScreenSize(width, height) || pojav_environ->glfwThreadVmEnv == NULL ||
        pojav_environ->vmGlfwClass == NULL || pojav_environ->method_internalChangeMonitorSize == NULL) {
        ;
        return;
    }
    (*pojav_environ->glfwThreadVmEnv)->CallStaticVoidMethod(pojav_environ->glfwThreadVmEnv, pojav_environ->vmGlfwClass, pojav_environ->method_internalChangeMonitorSize, width, height);
}

void pojavPumpEvents(void* window) {
    if(pojav_environ->shouldUpdateMouse && pojav_environ->GLFW_invoke_CursorPos) {
        pojav_environ->GLFW_invoke_CursorPos(window, floor(pojav_environ->cursorX),
                                             floor(pojav_environ->cursorY));
    }
    for (size_t index = 0; index < input_snapshot_count; index++) {
        GLFWInputEvent event = input_event_snapshot[index];
        switch (event.type) {
            case EVENT_TYPE_CHAR:
                if(pojav_environ->GLFW_invoke_Char) pojav_environ->GLFW_invoke_Char(window, event.i1);
                break;
            case EVENT_TYPE_CHAR_MODS:
                if(pojav_environ->GLFW_invoke_CharMods) pojav_environ->GLFW_invoke_CharMods(window, event.i1, event.i2);
                break;
            case EVENT_TYPE_CURSOR_ENTER:
                if(pojav_environ->GLFW_invoke_CursorEnter) pojav_environ->GLFW_invoke_CursorEnter(window, event.i1);
                break;
            case EVENT_TYPE_KEY:
                if(pojav_environ->GLFW_invoke_Key) pojav_environ->GLFW_invoke_Key(window, event.i1, event.i2, event.i3, event.i4);
                break;
            case EVENT_TYPE_MOUSE_BUTTON:
                if(pojav_environ->GLFW_invoke_MouseButton) pojav_environ->GLFW_invoke_MouseButton(window, event.i1, event.i2, event.i3);
                break;
            case EVENT_TYPE_SCROLL: {
                float x, y;
                memcpy(&x, &event.i1, sizeof(x));
                memcpy(&y, &event.i2, sizeof(y));
                if(pojav_environ->GLFW_invoke_Scroll) pojav_environ->GLFW_invoke_Scroll(window, (double)x, (double)y);
                break;
            }
        }
    }
}

/** Prepare the library for sending out callbacks to all windows */
void pojavStartPumping() {
    pthread_mutex_lock(&input_event_lock);
    input_snapshot_count = atomic_load_explicit(&pojav_environ->eventCounter, memory_order_relaxed);
    for (size_t i = 0; i < input_snapshot_count; i++) {
        input_event_snapshot[i] = pojav_environ->events[pojav_environ->outEventIndex];
        pojav_environ->outEventIndex = (pojav_environ->outEventIndex + 1) % EVENT_WINDOW_SIZE;
        atomic_fetch_sub_explicit(&pojav_environ->eventCounter, 1, memory_order_relaxed);
    }
    pojav_environ->inEventCount = input_snapshot_count;
    pojav_environ->outTargetIndex = pojav_environ->outEventIndex;
    pthread_mutex_unlock(&input_event_lock);

    //PumpEvents is called for every window, so this logic should be there in order to correctly distribute events to all windows.
    if((pojav_environ->cLastX != pojav_environ->cursorX || pojav_environ->cLastY != pojav_environ->cursorY) && pojav_environ->GLFW_invoke_CursorPos) {
        pojav_environ->cLastX = pojav_environ->cursorX;
        pojav_environ->cLastY = pojav_environ->cursorY;
        pojav_environ->shouldUpdateMouse = true;
    }
}

/** Prepare the library for the next round of new events */
void pojavStopPumping() {
    input_snapshot_count = 0;
    // Make sure the next frame won't send mouse or monitor updates if it's unnecessary
    pojav_environ->shouldUpdateMouse = false;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPos(JNIEnv *env, __attribute__((unused)) jclass clazz, __attribute__((unused)) jlong window, jobject xpos,
                                           jobject ypos) {
    *(double*)(*env)->GetDirectBufferAddress(env, xpos) = pojav_environ->cursorX;
    *(double*)(*env)->GetDirectBufferAddress(env, ypos) = pojav_environ->cursorY;
}

JNIEXPORT void JNICALL JavaCritical_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(__attribute__((unused)) jlong window, jint lengthx, jdouble* xpos, jint lengthy, jdouble* ypos) {
    *xpos = pojav_environ->cursorX;
    *ypos = pojav_environ->cursorY;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(JNIEnv *env, __attribute__((unused)) jclass clazz, __attribute__((unused)) jlong window,
                                            jdoubleArray xpos, jdoubleArray ypos) {
    (*env)->SetDoubleArrayRegion(env, xpos, 0,1, &pojav_environ->cursorX);
    (*env)->SetDoubleArrayRegion(env, ypos, 0,1, &pojav_environ->cursorY);
}

JNIEXPORT void JNICALL JavaCritical_org_lwjgl_glfw_GLFW_glfwSetCursorPos(__attribute__((unused)) jlong window, jdouble xpos,
                                                                         jdouble ypos) {
    pojav_environ->cLastX = pojav_environ->cursorX = xpos;
    pojav_environ->cLastY = pojav_environ->cursorY = ypos;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_glfwSetCursorPos(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jclass clazz, __attribute__((unused)) jlong window, jdouble xpos,
                                          jdouble ypos) {
    JavaCritical_org_lwjgl_glfw_GLFW_glfwSetCursorPos(window, xpos, ypos);
}



void sendData(int type, int i1, int i2, int i3, int i4) {
    pthread_mutex_lock(&input_event_lock);
    if (atomic_load_explicit(&pojav_environ->eventCounter, memory_order_relaxed) >= EVENT_WINDOW_SIZE) {
        bool log = !input_queue_full_logged;
        input_queue_full_logged = true;
        pthread_mutex_unlock(&input_event_lock);
        if (log) fprintf(stderr, "input_bridge: event queue full; dropping new events\n");
        return;
    }
    GLFWInputEvent *event = &pojav_environ->events[pojav_environ->inEventIndex];
    event->type = type;
    event->i1 = i1;
    event->i2 = i2;
    event->i3 = i3;
    event->i4 = i4;

    pojav_environ->inEventIndex = (pojav_environ->inEventIndex + 1) % EVENT_WINDOW_SIZE;
    atomic_fetch_add_explicit(&pojav_environ->eventCounter, 1, memory_order_relaxed);
    pthread_mutex_unlock(&input_event_lock);
}

void critical_set_stackqueue(jboolean use_input_stack_queue) {
    pojav_environ->isUseStackQueueCall = (int) use_input_stack_queue;
}

void noncritical_set_stackqueue(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jclass clazz, jboolean use_input_stack_queue) {
    critical_set_stackqueue(use_input_stack_queue);
}

JNIEXPORT jstring JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeClipboard(JNIEnv* env, __attribute__((unused)) jclass clazz, __attribute__((unused)) jint action, jbyteArray copySrc) {
    JavaVM* vm = pojav_environ->dalvikJavaVMPtr;
    if (vm == NULL || pojav_environ->bridgeClazz == NULL ||
        pojav_environ->method_accessAndroidClipboard == NULL) return NULL;
    JNIEnv* dalvikEnv = NULL;
    bool attached = false;
    jint status = (*vm)->GetEnv(vm, (void**)&dalvikEnv, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        status = (*vm)->AttachCurrentThread(vm, &dalvikEnv, NULL);
        attached = status == JNI_OK;
    }
    if (status != JNI_OK || dalvikEnv == NULL) return NULL;

    char* bytes = NULL;
    jbyteArray targetBytes = NULL;
    jclass stringClass = NULL;
    jstring charset = NULL;
    jstring copyDst = NULL;
    jstring androidResult = NULL;
    jstring result = NULL;
    const char* resultChars = NULL;
    if (copySrc != NULL) {
        jsize length = (*env)->GetArrayLength(env, copySrc);
        if ((*env)->ExceptionCheck(env)) goto cleanup;
        bytes = malloc((size_t)length + 1);
        if (bytes == NULL) goto cleanup;
        (*env)->GetByteArrayRegion(env, copySrc, 0, length, (jbyte*)bytes);
        if ((*env)->ExceptionCheck(env)) goto cleanup;
        /* GLFW passes a C string; xdg-open passes a NUL-delimited argv block.
         * Decode only the first bounded string, not its terminator or later args.
         * A byte array without a terminator is also accepted without reading past it. */
        const char* terminator = memchr(bytes, '\0', (size_t)length);
        if (terminator != NULL) length = (jsize)(terminator - bytes);
        targetBytes = (*dalvikEnv)->NewByteArray(dalvikEnv, length);
        if (targetBytes == NULL) goto cleanup;
        (*dalvikEnv)->SetByteArrayRegion(dalvikEnv, targetBytes, 0, length, (jbyte*)bytes);
        if ((*dalvikEnv)->ExceptionCheck(dalvikEnv)) goto cleanup;
        stringClass = (*dalvikEnv)->FindClass(dalvikEnv, "java/lang/String");
        if (stringClass == NULL) goto cleanup;
        jmethodID constructor = (*dalvikEnv)->GetMethodID(dalvikEnv, stringClass, "<init>", "([BLjava/lang/String;)V");
        if (constructor == NULL) goto cleanup;
        charset = (*dalvikEnv)->NewStringUTF(dalvikEnv, "UTF-8");
        if (charset == NULL) goto cleanup;
        copyDst = (*dalvikEnv)->NewObject(dalvikEnv, stringClass, constructor, targetBytes, charset);
        if (copyDst == NULL || (*dalvikEnv)->ExceptionCheck(dalvikEnv)) goto cleanup;
    }
    androidResult = (*dalvikEnv)->CallStaticObjectMethod(dalvikEnv, pojav_environ->bridgeClazz,
            pojav_environ->method_accessAndroidClipboard, action, copyDst);
    if ((*dalvikEnv)->ExceptionCheck(dalvikEnv)) goto cleanup;
    if (androidResult != NULL) {
        /* JNI strings use modified UTF-8 on both sides; this round-trip preserves NUL/surrogates. */
        resultChars = (*dalvikEnv)->GetStringUTFChars(dalvikEnv, androidResult, NULL);
        if (resultChars != NULL) result = (*env)->NewStringUTF(env, resultChars);
    }
cleanup:
    if (resultChars != NULL) (*dalvikEnv)->ReleaseStringUTFChars(dalvikEnv, androidResult, resultChars);
    if (androidResult != NULL) (*dalvikEnv)->DeleteLocalRef(dalvikEnv, androidResult);
    if (copyDst != NULL) (*dalvikEnv)->DeleteLocalRef(dalvikEnv, copyDst);
    if (charset != NULL) (*dalvikEnv)->DeleteLocalRef(dalvikEnv, charset);
    if (stringClass != NULL) (*dalvikEnv)->DeleteLocalRef(dalvikEnv, stringClass);
    if (targetBytes != NULL) (*dalvikEnv)->DeleteLocalRef(dalvikEnv, targetBytes);
    free(bytes);
    /* A same-VM caller must retain its exception for Java to receive. */
    if (dalvikEnv != env) inputJniFailed(dalvikEnv, "nativeClipboard");
    if (attached) (*vm)->DetachCurrentThread(vm);
    return result;
}

JNIEXPORT jint JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeGetGlContextGeneration(
    __attribute__((unused)) JNIEnv* env,
    __attribute__((unused)) jclass clazz
) {
    if (pojav_environ == NULL) {
        return 0;
    }
    return atomic_load_explicit(&pojav_environ->glContextGeneration, memory_order_relaxed);
}

JNIEXPORT jfloat JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeGetCursorX(
    __attribute__((unused)) JNIEnv* env,
    __attribute__((unused)) jclass clazz
) {
    if (pojav_environ == NULL) {
        return 0.0f;
    }
    return (jfloat)pojav_environ->cursorX;
}

JNIEXPORT jfloat JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeGetCursorY(
    __attribute__((unused)) JNIEnv* env,
    __attribute__((unused)) jclass clazz
) {
    if (pojav_environ == NULL) {
        return 0.0f;
    }
    return (jfloat)pojav_environ->cursorY;
}

JNIEXPORT jfloat JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeGetGyroscopeX(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz
) {
    if (pojav_environ == NULL) {
        return 0.0f;
    }
    return atomic_load_explicit(&pojav_environ->gyroscopeX, memory_order_relaxed);
}

JNIEXPORT jfloat JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeGetGyroscopeY(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz
) {
    if (pojav_environ == NULL) {
        return 0.0f;
    }
    return atomic_load_explicit(&pojav_environ->gyroscopeY, memory_order_relaxed);
}

JNIEXPORT jfloat JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeGetGyroscopeZ(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz
) {
    if (pojav_environ == NULL) {
        return 0.0f;
    }
    return atomic_load_explicit(&pojav_environ->gyroscopeZ, memory_order_relaxed);
}

JNIEXPORT jboolean JNICALL JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(jboolean inputReady) {
#ifdef DEBUG
    ;
#endif
    ;
    pojav_environ->isInputReady = inputReady;
    return pojav_environ->isUseStackQueueCall;
}

JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jboolean inputReady) {
    return JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(inputReady);
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetRuntimeForeground(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jboolean foreground
) {
    if (pojav_environ == NULL) {
        return;
    }
    atomic_store_explicit(&pojav_environ->runtimeForeground, foreground == JNI_TRUE, memory_order_relaxed);
}

JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeIsRuntimeForeground(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz
) {
    if (pojav_environ == NULL) {
        return JNI_TRUE;
    }
    return atomic_load_explicit(&pojav_environ->runtimeForeground, memory_order_relaxed) ? JNI_TRUE : JNI_FALSE;
}

// The JVM-side LWJGL shim has no way to query the real surface, so it reads the size the Android
// side last published. This must stay live: multi-window resizes arrive long after JVM startup.
JNIEXPORT jint JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeGetPhysicalWidth(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz
) {
    if (pojav_environ == NULL) {
        return 0;
    }
    return (jint) pojav_environ->savedWidth;
}

JNIEXPORT jint JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeGetPhysicalHeight(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz
) {
    if (pojav_environ == NULL) {
        return 0;
    }
    return (jint) pojav_environ->savedHeight;
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jboolean grabbing) {
    if (pojav_environ->bridgeClazz == NULL || pojav_environ->method_onGrabStateChanged == NULL) return;
    TRY_ATTACH_ENV(dvm_env, pojav_environ->dalvikJavaVMPtr, "nativeSetGrabbing failed!\n", return;);
    (*dvm_env)->CallStaticVoidMethod(dvm_env, pojav_environ->bridgeClazz, pojav_environ->method_onGrabStateChanged, grabbing);
    pojav_environ->isGrabbing = grabbing;
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeEnableGamepadDirectInput(__attribute__((unused)) JNIEnv *env, __attribute__((unused))  jclass clazz) {
    if (pojav_environ->bridgeClazz == NULL || pojav_environ->method_onDirectInputEnable == NULL) return JNI_FALSE;
    TRY_ATTACH_ENV(dvm_env, pojav_environ->dalvikJavaVMPtr, "nativeEnableGamepadDirectInput failed!\n", return JNI_FALSE;);
    (*dvm_env)->CallStaticVoidMethod(dvm_env, pojav_environ->bridgeClazz, pojav_environ->method_onDirectInputEnable);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSetGlBridgeSwapHeartbeatLoggingEnabled(
    __attribute__((unused)) JNIEnv *env,
    __attribute__((unused)) jclass clazz,
    jboolean enabled
) {
    gl_set_swap_heartbeat_logging_enabled(enabled == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeGetGlSwapCount(
    __attribute__((unused)) JNIEnv *env,
    __attribute__((unused)) jclass clazz
) {
    return (jint) gl_get_swap_count();
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeGetGlSurfaceState(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz
) {
    return (jint) gl_get_surface_state();
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeIsSwappyEnabled(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz
) {
    return amethyst_swappy_is_enabled() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeGetSwappyState(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz
) {
    return (jint) amethyst_swappy_get_state();
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSetActiveRefreshRateHz(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jfloat refreshRateHz
) {
    amethyst_swappy_set_active_refresh_rate((float) refreshRateHz);
}

JNIEXPORT jfloat JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeGetActiveRefreshRateHz(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz
) {
    return (jfloat) amethyst_swappy_get_active_refresh_rate();
}

jboolean critical_send_char(jchar codepoint) {
    if (pojav_environ->GLFW_invoke_Char && pojav_environ->isInputReady) {
        if (pojav_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_CHAR, codepoint, 0, 0, 0);
        } else {
            pojav_environ->GLFW_invoke_Char((void*) pojav_environ->showingWindow, (unsigned int) codepoint);
        }
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

jboolean noncritical_send_char(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jchar codepoint) {
    return critical_send_char(codepoint);
}

jboolean critical_send_char_mods(jchar codepoint, jint mods) {
    if (pojav_environ->GLFW_invoke_CharMods && pojav_environ->isInputReady) {
        if (pojav_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_CHAR_MODS, (int) codepoint, mods, 0, 0);
        } else {
            pojav_environ->GLFW_invoke_CharMods((void*) pojav_environ->showingWindow, codepoint, mods);
        }
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

jboolean noncritical_send_char_mods(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jchar codepoint, jint mods) {
    return critical_send_char_mods(codepoint, mods);
}
/*
JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSendCursorEnter(JNIEnv* env, jclass clazz, jint entered) {
    if (pojav_environ->GLFW_invoke_CursorEnter && pojav_environ->isInputReady) {
        pojav_environ->GLFW_invoke_CursorEnter(pojav_environ->showingWindow, entered);
    }
}
*/

void critical_send_cursor_pos(jfloat x, jfloat y) {
#ifdef DEBUG
    ;
#endif
    if (pojav_environ == NULL) {
        return;
    }
    // Keep glfwGetCursorPos/nativeGetCursor* consistent with callback delivery.
    pojav_environ->cursorX = x;
    pojav_environ->cursorY = y;
    if (pojav_environ->GLFW_invoke_CursorPos && pojav_environ->isInputReady) {
#ifdef DEBUG
        ;
#endif
        if (!pojav_environ->isCursorEntered) {
            if (pojav_environ->GLFW_invoke_CursorEnter) {
                pojav_environ->isCursorEntered = true;
                if (pojav_environ->isUseStackQueueCall) {
                    sendData(EVENT_TYPE_CURSOR_ENTER, 1, 0, 0, 0);
                } else {
                    pojav_environ->GLFW_invoke_CursorEnter((void*) pojav_environ->showingWindow, 1);
                }
            } else if (pojav_environ->isGrabbing) {
                // Some Minecraft versions does not use GLFWCursorEnterCallback
                // This is a smart check, as Minecraft will not in grab mode if already not.
                pojav_environ->isCursorEntered = true;
            }
        }

        if (!pojav_environ->isUseStackQueueCall) {
            pojav_environ->cLastX = x;
            pojav_environ->cLastY = y;
            pojav_environ->GLFW_invoke_CursorPos((void*) pojav_environ->showingWindow, (double) (x), (double) (y));
        }
    }
}

void noncritical_send_cursor_pos(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz,  jfloat x, jfloat y) {
    critical_send_cursor_pos(x, y);
}
void critical_send_key(jint key, jint scancode, jint action, jint mods) {
    if (pojav_environ->GLFW_invoke_Key && pojav_environ->isInputReady) {
        // Guard buffer writes: some JVM/GLFW combinations may expose a smaller
        // key-state buffer than expected and out-of-range writes can crash native code.
        jlong key_index = (jlong)key - 31;
        if (pojav_environ->keyDownBuffer != NULL && key_index >= 0 && key_index < key_buffer_capacity) {
            pojav_environ->keyDownBuffer[key_index] = (jbyte) action;
        }
        if (pojav_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_KEY, key, scancode, action, mods);
        } else {
            pojav_environ->GLFW_invoke_Key((void*) pojav_environ->showingWindow, key, scancode, action, mods);
        }
    }
}
void noncritical_send_key(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint key, jint scancode, jint action, jint mods) {
    critical_send_key(key, scancode, action, mods);
}

void critical_send_mouse_button(jint button, jint action, jint mods) {
    GLFW_invoke_MouseButton_func* mouseButtonCallback = pojav_environ->GLFW_invoke_MouseButton;
    jlong showingWindow = (jlong)pojav_environ->showingWindow;
    if (mouseButtonCallback == NULL || !pojav_environ->isInputReady || showingWindow == 0) {
        return;
    }
    // GLFW mouse buttons are in [0..7]. Reject anything outside to avoid
    // invalid native buffer writes when ABI quirks scramble callback args.
    if (button < 0 || button > 7) {
        return;
    }
    // Mouse actions should be GLFW_RELEASE(0) or GLFW_PRESS(1).
    // Ignore corrupted values to avoid propagating undefined state.
    if (action != 0 && action != 1) {
        return;
    }
    if (pojav_environ->mouseDownBuffer != NULL && button < mouse_buffer_capacity) {
        pojav_environ->mouseDownBuffer[button] = (jbyte) action;
    }
    if (pojav_environ->isUseStackQueueCall) {
        sendData(EVENT_TYPE_MOUSE_BUTTON, button, action, mods, 0);
    } else {
        mouseButtonCallback((void*) showingWindow, button, action, mods);
    }
}

void noncritical_send_mouse_button(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint button, jint action, jint mods) {
    critical_send_mouse_button(button, action, mods);
}

void critical_set_mouse_button_state(jint button, jboolean down) {
    if (button < 0 || button > 7 || button >= mouse_buffer_capacity || pojav_environ->mouseDownBuffer == NULL) {
        return;
    }
    pojav_environ->mouseDownBuffer[button] = down ? 1 : 0;
}

void noncritical_set_mouse_button_state(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint button, jboolean down) {
    critical_set_mouse_button_state(button, down);
}

void critical_set_gyroscope(jfloat x, jfloat y, jfloat z) {
    if (pojav_environ == NULL) {
        return;
    }
    atomic_store_explicit(&pojav_environ->gyroscopeX, x, memory_order_relaxed);
    atomic_store_explicit(&pojav_environ->gyroscopeY, y, memory_order_relaxed);
    atomic_store_explicit(&pojav_environ->gyroscopeZ, z, memory_order_relaxed);
}

void noncritical_set_gyroscope(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jfloat x,
        jfloat y,
        jfloat z
) {
    critical_set_gyroscope(x, y, z);
}

void critical_send_scroll(jdouble xoffset, jdouble yoffset) {
    if (pojav_environ->GLFW_invoke_Scroll && pojav_environ->isInputReady) {
        if (pojav_environ->isUseStackQueueCall) {
            float x = (float)xoffset, y = (float)yoffset;
            int xbits, ybits;
            _Static_assert(sizeof(float) == sizeof(int), "scroll event storage must fit a float");
            memcpy(&xbits, &x, sizeof(xbits));
            memcpy(&ybits, &y, sizeof(ybits));
            sendData(EVENT_TYPE_SCROLL, xbits, ybits, 0, 0);
        } else {
            pojav_environ->GLFW_invoke_Scroll((void*) pojav_environ->showingWindow, (double) xoffset, (double) yoffset);
        }
    }
}

void noncritical_send_scroll(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jdouble xoffset, jdouble yoffset) {
    critical_send_scroll(xoffset, yoffset);
}


JNIEXPORT void JNICALL Java_org_lwjgl_glfw_GLFW_nglfwSetShowingWindow(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jlong window) {
    pojav_environ->showingWindow = (jlong) window;
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetWindowAttrib(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint attrib, jint value) {
    // Check for stack queue no longer necessary here as the JVM crash's origin is resolved
    if (!pojav_environ->showingWindow || pojav_environ->runtimeJavaVMPtr == NULL) {
        // If the window is not shown, there is nothing to do yet.
        return;
    }
    if (pojav_environ->vmGlfwClass == NULL || pojav_environ->method_glftSetWindowAttrib == NULL) {
        return;
    }

    // We cannot use pojav_environ->runtimeJNIEnvPtr_JRE here because that environment is attached
    // on the thread that loaded pojavexec (which is the thread that first references the GLFW class)
    // But this method is only called from the Android UI thread

    // Technically the better solution would be to have a permanently attached env pointer stored
    // in environ for the Android UI thread but this is the only place that uses it
    // (very rarely, only in lifecycle callbacks) so i dont care

    TRY_ATTACH_ENV(jvm_env, pojav_environ->runtimeJavaVMPtr, "nativeSetWindowAttrib failed\n", return;);

    (*jvm_env)->CallStaticVoidMethod(
            jvm_env, pojav_environ->vmGlfwClass,
            pojav_environ->method_glftSetWindowAttrib,
            (jlong) pojav_environ->showingWindow, attrib, value
    );

    // Attaching every time is annoying, so stick the attachment to the Android GUI thread around
}

JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeRequestCloseWindow(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz) {
    if (!pojav_environ->showingWindow || pojav_environ->runtimeJavaVMPtr == NULL) {
        return JNI_FALSE;
    }
    if (pojav_environ->vmGlfwClass == NULL || pojav_environ->method_glfwSetWindowShouldClose == NULL) {
        return JNI_FALSE;
    }

    TRY_ATTACH_ENV(jvm_env, pojav_environ->runtimeJavaVMPtr, "nativeRequestCloseWindow failed: attach env\n", return JNI_FALSE;);

    (*jvm_env)->CallStaticVoidMethod(
            jvm_env,
            pojav_environ->vmGlfwClass,
            pojav_environ->method_glfwSetWindowShouldClose,
            (jlong) pojav_environ->showingWindow,
            JNI_TRUE
    );
    if ((*jvm_env)->ExceptionCheck(jvm_env)) {
        (*jvm_env)->ExceptionClear(jvm_env);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetAudioMuted(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jboolean muted
) {
    atomic_store_explicit(&pojav_audio_mute_request,
                          AUDIO_MUTE_PENDING | (muted == JNI_TRUE ? AUDIO_MUTE_REQUESTED : 0),
                          memory_order_release);
}

JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeHasQueuedAudioCommands(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz
) {
    return (atomic_load_explicit(&pojav_audio_command_pending, memory_order_acquire) ||
            (atomic_load_explicit(&pojav_audio_mute_request, memory_order_acquire) & AUDIO_MUTE_PENDING) ||
            atomic_load_explicit(&pojav_audio_health_check_pending, memory_order_acquire))
            ? JNI_TRUE
            : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeProcessQueuedAudioCommands(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz
) {
    processQueuedAudioCommandsOnCurrentThread();
}

JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeRecoverAudioOutput(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz
) {
    pthread_mutex_lock(&pojav_audio_recovery_lock);
    int requestedGeneration = atomic_load_explicit(
            &pojav_audio_recovery_requested_generation,
            memory_order_acquire
    );
    int completedGeneration = atomic_load_explicit(
            &pojav_audio_recovery_completed_generation,
            memory_order_acquire
    );
    int reportedGeneration = atomic_load_explicit(
            &pojav_audio_recovery_reported_generation,
            memory_order_acquire
    );
    // An unconsumed older success must not complete a newer pending request.
    if (completedGeneration == requestedGeneration && completedGeneration > reportedGeneration) {
        bool result = atomic_load_explicit(
                &pojav_audio_recovery_last_result,
                memory_order_acquire
        );
        atomic_store_explicit(
                &pojav_audio_recovery_reported_generation,
                completedGeneration,
                memory_order_release
        );
        pthread_mutex_unlock(&pojav_audio_recovery_lock);
        return result ? JNI_TRUE : JNI_FALSE;
    }

    pthread_mutex_unlock(&pojav_audio_recovery_lock);
    return JNI_FALSE;
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeRequestAudioRecovery(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz
) {
    queueAudioRecoveryCommand();
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeRequestAudioHealthCheck(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jboolean renewRetryBudget
) {
    if (!audioHealthChecksAllowed() && renewRetryBudget != JNI_TRUE) return;
    int flags = AUDIO_HEALTH_CHECK_PENDING;
    if (renewRetryBudget == JNI_TRUE) flags |= AUDIO_HEALTH_RENEW_BUDGET;
    atomic_fetch_or_explicit(&pojav_audio_health_check_pending, flags, memory_order_release);
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetAudioRecoveryAllowed(
        __attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jboolean allowed) {
    atomic_store_explicit(&pojav_audio_recovery_allowed, allowed == JNI_TRUE, memory_order_release);
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeRequestAudioRouteRecovery(
        __attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz) {
    // One atomic route event, not health()+explicit(): the owner may run between JNI calls.
    // Acceptance is independent of the execution gate, which may close between JNI calls.
    atomic_fetch_or_explicit(&pojav_audio_health_check_pending,
                             AUDIO_HEALTH_CHECK_PENDING | AUDIO_HEALTH_RENEW_BUDGET |
                             AUDIO_HEALTH_ROUTE_CHANGED, memory_order_release);
}

JNIEXPORT jint JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeGetAudioRuntimeState(
        __attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz) {
    return atomic_load_explicit(&pojav_audio_runtime_state, memory_order_acquire);
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetAudioRuntimeState(
        __attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint state) {
    atomic_store_explicit(&pojav_audio_runtime_state, state, memory_order_release);
    printf("%s event=audio_runtime_state state=%d\n", AUDIO_ROUTE_LOG_PREFIX, state);
    fflush(stdout);
}

JNIEXPORT jlong JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeGetAudioOperationSnapshot(
        __attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz) {
    return (jlong)atomic_load_explicit(&pojav_audio_operation_snapshot, memory_order_acquire);
}
const static JNINativeMethod critical_fcns[] = {
        {"nativeSetUseInputStackQueue", "(Z)V", critical_set_stackqueue},
        {"nativeSendChar", "(C)Z", critical_send_char},
        {"nativeSendCharMods", "(CI)Z", critical_send_char_mods},
        {"nativeSendKey", "(IIII)V", critical_send_key},
        {"nativeSendCursorPos", "(FF)V", critical_send_cursor_pos},
        {"nativeSendMouseButton", "(III)V", critical_send_mouse_button},
        {"nativeSetMouseButtonState", "(IZ)V", critical_set_mouse_button_state},
        {"nativeSetGyroscope", "(FFF)V", critical_set_gyroscope},
        {"nativeSendScroll", "(DD)V", critical_send_scroll},
};

const static JNINativeMethod noncritical_fcns[] = {
        {"nativeSetUseInputStackQueue", "(Z)V", noncritical_set_stackqueue},
        {"nativeSendChar", "(C)Z", noncritical_send_char},
        {"nativeSendCharMods", "(CI)Z", noncritical_send_char_mods},
        {"nativeSendKey", "(IIII)V", noncritical_send_key},
        {"nativeSendCursorPos", "(FF)V", noncritical_send_cursor_pos},
        {"nativeSendMouseButton", "(III)V", noncritical_send_mouse_button},
        {"nativeSetMouseButtonState", "(IZ)V", noncritical_set_mouse_button_state},
        {"nativeSetGyroscope", "(FFF)V", noncritical_set_gyroscope},
        {"nativeSendScroll", "(DD)V", noncritical_send_scroll},
};


static bool criticalNativeAvailable;

void dvm_testCriticalNative(void* arg0, void* arg1, void* arg2, void* arg3) {
    if(arg0 != 0 && arg2 == 0 && arg3 == 0) {
        criticalNativeAvailable = false;
    }else if (arg0 == 0 && arg1 == 0){
        criticalNativeAvailable = true;
    }else {
        criticalNativeAvailable = false; // just to be safe
    }
}

static bool tryCriticalNative(JNIEnv *env) {
    static const JNINativeMethod testJNIMethod[] = {
            { "testCriticalNative", "(II)V", dvm_testCriticalNative}
    };
    jclass criticalNativeTest = (*env)->FindClass(env, "net/kdt/pojavlaunch/CriticalNativeTest");
    if(criticalNativeTest == NULL) {
        ;
        (*env)->ExceptionClear(env);
        return false;
    }
    jmethodID criticalNativeTestMethod = inputStaticMethod(env, criticalNativeTest, "invokeTest", "()V");
    if (criticalNativeTestMethod == NULL) {
        (*env)->DeleteLocalRef(env, criticalNativeTest);
        return false;
    }
    if ((*env)->RegisterNatives(env, criticalNativeTest, testJNIMethod, 1) != JNI_OK) {
        inputJniFailed(env, "critical native probe registration");
        (*env)->DeleteLocalRef(env, criticalNativeTest);
        return false;
    }
    (*env)->CallStaticVoidMethod(env, criticalNativeTest, criticalNativeTestMethod);
    if (inputJniFailed(env, "critical native probe")) criticalNativeAvailable = false;
    (*env)->UnregisterNatives(env, criticalNativeTest);
    inputJniFailed(env, "critical native probe cleanup");
    (*env)->DeleteLocalRef(env, criticalNativeTest);
    return criticalNativeAvailable;
}

static void registerFunctions(JNIEnv *env) {
    bool critical_supported = tryCriticalNative(env);
    bool use_critical_cc = critical_supported;
    jclass bridge_class = (*env)->FindClass(env, "org/lwjgl/glfw/CallbackBridge");
    if (inputJniFailed(env, "CallbackBridge registration lookup") || bridge_class == NULL) return;
    if (critical_supported) {
        ;
    }else{
        ;
    }
    jint result = (*env)->RegisterNatives(env,
                            bridge_class,
                            use_critical_cc ? critical_fcns : noncritical_fcns,
                            sizeof(critical_fcns)/sizeof(critical_fcns[0]));
    if (result != JNI_OK) fprintf(stderr, "input_bridge: RegisterNatives failed: %d\n", result);
    inputJniFailed(env, "CallbackBridge RegisterNatives");
    (*env)->DeleteLocalRef(env, bridge_class);
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_internalGetGamepadDataPointer(JNIEnv *env, jclass clazz) {
    return (jlong) &pojav_environ->gamepadState;
}

JNIEXPORT jobject JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeCreateGamepadButtonBuffer(JNIEnv *env, jclass clazz) {
    return (*env)->NewDirectByteBuffer(env, &pojav_environ->gamepadState.buttons, sizeof(pojav_environ->gamepadState.buttons));
}

JNIEXPORT jobject JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeCreateGamepadAxisBuffer(JNIEnv *env, jclass clazz) {
    return (*env)->NewDirectByteBuffer(env, &pojav_environ->gamepadState.axes, sizeof(pojav_environ->gamepadState.axes));
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Tools_00024SDL_initializeControllerSubsystems(JNIEnv *env, jclass clazz){
    // SDL stack is intentionally omitted in this minimal STS launcher.
    // Keep this symbol to satisfy JNI calls from compatibility shims.
}
