package com.zsdsh.dsh.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.zsdsh.dsh.R
import com.zsdsh.dsh.ZsdshApp

class EngineService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(NOTIF_ID, notification("DeepSeek Harness 运行中"))
        Thread({
            val engine = ZsdshApp.instance.engine
            if (!engine.start()) {
                startForeground(NOTIF_ID, notification(engine.lastError ?: "引擎未启动"))
            }
        }, "dsh-engine-start").start()
        return START_STICKY
    }

    override fun onDestroy() {
        ZsdshApp.instance.engine.stop()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "DSH 引擎", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CHANNEL_ID = "zsdsh-engine"
        private const val NOTIF_ID = 3080

        fun start(context: Context) {
            val intent = Intent(context, EngineService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
