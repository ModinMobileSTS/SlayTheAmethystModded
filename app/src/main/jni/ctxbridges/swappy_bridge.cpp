#include "swappy_bridge.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <atomic>
#include <mutex>

#include <swappy/swappyGL.h>
#include <swappy/swappyGL_extra.h>

namespace {

std::mutex g_swappy_mutex;
bool g_swappy_initialized = false;
bool g_swappy_enabled = false;
std::atomic<int32_t> g_swappy_state{-1};
std::atomic<int32_t> g_active_refresh_rate_millihz{0};

extern "C" {
ANativeWindow* pojavAcquireBridgeWindow(void);
void pojavReleaseBridgeWindow(ANativeWindow* window);
}

uint64_t resolve_swap_interval_ns(float target_fps) {
    if (target_fps <= 0.0f) {
        return 0;
    }
    const uint64_t requested = static_cast<uint64_t>(std::llround(1000000000.0 / target_fps));
    return std::max<uint64_t>(1, requested);
}

}  // namespace

bool amethyst_swappy_init(JNIEnv* env, jobject activity, float target_fps) {
    if (env == nullptr || activity == nullptr || target_fps <= 0.0f) {
        return false;
    }

    std::lock_guard<std::mutex> lock(g_swappy_mutex);
    if (g_swappy_initialized) {
        return g_swappy_enabled;
    }
    g_swappy_state.store(-1, std::memory_order_release);
    const bool initialized = SwappyGL_init(env, activity);
    g_swappy_initialized = true;
    const uint64_t refresh_period = initialized ? SwappyGL_getRefreshPeriodNanos() : 0;
    // The display may be at a lower rate while the activity starts and move to a
    // higher rate after input. Swappy tracks refresh-rate callbacks, so the initial
    // period must not be used as a permanent capability decision.
    g_swappy_enabled = initialized && SwappyGL_isEnabled();
    g_swappy_state.store(g_swappy_enabled ? 1 : 0, std::memory_order_release);
    if (g_swappy_enabled) {
        // The launcher owns the target FPS. Avoid competing adaptive policies while the
        // existing renderer is being migrated to Swappy.
        SwappyGL_setAutoSwapInterval(false);
        SwappyGL_setAutoPipelineMode(false);
        SwappyGL_setSwapIntervalNS(resolve_swap_interval_ns(target_fps));

        // SurfaceView may have been connected before the Activity reaches its surface-ready
        // callback. Bind that window now so the first EGL swap uses the same native window.
        ANativeWindow* window = pojavAcquireBridgeWindow();
        if (window != nullptr) {
            SwappyGL_setWindow(window);
            pojavReleaseBridgeWindow(window);
        }
    }

    const uint64_t swap_interval = g_swappy_enabled
        ? SwappyGL_getSwapIntervalNS()
        : 0;

    std::printf(
        "SwappyBridge: init requested=true initialized=%d enabled=%d targetFps=%.3f "
        "refreshPeriodNs=%llu swapIntervalNs=%llu\n",
        initialized ? 1 : 0,
        g_swappy_enabled ? 1 : 0,
        static_cast<double>(target_fps),
        static_cast<unsigned long long>(refresh_period),
        static_cast<unsigned long long>(swap_interval)
    );
    std::fflush(stdout);
    return g_swappy_enabled;
}

void amethyst_swappy_set_active_refresh_rate(float refresh_rate_hz) {
    if (!std::isfinite(refresh_rate_hz) || refresh_rate_hz <= 0.0f) {
        g_active_refresh_rate_millihz.store(0, std::memory_order_release);
        return;
    }
    const int64_t millihz = std::llround(static_cast<double>(refresh_rate_hz) * 1000.0);
    g_active_refresh_rate_millihz.store(
        static_cast<int32_t>(std::max<int64_t>(1, std::min<int64_t>(millihz, INT32_MAX))),
        std::memory_order_release
    );
}

float amethyst_swappy_get_active_refresh_rate(void) {
    return static_cast<float>(g_active_refresh_rate_millihz.load(std::memory_order_acquire)) / 1000.0f;
}

void amethyst_swappy_set_window(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(g_swappy_mutex);
    if (!g_swappy_initialized || !g_swappy_enabled) {
        return;
    }
    if (!SwappyGL_setWindow(window)) {
        std::printf("SwappyBridge: setWindow failed window=%p\n", window);
        std::fflush(stdout);
    }
}

bool amethyst_swappy_is_enabled(void) {
    std::lock_guard<std::mutex> lock(g_swappy_mutex);
    return g_swappy_initialized && g_swappy_enabled;
}

int amethyst_swappy_get_state(void) {
    return g_swappy_state.load(std::memory_order_acquire);
}

bool amethyst_swappy_swap(EGLDisplay display, EGLSurface surface) {
    std::lock_guard<std::mutex> lock(g_swappy_mutex);
    if (!g_swappy_initialized || !g_swappy_enabled) {
        return false;
    }
    return SwappyGL_swap(display, surface);
}

void amethyst_swappy_destroy(void) {
    std::lock_guard<std::mutex> lock(g_swappy_mutex);
    if (!g_swappy_initialized) {
        return;
    }
    if (g_swappy_enabled) {
        SwappyGL_setWindow(nullptr);
    }
    SwappyGL_destroy();
    g_swappy_enabled = false;
    g_swappy_initialized = false;
    g_swappy_state.store(-1, std::memory_order_release);
    g_active_refresh_rate_millihz.store(0, std::memory_order_release);
    std::printf("SwappyBridge: destroy\n");
    std::fflush(stdout);
}
