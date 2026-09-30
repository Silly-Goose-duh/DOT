package com.dot.app.reminder

import com.dot.core.model.Reminder

/**
 * Scheduling seam. The runtime depends on this rather than on AlarmManager, so
 * "did a reminder actually get armed?" is assertable without a device.
 */
interface ReminderSchedulerPort {
    fun schedule(reminder: Reminder)
}
