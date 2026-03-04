/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <signal.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <ucontext.h>
#include <unistd.h>
// Boardwalk: missing include
#include <string.h>
#include "utils.h"
#include "environ/environ.h"

// Uncomment to try redirect signal handling to JVM
// #define TRY_SIG2JVM

// PojavLancher: fixme: are these wrong?
#define FULL_VERSION "1.8.0-internal"
#define DOT_VERSION "1.8"

static const char* const_progname = "java";
static const char* const_launcher = "openjdk";
static const char** const_jargs = NULL;
static const char** const_appclasspath = NULL;
static const jboolean const_javaw = JNI_FALSE;
static const jboolean const_cpwildcard = JNI_TRUE;
static const jint const_ergo_class = 0; // DEFAULT_POLICY

typedef jint JLI_Launch_func(int argc, char ** argv, /* main argc, argc */
        int jargc, const char** jargv,          /* java args */
        int appclassc, const char** appclassv,  /* app classpath */
        const char* fullversion,                /* full version defined */
        const char* dotversion,                 /* dot version defined */
        const char* pname,                      /* program name */
        const char* lname,                      /* launcher name */
        jboolean javaargs,                      /* JAVA_ARGS */
        jboolean cpwildcard,                    /* classpath wildcard*/
        jboolean javaw,                         /* windows-only javaw */
        jint ergo                               /* ergonomics class policy */
);

typedef struct {
    int signal_id;
    int signal_code;
    uintptr_t fault_addr;
    int sender_pid;
    int sender_uid;
} abort_signal_packet_t;

typedef struct {
    uintptr_t pc;
    uintptr_t sp;
    uintptr_t lr;
    pid_t tid;
} crash_context_snapshot_t;

static int signal_stack_fd = -1;

static void write_signal_line(const char* line) {
    if(signal_stack_fd == -1 || line == NULL) {
        return;
    }
    size_t len = strlen(line);
    if(len > 0) {
        write(signal_stack_fd, line, len);
    }
}

static void append_signal_linef(const char* fmt, ...) {
    if(signal_stack_fd == -1 || fmt == NULL) {
        return;
    }
    char buf[768];
    va_list args;
    va_start(args, fmt);
    int written = vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    if(written <= 0) {
        return;
    }
    size_t safe_len = (size_t)written;
    if(safe_len >= sizeof(buf)) {
        safe_len = sizeof(buf) - 1;
    }
    write(signal_stack_fd, buf, safe_len);
}

static crash_context_snapshot_t capture_crash_context_snapshot(void* ucontext) {
    crash_context_snapshot_t snapshot;
    memset(&snapshot, 0, sizeof(snapshot));
    snapshot.tid = (pid_t)syscall(__NR_gettid);
    if(ucontext == NULL) {
        return snapshot;
    }
    ucontext_t* context = (ucontext_t*)ucontext;
#if defined(__aarch64__)
    snapshot.pc = (uintptr_t)context->uc_mcontext.pc;
    snapshot.sp = (uintptr_t)context->uc_mcontext.sp;
    snapshot.lr = (uintptr_t)context->uc_mcontext.regs[30];
#elif defined(__arm__)
    snapshot.pc = (uintptr_t)context->uc_mcontext.arm_pc;
    snapshot.sp = (uintptr_t)context->uc_mcontext.arm_sp;
    snapshot.lr = (uintptr_t)context->uc_mcontext.arm_lr;
#elif defined(__x86_64__)
    snapshot.pc = (uintptr_t)context->uc_mcontext.gregs[REG_RIP];
    snapshot.sp = (uintptr_t)context->uc_mcontext.gregs[REG_RSP];
    snapshot.lr = 0;
#elif defined(__i386__)
    snapshot.pc = (uintptr_t)context->uc_mcontext.gregs[REG_EIP];
    snapshot.sp = (uintptr_t)context->uc_mcontext.gregs[REG_ESP];
    snapshot.lr = 0;
#endif
    return snapshot;
}

static void append_symbol_detail(const char* label, uintptr_t address) {
    if(signal_stack_fd == -1 || label == NULL || address == 0) {
        return;
    }
    Dl_info info;
    memset(&info, 0, sizeof(info));
    if(dladdr((void*)address, &info) != 0 && info.dli_fname != NULL) {
        uintptr_t base = (uintptr_t)info.dli_fbase;
        uintptr_t module_offset = address >= base ? (address - base) : 0;
        uintptr_t symbol_addr = (uintptr_t)info.dli_saddr;
        uintptr_t symbol_offset = 0;
        if(info.dli_sname != NULL && symbol_addr != 0 && address >= symbol_addr) {
            symbol_offset = address - symbol_addr;
        }
        append_signal_linef(
            "%s=0x%lx module=%s module_offset=0x%lx symbol=%s symbol_offset=0x%lx\n",
            label,
            (unsigned long)address,
            info.dli_fname,
            (unsigned long)module_offset,
            info.dli_sname != NULL ? info.dli_sname : "?",
            (unsigned long)symbol_offset
        );
        return;
    }
    append_signal_linef("%s=0x%lx module=unknown\n", label, (unsigned long)address);
}

static const char* signal_name(int signal_id) {
    switch(signal_id) {
        case SIGABRT: return "SIGABRT";
        case SIGSEGV: return "SIGSEGV";
        case SIGBUS: return "SIGBUS";
        case SIGILL: return "SIGILL";
        case SIGFPE: return "SIGFPE";
        default: return "UNKNOWN";
    }
}

static void setup_signal_stack_report_file() {
    if(signal_stack_fd != -1) {
        close(signal_stack_fd);
        signal_stack_fd = -1;
    }
}

static void append_signal_stack_header(int signal_id, const siginfo_t* info) {
    if(signal_stack_fd == -1) {
        return;
    }
    char header[512];
    int written = snprintf(
        header,
        sizeof(header),
        "signal=%s(%d)\nsi_code=%d\nsi_addr=0x%lx\nsender_pid=%d\nsender_uid=%d\n"
            "native_stack=unavailable_in_process_signal_handler\n"
            "hint=check pc/lr symbol lines below\n",
        signal_name(signal_id),
        signal_id,
        info != NULL ? info->si_code : 0,
        (unsigned long)(info != NULL ? (uintptr_t)info->si_addr : 0),
        info != NULL ? info->si_pid : 0,
        info != NULL ? info->si_uid : 0
    );
    if(written > 0) {
        size_t safe_len = (size_t)written;
        if(safe_len >= sizeof(header)) {
            safe_len = sizeof(header) - 1;
        }
        write(signal_stack_fd, header, safe_len);
    }
}

static void append_crash_context_snapshot(const crash_context_snapshot_t* snapshot) {
    if(signal_stack_fd == -1 || snapshot == NULL) {
        return;
    }
    append_signal_linef(
        "thread_tid=%d\npc=0x%lx\nsp=0x%lx\nlr=0x%lx\n",
        (int)snapshot->tid,
        (unsigned long)snapshot->pc,
        (unsigned long)snapshot->sp,
        (unsigned long)snapshot->lr
    );
    append_symbol_detail("pc_symbol", snapshot->pc);
    append_symbol_detail("lr_symbol", snapshot->lr);
}

static void abort_waiter_handler(int signal, siginfo_t* info, void* ucontext) {
    abort_signal_packet_t packet;
    memset(&packet, 0, sizeof(packet));
    packet.signal_id = signal;
    if(info != NULL) {
        packet.signal_code = info->si_code;
        packet.fault_addr = (uintptr_t)info->si_addr;
        packet.sender_pid = info->si_pid;
        packet.sender_uid = info->si_uid;
    }
    append_signal_stack_header(signal, info);
    crash_context_snapshot_t context_snapshot = capture_crash_context_snapshot(ucontext);
    append_crash_context_snapshot(&context_snapshot);
    if(signal_stack_fd != -1) {
        append_signal_linef(
            "detail=Captured signal %s(%d), si_code=%d, fault_addr=0x%lx, sender_pid=%d, sender_uid=%d\n",
            signal_name(packet.signal_id),
            packet.signal_id,
            packet.signal_code,
            (unsigned long)packet.fault_addr,
            packet.sender_pid,
            packet.sender_uid
        );
        fsync(signal_stack_fd);
    }
    // IMPORTANT: re-raise with default handling so debuggerd/JVM can emit full fatal stack logs.
    raise(signal);
    _exit(128 + signal);
}

static void abort_waiter_setup() {
    // Track common fatal signals and immediately forward to default handler
    // after writing minimal metadata.
    const static int tracked_signals[] = {SIGABRT, SIGSEGV, SIGBUS, SIGILL, SIGFPE};
    const static int ntracked = (sizeof(tracked_signals) / sizeof(tracked_signals[0]));
    struct sigaction sigactions[ntracked];
    memset(sigactions, 0, sizeof(sigactions));
    for(size_t i = 0; i < ntracked; i++) {
        sigemptyset(&sigactions[i].sa_mask);
        sigactions[i].sa_sigaction = abort_waiter_handler;
        // SA_RESETHAND + SA_NODEFER allows immediate re-raise to hit default disposition.
        sigactions[i].sa_flags = SA_SIGINFO | SA_RESETHAND | SA_NODEFER;
    }
    for(size_t i = 0; i < ntracked; i++) {
        if(sigaction(tracked_signals[i], &sigactions[i], NULL) != 0) {
            printf("Failed to set signal hander for signal %zu: %s", i, strerror(errno));
        }
    }
}

static jint launchJVM(int margc, char** margv) {
   void* libjli = dlopen("libjli.so", RTLD_LAZY | RTLD_GLOBAL);

   // Unset all signal handlers to create a good slate for JVM signal detection.
   struct sigaction clean_sa;
   memset(&clean_sa, 0, sizeof (struct sigaction));
   for(int sigid = SIGHUP; sigid < NSIG; sigid++) {
       clean_sa.sa_handler = SIG_DFL;
       sigaction(sigid, &clean_sa, NULL);
   }
   // Set up the thread that will abort the launcher with an user-facing dialog on a signal.
   setup_signal_stack_report_file();
   abort_waiter_setup();

   // Boardwalk: silence
   // ;
   if (NULL == libjli) {
       ;
       return -1;
   }
   ;

   JLI_Launch_func *pJLI_Launch =
          (JLI_Launch_func *)dlsym(libjli, "JLI_Launch");
    // Boardwalk: silence
    // ;

   if (NULL == pJLI_Launch) {
       ;
       return -1;
   }

   ;

   return pJLI_Launch(margc, margv,
                   0, NULL, // sizeof(const_jargs) / sizeof(char *), const_jargs,
                   0, NULL, // sizeof(const_appclasspath) / sizeof(char *), const_appclasspath,
                   FULL_VERSION,
                   DOT_VERSION,
                   *margv, // (const_progname != NULL) ? const_progname : *margv,
                   *margv, // (const_launcher != NULL) ? const_launcher : *margv,
                   (const_jargs != NULL) ? JNI_TRUE : JNI_FALSE,
                   const_cpwildcard, const_javaw, const_ergo_class);
/*
   return pJLI_Launch(argc, argv, 
       0, NULL, 0, NULL, FULL_VERSION,
       DOT_VERSION, *margv, *margv, // "java", "openjdk",
       JNI_FALSE, JNI_TRUE, JNI_FALSE, 0);
*/
}

/*
 * Class:     com_oracle_dalvik_VMLauncher
 * Method:    launchJVM
 * Signature: ([Ljava/lang/String;)I
 */
JNIEXPORT jint JNICALL Java_com_oracle_dalvik_VMLauncher_launchJVM(JNIEnv *env, jclass clazz, jobjectArray argsArray) {

   jint res = 0;

    if (argsArray == NULL) {
        ;
        //handle error
        return 0;
    }

    int argc = (*env)->GetArrayLength(env, argsArray);
    char **argv = convert_to_char_array(env, argsArray);

    ;

    res = launchJVM(argc, argv);

    ;
    free_char_array(env, argsArray, argv);

    ;

    return res;
}
