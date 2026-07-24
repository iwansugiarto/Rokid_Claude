package com.rokid.relayhud

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** OkHttp WebSocket 客户端:连接/自动重连,收发中继协议。回调在主线程。 */
class RelayClient(
    private val url: String,
    lang: String,
    private val onMessage: (ServerMessage) -> Unit,
    private val onStatus: (String, Boolean) -> Unit,
) {
    private var lang = lang                   // 可变:setLang 更新,重连 hello 用最新值
    private val s get() = strings(this.lang)  // 随 lang 走,断线重连用切换后语言的状态文案
    // pingInterval:穿 NAT/反代的长连接会被静默回收,周期 ping 保活 + 快速发现死链(否则要等下次发送才知道)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var closed = false

    fun connect() {
        val req = Request.Builder().url(url)
            .addHeader("ngrok-skip-browser-warning", "true")
            .build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post { onStatus(s.connected, true) }
                webSocket.send("""{"type":"hello","lang":"$lang"}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = parseServerMessage(text)
                main.post { onMessage(msg) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { onStatus(s.disconnected, false) }
                scheduleReconnect()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { onStatus(s.closed, false) }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (closed) return
        main.postDelayed({ if (!closed) connect() }, 1000)
    }

    private fun send(json: String) { ws?.send(json) }

    fun sendPrompt(prompt: String) {
        val p = prompt.trim()
        if (p.isEmpty()) return
        send(JSONObject().put("type", "prompt").put("prompt", p).toString())
    }
    fun sendAudio(wavBase64: String) {
        send(JSONObject().put("type", "audio").put("wav", wavBase64).toString())
    }
    fun sendPhoto(jpegBase64: String) {
        send(JSONObject().put("type", "photo").put("jpeg", jpegBase64).toString())
    }
    fun stop() = send("""{"type":"stop"}""")
    fun newSession() = send("""{"type":"newSession"}""")
    fun setLang(newLang: String) {
        lang = newLang
        send(JSONObject().put("type", "setLang").put("lang", newLang).toString())
    }
    fun sendDecision(id: String, choice: String, allowKey: String) {
        send(JSONObject().put("type", "permissionDecision").put("id", id).put("choice", choice).put("allowKey", allowKey).toString())
    }

    fun close() { closed = true; ws?.close(1000, null) }
}
