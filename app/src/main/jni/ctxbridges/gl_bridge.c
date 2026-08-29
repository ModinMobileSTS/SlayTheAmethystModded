#include <EGL/egl.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <string.h>
#include <malloc.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <errno.h>
#include <pthread.h>
#include <time.h>
#include <environ/environ.h>
#include "gl_bridge.h"
#include "egl_loader.h"

#define TAG __FILE_NAME__
//
// Created by maks on 17.09.2022.
//

static __thread gl_render_window_t* currentBundle;
static EGLDisplay g_EglDisplay;
static pthread_mutex_t g_surface_mutex = PTHREAD_MUTEX_INITIALIZER;
static uint32_t g_swap_diag_counter = 0;
static uint32_t g_next_surface_restore_poll_swap = 0;
static bool g_swap_heartbeat_logging_enabled = false;
static bool g_swap_profiler_initialized = false;
static bool g_swap_profiler_enabled = false;
static int64_t g_swap_profiler_slow_ns = 16000000LL;


#define GL_RESTORE_SURFACE_POLL_INTERVAL_SWAPS 30

#ifndef EGL_CONTEXT_LOST
#define EGL_CONTEXT_LOST 0x300E
#endif

#ifndef EGL_BAD_NATIVE_WINDOW
#define EGL_BAD_NATIVE_WINDOW 0x300B
#endif

#ifndef EGL_OPENGL_ES3_BIT_KHR
#define EGL_OPENGL_ES3_BIT_KHR 0x0040
#endif

#ifndef EGL_OPENGL_BIT
#define EGL_OPENGL_BIT 0x0008
#endif

static int gl_get_context_client_version() {
    const char* libglEsValue = getenv("LIBGL_ES");
    int libgl_es = (int)strtol(libglEsValue == NULL ? "2" : libglEsValue, NULL, 0);
    if (libgl_es < 0 || libgl_es > INT16_MAX) libgl_es = 2;
    return libgl_es;
}

void gl_set_swap_heartbeat_logging_enabled(bool enabled) {
    g_swap_heartbeat_logging_enabled = enabled;
}

uint32_t gl_get_swap_count(void) {
    return g_swap_diag_counter;
}

static int64_t gl_now_monotonic_ns() {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return 0;
    }
    return ((int64_t)ts.tv_sec * 1000000000LL) + (int64_t)ts.tv_nsec;
}

static bool gl_env_flag_enabled(const char* name) {
    const char* value = getenv(name);
    if (value == NULL || value[0] == '\0') return false;
    if (strcmp(value, "0") == 0) return false;
    if (strcmp(value, "false") == 0 || strcmp(value, "FALSE") == 0) return false;
    if (strcmp(value, "off") == 0 || strcmp(value, "OFF") == 0) return false;
    return true;
}

static int64_t gl_env_int_ns(const char* name, int64_t default_ms) {
    const char* value = getenv(name);
    if (value == NULL || value[0] == '\0') return default_ms * 1000000LL;
    char* end = NULL;
    long parsed = strtol(value, &end, 10);
    if (end == value || parsed <= 0) return default_ms * 1000000LL;
    if (parsed > 10000) parsed = 10000;
    return ((int64_t)parsed) * 1000000LL;
}

static void gl_init_swap_profiler_if_needed() {
    if (g_swap_profiler_initialized) return;
    g_swap_profiler_initialized = true;
    g_swap_profiler_enabled = gl_env_flag_enabled("AMETHYST_GDX_SWAP_PROFILER");
    g_swap_profiler_slow_ns = gl_env_int_ns("AMETHYST_GDX_SWAP_PROFILER_SLOW_MS", 16);
}

static void gl_advance_context_generation(const char* reason) {
    if (pojav_environ == NULL) return;
    atomic_fetch_add_explicit(&pojav_environ->glContextGeneration, 1, memory_order_relaxed);
    ;
}

static bool gl_replace_queued_surface_locked(
        gl_render_window_t* bundle,
        ANativeWindow* window,
        uint64_t generation
) {
    if (bundle == NULL) {
        return false;
    }
    if (generation < bundle->latestSurfaceGeneration) {
        return false;
    }
    if (bundle->newNativeSurface != NULL) {
        ANativeWindow_release(bundle->newNativeSurface);
        bundle->newNativeSurface = NULL;
    }
    if (window != NULL) {
        ANativeWindow_acquire(window);
        bundle->newNativeSurface = window;
    }
    bundle->newSurfaceGeneration = generation;
    bundle->latestSurfaceGeneration = generation;
    return true;
}

static void gl_queue_surface(
        gl_render_window_t* bundle,
        ANativeWindow* window,
        uint64_t generation,
        const char* reason
) {
    if (bundle == NULL) {
        return;
    }
    pthread_mutex_lock(&g_surface_mutex);
    gl_replace_queued_surface_locked(bundle, window, generation);
    pthread_mutex_unlock(&g_surface_mutex);
    ;
}

static void gl_queue_bridge_window_surface(gl_render_window_t* bundle, const char* reason) {
    if (bundle == NULL) {
        return;
    }
    uint64_t generation = 0;
    ANativeWindow* window = pojavAcquireBridgeWindowWithGeneration(&generation);
    pthread_mutex_lock(&g_surface_mutex);
    gl_replace_queued_surface_locked(bundle, window, generation);
    pthread_mutex_unlock(&g_surface_mutex);
    pojavReleaseBridgeWindow(window);
    ;
}

static ANativeWindow* gl_take_queued_surface(
        gl_render_window_t* bundle,
        uint64_t* generation
) {
    ANativeWindow* queued = NULL;
    if (bundle == NULL) {
        return NULL;
    }
    pthread_mutex_lock(&g_surface_mutex);
    queued = bundle->newNativeSurface;
    bundle->newNativeSurface = NULL;
    if (generation != NULL) {
        *generation = bundle->newSurfaceGeneration;
    }
    bundle->newSurfaceGeneration = 0;
    pthread_mutex_unlock(&g_surface_mutex);
    return queued;
}

static void gl_finish_surface_switch(gl_render_window_t* bundle, uint64_t consumedGeneration) {
    pthread_mutex_lock(&g_surface_mutex);
    bundle->activeSurfaceGeneration = consumedGeneration;
    char nextState = bundle->newSurfaceGeneration != 0
        ? STATE_RENDERER_NEW_WINDOW
        : STATE_RENDERER_ALIVE;
    atomic_store_explicit(&bundle->state, nextState, memory_order_release);
    pthread_mutex_unlock(&g_surface_mutex);
    pojavAcknowledgeBridgeWindowGeneration(consumedGeneration);
}

static ANativeWindow* gl_take_queued_or_bridge_surface(
        gl_render_window_t* bundle,
        uint64_t* generation
) {
    ANativeWindow* queued = gl_take_queued_surface(bundle, generation);
    if (queued != NULL || (generation != NULL && *generation != 0)) {
        return queued;
    }

    bool isMainWindowBundle = false;
    pthread_mutex_lock(&g_surface_mutex);
    isMainWindowBundle = pojav_environ != NULL &&
        pojav_environ->mainWindowBundle == (basic_render_window_t*)bundle;
    pthread_mutex_unlock(&g_surface_mutex);
    if (isMainWindowBundle) {
        queued = pojavAcquireBridgeWindowWithGeneration(generation);
    }

    if (queued != NULL) {
        ;
        printf("GLBridgeDiag: no queued surface, reusing bridge window\n");
    }
    return queued;
}

static bool gl_try_restore_main_window_surface(gl_render_window_t* bundle, const char* reason) {
    if (bundle == NULL || bundle->nativeSurface != NULL) {
        return false;
    }

    bool queued = false;
    uint64_t generation = 0;
    ANativeWindow* window = pojavAcquireBridgeWindowWithGeneration(&generation);
    if (window == NULL) {
        return false;
    }

    pthread_mutex_lock(&g_surface_mutex);
    if (pojav_environ != NULL &&
        pojav_environ->mainWindowBundle == (basic_render_window_t*)bundle &&
        bundle->nativeSurface == NULL) {
        queued = gl_replace_queued_surface_locked(bundle, window, generation);
        if (queued) {
            atomic_store_explicit(&bundle->state, STATE_RENDERER_NEW_WINDOW, memory_order_release);
        }
    }
    pthread_mutex_unlock(&g_surface_mutex);
    pojavReleaseBridgeWindow(window);

    if (queued) {
        ;
        printf("GLBridgeDiag: scheduling window restore (%s)\n", reason == NULL ? "unknown" : reason);
    }
    return queued;
}

static bool gl_is_desktop_opengl_renderer(void) {
    const char* renderer = getenv("AMETHYST_RENDERER");
    return renderer != NULL && strncmp(renderer, "opengles3_desktopgl", 19) == 0;
}

static bool gl_create_context_for_bundle(
        gl_render_window_t* bundle,
        EGLContext sharedContext,
        const EGLint* contextAttributes,
        const char* reason
) {
    if (bundle == NULL) return false;
    bundle->context = eglCreateContext_p(g_EglDisplay, bundle->config, sharedContext, contextAttributes);
    if (bundle->context == EGL_NO_CONTEXT) {
        EGLint createErr = eglGetError_p();
        // Some MobileGlues / vendor stacks advertise ES3 in eglChooseConfig but still
        // fail eglCreateContext with ES3 client attributes. Retry with ES2 so the game
        // can boot on those devices instead of dying with "No context is current".
        if (contextAttributes != NULL && contextAttributes[0] == EGL_CONTEXT_CLIENT_VERSION
                && contextAttributes[1] >= 3) {
            const EGLint es2ContextAttributes[] = {
                EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL_NONE
            };
            bundle->context = eglCreateContext_p(
                    g_EglDisplay,
                    bundle->config,
                    sharedContext,
                    es2ContextAttributes
            );
            if (bundle->context != EGL_NO_CONTEXT) {
                printf("GLBridgeDiag: eglCreateContext ES3 failed err=0x%04x, retried ES2 (%s)\n",
                        createErr, reason == NULL ? "unknown" : reason);
                gl_advance_context_generation(reason);
                return true;
            }
            printf("GLBridgeDiag: eglCreateContext failed err=0x%04x, ES2 retry failed err=0x%04x (%s)\n",
                    createErr, eglGetError_p(), reason == NULL ? "unknown" : reason);
        } else {
            printf("GLBridgeDiag: eglCreateContext failed err=0x%04x (%s)\n",
                    createErr, reason == NULL ? "unknown" : reason);
        }
        return false;
    }
    gl_advance_context_generation(reason);
    return true;
}

static bool gl_recreate_context(gl_render_window_t* bundle, const char* reason) {
    if (bundle == NULL) return false;
    const EGLint esContextAttributes[] = {
        EGL_CONTEXT_CLIENT_VERSION, gl_get_context_client_version(),
        EGL_NONE
    };
    const EGLint desktopContextAttributes[] = {
        EGL_NONE
    };
    const EGLint* contextAttributes = gl_is_desktop_opengl_renderer()
        ? desktopContextAttributes
        : esContextAttributes;

    // Detach current context before replacing it.
    eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    if (bundle->context != NULL && bundle->context != EGL_NO_CONTEXT) {
        if (!eglDestroyContext_p(g_EglDisplay, bundle->context)) {
            ;
        }
    }
    bundle->context = EGL_NO_CONTEXT;
    if (!gl_create_context_for_bundle(bundle, EGL_NO_CONTEXT, contextAttributes, reason)) {
        return false;
    }
    ;
    return true;
}

static bool gl_make_current_with_recovery(gl_render_window_t* bundle, const char* reason);

static bool gl_egl_ready() {
    return eglChooseConfig_p != NULL
           && eglGetConfigAttrib_p != NULL
           && eglBindAPI_p != NULL
           && eglCreateContext_p != NULL
           && eglGetError_p != NULL
           && eglCreateWindowSurface_p != NULL
           && eglCreatePbufferSurface_p != NULL
           && eglMakeCurrent_p != NULL
           && eglSwapBuffers_p != NULL;
}

bool gl_init() {
    if(!dlsym_EGL()) {
        ;
        return false;
    }
    if (!gl_egl_ready()) {
        ;
        return false;
    }
    g_EglDisplay = eglGetDisplay_p(EGL_DEFAULT_DISPLAY);
    if (g_EglDisplay == EGL_NO_DISPLAY) {
        ;
        return false;
    }
    if (eglInitialize_p(g_EglDisplay, 0, 0) != EGL_TRUE) {
        ;
        return false;
    }
    return true;
}

gl_render_window_t* gl_get_current() {
    return currentBundle;
}

static void gl4esi_get_display_dimensions(int* width, int* height) {
    if(currentBundle == NULL) goto zero;
    EGLSurface surface = currentBundle->surface;
    // Fetch dimensions from the EGL surface - the most reliable way
    EGLBoolean result_width = eglQuerySurface_p(g_EglDisplay, surface, EGL_WIDTH, width);
    EGLBoolean result_height = eglQuerySurface_p(g_EglDisplay, surface, EGL_HEIGHT, height);
    if(!result_width || !result_height) goto zero;
    return;

    zero:
    // No idea what to do, but feeding gl4es incorrect or non-initialized dimensions may be
    // a bad idea. Set to zero in case of errors.
    *width = 0;
    *height = 0;
}

gl_render_window_t* gl_init_context(gl_render_window_t *share) {
    if (!gl_egl_ready()) {
        ;
        return NULL;
    }
    if (g_EglDisplay == EGL_NO_DISPLAY) {
        ;
        return NULL;
    }
    gl_render_window_t* bundle = malloc(sizeof(gl_render_window_t));
    memset(bundle, 0, sizeof(gl_render_window_t));
    const bool desktopOpenGL = gl_is_desktop_opengl_renderer();
    int requestedContextVersion = gl_get_context_client_version();
    EGLint renderableType = desktopOpenGL
        ? EGL_OPENGL_BIT
        : (requestedContextVersion >= 3 ? EGL_OPENGL_ES3_BIT_KHR : EGL_OPENGL_ES2_BIT);
    EGLint egl_attributes[] = {
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, renderableType,
        EGL_NONE
    };
    EGLint num_configs = 0;

    if (eglChooseConfig_p(g_EglDisplay, egl_attributes, NULL, 0, &num_configs) != EGL_TRUE) {
        if (!desktopOpenGL && requestedContextVersion >= 3) {
            renderableType = EGL_OPENGL_ES2_BIT;
            requestedContextVersion = 2;
            egl_attributes[13] = renderableType;
            num_configs = 0;
            if (eglChooseConfig_p(g_EglDisplay, egl_attributes, NULL, 0, &num_configs) != EGL_TRUE) {
                printf("GLBridgeDiag: eglChooseConfig failed err=0x%04x (ES3 then ES2)\n", eglGetError_p());
                free(bundle);
                return NULL;
            }
        } else {
            printf("GLBridgeDiag: eglChooseConfig failed err=0x%04x\n", eglGetError_p());
            free(bundle);
            return NULL;
        }
    }
    if (num_configs == 0) {
        if (!desktopOpenGL && requestedContextVersion >= 3) {
            renderableType = EGL_OPENGL_ES2_BIT;
            requestedContextVersion = 2;
            egl_attributes[13] = renderableType;
            if (eglChooseConfig_p(g_EglDisplay, egl_attributes, NULL, 0, &num_configs) != EGL_TRUE || num_configs == 0) {
                printf("GLBridgeDiag: eglChooseConfig returned no configs err=0x%04x (ES3 then ES2)\n", eglGetError_p());
                free(bundle);
                return NULL;
            }
        } else {
            printf("GLBridgeDiag: eglChooseConfig returned no configs err=0x%04x\n", eglGetError_p());
            free(bundle);
            return NULL;
        }
    }

    // Get the first matching config
    eglChooseConfig_p(g_EglDisplay, egl_attributes, &bundle->config, 1, &num_configs);
    eglGetConfigAttrib_p(g_EglDisplay, bundle->config, EGL_NATIVE_VISUAL_ID, &bundle->format);

    {
        EGLBoolean bindResult;
        if (desktopOpenGL) {
            printf("EGLBridge: Binding to desktop OpenGL\n");
            bindResult = eglBindAPI_p(EGL_OPENGL_API);
        } else {
            printf("EGLBridge: Binding to OpenGL ES\n");
            bindResult = eglBindAPI_p(EGL_OPENGL_ES_API);
        }
        if (!bindResult) printf("EGLBridge: bind failed: 0x%04x\n", eglGetError_p());
    }

    const EGLint esContextAttributes[] = {
        EGL_CONTEXT_CLIENT_VERSION, requestedContextVersion,
        EGL_NONE
    };
    const EGLint desktopContextAttributes[] = {
        EGL_NONE
    };
    const EGLint* contextAttributes = desktopOpenGL ? desktopContextAttributes : esContextAttributes;

    if (!gl_create_context_for_bundle(
            bundle,
            share == NULL ? EGL_NO_CONTEXT : share->context,
            contextAttributes,
            "initial create"
    )) {
        free(bundle);
        return NULL;
    }
    return bundle;
}

void gl_swap_surface(gl_render_window_t* bundle) {
    if (bundle == NULL) {
        return;
    }
    uint64_t queuedGeneration = 0;
    ANativeWindow* queuedSurface = gl_take_queued_or_bridge_surface(bundle, &queuedGeneration);
    if(bundle->nativeSurface != NULL) {
        ANativeWindow_release(bundle->nativeSurface);
        bundle->nativeSurface = NULL;
    }
    if(bundle->surface != NULL && bundle->surface != EGL_NO_SURFACE) {
        if (!eglDestroySurface_p(g_EglDisplay, bundle->surface)) {
            ;
        }
    }
    bundle->surface = EGL_NO_SURFACE;
    if(queuedSurface != NULL) {
        ;
        bundle->nativeSurface = queuedSurface;
        // Preserve the Java-synchronized window size across surface recreation so
        // render scale does not silently snap back to the platform default buffer size.
        int geometryWidth = 0;
        int geometryHeight = 0;
        if (pojav_environ != NULL) {
            if (pojav_environ->savedWidth > 0) {
                geometryWidth = pojav_environ->savedWidth;
            }
            if (pojav_environ->savedHeight > 0) {
                geometryHeight = pojav_environ->savedHeight;
            }
        }
        if (pojav_window_geometry_matches(
                pojav_environ,
                bundle->nativeSurface,
                geometryWidth,
                geometryHeight,
                bundle->format
        )) {
            printf(
                "GLBridgeDiag: skip duplicate geometry %d,%d fmt=%d\n",
                geometryWidth,
                geometryHeight,
                bundle->format
            );
        } else {
            int geometryResult = ANativeWindow_setBuffersGeometry(
                bundle->nativeSurface,
                geometryWidth,
                geometryHeight,
                bundle->format
            );
            if (geometryResult != 0) {
                printf(
                    "GLBridgeDiag: ANativeWindow_setBuffersGeometry(%d,%d) failed: %d\n",
                    geometryWidth,
                    geometryHeight,
                    geometryResult
                );
            } else {
                pojav_record_window_geometry(
                    pojav_environ,
                    bundle->nativeSurface,
                    geometryWidth,
                    geometryHeight,
                    bundle->format
                );
            }
        }
        bundle->surface = eglCreateWindowSurface_p(g_EglDisplay, bundle->config, bundle->nativeSurface, NULL);
        printf("GLBridgeDiag: created WINDOW surface=%p native=%p\n", bundle->surface, bundle->nativeSurface);
    }else{
        ;
        bundle->nativeSurface = NULL;
        const EGLint pbuffer_attrs[] = {EGL_WIDTH, 1 , EGL_HEIGHT, 1, EGL_NONE};
        bundle->surface = eglCreatePbufferSurface_p(g_EglDisplay, bundle->config, pbuffer_attrs);
        printf("GLBridgeDiag: created PBUFFER surface=%p\n", bundle->surface);
    }
    if (bundle->surface == EGL_NO_SURFACE || bundle->surface == NULL) {
        ;
        printf("GLBridgeDiag: surface create failed err=0x%04x\n", eglGetError_p());
    }
    gl_finish_surface_switch(bundle, queuedGeneration);
}

static bool gl_make_current_with_recovery(gl_render_window_t* bundle, const char* reason) {
    if (bundle == NULL) {
        return false;
    }
    for (int attempt = 0; attempt < 3; attempt++) {
        if (bundle->surface == NULL || bundle->surface == EGL_NO_SURFACE) {
            gl_swap_surface(bundle);
            if (bundle->surface == NULL || bundle->surface == EGL_NO_SURFACE) {
                return false;
            }
        }

        if (eglMakeCurrent_p(g_EglDisplay, bundle->surface, bundle->surface, bundle->context)) {
            currentBundle = bundle;
            return true;
        }

        EGLint makeCurrentErr = eglGetError_p();
        if (makeCurrentErr == EGL_BAD_SURFACE || makeCurrentErr == EGL_BAD_NATIVE_WINDOW) {
            gl_swap_surface(bundle);
            continue;
        }
        if (makeCurrentErr == EGL_CONTEXT_LOST || makeCurrentErr == EGL_BAD_CONTEXT) {
            if (!gl_recreate_context(bundle, reason)) {
                return false;
            }
            gl_swap_surface(bundle);
            continue;
        }
        ;
        return false;
    }
    ;
    return false;
}

void gl_make_current(gl_render_window_t* bundle) {

    if(bundle == NULL) {
        if(eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)) {
            currentBundle = NULL;
        }
        return;
    }
    bool hasSetMainWindow = false;
    uint64_t generation = 0;
    ANativeWindow* window = pojavAcquireBridgeWindowWithGeneration(&generation);
    pthread_mutex_lock(&g_surface_mutex);
    if(pojav_environ->mainWindowBundle == NULL) {
        pojav_environ->mainWindowBundle = (basic_render_window_t*)bundle;
        hasSetMainWindow = true;
    }
    if (pojav_environ->mainWindowBundle == (basic_render_window_t*)bundle) {
        gl_replace_queued_surface_locked(bundle, window, generation);
    }
    pthread_mutex_unlock(&g_surface_mutex);
    pojavReleaseBridgeWindow(window);
    if (hasSetMainWindow) {
        ;
    }
    if(bundle->surface == NULL) { //it likely will be on the first run
        gl_swap_surface(bundle);
    }
    if(!gl_make_current_with_recovery(bundle, "gl_make_current")) {
        if(hasSetMainWindow) {
            pthread_mutex_lock(&g_surface_mutex);
            if (pojav_environ->mainWindowBundle == (basic_render_window_t*)bundle) {
                gl_replace_queued_surface_locked(bundle, NULL, generation);
            }
            pthread_mutex_unlock(&g_surface_mutex);
            gl_swap_surface(bundle);
            pthread_mutex_lock(&g_surface_mutex);
            if (pojav_environ->mainWindowBundle == (basic_render_window_t*)bundle &&
                bundle->newSurfaceGeneration == 0) {
                pojav_environ->mainWindowBundle = NULL;
            }
            pthread_mutex_unlock(&g_surface_mutex);
        }
    }

}

void gl_swap_buffers() {
    if (currentBundle == NULL) {
        return;
    }
    gl_init_swap_profiler_if_needed();
    int64_t swapStartNs = g_swap_profiler_enabled ? gl_now_monotonic_ns() : 0;
    int64_t preflightNs = 0;
    int64_t windowSwitchNs = 0;
    int64_t contextRecoveryNs = 0;
    int64_t eglSwapNs = 0;
    int64_t stageStartNs = swapStartNs;

    // If we were forced onto a pbuffer but the Java bridge window is back,
    // promote back to the on-screen surface automatically. Normal surface
    // changes are delivered by gl_setup_window(); only poll while rendering to
    // a pbuffer and throttle the poll to avoid a mutex/refcount round-trip on
    // every frame.
    if (currentBundle->nativeSurface == NULL &&
        g_swap_diag_counter >= g_next_surface_restore_poll_swap) {
        g_next_surface_restore_poll_swap =
            g_swap_diag_counter + GL_RESTORE_SURFACE_POLL_INTERVAL_SWAPS;
        gl_try_restore_main_window_surface(currentBundle, "swap preflight");
    }
    if (g_swap_profiler_enabled) {
        int64_t nowNs = gl_now_monotonic_ns();
        preflightNs += nowNs - stageStartNs;
        stageStartNs = nowNs;
    }

    if(atomic_load_explicit(&currentBundle->state, memory_order_acquire) == STATE_RENDERER_NEW_WINDOW) {
        // Detach everything to destroy the old EGLSurface safely.
        eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        gl_swap_surface(currentBundle);
        if (!gl_make_current_with_recovery(currentBundle, "window switch")) {
            return;
        }
    }
    if (g_swap_profiler_enabled) {
        int64_t nowNs = gl_now_monotonic_ns();
        windowSwitchNs += nowNs - stageStartNs;
        stageStartNs = nowNs;
    }

    // If context was silently dropped/unbound, recover before swap.
    if (eglGetCurrentContext_p != NULL && eglGetCurrentContext_p() != currentBundle->context) {
        if (!gl_make_current_with_recovery(currentBundle, "swap preflight")) {
            return;
        }
    }
    if (g_swap_profiler_enabled) {
        int64_t nowNs = gl_now_monotonic_ns();
        contextRecoveryNs += nowNs - stageStartNs;
        stageStartNs = nowNs;
    }

    if(currentBundle->surface != NULL && currentBundle->surface != EGL_NO_SURFACE) {
        if(!eglSwapBuffers_p(g_EglDisplay, currentBundle->surface)) {
            EGLint swapErr = eglGetError_p();
            if (swapErr == EGL_BAD_SURFACE || swapErr == EGL_BAD_NATIVE_WINDOW) {
                eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                gl_queue_bridge_window_surface(currentBundle, "swap bad surface");
                gl_swap_surface(currentBundle);
                if (gl_make_current_with_recovery(currentBundle, "swap bad surface")) {
                    ;
                }
                return;
            }
            if (swapErr == EGL_CONTEXT_LOST || swapErr == EGL_BAD_CONTEXT) {
                if (gl_recreate_context(currentBundle, "swap context loss")) {
                    gl_swap_surface(currentBundle);
                    gl_make_current_with_recovery(currentBundle, "swap context loss");
                }
                return;
            }
            ;
            printf("GLBridgeDiag: eglSwapBuffers failed err=0x%04x surface=%p native=%p state=%d\n",
                   swapErr, currentBundle->surface, currentBundle->nativeSurface,
                   atomic_load_explicit(&currentBundle->state, memory_order_relaxed));
            return;
        }
    }
    if (g_swap_profiler_enabled) {
        int64_t nowNs = gl_now_monotonic_ns();
        eglSwapNs += nowNs - stageStartNs;
        stageStartNs = nowNs;
    }

    g_swap_diag_counter++;
    if (g_swap_profiler_enabled) {
        int64_t totalNs = stageStartNs - swapStartNs;
        if (totalNs >= g_swap_profiler_slow_ns) {
            printf("GLBridgePerf: swap slow #%u totalMs=%.3f preflightMs=%.3f windowSwitchMs=%.3f contextRecoveryMs=%.3f eglSwapMs=%.3f surface=%p native=%p state=%d\n",
                   g_swap_diag_counter,
                   ((double)totalNs) / 1000000.0,
                   ((double)preflightNs) / 1000000.0,
                   ((double)windowSwitchNs) / 1000000.0,
                   ((double)contextRecoveryNs) / 1000000.0,
                   ((double)eglSwapNs) / 1000000.0,
                   currentBundle->surface,
                   currentBundle->nativeSurface,
                    atomic_load_explicit(&currentBundle->state, memory_order_relaxed));
        }
    }
    if (g_swap_heartbeat_logging_enabled) {
        if ((g_swap_diag_counter % 600) == 0) {
            printf("GLBridgeDiag: swap heartbeat #%u surface=%p native=%p state=%d\n",
                    g_swap_diag_counter, currentBundle->surface, currentBundle->nativeSurface,
                    atomic_load_explicit(&currentBundle->state, memory_order_relaxed));
        }
    }

}

void gl_setup_window() {
    bool updated = false;
    bool hasMainBundle = false;
    uint64_t generation = 0;
    ANativeWindow* window = pojavAcquireBridgeWindowWithGeneration(&generation);
    pthread_mutex_lock(&g_surface_mutex);
    if(pojav_environ->mainWindowBundle != NULL) {
        hasMainBundle = true;
        ;
        gl_render_window_t* mainBundle = (gl_render_window_t*)pojav_environ->mainWindowBundle;
        updated = gl_replace_queued_surface_locked(mainBundle, window, generation);
        if (updated) {
            atomic_store_explicit(&mainBundle->state, STATE_RENDERER_NEW_WINDOW, memory_order_release);
        }
    }
    pthread_mutex_unlock(&g_surface_mutex);
    pojavReleaseBridgeWindow(window);
    if (!hasMainBundle) {
        pojavAcknowledgeBridgeWindowGeneration(generation);
    }
    if (updated) {
        ;
    }
}

void gl_swap_interval(int swapInterval) {
    if(pojav_environ->force_vsync) swapInterval = 1;
    if (g_EglDisplay == EGL_NO_DISPLAY) {
        ;
        return;
    }
    if (eglSwapInterval_p == NULL) {
        ;
        return;
    }
    eglSwapInterval_p(g_EglDisplay, swapInterval);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_opengl_PojavRendererInit_nativeInitGl4esInternals(JNIEnv *env, jclass clazz,
                                                            jobject function_provider) {
    ;
    jclass funcProviderClass = (*env)->GetObjectClass(env, function_provider);
    jmethodID method_getFunctionAddress = (*env)->GetMethodID(env, funcProviderClass, "getFunctionAddress", "(Ljava/lang/CharSequence;)J");
#define GETSYM(N) ((*env)->CallLongMethod(env, function_provider, method_getFunctionAddress, (*env)->NewStringUTF(env, N)));

    void (*set_getmainfbsize)(void (*new_getMainFBSize)(int* width, int* height)) = (void*)GETSYM("set_getmainfbsize");
    if(set_getmainfbsize != NULL) {
        ;
        set_getmainfbsize(gl4esi_get_display_dimensions);
    }

#undef GETSYM
}
