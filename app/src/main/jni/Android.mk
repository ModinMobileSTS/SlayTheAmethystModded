LOCAL_PATH := $(call my-dir)
HERE_PATH := $(LOCAL_PATH)

$(call import-module,prefab/bytehook)
LOCAL_PATH := $(HERE_PATH)

include $(CLEAR_VARS)
LOCAL_MODULE := pojavexec
LOCAL_LDLIBS := -ldl -llog -landroid
LOCAL_C_INCLUDES := \
    $(HERE_PATH) \
    $(HERE_PATH)/ctxbridges \
    $(HERE_PATH)/environ \
    $(HERE_PATH)/jvm_hooks \
    $(HERE_PATH)/driver_helper \
    $(HERE_PATH)/GL
LOCAL_CFLAGS += -Wall -Wextra -Wno-unused-parameter -Wno-unused-function
LOCAL_CFLAGS += -D_GNU_SOURCE -std=gnu17
LOCAL_SRC_FILES := \
    egl_bridge.c \
    ctxbridges/loader_dlopen.c \
    ctxbridges/gl_bridge.c \
    ctxbridges/osm_bridge.c \
    ctxbridges/egl_loader.c \
    ctxbridges/osmesa_loader.c \
    ctxbridges/swap_interval_no_egl.c \
    environ/environ.c \
    jvm_hooks/emui_iterator_fix_hook.c \
    jvm_hooks/java_exec_hooks.c \
    jvm_hooks/lwjgl_dlopen_hook.c \
    input_bridge_v3.c \
    jre_launcher.c \
    utils.c \
    stdio_is.c \
    driver_helper/nsbypass.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := exithook
LOCAL_LDLIBS := -ldl -llog
LOCAL_C_INCLUDES := $(HERE_PATH)
LOCAL_SHARED_LIBRARIES := bytehook pojavexec
LOCAL_CFLAGS += -D_GNU_SOURCE -std=gnu17
LOCAL_SRC_FILES := \
    native_hooks/exit_hook.c \
    native_hooks/chmod_hook.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := pojavexec_awt
LOCAL_C_INCLUDES := $(HERE_PATH)
LOCAL_CFLAGS += -D_GNU_SOURCE -std=gnu17
LOCAL_SRC_FILES := awt_bridge.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := awt_headless
include $(BUILD_SHARED_LIBRARY)

LOCAL_PATH := $(HERE_PATH)/awt_xawt
include $(CLEAR_VARS)
LOCAL_MODULE := awt_xawt
LOCAL_SHARED_LIBRARIES := awt_headless
LOCAL_CFLAGS += -D_GNU_SOURCE -std=gnu17
LOCAL_SRC_FILES := xawt_fake.c
include $(BUILD_SHARED_LIBRARY)
