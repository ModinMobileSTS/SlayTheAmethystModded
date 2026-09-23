package io.stamethyst.backend.process

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

object AppProcess {
    @JvmStatic
    fun isDefaultProcess(context: Context): Boolean {
        val processName = currentProcessName(context)
        return processName.isNullOrEmpty() || processName == context.packageName
    }

    @JvmStatic
    fun currentProcessName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { Application.getProcessName().trim() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }

        val pid = android.os.Process.myPid()
        runCatching {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activityManager
                ?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
                ?.trim()
        }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return runCatching {
            File("/proc/self/cmdline")
                .readText()
                .trimEnd('\u0000')
                .trim()
        }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }
}
