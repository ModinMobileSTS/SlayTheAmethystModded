package io.stamethyst.backend.steamcloud

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

/** Exercises real Android builders, without Steam credentials, network or save changes. */
class SteamCloudSyncResultNotificationInstrumentedTest {
    @Test fun resultNotificationIsDismissibleAndUsesADifferentChannelFromProgress() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SteamCloudSyncResultNotice.entries.forEach { notice ->
            val notification = SteamCloudSyncResultNotification.build(context, notice)
            assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
            assertEquals(0, notification.flags and Notification.FLAG_FOREGROUND_SERVICE)
            assertNotEquals(0, notification.flags and Notification.FLAG_AUTO_CANCEL)
            assertEquals(context.getString(notice.title), notification.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals(context.getString(notice.message), notification.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertEquals(SteamCloudSyncResultNotification.CHANNEL_ID, notification.channelId)
            assertNotNull(notification.contentIntent)
        }
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(SteamCloudSyncResultNotification.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test fun errorDetailIsShownOnlyForFailureNotSuccessOrDeferral() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val failure = SteamCloudSyncResultNotification.build(context, SteamCloudSyncResultNotice.FAILED, "test network failure")
        assertTrue(failure.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("test network failure"))
        listOf(SteamCloudSyncResultNotice.COMPLETED, SteamCloudSyncResultNotice.DEFERRED).forEach { notice ->
            val notification = SteamCloudSyncResultNotification.build(context, notice, "not a failure")
            assertEquals(context.getString(notice.message), notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        }
    }
}
