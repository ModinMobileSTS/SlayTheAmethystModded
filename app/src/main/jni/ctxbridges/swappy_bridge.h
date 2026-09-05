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
bool amethyst_swappy_swap(EGLDisplay display, EGLSurface surface);
void amethyst_swappy_destroy(void);

#ifdef __cplusplus
}
#endif

#endif
