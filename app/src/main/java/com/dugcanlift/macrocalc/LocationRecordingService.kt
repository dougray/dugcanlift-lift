package com.dugcanlift.macrocalc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * A minimal, un-bound foreground service. Its only job is the persistent
 * low-priority notification Android requires before any app can keep
 * receiving location updates once it's no longer in the foreground — a
 * locked screen or a backgrounded app mid-run.
 *
 * It does not touch `LocationManager` itself; [LocationTracker] owns that
 * registration directly and keeps receiving callbacks on the main looper
 * regardless of which component is holding the foreground promotion, since
 * this is all one process. This service exists solely so the OS grants that
 * promotion in the first place.
 *
 * Because it is started while the app is visible (the Start tap), its
 * `location` type keeps "while in use" location access for as long as it
 * runs — which is why the app needs no `ACCESS_BACKGROUND_LOCATION`.
 *
 * `targetSdk 37` (confirmed against the installed `android-37.0` SDK, which
 * still defines `ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION` and the
 * `startForeground(id, notification, type)` overload used below) requires
 * both the manifest `<service>` element's `android:foregroundServiceType="location"`
 * AND this runtime call passing the matching `ServiceInfo` type — omitting
 * either throws `MissingForegroundServiceTypeException` /
 * `SecurityException` on API 34+.
 */
class LocationRecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            // Pre-Q has no foreground service type concept at all.
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // NOT_STICKY: a restart after process death would re-post this ongoing
    // notification with no LocationManager registration behind it (that
    // state lives in LocationTracker, in-process, and does not survive
    // process death) — a permanent zombie notification recording nothing.
    // Better to let the service simply not come back; the user has to start
    // a new recording either way.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun buildNotification(): Notification {
        // Turns a stranded recording (the specific failure C-2 fixes, but
        // defense in depth for anyone who ends up there anyway) from "stuck"
        // into "recoverable": tapping the notification brings the user back
        // to the Train tab where the live recording screen is showing.
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TRAIN_TAB_INDEX)
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            // Static text for now — updating this notification's text with
            // live distance as the route accumulates remains a nice-to-have,
            // not a requirement.
            .setContentText("Recording your route")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Route recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing notification shown while a Run or Hike is being recorded."
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "route_recording"
        private const val NOTIFICATION_ID = 4201
    }
}
