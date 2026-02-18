//
// Created by maks on 21.09.2022.
//
#include <stddef.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <string.h>
#include "egl_loader.h"

EGLBoolean (*eglMakeCurrent_p) (EGLDisplay dpy, EGLSurface draw, EGLSurface read, EGLContext ctx);
EGLBoolean (*eglDestroyContext_p) (EGLDisplay dpy, EGLContext ctx);
EGLBoolean (*eglDestroySurface_p) (EGLDisplay dpy, EGLSurface surface);
EGLBoolean (*eglTerminate_p) (EGLDisplay dpy);
EGLBoolean (*eglReleaseThread_p) (void);
EGLContext (*eglGetCurrentContext_p) (void);
EGLDisplay (*eglGetDisplay_p) (NativeDisplayType display);
EGLBoolean (*eglInitialize_p) (EGLDisplay dpy, EGLint *major, EGLint *minor);
EGLBoolean (*eglChooseConfig_p) (EGLDisplay dpy, const EGLint *attrib_list, EGLConfig *configs, EGLint config_size, EGLint *num_config);
EGLBoolean (*eglGetConfigAttrib_p) (EGLDisplay dpy, EGLConfig config, EGLint attribute, EGLint *value);
EGLBoolean (*eglBindAPI_p) (EGLenum api);
EGLSurface (*eglCreatePbufferSurface_p) (EGLDisplay dpy, EGLConfig config, const EGLint *attrib_list);
EGLSurface (*eglCreateWindowSurface_p) (EGLDisplay dpy, EGLConfig config, NativeWindowType window, const EGLint *attrib_list);
EGLBoolean (*eglSwapBuffers_p) (EGLDisplay dpy, EGLSurface draw);
EGLint (*eglGetError_p) (void);
EGLContext (*eglCreateContext_p) (EGLDisplay dpy, EGLConfig config, EGLContext share_list, const EGLint *attrib_list);
EGLBoolean (*eglSwapInterval_p) (EGLDisplay dpy, EGLint interval);
EGLSurface (*eglGetCurrentSurface_p) (EGLint readdraw);
EGLBoolean (*eglQuerySurface_p)( 	EGLDisplay display,
                                           EGLSurface surface,
                                           EGLint attribute,
                                           EGLint * value);
__eglMustCastToProperFunctionPointerType (*eglGetProcAddress_p) (const char *procname);

static void* load_egl_symbol(void* dl_handle, const char* name) {
    void* symbol = dlsym(dl_handle, name);
    if (symbol == NULL && eglGetProcAddress_p != NULL) {
        symbol = (void*) eglGetProcAddress_p(name);
    }
    return symbol;
}

static bool has_required_symbols() {
    return eglMakeCurrent_p != NULL
           && eglDestroyContext_p != NULL
           && eglDestroySurface_p != NULL
           && eglTerminate_p != NULL
           && eglReleaseThread_p != NULL
           && eglGetDisplay_p != NULL
           && eglInitialize_p != NULL
           && eglChooseConfig_p != NULL
           && eglGetConfigAttrib_p != NULL
           && eglBindAPI_p != NULL
           && eglCreatePbufferSurface_p != NULL
           && eglCreateWindowSurface_p != NULL
           && eglSwapBuffers_p != NULL
           && eglGetError_p != NULL
           && eglCreateContext_p != NULL;
}

bool dlsym_EGL() {
    char* gles = getenv("LIBGL_GLES");
    char* eglName = (strncmp(gles ? gles : "", "libGLESv2_angle.so", 18) == 0) ? "libEGL_angle.so" : getenv("POJAVEXEC_EGL");
    // Kopper needs this
    if (eglName != NULL && strncmp(eglName, "libEGL_mesa.so", 14) == 0) {
        void* cutils_handle = dlopen("libcutils.so", RTLD_GLOBAL | RTLD_NOW);
        if (cutils_handle == NULL) {
            cutils_handle = dlopen("/system/lib64/libcutils.so", RTLD_GLOBAL | RTLD_NOW);
        }
        if (cutils_handle == NULL) {
            printf("EGLBridge: optional libcutils preload failed, continue without it\n");
        }
    }

    void* dl_handle = NULL;
    if (eglName != NULL) {
        dl_handle = dlopen(eglName, RTLD_LOCAL | RTLD_LAZY);
        if (dl_handle == NULL) {
            printf("EGLBridge: failed to dlopen requested EGL \"%s\", trying system libEGL.so\n", eglName);
        }
    }
    if (dl_handle == NULL) {
        dl_handle = dlopen("libEGL.so", RTLD_LOCAL | RTLD_LAZY);
    }
    if (dl_handle == NULL) {
        printf("EGLBridge: failed to load EGL library\n");
        return false;
    }
    // Some EGL implementations don't expose every core symbol via eglGetProcAddress.
    // Keep it optional and resolve each function with dlsym fallback.
    eglGetProcAddress_p = dlsym(dl_handle, "eglGetProcAddress");
    eglBindAPI_p = (void*) load_egl_symbol(dl_handle, "eglBindAPI");
    eglChooseConfig_p = (void*) load_egl_symbol(dl_handle, "eglChooseConfig");
    eglCreateContext_p = (void*) load_egl_symbol(dl_handle, "eglCreateContext");
    eglCreatePbufferSurface_p = (void*) load_egl_symbol(dl_handle, "eglCreatePbufferSurface");
    eglCreateWindowSurface_p = (void*) load_egl_symbol(dl_handle, "eglCreateWindowSurface");
    eglDestroyContext_p = (void*) load_egl_symbol(dl_handle, "eglDestroyContext");
    eglDestroySurface_p = (void*) load_egl_symbol(dl_handle, "eglDestroySurface");
    eglGetConfigAttrib_p = (void*) load_egl_symbol(dl_handle, "eglGetConfigAttrib");
    eglGetCurrentContext_p = (void*) load_egl_symbol(dl_handle, "eglGetCurrentContext");
    eglGetDisplay_p = (void*) load_egl_symbol(dl_handle, "eglGetDisplay");
    eglGetError_p = (void*) load_egl_symbol(dl_handle, "eglGetError");
    eglInitialize_p = (void*) load_egl_symbol(dl_handle, "eglInitialize");
    eglMakeCurrent_p = (void*) load_egl_symbol(dl_handle, "eglMakeCurrent");
    eglSwapBuffers_p = (void*) load_egl_symbol(dl_handle, "eglSwapBuffers");
    eglReleaseThread_p = (void*) load_egl_symbol(dl_handle, "eglReleaseThread");
    eglSwapInterval_p = (void*) load_egl_symbol(dl_handle, "eglSwapInterval");
    eglTerminate_p = (void*) load_egl_symbol(dl_handle, "eglTerminate");
    eglGetCurrentSurface_p = (void*) load_egl_symbol(dl_handle, "eglGetCurrentSurface");
    eglQuerySurface_p = (void*) load_egl_symbol(dl_handle, "eglQuerySurface");

    if (!has_required_symbols()) {
        printf("EGLBridge: missing required EGL symbols (egl=%s)\n", eglName == NULL ? "libEGL.so" : eglName);
        return false;
    }
    return true;
}
