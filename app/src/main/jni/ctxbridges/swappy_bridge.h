#ifndef STS_SWAPPY_BRIDGE_H
#define STS_SWAPPY_BRIDGE_H

#include <EGL/egl.h>
#include <android/native_window.h>
#include <jni.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

bool amethyst_swappy_init(JNIEnv* env, jobject activity, float target_fps);
void amethyst_swappy_set_window(ANativeWindow* window);
bool amethyst_swappy_is_enabled(void);
int amethyst_swappy_get_state(void);
bool amethyst_swappy_swap(EGLDisplay display, EGLSurface surface);
void amethyst_swappy_destroy(void);
void amethyst_swappy_set_active_refresh_rate(float refresh_rate_hz);
float amethyst_swappy_get_active_refresh_rate(void);

#ifdef __cplusplus
}
#endif

#endif
