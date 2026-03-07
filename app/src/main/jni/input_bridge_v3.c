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

#define TAG __FILE_NAME__
#include "utils.h"
#include "environ/environ.h"
#include "jvm_hooks/jvm_hooks.h"
#include "ctxbridges/gl_bridge.h"

#define EVENT_TYPE_CHAR 1000
#define EVENT_TYPE_CHAR_MODS 1001
#define EVENT_TYPE_CURSOR_ENTER 1002
#define EVENT_TYPE_KEY 1005
#define EVENT_TYPE_MOUSE_BUTTON 1006
#define EVENT_TYPE_SCROLL 1007
#define CLIPBOARD_CONTEXT_GENERATION_QUERY 2999

#define TRY_ATTACH_ENV(env_name, vm, error_message, then) JNIEnv* env_name;\
do {                                                                       \
    env_name = get_attached_env(vm);                                       \
    if(env_name == NULL) {                                                 \
        printf(error_message);                                             \
        then                                                               \
    }                                                                      \
} while(0)

#define AL_GAIN 0x100A
#define MIN_VALID_SCREEN_SIZE 1
#define MAX_VALID_SCREEN_SIZE 32768

static bool isValidScreenSize(int width, int height) {
    return width >= MIN_VALID_SCREEN_SIZE &&
           height >= MIN_VALID_SCREEN_SIZE &&
           width <= MAX_VALID_SCREEN_SIZE &&
           height <= MAX_VALID_SCREEN_SIZE;
}

typedef void* (*POJAV_alcGetCurrentContext_fn)(void);
typedef void (*POJAV_alListenerf_fn)(int param, float value);
typedef void (*POJAV_alGetListenerf_fn)(int param, float* value);

static POJAV_alcGetCurrentContext_fn pojav_alcGetCurrentContext = NULL;
static POJAV_alListenerf_fn pojav_alListenerf = NULL;
static POJAV_alGetListenerf_fn pojav_alGetListenerf = NULL;
static bool pojav_openal_resolve_attempted = false;
static bool pojav_openal_resolved = false;
static bool pojav_openal_no_context_logged = false;
static bool pojav_audio_force_muted = false;
static float pojav_saved_listener_gain = 1.0f;
static bool pojav_saved_listener_gain_valid = false;

static bool resolveOpenalSymbols(void) {
    if (pojav_openal_resolved) {
        return true;
    }
    if (pojav_openal_resolve_attempted) {
        return false;
    }
    pojav_openal_resolve_attempted = true;

    pojav_alcGetCurrentContext = (POJAV_alcGetCurrentContext_fn) dlsym(RTLD_DEFAULT, "alcGetCurrentContext");
    pojav_alListenerf = (POJAV_alListenerf_fn) dlsym(RTLD_DEFAULT, "alListenerf");
    pojav_alGetListenerf = (POJAV_alGetListenerf_fn) dlsym(RTLD_DEFAULT, "alGetListenerf");

    if (pojav_alcGetCurrentContext == NULL || pojav_alListenerf == NULL) {
        void* handle = dlopen("libopenal.so", RTLD_NOW | RTLD_GLOBAL);
        if (handle != NULL) {
            if (pojav_alcGetCurrentContext == NULL) {
                pojav_alcGetCurrentContext = (POJAV_alcGetCurrentContext_fn) dlsym(handle, "alcGetCurrentContext");
            }
            if (pojav_alListenerf == NULL) {
                pojav_alListenerf = (POJAV_alListenerf_fn) dlsym(handle, "alListenerf");
            }
            if (pojav_alGetListenerf == NULL) {
                pojav_alGetListenerf = (POJAV_alGetListenerf_fn) dlsym(handle, "alGetListenerf");
            }
        }
    }

    if (pojav_alcGetCurrentContext == NULL || pojav_alListenerf == NULL) {
        ;
        return false;
    }

    pojav_openal_resolved = true;
    return true;
}

static void registerFunctions(JNIEnv *env);

jint JNI_OnLoad(JavaVM* vm, __attribute__((unused)) void* reserved) {
    if (pojav_environ->dalvikJavaVMPtr == NULL) {
        ;
        //Save dalvik global JavaVM pointer
        pojav_environ->dalvikJavaVMPtr = vm;
        JNIEnv *dvEnv;
        (*vm)->GetEnv(vm, (void**) &dvEnv, JNI_VERSION_1_4);
        pojav_environ->bridgeClazz = (*dvEnv)->NewGlobalRef(dvEnv,(*dvEnv) ->FindClass(dvEnv,"org/lwjgl/glfw/CallbackBridge"));
        pojav_environ->method_accessAndroidClipboard = (*dvEnv)->GetStaticMethodID(dvEnv, pojav_environ->bridgeClazz, "accessAndroidClipboard", "(ILjava/lang/String;)Ljava/lang/String;");
        pojav_environ->method_onGrabStateChanged = (*dvEnv)->GetStaticMethodID(dvEnv, pojav_environ->bridgeClazz, "onGrabStateChanged", "(Z)V");
        pojav_environ->method_onDirectInputEnable = (*dvEnv)->GetStaticMethodID(dvEnv, pojav_environ->bridgeClazz, "onDirectInputEnable", "()V");
        pojav_environ->isUseStackQueueCall = JNI_FALSE;
    } else if (pojav_environ->dalvikJavaVMPtr != vm) {
        ;
        pojav_environ->runtimeJavaVMPtr = vm;
        JNIEnv *vmEnv;
        (*vm)->GetEnv(vm, (void**) &vmEnv, JNI_VERSION_1_4);
        pojav_environ->vmGlfwClass = (*vmEnv)->NewGlobalRef(vmEnv, (*vmEnv)->FindClass(vmEnv, "org/lwjgl/glfw/GLFW"));
        pojav_environ->method_glftSetWindowAttrib = (*vmEnv)->GetStaticMethodID(vmEnv, pojav_environ->vmGlfwClass, "glfwSetWindowAttrib", "(JII)V");
        pojav_environ->method_glfwSetWindowShouldClose = (*vmEnv)->GetStaticMethodID(vmEnv, pojav_environ->vmGlfwClass, "glfwSetWindowShouldClose", "(JZ)V");
        pojav_environ->method_internalWindowSizeChanged = (*vmEnv)->GetStaticMethodID(vmEnv, pojav_environ->vmGlfwClass, "internalWindowSizeChanged", "(J)V");
        pojav_environ->method_internalChangeMonitorSize = (*vmEnv)->GetStaticMethodID(vmEnv, pojav_environ->vmGlfwClass, "internalChangeMonitorSize", "(II)V");
        jfieldID field_keyDownBuffer = (*vmEnv)->GetStaticFieldID(vmEnv, pojav_environ->vmGlfwClass, "keyDownBuffer", "Ljava/nio/ByteBuffer;");
        jobject keyDownBufferJ = (*vmEnv)->GetStaticObjectField(vmEnv, pojav_environ->vmGlfwClass, field_keyDownBuffer);
        pojav_environ->keyDownBuffer = (*vmEnv)->GetDirectBufferAddress(vmEnv, keyDownBufferJ);
        jfieldID field_mouseDownBuffer = (*vmEnv)->GetStaticFieldID(vmEnv, pojav_environ->vmGlfwClass, "mouseDownBuffer", "Ljava/nio/ByteBuffer;");
        jobject mouseDownBufferJ = (*vmEnv)->GetStaticObjectField(vmEnv, pojav_environ->vmGlfwClass, field_mouseDownBuffer);
        pojav_environ->mouseDownBuffer = (*vmEnv)->GetDirectBufferAddress(vmEnv, mouseDownBufferJ);
        hookExec(vmEnv);
        installLwjglDlopenHook(vmEnv);
        installEMUIIteratorMititgation(vmEnv);
    }

    if(pojav_environ->dalvikJavaVMPtr == vm) {
        //perform in all DVM instances, not only during first ever set up
        JNIEnv *env;
        (*vm)->GetEnv(vm, (void**) &env, JNI_VERSION_1_4);
        registerFunctions(env);
    }
    pojav_environ->isGrabbing = JNI_FALSE;

    return JNI_VERSION_1_4;
}

#define ADD_CALLBACK_WWIN(NAME) \
JNIEXPORT jlong JNICALL Java_org_lwjgl_glfw_GLFW_nglfwSet##NAME##Callback(JNIEnv * env, jclass cls, jlong window, jlong callbackptr) { \
    void** oldCallback = (void**) &pojav_environ->GLFW_invoke_##NAME; \
    pojav_environ->GLFW_invoke_##NAME = (GLFW_invoke_##NAME##_func*) (uintptr_t) callbackptr; \
    return (jlong) (uintptr_t) *oldCallback; \
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
    if (!isValidScreenSize(width, height)) {
        ;
        return;
    }
    (*pojav_environ->glfwThreadVmEnv)->CallStaticVoidMethod(pojav_environ->glfwThreadVmEnv, pojav_environ->vmGlfwClass, pojav_environ->method_internalChangeMonitorSize, width, height);
}
void updateWindowSize(void* window) {
    (*pojav_environ->glfwThreadVmEnv)->CallStaticVoidMethod(pojav_environ->glfwThreadVmEnv, pojav_environ->vmGlfwClass, pojav_environ->method_internalWindowSizeChanged, (jlong)window);
}

void pojavPumpEvents(void* window) {
    if(pojav_environ->shouldUpdateMouse) {
        pojav_environ->GLFW_invoke_CursorPos(window, floor(pojav_environ->cursorX),
                                             floor(pojav_environ->cursorY));
    }
    if(pojav_environ->shouldUpdateMonitorSize) {
        updateWindowSize(window);
    }

    size_t index = pojav_environ->outEventIndex;
    size_t targetIndex = pojav_environ->outTargetIndex;

    while (targetIndex != index) {
        GLFWInputEvent event = pojav_environ->events[index];
        switch (event.type) {
            case EVENT_TYPE_CHAR:
                if(pojav_environ->GLFW_invoke_Char) pojav_environ->GLFW_invoke_Char(window, event.i1);
                break;
            case EVENT_TYPE_CHAR_MODS:
                if(pojav_environ->GLFW_invoke_CharMods) pojav_environ->GLFW_invoke_CharMods(window, event.i1, event.i2);
                break;
            case EVENT_TYPE_KEY:
                if(pojav_environ->GLFW_invoke_Key) pojav_environ->GLFW_invoke_Key(window, event.i1, event.i2, event.i3, event.i4);
                break;
            case EVENT_TYPE_MOUSE_BUTTON:
                if(pojav_environ->GLFW_invoke_MouseButton) pojav_environ->GLFW_invoke_MouseButton(window, event.i1, event.i2, event.i3);
                break;
            case EVENT_TYPE_SCROLL:
                if(pojav_environ->GLFW_invoke_Scroll) pojav_environ->GLFW_invoke_Scroll(window, event.i1, event.i2);
                break;
        }

        index++;
        if (index >= EVENT_WINDOW_SIZE)
            index -= EVENT_WINDOW_SIZE;
    }

    // The out target index is updated by the rewinder
}

/** Prepare the library for sending out callbacks to all windows */
void pojavStartPumping() {
    size_t counter = atomic_load_explicit(&pojav_environ->eventCounter, memory_order_acquire);
    size_t index = pojav_environ->outEventIndex;

    unsigned targetIndex = index + counter;
    if (targetIndex >= EVENT_WINDOW_SIZE)
        targetIndex -= EVENT_WINDOW_SIZE;

    // Only accessed by one unique thread, no need for atomic store
    pojav_environ->inEventCount = counter;
    pojav_environ->outTargetIndex = targetIndex;

    //PumpEvents is called for every window, so this logic should be there in order to correctly distribute events to all windows.
    if((pojav_environ->cLastX != pojav_environ->cursorX || pojav_environ->cLastY != pojav_environ->cursorY) && pojav_environ->GLFW_invoke_CursorPos) {
        pojav_environ->cLastX = pojav_environ->cursorX;
        pojav_environ->cLastY = pojav_environ->cursorY;
        pojav_environ->shouldUpdateMouse = true;
    }
    if(pojav_environ->shouldUpdateMonitorSize) {
        // Perform a monitor size update here to avoid doing it on every single window
        updateMonitorSize(pojav_environ->savedWidth, pojav_environ->savedHeight);
        // Mark the monitor size as consumed (since GLFW was made aware of it)
        pojav_environ->monitorSizeConsumed = true;
    }
}

/** Prepare the library for the next round of new events */
void pojavStopPumping() {
    pojav_environ->outEventIndex = pojav_environ->outTargetIndex;

    // New events may have arrived while pumping, so remove only the difference before the start and end of execution
    atomic_fetch_sub_explicit(&pojav_environ->eventCounter, pojav_environ->inEventCount, memory_order_acquire);
    // Make sure the next frame won't send mouse or monitor updates if it's unnecessary
    pojav_environ->shouldUpdateMouse = false;
    // Only reset the update flag if the monitor size was consumed by pojavStartPumping. This
    // will delay the update to next frame if it had occured between pojavStartPumping and pojavStopPumping,
    // but it's better than not having it apply at all
    if(pojav_environ->shouldUpdateMonitorSize && pojav_environ->monitorSizeConsumed) {
        pojav_environ->shouldUpdateMonitorSize = false;
        pojav_environ->monitorSizeConsumed = false;
    }

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
    GLFWInputEvent *event = &pojav_environ->events[pojav_environ->inEventIndex];
    event->type = type;
    event->i1 = i1;
    event->i2 = i2;
    event->i3 = i3;
    event->i4 = i4;

    if (++pojav_environ->inEventIndex >= EVENT_WINDOW_SIZE)
        pojav_environ->inEventIndex -= EVENT_WINDOW_SIZE;

    atomic_fetch_add_explicit(&pojav_environ->eventCounter, 1, memory_order_acquire);
}

void critical_set_stackqueue(jboolean use_input_stack_queue) {
    pojav_environ->isUseStackQueueCall = (int) use_input_stack_queue;
}

void noncritical_set_stackqueue(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jclass clazz, jboolean use_input_stack_queue) {
    critical_set_stackqueue(use_input_stack_queue);
}

JNIEXPORT jstring JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeClipboard(JNIEnv* env, __attribute__((unused)) jclass clazz, jint action, jbyteArray copySrc) {
#ifdef DEBUG
    ;
#endif

    if (action == CLIPBOARD_CONTEXT_GENERATION_QUERY) {
        int generation = 0;
        if (pojav_environ != NULL) {
            generation = atomic_load_explicit(&pojav_environ->glContextGeneration, memory_order_relaxed);
        }
        char generationString[32];
        snprintf(generationString, sizeof(generationString), "%d", generation);
        return (*env)->NewStringUTF(env, generationString);
    }

    JNIEnv *dalvikEnv;
    (*pojav_environ->dalvikJavaVMPtr)->AttachCurrentThread(pojav_environ->dalvikJavaVMPtr, &dalvikEnv, NULL);
    assert(dalvikEnv != NULL);
    assert(pojav_environ->bridgeClazz != NULL);

    ;
    char *copySrcC;
    jstring copyDst = NULL;
    if (copySrc) {
        copySrcC = (char *)((*env)->GetByteArrayElements(env, copySrc, NULL));
        copyDst = (*dalvikEnv)->NewStringUTF(dalvikEnv, copySrcC);
    }

    ;
    jstring pasteDst = convertStringJVM(dalvikEnv, env, (jstring) (*dalvikEnv)->CallStaticObjectMethod(dalvikEnv, pojav_environ->bridgeClazz, pojav_environ->method_accessAndroidClipboard, action, copyDst));

    if (copySrc) {
        (*dalvikEnv)->DeleteLocalRef(dalvikEnv, copyDst);
        (*env)->ReleaseByteArrayElements(env, copySrc, (jbyte *)copySrcC, 0);
    }
    (*pojav_environ->dalvikJavaVMPtr)->DetachCurrentThread(pojav_environ->dalvikJavaVMPtr);
    return pasteDst;
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

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jboolean grabbing) {
    TRY_ATTACH_ENV(dvm_env, pojav_environ->dalvikJavaVMPtr, "nativeSetGrabbing failed!\n", return;);
    (*dvm_env)->CallStaticVoidMethod(dvm_env, pojav_environ->bridgeClazz, pojav_environ->method_onGrabStateChanged, grabbing);
    pojav_environ->isGrabbing = grabbing;
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeEnableGamepadDirectInput(__attribute__((unused)) JNIEnv *env, __attribute__((unused))  jclass clazz) {
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
            pojav_environ->GLFW_invoke_CursorPos((void*) pojav_environ->showingWindow, (double) (x), (double) (y));
        } else {
            pojav_environ->cursorX = x;
            pojav_environ->cursorY = y;
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
        int key_index = key - 31;
        if (pojav_environ->keyDownBuffer != NULL && key_index >= 0 && key_index < 512) {
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
    if (pojav_environ->mouseDownBuffer != NULL) {
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

void critical_send_screen_size(jint width, jint height) {
    if (!isValidScreenSize(width, height)) {
        ;
        return;
    }
    pojav_environ->savedWidth = width;
    pojav_environ->savedHeight = height;
    ;
    // Even if there was call to pojavStartPumping that consumed the size, this call
    // might happen right after it (or right before pojavStopPumping)
    // So unmark the size as "consumed"
    pojav_environ->monitorSizeConsumed = false;
    pojav_environ->shouldUpdateMonitorSize = true;
    // Don't use the direct updates  for screen dimensions.
    // This is done to ensure that we have predictable conditions to correctly call
    // updateMonitorSize() and updateWindowSize() while on the render thread with an attached
    // JNIEnv.
}

void noncritical_send_screen_size(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint width, jint height) {
    critical_send_screen_size(width, height);
}

void critical_send_scroll(jdouble xoffset, jdouble yoffset) {
    if (pojav_environ->GLFW_invoke_Scroll && pojav_environ->isInputReady) {
        if (pojav_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_SCROLL, (int)xoffset, (int)yoffset, 0, 0);
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
    if (!resolveOpenalSymbols()) {
        return;
    }

    if (pojav_alcGetCurrentContext() == NULL) {
        if (!pojav_openal_no_context_logged) {
            ;
            pojav_openal_no_context_logged = true;
        }
        return;
    }
    pojav_openal_no_context_logged = false;

    if (muted) {
        if (pojav_audio_force_muted) {
            return;
        }
        if (pojav_alGetListenerf != NULL) {
            float currentGain = 1.0f;
            pojav_alGetListenerf(AL_GAIN, &currentGain);
            if (currentGain >= 0.0f && currentGain <= 16.0f) {
                pojav_saved_listener_gain = currentGain;
                pojav_saved_listener_gain_valid = true;
            }
        }
        pojav_alListenerf(AL_GAIN, 0.0f);
        pojav_audio_force_muted = true;
        ;
        return;
    }

    if (!pojav_audio_force_muted) {
        return;
    }
    pojav_alListenerf(AL_GAIN, pojav_saved_listener_gain_valid ? pojav_saved_listener_gain : 1.0f);
    pojav_audio_force_muted = false;
    ;
}

const static JNINativeMethod critical_fcns[] = {
        {"nativeSetUseInputStackQueue", "(Z)V", critical_set_stackqueue},
        {"nativeSendChar", "(C)Z", critical_send_char},
        {"nativeSendCharMods", "(CI)Z", critical_send_char_mods},
        {"nativeSendKey", "(IIII)V", critical_send_key},
        {"nativeSendCursorPos", "(FF)V", critical_send_cursor_pos},
        {"nativeSendMouseButton", "(III)V", critical_send_mouse_button},
        {"nativeSendScroll", "(DD)V", critical_send_scroll},
        {"nativeSendScreenSize", "(II)V", critical_send_screen_size}
};

const static JNINativeMethod noncritical_fcns[] = {
        {"nativeSetUseInputStackQueue", "(Z)V", noncritical_set_stackqueue},
        {"nativeSendChar", "(C)Z", noncritical_send_char},
        {"nativeSendCharMods", "(CI)Z", noncritical_send_char_mods},
        {"nativeSendKey", "(IIII)V", noncritical_send_key},
        {"nativeSendCursorPos", "(FF)V", noncritical_send_cursor_pos},
        {"nativeSendMouseButton", "(III)V", noncritical_send_mouse_button},
        {"nativeSendScroll", "(DD)V", noncritical_send_scroll},
        {"nativeSendScreenSize", "(II)V", noncritical_send_screen_size}
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
    jmethodID criticalNativeTestMethod = (*env)->GetStaticMethodID(env, criticalNativeTest, "invokeTest", "()V");
    (*env)->RegisterNatives(env, criticalNativeTest, testJNIMethod, 1);
    (*env)->CallStaticVoidMethod(env, criticalNativeTest, criticalNativeTestMethod);
    (*env)->UnregisterNatives(env, criticalNativeTest);
    return criticalNativeAvailable;
}

static void registerFunctions(JNIEnv *env) {
    bool critical_supported = tryCriticalNative(env);
    bool use_critical_cc = false;
    jclass bridge_class = (*env)->FindClass(env, "org/lwjgl/glfw/CallbackBridge");
    if (critical_supported) {
        ;
    }else{
        ;
    }
    (*env)->RegisterNatives(env,
                            bridge_class,
                            use_critical_cc ? critical_fcns : noncritical_fcns,
                            sizeof(critical_fcns)/sizeof(critical_fcns[0]));
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
