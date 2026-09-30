package com.dot.app.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Resolves an allowlisted package to something the system can actually start.
 *
 * The allowlist in InputGuards is the security boundary — this class never sees
 * free-form model text. It only answers two things honestly: is the package
 * installed, and did the launch actually happen.
 */
class PackageVisibility(private val context: Context) {

    fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun launchIntent(packageName: String): Intent? {
        if (!isInstalled(packageName)) return null
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /** Used by the Settings screen to show which aliases are actually available. */
    fun installedAliases(aliases: Map<String, String>): Map<String, Boolean> =
        aliases.entries.associate { (alias, pkg) -> alias to isInstalled(pkg) }

    fun appStoreUri(packageName: String): Uri = Uri.parse("market://details?id=$packageName")
}
