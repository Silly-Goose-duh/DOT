package com.dot.app.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dot.core.common.LogRedactor
import com.dot.core.database.DotDatabase
import com.dot.core.model.Reminder
import com.dot.core.model.ReminderState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * AlarmManager-backed reminder scheduling.
 *
 * Reminders only ever carry local titles. No note body, no email subject and no
 * raw transcript is placed in the notification text — that is the PRD 21 logging
 * rule applied to a user-visible surface.
 */
class ReminderScheduler(
    private val context: Context,
    private val zoneId: ZoneId,
) : ReminderSchedulerPort {

    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    override fun schedule(reminder: Reminder) {
        val am = alarmManager ?: return
        val triggerAt = reminder.remindAt.toEpochMilli()
        if (triggerAt <= System.currentTimeMillis()) return

        val pending = pendingIntent(reminder)
        val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            am.canScheduleExactAlarms()

        try {
            if (canScheduleExact) {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pending,
                )
            } else {
                // No exact-alarm permission: an inexact alarm still fires, just
                // possibly late. Degrade rather than drop the reminder.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancel(reminderId: String) {
        val am = alarmManager ?: return
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            data = android.net.Uri.parse("dot://reminder/$reminderId")
        }
        am.cancel(
            PendingIntent.getBroadcast(
                context,
                reminderId.hashCode(),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: return,
        )
    }

    private fun pendingIntent(reminder: Reminder): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            data = android.net.Uri.parse("dot://reminder/${reminder.id}")
            putExtra(EXTRA_TITLE, reminder.title)
        }
        return PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_FIRE = "com.dot.app.REMINDER_FIRE"
        const val EXTRA_TITLE = "title"
        const val CHANNEL_ID = "dot_reminders"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Reminders",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Local reminders you set in DOT"
                },
            )
        }
    }
}

/** Fires one notification, then leaves the row in the DB for the app to reconcile. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_FIRE) return
        ReminderScheduler.ensureChannel(context)

        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE).orEmpty()
            .let { LogRedactor.safeValue(it, maxLen = 60) }
            .ifBlank { "Reminder" }

        val id = intent.data?.lastPathSegment ?: return
        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("DOT")
            .setContentText(title)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // notify() throws SecurityException without POST_NOTIFICATIONS on 33+.
        // A missing permission must not crash the receiver.
        try {
            NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
        } catch (e: SecurityException) {
            // Permission denied: nothing to do, and no user-visible error state.
        }
    }
}

/**
 * Alarms do not survive a reboot, so every still-pending reminder is rescheduled.
 * Work happens inside goAsync() so the process stays alive past onReceive().
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ReminderScheduler.ensureChannel(context)

        val pending = goAsync()
        val appContext = context.applicationContext
        val db = androidx.room.Room
            .databaseBuilder(appContext, DotDatabase::class.java, "dot.db")
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val now = Instant.now()
                db.reminderDao().all()
                    .map { it.toDomain() }
                    .filter { it.state == ReminderState.SCHEDULED && it.remindAt.isAfter(now) }
                    .forEach { ReminderScheduler(appContext, ZoneId.systemDefault()).schedule(it) }
            } finally {
                db.close()
                pending.finish()
            }
        }
    }
}
