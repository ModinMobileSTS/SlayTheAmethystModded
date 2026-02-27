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
#include <pthread.h>
#include <environ/environ.h>
#include "gl_bridge.h"
#include "egl_loader.h"

#define TAG __FILE_NAME__
#include <log.h>

//
// Created by maks on 17.09.2022.
//

static __thread gl_render_window_t* currentBundle;
static EGLDisplay g_EglDisplay;
static pthread_mutex_t g_surface_mutex = PTHREAD_MUTEX_INITIALIZER;
static uint32_t g_swap_diag_counter = 0;

#ifndef EGL_CONTEXT_LOST
#define EGL_CONTEXT_LOST 0x300E
#endif

static int gl_get_context_client_version() {
    const char* libglEsValue = getenv("LIBGL_ES");
    int libgl_es = (int)strtol(libglEsValue == NULL ? "2" : libglEsValue, NULL, 0);
    if (libgl_es < 0 || libgl_es > INT16_MAX) libgl_es = 2;
    return libgl_es;
}

static void gl_advance_context_generation(const char* reason) {
    if (pojav_environ == NULL) return;
    int generation = atomic_fetch_add_explicit(&pojav_environ->glContextGeneration, 1, memory_order_relaxed) + 1;
    LOGI("GL context generation -> %d (%s)", generation, reason == NULL ? "unknown" : reason);
}

static void gl_replace_queued_surface_locked(gl_render_window_t* bundle, ANativeWindow* window) {
    if (bundle == NULL) {
        return;
    }
    if (bundle->newNativeSurface != NULL) {
        ANativeWindow_release(bundle->newNativeSurface);
        bundle->newNativeSurface = NULL;
    }
    if (window != NULL) {
        ANativeWindow_acquire(window);
        bundle->newNativeSurface = window;
    }
}

static void gl_queue_surface(gl_render_window_t* bundle, ANativeWindow* window, const char* reason) {
    if (bundle == NULL) {
        return;
    }
    pthread_mutex_lock(&g_surface_mutex);
    gl_replace_queued_surface_locked(bundle, window);
    pthread_mutex_unlock(&g_surface_mutex);
    LOGI("Queued %s surface update (%s)", window == NULL ? "NULL" : "native",
         reason == NULL ? "unknown" : reason);
}

static void gl_queue_bridge_window_surface(gl_render_window_t* bundle, const char* reason) {
    if (bundle == NULL) {
        return;
    }
    bool hasWindow = false;
    pthread_mutex_lock(&g_surface_mutex);
    ANativeWindow* window = (pojav_environ == NULL) ? NULL : pojav_environ->pojavWindow;
    gl_replace_queued_surface_locked(bundle, window);
    hasWindow = window != NULL;
    pthread_mutex_unlock(&g_surface_mutex);
    LOGI("Queued %s bridge window (%s)", hasWindow ? "native" : "NULL",
         reason == NULL ? "unknown" : reason);
}

static ANativeWindow* gl_take_queued_surface(gl_render_window_t* bundle) {
    ANativeWindow* queued = NULL;
    if (bundle == NULL) {
        return NULL;
    }
    pthread_mutex_lock(&g_surface_mutex);
    queued = bundle->newNativeSurface;
    bundle->newNativeSurface = NULL;
    pthread_mutex_unlock(&g_surface_mutex);
    return queued;
}

static ANativeWindow* gl_take_queued_or_bridge_surface(gl_render_window_t* bundle) {
    ANativeWindow* queued = gl_take_queued_surface(bundle);
    if (queued != NULL) {
        return queued;
    }

    pthread_mutex_lock(&g_surface_mutex);
    bool canReuseBridgeWindow = pojav_environ != NULL &&
        pojav_environ->mainWindowBundle == (basic_render_window_t*)bundle &&
        pojav_environ->pojavWindow != NULL;
    if (canReuseBridgeWindow) {
        queued = pojav_environ->pojavWindow;
        ANativeWindow_acquire(queued);
    }
    pthread_mutex_unlock(&g_surface_mutex);

    if (queued != NULL) {
        LOGI("No queued surface, reusing current bridge window");
        printf("GLBridgeDiag: no queued surface, reusing bridge window\n");
    }
    return queued;
}

static bool gl_try_restore_main_window_surface(gl_render_window_t* bundle, const char* reason) {
    if (bundle == NULL) {
        return false;
    }
    bool queued = false;
    pthread_mutex_lock(&g_surface_mutex);
    if (pojav_environ != NULL &&
        pojav_environ->mainWindowBundle == (basic_render_window_t*)bundle &&
        pojav_environ->pojavWindow != NULL &&
        bundle->nativeSurface == NULL) {
        gl_replace_queued_surface_locked(bundle, pojav_environ->pojavWindow);
        bundle->state = STATE_RENDERER_NEW_WINDOW;
        queued = true;
    }
    pthread_mutex_unlock(&g_surface_mutex);

    if (queued) {
        LOGI("Detected bridge window while on fallback surface, scheduling window restore (%s)",
             reason == NULL ? "unknown" : reason);
        printf("GLBridgeDiag: scheduling window restore (%s)\n", reason == NULL ? "unknown" : reason);
    }
    return queued;
}

static bool gl_create_context_for_bundle(gl_render_window_t* bundle, EGLContext sharedContext, const char* reason) {
    if (bundle == NULL) return false;
    const EGLint egl_context_attributes[] = {
        EGL_CONTEXT_CLIENT_VERSION, gl_get_context_client_version(),
        EGL_NONE
    };
    bundle->context = eglCreateContext_p(g_EglDisplay, bundle->config, sharedContext, egl_context_attributes);
    if (bundle->context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed (%s): %04x", reason == NULL ? "unknown" : reason, eglGetError_p());
        return false;
    }
    gl_advance_context_generation(reason);
    return true;
}

static bool gl_recreate_context(gl_render_window_t* bundle, const char* reason) {
    if (bundle == NULL) return false;

    // Detach current context before replacing it.
    eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    if (bundle->context != NULL && bundle->context != EGL_NO_CONTEXT) {
        if (!eglDestroyContext_p(g_EglDisplay, bundle->context)) {
            LOGW("eglDestroyContext failed while recovering (%s): %04x",
                 reason == NULL ? "unknown" : reason, eglGetError_p());
        }
    }
    bundle->context = EGL_NO_CONTEXT;
    if (!gl_create_context_for_bundle(bundle, EGL_NO_CONTEXT, reason)) {
        return false;
    }
    LOGI("Recreated EGL context (%s)", reason == NULL ? "unknown" : reason);
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
        LOGE("%s", "dlsym_EGL() failed");
        return false;
    }
    if (!gl_egl_ready()) {
        LOGE("%s", "EGL core symbols are not ready");
        return false;
    }
    g_EglDisplay = eglGetDisplay_p(EGL_DEFAULT_DISPLAY);
    if (g_EglDisplay == EGL_NO_DISPLAY) {
        LOGE("%s", "eglGetDisplay_p(EGL_DEFAULT_DISPLAY) returned EGL_NO_DISPLAY");
        return false;
    }
    if (eglInitialize_p(g_EglDisplay, 0, 0) != EGL_TRUE) {
        LOGE("eglInitialize_p() failed: %04x", eglGetError_p());
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
        LOGE("%s", "gl_init_context aborted: EGL symbols are unavailable");
        return NULL;
    }
    if (g_EglDisplay == EGL_NO_DISPLAY) {
        LOGE("%s", "gl_init_context aborted: EGL display is not initialized");
        return NULL;
    }
    gl_render_window_t* bundle = malloc(sizeof(gl_render_window_t));
    memset(bundle, 0, sizeof(gl_render_window_t));
    EGLint egl_attributes[] = { EGL_BLUE_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_RED_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_DEPTH_SIZE, 24, EGL_SURFACE_TYPE, EGL_WINDOW_BIT|EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE };
    EGLint num_configs = 0;

    if (eglChooseConfig_p(g_EglDisplay, egl_attributes, NULL, 0, &num_configs) != EGL_TRUE) {
        LOGE("eglChooseConfig_p() failed: %04x", eglGetError_p());
        free(bundle);
        return NULL;
    }
    if (num_configs == 0) {
        LOGE("%s", "eglChooseConfig_p() found no matching config");
        free(bundle);
        return NULL;
    }

    // Get the first matching config
    eglChooseConfig_p(g_EglDisplay, egl_attributes, &bundle->config, 1, &num_configs);
    eglGetConfigAttrib_p(g_EglDisplay, bundle->config, EGL_NATIVE_VISUAL_ID, &bundle->format);

    {
        EGLBoolean bindResult;
        const char* renderer = getenv("AMETHYST_RENDERER");
        if (renderer != NULL && strncmp(renderer, "opengles3_desktopgl", 19) == 0) {
            printf("EGLBridge: Binding to desktop OpenGL\n");
            bindResult = eglBindAPI_p(EGL_OPENGL_API);
        } else {
            printf("EGLBridge: Binding to OpenGL ES\n");
            bindResult = eglBindAPI_p(EGL_OPENGL_ES_API);
        }
        if (!bindResult) printf("EGLBridge: bind failed: 0x%04x\n", eglGetError_p());
    }

    if (!gl_create_context_for_bundle(bundle, share == NULL ? EGL_NO_CONTEXT : share->context, "initial create")) {
        free(bundle);
        return NULL;
    }
    return bundle;
}

void gl_swap_surface(gl_render_window_t* bundle) {
    if (bundle == NULL) {
        return;
    }
    ANativeWindow* queuedSurface = gl_take_queued_or_bridge_surface(bundle);
    if(bundle->nativeSurface != NULL) {
        ANativeWindow_release(bundle->nativeSurface);
        bundle->nativeSurface = NULL;
    }
    if(bundle->surface != NULL && bundle->surface != EGL_NO_SURFACE) {
        if (!eglDestroySurface_p(g_EglDisplay, bundle->surface)) {
            LOGW("eglDestroySurface failed: %04x", eglGetError_p());
        }
    }
    bundle->surface = EGL_NO_SURFACE;
    if(queuedSurface != NULL) {
        LOGI("Switching to new native surface");
        bundle->nativeSurface = queuedSurface;
        ANativeWindow_setBuffersGeometry(bundle->nativeSurface, 0, 0, bundle->format);
        bundle->surface = eglCreateWindowSurface_p(g_EglDisplay, bundle->config, bundle->nativeSurface, NULL);
        printf("GLBridgeDiag: created WINDOW surface=%p native=%p\n", bundle->surface, bundle->nativeSurface);
    }else{
        LOGI("No new native surface, switching to 1x1 pbuffer");
        bundle->nativeSurface = NULL;
        const EGLint pbuffer_attrs[] = {EGL_WIDTH, 1 , EGL_HEIGHT, 1, EGL_NONE};
        bundle->surface = eglCreatePbufferSurface_p(g_EglDisplay, bundle->config, pbuffer_attrs);
        printf("GLBridgeDiag: created PBUFFER surface=%p\n", bundle->surface);
    }
    if (bundle->surface == EGL_NO_SURFACE || bundle->surface == NULL) {
        LOGE("Failed to create EGL surface: %04x", eglGetError_p());
        printf("GLBridgeDiag: surface create failed err=0x%04x\n", eglGetError_p());
    }
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
        if (makeCurrentErr == EGL_BAD_SURFACE) {
            LOGW("eglMakeCurrent failed with EGL_BAD_SURFACE (%s), recreating surface",
                 reason == NULL ? "unknown" : reason);
            gl_swap_surface(bundle);
            continue;
        }
        if (makeCurrentErr == EGL_CONTEXT_LOST || makeCurrentErr == EGL_BAD_CONTEXT) {
            LOGW("eglMakeCurrent failed with context error (%04x, %s), recreating context",
                 makeCurrentErr, reason == NULL ? "unknown" : reason);
            if (!gl_recreate_context(bundle, reason)) {
                return false;
            }
            gl_swap_surface(bundle);
            continue;
        }
        LOGE("eglMakeCurrent failed (%s): %04x", reason == NULL ? "unknown" : reason, makeCurrentErr);
        return false;
    }
    LOGE("eglMakeCurrent recovery exhausted (%s)", reason == NULL ? "unknown" : reason);
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
    pthread_mutex_lock(&g_surface_mutex);
    if(pojav_environ->mainWindowBundle == NULL) {
        pojav_environ->mainWindowBundle = (basic_render_window_t*)bundle;
        hasSetMainWindow = true;
    }
    if (pojav_environ->mainWindowBundle == (basic_render_window_t*)bundle) {
        gl_replace_queued_surface_locked(bundle, pojav_environ->pojavWindow);
    }
    pthread_mutex_unlock(&g_surface_mutex);
    if (hasSetMainWindow) {
        LOGI("Main window bundle is now %p", pojav_environ->mainWindowBundle);
    }
    if(bundle->surface == NULL) { //it likely will be on the first run
        gl_swap_surface(bundle);
    }
    if(!gl_make_current_with_recovery(bundle, "gl_make_current")) {
        if(hasSetMainWindow) {
            pthread_mutex_lock(&g_surface_mutex);
            if (pojav_environ->mainWindowBundle == (basic_render_window_t*)bundle) {
                gl_replace_queued_surface_locked(bundle, NULL);
                pojav_environ->mainWindowBundle = NULL;
            }
            pthread_mutex_unlock(&g_surface_mutex);
            gl_swap_surface(bundle);
        }
    }

}

void gl_swap_buffers() {
    if (currentBundle == NULL) {
        return;
    }

    // If we were forced onto a pbuffer but the Java bridge window is back,
    // promote back to the on-screen surface automatically.
    gl_try_restore_main_window_surface(currentBundle, "swap preflight");

    if(currentBundle->state == STATE_RENDERER_NEW_WINDOW) {
        // Detach everything to destroy the old EGLSurface safely.
        eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        gl_swap_surface(currentBundle);
        if (!gl_make_current_with_recovery(currentBundle, "window switch")) {
            return;
        }
        currentBundle->state = STATE_RENDERER_ALIVE;
    }

    // If context was silently dropped/unbound, recover before swap.
    if (eglGetCurrentContext_p != NULL && eglGetCurrentContext_p() != currentBundle->context) {
        if (!gl_make_current_with_recovery(currentBundle, "swap preflight")) {
            return;
        }
    }

    if(currentBundle->surface != NULL && currentBundle->surface != EGL_NO_SURFACE) {
        if(!eglSwapBuffers_p(g_EglDisplay, currentBundle->surface)) {
            EGLint swapErr = eglGetError_p();
            if (swapErr == EGL_BAD_SURFACE) {
                eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                gl_queue_bridge_window_surface(currentBundle, "swap bad surface");
                gl_swap_surface(currentBundle);
                if (gl_make_current_with_recovery(currentBundle, "swap bad surface")) {
                    LOGI("The window surface died, switched to fallback surface");
                }
                return;
            }
            if (swapErr == EGL_CONTEXT_LOST || swapErr == EGL_BAD_CONTEXT) {
                LOGW("eglSwapBuffers context error: %04x", swapErr);
                if (gl_recreate_context(currentBundle, "swap context loss")) {
                    gl_swap_surface(currentBundle);
                    gl_make_current_with_recovery(currentBundle, "swap context loss");
                }
                return;
            }
            LOGE("eglSwapBuffers failed: %04x", swapErr);
            printf("GLBridgeDiag: eglSwapBuffers failed err=0x%04x surface=%p native=%p state=%d\n",
                   swapErr, currentBundle->surface, currentBundle->nativeSurface, currentBundle->state);
            return;
        }
    }

    g_swap_diag_counter++;
    if ((g_swap_diag_counter % 600) == 0) {
        printf("GLBridgeDiag: swap heartbeat #%u surface=%p native=%p state=%d\n",
               g_swap_diag_counter, currentBundle->surface, currentBundle->nativeSurface, currentBundle->state);
    }

}

void gl_setup_window() {
    bool updated = false;
    pthread_mutex_lock(&g_surface_mutex);
    if(pojav_environ->mainWindowBundle != NULL) {
        LOGI("Main window bundle is not NULL, changing state");
        gl_render_window_t* mainBundle = (gl_render_window_t*)pojav_environ->mainWindowBundle;
        mainBundle->state = STATE_RENDERER_NEW_WINDOW;
        gl_replace_queued_surface_locked(mainBundle, pojav_environ->pojavWindow);
        updated = true;
    }
    pthread_mutex_unlock(&g_surface_mutex);
    if (updated) {
        LOGI("Queued window surface for next swap");
    }
}

void gl_swap_interval(int swapInterval) {
    if(pojav_environ->force_vsync) swapInterval = 1;
    if (g_EglDisplay == EGL_NO_DISPLAY) {
        LOGW("Skip swap interval update: EGL display is not ready");
        return;
    }
    if (eglSwapInterval_p == NULL) {
        LOGW("Skip swap interval update: eglSwapInterval function is unavailable");
        return;
    }
    eglSwapInterval_p(g_EglDisplay, swapInterval);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_opengl_PojavRendererInit_nativeInitGl4esInternals(JNIEnv *env, jclass clazz,
                                                            jobject function_provider) {
    LOGI("GL4ES internals initializing...");
    jclass funcProviderClass = (*env)->GetObjectClass(env, function_provider);
    jmethodID method_getFunctionAddress = (*env)->GetMethodID(env, funcProviderClass, "getFunctionAddress", "(Ljava/lang/CharSequence;)J");
#define GETSYM(N) ((*env)->CallLongMethod(env, function_provider, method_getFunctionAddress, (*env)->NewStringUTF(env, N)));

    void (*set_getmainfbsize)(void (*new_getMainFBSize)(int* width, int* height)) = (void*)GETSYM("set_getmainfbsize");
    if(set_getmainfbsize != NULL) {
        LOGI("GL4ES internals initialized dimension callback");
        set_getmainfbsize(gl4esi_get_display_dimensions);
    }

#undef GETSYM
}
