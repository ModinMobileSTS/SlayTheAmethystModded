#include <jni.h>
#include <assert.h>
#include <dlfcn.h>

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <pthread.h>
#include <errno.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include <EGL/egl.h>
#include <GL/osmesa.h>
#include "ctxbridges/osmesa_loader.h"
#include "ctxbridges/swappy_bridge.h"
#include "driver_helper/nsbypass.h"

#ifdef GLES_TEST
#include <GLES2/gl2.h>
#endif

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/rect.h>
#include <string.h>
#include <environ/environ.h>
#include <android/dlext.h>
#include "utils.h"
#include "ctxbridges/bridge_tbl.h"
#include "ctxbridges/osm_bridge.h"

#define GLFW_CLIENT_API 0x22001
/* Consider GLFW_NO_API as Vulkan API */
#define GLFW_NO_API 0
#define GLFW_OPENGL_API 0x30001
#define GLFW_OPENGL_ES_API 0x30002

// This means that the function is an external API and that it will be used
#define EXTERNAL_API __attribute__((used))
// This means that you are forced to have this function/variable for ABI compatibility
#define ABI_COMPAT __attribute__((unused))


struct PotatoBridge {

    /* EGLContext */ void* eglContext;
    /* EGLDisplay */ void* eglDisplay;
    /* EGLSurface */ void* eglSurface;
/*
    void* eglSurfaceRead;
    void* eglSurfaceDraw;
*/
};
EGLConfig config;
struct PotatoBridge potatoBridge;
static bool g_bridge_ready = false;
static pthread_mutex_t g_pojav_window_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_pojav_window_consumed;
static pthread_once_t g_pojav_window_consumed_once = PTHREAD_ONCE_INIT;
static uint64_t g_bridge_window_generation = 0;
static uint64_t g_consumed_bridge_window_generation = 0;
static const int64_t BRIDGE_WINDOW_DETACH_TIMEOUT_MS = 500;

static void pojav_init_window_consumed_condition(void) {
    pthread_condattr_t attributes;
    pthread_condattr_init(&attributes);
    pthread_condattr_setclock(&attributes, CLOCK_MONOTONIC);
    pthread_cond_init(&g_pojav_window_consumed, &attributes);
    pthread_condattr_destroy(&attributes);
}

EXTERNAL_API ANativeWindow* pojavAcquireBridgeWindowWithGeneration(uint64_t* generation) {
    pthread_mutex_lock(&g_pojav_window_mutex);
    ANativeWindow* window = (pojav_environ == NULL) ? NULL : pojav_environ->pojavWindow;
    if (generation != NULL) {
        *generation = g_bridge_window_generation;
    }
    if (window != NULL) {
        ANativeWindow_acquire(window);
    }
    pthread_mutex_unlock(&g_pojav_window_mutex);
    return window;
}

EXTERNAL_API ANativeWindow* pojavAcquireBridgeWindow(void) {
    return pojavAcquireBridgeWindowWithGeneration(NULL);
}

EXTERNAL_API void pojavReleaseBridgeWindow(ANativeWindow* window) {
    if (window != NULL) {
        ANativeWindow_release(window);
    }
}

EXTERNAL_API void pojavAcknowledgeBridgeWindowGeneration(uint64_t generation) {
    if (generation == 0) {
        return;
    }
    pthread_once(&g_pojav_window_consumed_once, pojav_init_window_consumed_condition);
    pthread_mutex_lock(&g_pojav_window_mutex);
    if (generation > g_consumed_bridge_window_generation) {
        g_consumed_bridge_window_generation = generation;
        pthread_cond_broadcast(&g_pojav_window_consumed);
    }
    pthread_mutex_unlock(&g_pojav_window_mutex);
}

static bool pojav_wait_for_bridge_window_generation(uint64_t generation, int64_t timeout_ms) {
    if (generation == 0) {
        return true;
    }

    struct timespec deadline;
    pthread_once(&g_pojav_window_consumed_once, pojav_init_window_consumed_condition);
    clock_gettime(CLOCK_MONOTONIC, &deadline);
    deadline.tv_sec += timeout_ms / 1000;
    deadline.tv_nsec += (timeout_ms % 1000) * 1000000LL;
    if (deadline.tv_nsec >= 1000000000L) {
        deadline.tv_sec++;
        deadline.tv_nsec -= 1000000000L;
    }

    pthread_mutex_lock(&g_pojav_window_mutex);
    while (g_consumed_bridge_window_generation < generation) {
        int wait_result = pthread_cond_timedwait(
            &g_pojav_window_consumed,
            &g_pojav_window_mutex,
            &deadline
        );
        if (wait_result == ETIMEDOUT) {
            break;
        }
        if (wait_result != 0) {
            break;
        }
    }
    bool consumed = g_consumed_bridge_window_generation >= generation;
    pthread_mutex_unlock(&g_pojav_window_mutex);
    return consumed;
}

#include "ctxbridges/egl_loader.h"
#include "ctxbridges/osmesa_loader.h"

#define RENDERER_GL4ES 1
#define RENDERER_VK_ZINK 2
#define RENDERER_VULKAN 4

EXTERNAL_API void pojavTerminate() {
    printf("EGLBridge: Terminating\n");

    switch (pojav_environ->config_renderer) {
        case RENDERER_GL4ES: {
            eglMakeCurrent_p(potatoBridge.eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface_p(potatoBridge.eglDisplay, potatoBridge.eglSurface);
            eglDestroyContext_p(potatoBridge.eglDisplay, potatoBridge.eglContext);
            eglTerminate_p(potatoBridge.eglDisplay);
            eglReleaseThread_p();

            potatoBridge.eglContext = EGL_NO_CONTEXT;
            potatoBridge.eglDisplay = EGL_NO_DISPLAY;
            potatoBridge.eglSurface = EGL_NO_SURFACE;
        } break;

            //case RENDERER_VIRGL:
        case RENDERER_VK_ZINK: {
            // Nothing to do here
        } break;
    }
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeWindow(JNIEnv* env, ABI_COMPAT jclass clazz, jobject surface) {
    ANativeWindow* nextWindow = NULL;
    if (surface != NULL) {
        nextWindow = ANativeWindow_fromSurface(env, surface);
    }

    pthread_mutex_lock(&g_pojav_window_mutex);
    ANativeWindow* previousWindow = pojav_environ->pojavWindow;
    pojav_environ->pojavWindow = nextWindow;
    if (previousWindow != nextWindow) {
        g_bridge_window_generation++;
        pojav_reset_window_geometry_cache(pojav_environ);
    }
    pthread_mutex_unlock(&g_pojav_window_mutex);

    if (previousWindow == nextWindow && nextWindow != NULL) {
        ANativeWindow_release(nextWindow);
        return;
    }
    if (previousWindow != NULL) {
        ANativeWindow_release(previousWindow);
    }
    amethyst_swappy_set_window(nextWindow);
    if (nextWindow != NULL && br_setup_window != NULL) {
        br_setup_window();
    }
}


JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_releaseBridgeWindow(
        ABI_COMPAT JNIEnv *env,
        ABI_COMPAT jclass clazz
) {
    amethyst_swappy_set_window(NULL);
    pthread_mutex_lock(&g_pojav_window_mutex);
    ANativeWindow* window = pojav_environ->pojavWindow;
    if (window == NULL) {
        pthread_mutex_unlock(&g_pojav_window_mutex);
        return;
    }
    pojav_environ->pojavWindow = NULL;
    uint64_t release_generation = ++g_bridge_window_generation;
    pojav_reset_window_geometry_cache(pojav_environ);
    pthread_mutex_unlock(&g_pojav_window_mutex);

    if (br_setup_window != NULL) {
        // Notify renderer bridge that the window is gone so it can switch to pbuffer early.
        br_setup_window();
    } else {
        pojavAcknowledgeBridgeWindowGeneration(release_generation);
    }
    if (window != NULL) {
        ANativeWindow_release(window);
    }
    bool consumed = pojav_wait_for_bridge_window_generation(
        release_generation,
        BRIDGE_WINDOW_DETACH_TIMEOUT_MS
    );
    if (!consumed) {
        printf(
            "EGLBridge: timed out waiting %lld ms for render thread to detach window generation %llu\n",
            (long long)BRIDGE_WINDOW_DETACH_TIMEOUT_MS,
            (unsigned long long)release_generation
        );
    }
}

JNIEXPORT jboolean JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_initializeSwappyFramePacing(
        JNIEnv* env,
        ABI_COMPAT jclass clazz,
        jobject activity,
        jfloat targetFps
) {
    return amethyst_swappy_init(env, activity, (float) targetFps) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_destroySwappyFramePacing(
        ABI_COMPAT JNIEnv* env,
        ABI_COMPAT jclass clazz
) {
    amethyst_swappy_destroy();
}

EXTERNAL_API void* pojavGetCurrentContext() {
    return br_get_current();
}

//#define ADRENO_POSSIBLE
#ifdef ADRENO_POSSIBLE
void* load_turnip_vulkan() {
    if(getenv("POJAV_LOAD_TURNIP") == NULL) return NULL;
    const char* native_dir = getenv("POJAV_NATIVEDIR");
    const char* cache_dir = getenv("TMPDIR");
    if(!linker_ns_load(native_dir)) return NULL;
    void* linkerhook = linker_ns_dlopen("liblinkerhook.so", RTLD_LOCAL | RTLD_NOW);
    if(linkerhook == NULL) return NULL;
    void* turnip_driver_handle = linker_ns_dlopen("libvulkan_freedreno.so", RTLD_LOCAL | RTLD_NOW);
    if(turnip_driver_handle == NULL) {
        printf("AdrenoSupp: Failed to load Turnip!\n%s\n", dlerror());
        dlclose(linkerhook);
        return NULL;
    }
    void* dl_android = linker_ns_dlopen("libdl_android.so", RTLD_LOCAL | RTLD_LAZY);
    if(dl_android == NULL) {
        dlclose(linkerhook);
        dlclose(turnip_driver_handle);
        return NULL;
    }
    void* android_get_exported_namespace = dlsym(dl_android, "android_get_exported_namespace");
    void (*linkerhook_pass_handles)(void*, void*, void*) = dlsym(linkerhook, "app__pojav_linkerhook_pass_handles");
    if(linkerhook_pass_handles == NULL || android_get_exported_namespace == NULL) {
        dlclose(dl_android);
        dlclose(linkerhook);
        dlclose(turnip_driver_handle);
        return NULL;
    }
    linkerhook_pass_handles(turnip_driver_handle, android_dlopen_ext, android_get_exported_namespace);
    void* libvulkan = linker_ns_dlopen_unique(cache_dir, "libvulkan.so", RTLD_LOCAL | RTLD_NOW);
    return libvulkan;
}
#endif

static void set_vulkan_ptr(void* ptr) {
    char envval[64];
    sprintf(envval, "%"PRIxPTR, (uintptr_t)ptr);
    setenv("VULKAN_PTR", envval, 1);
}

void load_vulkan() {
    if(android_get_device_api_level() >= 28) { // the loader does not support below that
#ifdef ADRENO_POSSIBLE
        void* result = load_turnip_vulkan();
        if(result != NULL) {
            printf("AdrenoSupp: Loaded Turnip, loader address: %p\n", result);
            set_vulkan_ptr(result);
            return;
        }
#endif
    }
    printf("OSMDroid: loading vulkan regularly...\n");
    void* vulkan_ptr = dlopen("libvulkan.so", RTLD_LAZY | RTLD_LOCAL);
    printf("OSMDroid: loaded vulkan, ptr=%p\n", vulkan_ptr);
    set_vulkan_ptr(vulkan_ptr);
}

int pojavInitOpenGL() {
    g_bridge_ready = false;
    // Only affects GL4ES as of now
    pojav_environ->force_vsync = false;
    const char *forceVsync = getenv("FORCE_VSYNC");
    if (forceVsync != NULL && strcmp(forceVsync, "true") == 0) {
        pojav_environ->force_vsync = true;
        printf("EGLBridge: force swap interval pacing enabled\n");
    }

    // NOTE: Override for now.
    const char *renderer = getenv("AMETHYST_RENDERER");
    if (renderer == NULL || renderer[0] == '\0') {
        printf("EGLBridge: AMETHYST_RENDERER is empty, fallback to opengles2\n");
        renderer = "opengles2";
    }

    if (strncmp("opengles", renderer, 8) == 0) {
        pojav_environ->config_renderer = RENDERER_GL4ES;
        if (!strcmp(renderer, "opengles3_desktopgl_zink_kopper")) {
            load_vulkan();
            setenv("MESA_LOADER_DRIVER_OVERRIDE", "zink", 1);
            setenv("GALLIUM_DRIVER", "zink", 1);
            setenv("MESA_ANDROID_NO_KMS_SWRAST", "1", 1);
        }
        set_gl_bridge_tbl();
    } else if (strcmp(renderer, "vulkan_zink") == 0) {
        pojav_environ->config_renderer = RENDERER_VK_ZINK;
        load_vulkan();
        setenv("GALLIUM_DRIVER","zink",1);
        set_osm_bridge_tbl();
    } else {
        printf("EGLBridge: Unknown renderer \"%s\", fallback to GL4ES bridge\n", renderer);
        pojav_environ->config_renderer = RENDERER_GL4ES;
        set_gl_bridge_tbl();
    }
    if(br_init()) {
        g_bridge_ready = true;
        br_setup_window();
    } else {
        printf("EGLBridge: bridge init failed for renderer \"%s\"\n", renderer);
    }
    return 0;
}

extern void updateMonitorSize(int width, int height);

EXTERNAL_API int pojavInit() {
    pojav_environ->glfwThreadVmEnv = get_attached_env(pojav_environ->runtimeJavaVMPtr);
    if(pojav_environ->glfwThreadVmEnv == NULL) {
        printf("Failed to attach Java-side JNIEnv to GLFW thread\n");
        return 0;
    }
    ANativeWindow* window = pojavAcquireBridgeWindow();
    if (window == NULL) {
        printf("EGLBridge: pojavInit aborted because bridge window is NULL\n");
        return 0;
    }
    int nativeWidth = ANativeWindow_getWidth(window);
    int nativeHeight = ANativeWindow_getHeight(window);
    int javaWidth = pojav_environ->savedWidth;
    int javaHeight = pojav_environ->savedHeight;
    bool javaSizeValid = javaWidth > 0 && javaHeight > 0;
    bool nativeSizeValid = nativeWidth > 0 && nativeHeight > 0;
    bool orientationMismatch = false;
    if (javaSizeValid && nativeSizeValid) {
        bool javaLandscape = javaWidth >= javaHeight;
        bool nativeLandscape = nativeWidth >= nativeHeight;
        orientationMismatch = javaLandscape != nativeLandscape;
    }
    if (!javaSizeValid && nativeSizeValid) {
        pojav_environ->savedWidth = nativeWidth;
        pojav_environ->savedHeight = nativeHeight;
        printf("EGLBridge: screen size from Java is invalid, fallback to ANativeWindow %dx%d\n",
               nativeWidth, nativeHeight);
    } else if (orientationMismatch) {
        pojav_environ->savedWidth = nativeWidth;
        pojav_environ->savedHeight = nativeHeight;
        printf("EGLBridge: Java size %dx%d conflicts with ANativeWindow orientation %dx%d, fallback to ANativeWindow\n",
               javaWidth, javaHeight, nativeWidth, nativeHeight);
    } else {
        printf("EGLBridge: keep Java-provided screen size %dx%d (ANativeWindow is %dx%d)\n",
               javaWidth, javaHeight, nativeWidth, nativeHeight);
    }
    const char* renderer = getenv("AMETHYST_RENDERER");
    bool isKopperRenderer = renderer != NULL &&
            strcmp(renderer, "opengles3_desktopgl_zink_kopper") == 0;
    if (isKopperRenderer) {
        printf("EGLBridge: skip ANativeWindow_setBuffersGeometry for Kopper renderer\n");
    } else {
        const int geometryWidth = pojav_environ->savedWidth;
        const int geometryHeight = pojav_environ->savedHeight;
        const int geometryFormat = AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM;
        if (pojav_window_geometry_matches(
                pojav_environ,
                window,
                geometryWidth,
                geometryHeight,
                geometryFormat
        )) {
            printf("EGLBridge: skip duplicate geometry %dx%d fmt=%d\n",
                   geometryWidth, geometryHeight, geometryFormat);
        } else {
            int geometryResult = ANativeWindow_setBuffersGeometry(
                    window,
                    geometryWidth,
                    geometryHeight,
                    geometryFormat
            );
            if (geometryResult != 0) {
                printf("EGLBridge: ANativeWindow_setBuffersGeometry(%dx%d) failed: %d\n",
                       geometryWidth, geometryHeight, geometryResult);
            } else {
                pojav_record_window_geometry(
                        pojav_environ,
                        window,
                        geometryWidth,
                        geometryHeight,
                        geometryFormat
                );
            }
        }
    }
    updateMonitorSize(pojav_environ->savedWidth, pojav_environ->savedHeight);
    ANativeWindow_release(window);
    pojavInitOpenGL();
    return 1;
}

EXTERNAL_API void pojavSetWindowHint(int hint, int value) {
    if (hint != GLFW_CLIENT_API) return;
    switch (value) {
        case GLFW_NO_API:
            pojav_environ->config_renderer = RENDERER_VULKAN;
            /* Nothing to do: initialization is handled in Java-side */
            // pojavInitVulkan();
            break;
        case GLFW_OPENGL_API:
        case GLFW_OPENGL_ES_API:
            /* Nothing to do: initialization is called in pojavCreateContext */
            // pojavInitOpenGL();
            break;
        default:
            printf("GLFW: Unimplemented API 0x%x\n", value);
            abort();
    }
}

EXTERNAL_API void pojavSwapBuffers() {
    if (!g_bridge_ready || br_swap_buffers == NULL) {
        return;
    }
    br_swap_buffers();
}


EXTERNAL_API void pojavMakeCurrent(void* window) {
    if (!g_bridge_ready || br_make_current == NULL) {
        return;
    }
    br_make_current((basic_render_window_t*)window);
}

EXTERNAL_API void* pojavCreateContext(void* contextSrc) {
    if (pojav_environ->config_renderer == RENDERER_VULKAN) {
        return (void *) pojav_environ->pojavWindow;
    }
    if (!g_bridge_ready || br_init_context == NULL) {
        printf("EGLBridge: create context skipped, bridge is not ready\n");
        return NULL;
    }
    return br_init_context((basic_render_window_t*)contextSrc);
}

void* maybe_load_vulkan() {
    // We use the env var because
    // 1. it's easier to do that
    // 2. it won't break if something will try to load vulkan and osmesa simultaneously
    if(getenv("VULKAN_PTR") == NULL) load_vulkan();
    return (void*) strtoul(getenv("VULKAN_PTR"), NULL, 0x10);
}

EXTERNAL_API JNIEXPORT jlong JNICALL
Java_org_lwjgl_vulkan_VK_getVulkanDriverHandle(ABI_COMPAT JNIEnv *env, ABI_COMPAT jclass thiz) {
    printf("EGLBridge: LWJGL-side Vulkan loader requested the Vulkan handle\n");
    return (jlong) maybe_load_vulkan();
}

EXTERNAL_API void pojavSwapInterval(int interval) {
    if (!g_bridge_ready) {
        return;
    }
    if (br_swap_interval == NULL) {
        printf("EGLBridge: Swap interval callback is unavailable, skip interval=%d\n", interval);
        return;
    }
    br_swap_interval(interval);
}
