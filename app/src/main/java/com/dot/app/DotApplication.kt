package com.dot.app

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class DotApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        refreshCapabilityFlags()
    }

    /**
     * Capability flags are read from the real platform, never assumed. v0.1 ships
     * no external providers, so authentication is always false — that is the
     * actual state, not a placeholder. MainActivity re-reads these after the user
     * answers the notification prompt.
     */
    fun refreshCapabilityFlags() {
        container.bindCapabilityFlags(
            hasPermission = hasNotificationPermission(),
            isAuthenticated = false,
        )
    }

    fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
}
