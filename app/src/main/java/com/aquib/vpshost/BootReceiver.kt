package com.aquib.vpshost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After reboot (or app update) bring everything back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Watchdog.ensure(c) }
}
