package com.dugcanlift.macrocalc

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dugcanlift.macrocalc.data.RestCountdown
import com.dugcanlift.macrocalc.data.RestOutlets
import com.dugcanlift.macrocalc.data.RestTimer

/**
 * Whether LIFT is on screen, set by [MainActivity]'s `onResume`/`onPause` (`onStop` can come seconds late). On screen, the rest banner
 * says "Rest over" and the phone taps ([LiveSessionEffects]), so the alert is not posted on top of it --
 * LIFT iPhone shows its alert in the foreground as a sound only, for the same reason.
 */
object AppVisibility {
    @Volatile
    var visible: Boolean = false
}

/**
 * The rest outside the screen it was started on: the Android counterpart of LIFT iPhone's
 * `SystemRestOutlets`, with an ongoing notification in place of the Live Activity.
 *
 * - **The countdown** is an ongoing, silent notification whose chronometer counts down to the end of
 *   the rest. The system draws it, so it stays right on the lock screen with LIFT in the background,
 *   and it times itself out at the end ([NotificationCompat.Builder.setTimeoutAfter]).
 * - **"Rest over"** is a second notification at the end, posted by whichever comes first: a timer in
 *   the running app, or an alarm ([AlarmManager.setAndAllowWhileIdle], which needs no permission)
 *   for a phone that is asleep or a process Android ended. An inexact alarm, because an exact one
 *   needs a permission LIFT does not ask for: with the phone asleep it can arrive late. The
 *   countdown itself never is.
 *
 * **Nothing new is asked for.** Both use `POST_NOTIFICATIONS`, which route recording already
 * declares. Without it, nothing is posted and the timer runs on screen as before.
 */
class RestNotifications(context: Context) : RestOutlets {

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var pendingOver: Runnable? = null

    override fun restStarted(rest: RestCountdown) = show(rest)

    override fun restChanged(rest: RestCountdown) = show(rest)

    override fun restCleared() {
        cancelOver()
        val manager = NotificationManagerCompat.from(app)
        manager.cancel(COUNTDOWN_ID)
        manager.cancel(OVER_ID)
    }

    override fun sessionEnded() = restCleared()

    private fun show(rest: RestCountdown) {
        cancelOver()
        NotificationManagerCompat.from(app).cancel(OVER_ID)
        val now = System.currentTimeMillis()
        val left = rest.endsAtMs - now
        if (left <= 0) return
        postCountdown(app, rest, left)
        // The running app says it's over on time; the alarm covers a phone asleep or a process gone.
        val over = Runnable { restOver(app, rest.endsAtMs) }
        pendingOver = over
        handler.postDelayed(over, left)
        alarm(app)?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, rest.endsAtMs, overIntent(app, rest.endsAtMs))
    }

    private fun cancelOver() {
        pendingOver?.let(handler::removeCallbacks)
        pendingOver = null
        alarm(app)?.cancel(overIntent(app, 0))
    }

    companion object {
        private const val COUNTDOWN_CHANNEL = "rest_timer"
        private const val OVER_CHANNEL = "rest_over"
        private const val COUNTDOWN_ID = 4301
        private const val OVER_ID = 4302
        internal const val EXTRA_ENDS_AT = "com.dugcanlift.macrocalc.REST_ENDS_AT"
        private const val KEY_ALERTED = "rest_alerted_for"

        private fun alarm(context: Context): AlarmManager? = context.getSystemService(AlarmManager::class.java)

        /** One alarm at a time: the request code and intent never change, so a new one replaces the old. */
        private fun overIntent(context: Context, endsAtMs: Long): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, RestOverReceiver::class.java).putExtra(EXTRA_ENDS_AT, endsAtMs),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun openTrain(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TRAIN_TAB_INDEX)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun permitted(context: Context): Boolean =
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()

        private fun channels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(COUNTDOWN_CHANNEL, "Rest timer", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Counts down your rest between sets during a live session."
                    setShowBadge(false)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(OVER_CHANNEL, "Rest over", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Says when your rest between sets is over."
                }
            )
        }

        /** "Resting", the time left counting down, and "Next: Bench Press (Barbell)": the Live Activity's words. */
        private fun postCountdown(context: Context, rest: RestCountdown, leftMs: Long) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return
            if (!permitted(context)) return
            channels(context)
            val notification = NotificationCompat.Builder(context, COUNTDOWN_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Resting")
                .apply { rest.exerciseName?.let { setContentText("Next: $it") } }
                .setWhen(rest.endsAtMs)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(leftMs)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openTrain(context))
                .build()
            NotificationManagerCompat.from(context).notify(COUNTDOWN_ID, notification)
        }

        /**
         * The rest ending [endsAtMs] is over. Said once, and only if it is still the rest wanted -- a
         * rest skipped, moved or replaced since says nothing -- and only with LIFT off screen.
         */
        internal fun restOver(context: Context, endsAtMs: Long) {
            val app = context.applicationContext
            val current = LiveSessionStore.get(app).timer.current.value
            if (current == null || current.endsAtMs != endsAtMs) return
            NotificationManagerCompat.from(app).cancel(COUNTDOWN_ID)
            // On screen the banner says it, so nothing is posted -- and nothing is marked said: the
            // alarm that follows may find LIFT gone from the screen, and then it is still news.
            if (AppVisibility.visible) return
            val prefs = app.getSharedPreferences("dcl_live_session", Context.MODE_PRIVATE)
            if (prefs.getLong(KEY_ALERTED, 0L) == endsAtMs) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return
            if (!permitted(app)) return
            channels(app)
            val notification = NotificationCompat.Builder(app, OVER_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Rest over")
                .setContentText(current.exerciseName?.let { "Back to $it." } ?: "Time for your next set.")
                .setAutoCancel(true)
                .setTimeoutAfter(RestTimer.KEEPS_OVER_FOR_MS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openTrain(app))
                .build()
            NotificationManagerCompat.from(app).notify(OVER_ID, notification)
            prefs.edit().putLong(KEY_ALERTED, endsAtMs).apply()
        }
    }
}

/** The alarm at the end of a rest, for a phone asleep or a process Android ended ([RestNotifications]). */
class RestOverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val endsAt = intent.getLongExtra(RestNotifications.EXTRA_ENDS_AT, 0L)
        if (endsAt > 0) RestNotifications.restOver(context, endsAt)
    }
}
