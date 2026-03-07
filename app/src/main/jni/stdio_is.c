#include <jni.h>
#include <sys/types.h>
#include <stdbool.h>
#include <unistd.h>
#include <signal.h>

#include "stdio_is.h"

//
// Created by maks on 17.02.21.
//

static volatile jobject exitTrap_ctx;
static volatile jclass exitTrap_exitClass;
static volatile jmethodID exitTrap_staticMethod;
static JavaVM *exitTrap_jvm;

_Noreturn void nominal_exit(int code, bool is_signal, const char* detail) {
    JNIEnv *env;
    jint errorCode = (*exitTrap_jvm)->GetEnv(exitTrap_jvm, (void**)&env, JNI_VERSION_1_6);
    if(errorCode == JNI_EDETACHED) {
        errorCode = (*exitTrap_jvm)->AttachCurrentThread(exitTrap_jvm, &env, NULL);
    }
    if(errorCode != JNI_OK) {
        killpg(getpgrp(), SIGTERM);
    }
    jstring detailString = NULL;
    if(detail != NULL && detail[0] != '\0') {
        detailString = (*env)->NewStringUTF(env, detail);
    }
    (*env)->CallStaticVoidMethod(
        env,
        exitTrap_exitClass,
        exitTrap_staticMethod,
        exitTrap_ctx,
        code,
        is_signal,
        detailString
    );
    if(detailString != NULL) {
        (*env)->DeleteLocalRef(env, detailString);
    }
    (*env)->DeleteGlobalRef(env, exitTrap_ctx);
    (*env)->DeleteGlobalRef(env, exitTrap_exitClass);

    jclass systemClass = (*env)->FindClass(env,"java/lang/System");
    jmethodID exitMethod = (*env)->GetStaticMethodID(env, systemClass, "exit", "(I)V");
    (*env)->CallStaticVoidMethod(env, systemClass, exitMethod, 0);
    while(1) {}
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_setupExitMethod(JNIEnv *env, jclass clazz,
                                                        jobject context) {
    exitTrap_ctx = (*env)->NewGlobalRef(env,context);
    (*env)->GetJavaVM(env,&exitTrap_jvm);
    exitTrap_exitClass = (*env)->NewGlobalRef(env,(*env)->FindClass(env,"net/kdt/pojavlaunch/ExitActivity"));
    exitTrap_staticMethod = (*env)->GetStaticMethodID(
        env,
        exitTrap_exitClass,
        "showExitMessage",
        "(Landroid/content/Context;IZLjava/lang/String;)V"
    );
}
