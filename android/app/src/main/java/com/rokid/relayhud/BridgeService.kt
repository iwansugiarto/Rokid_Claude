package com.rokid.relayhud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * 前台服务承载 bridge:退到后台/息屏也继续替眼镜转发(Activity 会被回收,服务不会)。
 * 通知常驻,点按回到状态页;通知里的"停止"结束桥。
 */
class BridgeService : Service() {
    companion object {
        const val CHANNEL = "rokid_bridge"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "com.rokid.relayhud.STOP_BRIDGE"
        @Volatile var server: BridgeServer? = null
        @Volatile var clientCount = 0
        @Volatile var upstreamOk = false
        @Volatile var sttOn = false
        /** 状态页用它刷新 UI(简单回调,避免引入额外依赖)。 */
        @Volatile var onStat: ((Int, Boolean) -> Unit)? = null
    }

    private val TAG = "RKBRIDGE"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopBridge(); return START_NOT_STICKY }

        val cfg = loadAppConfig(this)
        startForeground(NOTIF_ID, buildNotification(cfg.bridgePort))

        if (server == null) {
            val stt = PhoneStt(this)
            sttOn = stt.available()
            server = BridgeServer(cfg.bridgePort, cfg.serverUrl, cfg.token, stt, cfg.lang) { n, ok ->
                clientCount = n; upstreamOk = ok
                onStat?.invoke(n, ok)
                // 通知随连接数更新,一眼知道桥还活着
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIF_ID, buildNotification(cfg.bridgePort))
            }.also { it.isReuseAddr = true; it.start() }
            Log.i(TAG, "bridge service started")
        }
        return START_STICKY   // 被系统杀掉后尽量拉起
    }

    private fun stopBridge() {
        server?.shutdown(); server = null
        clientCount = 0; upstreamOk = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "bridge service stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.shutdown(); server = null
    }

    private fun buildNotification(port: Int): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Rokid Claude Bridge", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, BridgeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val state = buildString {
            append(if (upstreamOk) "relay ✓" else "relay …")
            append(" · glasses $clientCount")
            if (sttOn) append(" · STT on-device")
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Rokid Claude Bridge · :$port")
            .setContentText(state)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }
}
