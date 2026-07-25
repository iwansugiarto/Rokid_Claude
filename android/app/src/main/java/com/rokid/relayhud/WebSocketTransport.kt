package com.rokid.relayhud

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * WebSocket 传输(OkHttp)。token 走 Authorization: Bearer 头 —— 不再进 URL,避免被反代/Apache 访问日志记录。
 * 保活/闲置省电:pingInterval 60s + pause() 彻底断连(灭屏闲置时不再唤醒 radio)。
 */
class WebSocketTransport(
    private val url: String,
    private val token: String,
) : RelayTransport {
    override var onOpen: () -> Unit = {}
    override var onText: (String) -> Unit = {}
    override var onStatus: (String, Boolean) -> Unit = { _, _ -> }

    private val client = OkHttpClient.Builder()
        .pingInterval(60, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var closed = false
    @Volatile private var suspended = false

    override fun connect() {
        val b = Request.Builder().url(url)
            .addHeader("ngrok-skip-browser-warning", "true")
        if (token.isNotEmpty()) b.addHeader("Authorization", "Bearer $token")
        ws = client.newWebSocket(b.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post { onStatus("", true); onOpen() }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { onText(text) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { onStatus("", false) }
                scheduleReconnect()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { onStatus("", false) }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (closed || suspended) return
        main.postDelayed({ if (!closed && !suspended) connect() }, 1000)
    }

    override fun sendRaw(json: String): Boolean = ws?.send(json) ?: false

    override fun pause() { suspended = true; ws?.close(1000, null) }

    override fun resume() {
        if (!suspended) return
        suspended = false
        connect()
    }

    override fun close() { closed = true; ws?.close(1000, null) }
}
