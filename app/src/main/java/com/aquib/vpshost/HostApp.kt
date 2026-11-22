package com.aquib.vpshost

import android.app.Application

class HostApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The :node process also creates an Application; only the main process orchestrates.
        if (getProcessName() != packageName) return
        Watchdog.ensure(this)
    }
}
