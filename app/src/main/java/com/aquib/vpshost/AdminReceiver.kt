package com.aquib.vpshost

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Disabling administrator lets Android stop the server app. The app will lock until it is granted again."
}
