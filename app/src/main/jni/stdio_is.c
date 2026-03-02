#include <jni.h>
#include <sys/types.h>
#include <stdbool.h>
#include <unistd.h>
#include <pthread.h>
#include <stdio.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>
#include <stdlib.h>
#include <time.h>
#include <environ/environ.h>

#include "stdio_is.h"

//
// Created by maks on 17.02.21.
//

static volatile jobject exitTrap_ctx;
static volatile jclass exitTrap_exitClass;
static volatile jmethodID exitTrap_staticMethod;
static JavaVM *exitTrap_jvm;

static int pfd[2];
static pthread_t logger;
static jmethodID logger_onEventLogged;
static volatile jobject logListener = NULL;
static int latestlog_fd = -1;
static pthread_mutex_t log_io_mutex = PTHREAD_MUTEX_INITIALIZER;
static size_t latestlog_bytes_since_sync = 0;
static long long latestlog_last_sync_ms = 0;

#define LOG_SYNC_INTERVAL_MS 1200LL
#define LOG_SYNC_BYTES_THRESHOLD (64 * 1024)

static long long monotonic_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return 0;
    }
    return ((long long) ts.tv_sec * 1000LL) + ((long long) ts.tv_nsec / 1000000LL);
}

static bool shouldForceLogSync(const char* text) {
    if (text == NULL) {
        return false;
    }
    return strstr(text, "FATAL EXCEPTION") != NULL
        || strstr(text, "OutOfMemoryError") != NULL
        || strstr(text, "EXCEPTION_ACCESS_VIOLATION") != NULL
        || strstr(text, "SIGSEGV") != NULL
        || strstr(text, "SIGABRT") != NULL
        || strstr(text, "ERROR:") != NULL;
}

static void syncLatestLogLocked(bool force) {
    if (latestlog_fd == -1) {
        return;
    }

    long long now = monotonic_ms();
    bool intervalElapsed = false;
    if (now > 0 && latestlog_last_sync_ms > 0) {
        intervalElapsed = (now - latestlog_last_sync_ms) >= LOG_SYNC_INTERVAL_MS;
    }

    if (!force && latestlog_bytes_since_sync < LOG_SYNC_BYTES_THRESHOLD && !intervalElapsed) {
        return;
    }

    fdatasync(latestlog_fd);
    latestlog_bytes_since_sync = 0;
    if (now > 0) {
        latestlog_last_sync_ms = now;
    }
}


static bool recordBuffer(char* buf, ssize_t len) {
    if (buf == NULL || len <= 0) {
        return false;
    }
    if (strstr(buf, "Session ID is")) return false;
    pthread_mutex_lock(&log_io_mutex);
    if (latestlog_fd != -1) {
        ssize_t written = write(latestlog_fd, buf, len);
        if (written > 0) {
            latestlog_bytes_since_sync += (size_t)written;
        }
        syncLatestLogLocked(shouldForceLogSync(buf));
    }
    pthread_mutex_unlock(&log_io_mutex);
    return true;
}

static void *logger_thread() {
    JNIEnv *env;
    jstring writeString;
    JavaVM* dvm = pojav_environ->dalvikJavaVMPtr;
    (*dvm)->AttachCurrentThread(dvm, &env, NULL);
    ssize_t  rsize;
    char buf[2050];
    while((rsize = read(pfd[0], buf, sizeof(buf)-1)) > 0) {
        buf[rsize]=0x00;
        bool shouldRecordString = recordBuffer(buf, rsize); //record with newline int latestlog
        if(buf[rsize-1]=='\n') {
            rsize=rsize-1; //truncate
        }
        if(shouldRecordString && logListener != NULL) {
            writeString = (*env)->NewStringUTF(env, buf); //send to app without newline
            (*env)->CallVoidMethod(env, logListener, logger_onEventLogged, writeString);
            (*env)->DeleteLocalRef(env, writeString);
        }
    }
    pthread_mutex_lock(&log_io_mutex);
    syncLatestLogLocked(true);
    pthread_mutex_unlock(&log_io_mutex);
    (*dvm)->DetachCurrentThread(dvm);
    return NULL;
}
JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Logger_begin(JNIEnv *env, __attribute((unused)) jclass clazz, jstring logPath) {
    pthread_mutex_lock(&log_io_mutex);
    if (latestlog_fd != -1) {
        fdatasync(latestlog_fd);
        close(latestlog_fd);
        latestlog_fd = -1;
        latestlog_bytes_since_sync = 0;
        latestlog_last_sync_ms = 0;
    }
    pthread_mutex_unlock(&log_io_mutex);
    if(logger_onEventLogged == NULL) {
        jclass eventLogListener = (*env)->FindClass(env, "net/kdt/pojavlaunch/Logger$eventLogListener");
        logger_onEventLogged = (*env)->GetMethodID(env, eventLogListener, "onEventLogged", "(Ljava/lang/String;)V");
    }
    jclass ioeClass = (*env)->FindClass(env, "java/io/IOException");


    setvbuf(stdout, 0, _IOLBF, 0); // make stdout line-buffered
    setvbuf(stderr, 0, _IONBF, 0); // make stderr unbuffered

    /* create the pipe and redirect stdout and stderr */
    pipe(pfd);
    dup2(pfd[1], 1);
    dup2(pfd[1], 2);

    /* open latestlog.txt for writing */
    const char* logFilePath = (*env)->GetStringUTFChars(env, logPath, NULL);
    int openedLogFd = open(logFilePath, O_WRONLY | O_TRUNC | O_CREAT, 0644);
    if(openedLogFd == -1) {
        (*env)->ReleaseStringUTFChars(env, logPath, logFilePath);
        (*env)->ThrowNew(env, ioeClass, strerror(errno));
        return;
    }
    (*env)->ReleaseStringUTFChars(env, logPath, logFilePath);
    pthread_mutex_lock(&log_io_mutex);
    latestlog_fd = openedLogFd;
    latestlog_bytes_since_sync = 0;
    latestlog_last_sync_ms = monotonic_ms();
    pthread_mutex_unlock(&log_io_mutex);

    /* spawn the logging thread */
    int result = pthread_create(&logger, 0, logger_thread, 0);
    if(result != 0) {
        pthread_mutex_lock(&log_io_mutex);
        if (latestlog_fd != -1) {
            close(latestlog_fd);
            latestlog_fd = -1;
            latestlog_bytes_since_sync = 0;
            latestlog_last_sync_ms = 0;
        }
        pthread_mutex_unlock(&log_io_mutex);
        (*env)->ThrowNew(env, ioeClass, strerror(result));
    }
    pthread_detach(logger);
}

_Noreturn void nominal_exit(int code, bool is_signal, const char* detail) {
    JNIEnv *env;
    jint errorCode = (*exitTrap_jvm)->GetEnv(exitTrap_jvm, (void**)&env, JNI_VERSION_1_6);
    if(errorCode == JNI_EDETACHED) {
        errorCode = (*exitTrap_jvm)->AttachCurrentThread(exitTrap_jvm, &env, NULL);
    }
    if(errorCode != JNI_OK) {
        // Step on a landmine and die, since we can't invoke the Dalvik exit without attaching to
        // Dalvik.
        // I mean, if Zygote can do that, why can't I?
        killpg(getpgrp(), SIGTERM);
    }
    if(code != 0) {
        // Exit code 0 is pretty established as "eh it's fine"
        // so only open the GUI if the code is != 0
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
    }
    // Delete the reference, not gonna need 'em later anyway
    (*env)->DeleteGlobalRef(env, exitTrap_ctx);
    (*env)->DeleteGlobalRef(env, exitTrap_exitClass);

    // A hat trick, if you will
    // Call the Android System.exit() to perform Android's shutdown hooks and do a
    // fully clean exit.
    // After doing this, either of these will happen:
    // 1. Runtime calls exit() for real and it will be handled by ByteHook's recurse handler
    // and redirected back to the OS
    // 2. Zygote sends SIGTERM (no handling necessary, the process perishes)
    // 3. A different thread calls exit() and the hook will go through the exit_tripped path
    jclass systemClass = (*env)->FindClass(env,"java/lang/System");
    jmethodID exitMethod = (*env)->GetStaticMethodID(env, systemClass, "exit", "(I)V");
    (*env)->CallStaticVoidMethod(env, systemClass, exitMethod, 0);
    // System.exit() should not ever return, but the compiler doesn't know about that
    // so put a while loop here
    while(1) {}
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_Logger_appendToLog(JNIEnv *env, __attribute((unused)) jclass clazz, jstring text) {
    jsize appendStringLength = (*env)->GetStringUTFLength(env, text);
    char newChars[appendStringLength+2];
    (*env)->GetStringUTFRegion(env, text, 0, (*env)->GetStringLength(env, text), newChars);
    newChars[appendStringLength] = '\n';
    newChars[appendStringLength+1] = 0;
    if(recordBuffer(newChars, appendStringLength+1) && logListener != NULL) {
        (*env)->CallVoidMethod(env, logListener, logger_onEventLogged, text);
    }
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Logger_setLogListener(JNIEnv *env, __attribute((unused)) jclass clazz, jobject log_listener) {
    jobject logListenerLocal = logListener;
    if(log_listener == NULL) {
        logListener = NULL;
    }else{
        logListener = (*env)->NewGlobalRef(env, log_listener);
    }
    if(logListenerLocal != NULL) (*env)->DeleteGlobalRef(env, logListenerLocal);
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
