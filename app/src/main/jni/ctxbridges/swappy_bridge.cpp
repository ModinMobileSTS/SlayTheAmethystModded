#include "swappy_bridge.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <mutex>

#include <swappy/swappyGL.h>
#include <swappy/swappyGL_extra.h>

namespace {

std::mutex g_swappy_mutex;
bool g_swappy_initialized = false;
bool g_swappy_enabled = false;

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

bool refresh_period_can_represent_target(uint64_t refresh_period, float target_fps) {
    if (refresh_period == 0 || target_fps <= 0.0f) {
        return true;
    }

    const uint64_t requested_period = resolve_swap_interval_ns(target_fps);
    const uint64_t intervals =
        std::max<uint64_t>(1, (requested_period + refresh_period / 2) / refresh_period);
    const uint64_t represented_period = refresh_period * intervals;
    const uint64_t difference = represented_period > requested_period
        ? represented_period - requested_period
        : requested_period - represented_period;

    // Swappy presents on whole display intervals. If the requested cadence cannot be represented
    // closely, raw Swappy pacing would silently choose the next integer interval (90 FPS on a
    // 120Hz panel becomes 60 FPS), so the caller must keep using the software pacer instead.
    return difference <= 250000ULL;
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

    const bool initialized = SwappyGL_init(env, activity);
    g_swappy_initialized = true;
    const uint64_t refresh_period = initialized ? SwappyGL_getRefreshPeriodNanos() : 0;
    const bool compatible_refresh =
        refresh_period_can_represent_target(refresh_period, target_fps);
    g_swappy_enabled = initialized && SwappyGL_isEnabled() && compatible_refresh;
    if (initialized && !compatible_refresh) {
        std::printf(
            "SwappyBridge: disabled reason=incompatible_refresh targetFps=%.3f "
            "refreshPeriodNs=%llu requestedPeriodNs=%llu\n",
            static_cast<double>(target_fps),
            static_cast<unsigned long long>(refresh_period),
            static_cast<unsigned long long>(resolve_swap_interval_ns(target_fps))
        );
        std::fflush(stdout);
    }
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
    std::printf("SwappyBridge: destroy\n");
    std::fflush(stdout);
}
