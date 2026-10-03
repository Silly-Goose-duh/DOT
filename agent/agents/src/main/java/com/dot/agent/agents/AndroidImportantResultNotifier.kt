package com.dot.agent.agents

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Thin Android adapter.
 *
 * All decision-making and text construction happen in [NotificationPolicy] and
 * [NotificationContentFactory]; this class only posts. That split is what allows
 * "is this worth notifying?" to be tested without a device, and it keeps the
 * notification code small enough to review by eye against SECURITY.md.
 */
class AndroidImportantResultNotifier(
    context: Context,
    private val channelId: String = DEFAULT_CHANNEL_ID,
) : ImportantResultNotifier {

    private val appContext = context.applicationContext

    init {
        ensureChannel()
    }

    override suspend fun notifyImportant(result: ImportantResult): NotifyResult {
        if (!canPostNotifications()) {
            // Permission denial must not crash and must not look like success.
            return NotifyResult.Suppressed(reason = PERMISSION_REQUIRED)
        }
        val content = NotificationContentFactory.build(result)
        if (content.body.isBlank()) return NotifyResult.Suppressed(reason = EMPTY_BODY)

        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        return try {
            NotificationManagerCompat.from(appContext)
                .notify(notificationIdFor(result.agentId), notification)
            NotifyResult.Posted(content)
        } catch (security: SecurityException) {
            // A revoked POST_NOTIFICATIONS between the check and the post.
            NotifyResult.Suppressed(reason = PERMISSION_REQUIRED)
        }
    }

    fun canPostNotifications(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(channelId) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                "Agent results",
                // DEFAULT, not HIGH: an inbox summary is information, not an alarm.
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Summaries from background agents when something needs attention."
                setShowBadge(true)
            },
        )
    }

    private fun notificationIdFor(agentId: String): Int = 1000 + (agentId.hashCode() and 0x0FFF)

    companion object {
        const val DEFAULT_CHANNEL_ID = "dot_agent_results"
        private const val PERMISSION_REQUIRED = "permission_required"
        private const val EMPTY_BODY = "empty_body"
    }
}