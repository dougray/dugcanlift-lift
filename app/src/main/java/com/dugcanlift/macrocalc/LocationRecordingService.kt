package com.dugcanlift.macrocalc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            // Static text for now — Task 6/7 wires the live recording screen;
            // updating this notification's text with live distance as the
            // route accumulates is a nice-to-have left for that task, not a
            // requirement of this one.
            .setContentText("Recording your route")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

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
