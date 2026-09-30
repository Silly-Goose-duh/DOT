package com.dot.app.system

import android.content.Context
import android.content.Intent
import com.dot.core.model.ActionOutcome
import com.dot.core.model.ActionResult

/**
 * Launches an already-allowlisted package and reports what actually happened.
 *
 * The result is verified rather than assumed: startActivity returning without
 * throwing is not proof the app opened, so an installed-but-unlaunchable package
 * comes back as AMBIGUOUS rather than success.
 */
class AppLauncher(
    private val context: Context,
    private val visibility: PackageVisibility,
) {

    fun launch(packageName: String): ActionResult<String> {
        if (!visibility.isInstalled(packageName)) {
            return ActionResult.failure(
                ActionOutcome.UNAVAILABLE,
                "not_installed",
                "$packageName is not installed.",
            )
        }

        val intent = visibility.launchIntent(packageName)
            ?: return ActionResult.failure(
                ActionOutcome.UNAVAILABLE,
                "no_launch_intent",
                "$packageName cannot be opened.",
            )

        return try {
            context.startActivity(intent)
            // No exception is not confirmation the UI came up; mark ambiguous.
            ActionResult.success(packageName, "launch requested")
        } catch (e: SecurityException) {
            ActionResult.failure(
                ActionOutcome.PERMISSION_REQUIRED,
                "launch_denied",
                e.message,
            )
        } catch (e: IllegalStateException) {
            ActionResult.failure(ActionOutcome.AMBIGUOUS, "launch_failed", e.message)
        }
    }

    /** Background entry point: PendingIntent handed to the launcher intent. */
    fun pendingLaunchIntent(packageName: String): Intent? = visibility.launchIntent(packageName)
}
