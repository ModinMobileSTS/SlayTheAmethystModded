package io.stamethyst.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

internal fun shouldRequestSteamCloudNotificationPermission(
    sdkInt: Int,
    available: Boolean,
    granted: Boolean,
    previouslyRequested: Boolean,
): Boolean = sdkInt >= 33 && available && !granted && !previouslyRequested

/** Ask once when cloud saves become available, not after the launcher has closed for the game. */
@Composable
internal fun SteamCloudNotificationPermissionEffect(available: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) { context.getSharedPreferences("steam_cloud_notifications", Context.MODE_PRIVATE) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(available, lifecycleOwner) {
        if (!available) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (shouldRequestSteamCloudNotificationPermission(Build.VERSION.SDK_INT, available,
                    granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                    previouslyRequested = preferences.getBoolean("permission_requested", false))) {
                // Declining must not gate game launch or lead to a prompt on every launcher return.
                preferences.edit().putBoolean("permission_requested", true).apply()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
