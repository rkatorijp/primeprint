package com.sukoon.autoprint

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val enabled = Prefs.get(context).getBoolean(Prefs.KEY_SERVICE_ENABLED, false)
            if (enabled) {
                val serviceIntent = Intent(context, EmailCheckService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
