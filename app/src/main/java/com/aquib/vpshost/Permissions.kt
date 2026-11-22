package com.aquib.vpshost

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Every item here must be granted before the app lets the user in. */
enum class Perm(val title: String, val why: String) {
    NOTIFICATIONS("Notifications", "Shows the always-on status notification that keeps the server alive."),
    ALL_FILES("All files access", "Stores and reads the Express server files on the device storage."),
    BATTERY("Unrestricted battery", "Stops Android from stopping the server in the background."),
    DEVICE_ADMIN("Device administrator", "Makes the app harder to kill or remove by accident."),
    DND_ACCESS("Do Not Disturb access", "Lets the low-battery alarm sound even when the phone is silent or in Do Not Disturb.");
}

object Permissions {
    fun adminComponent(c: Context) = ComponentName(c, AdminReceiver::class.java)

    fun has(c: Context, p: Perm): Boolean = when (p) {
        Perm.NOTIFICATIONS -> Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        Perm.ALL_FILES -> Environment.isExternalStorageManager()
        Perm.BATTERY -> c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
        Perm.DEVICE_ADMIN -> c.getSystemService(DevicePolicyManager::class.java).isAdminActive(adminComponent(c))
        Perm.DND_ACCESS -> c.getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted
    }

    fun missing(c: Context) = Perm.values().filter { !has(c, it) }
    fun allGranted(c: Context) = missing(c).isEmpty()

    /** Launches the system screen/dialog for one permission. */
    fun request(a: Activity, p: Perm, notifLauncher: () -> Unit) {
        val pkg = Uri.parse("package:${a.packageName}")
        when (p) {
            Perm.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= 33) notifLauncher() else a.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName))
            Perm.ALL_FILES -> a.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg))
            Perm.BATTERY -> a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            Perm.DEVICE_ADMIN -> a.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(a))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Keeps the server app running and protected from accidental removal."))
            Perm.DND_ACCESS -> a.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
    }
}
