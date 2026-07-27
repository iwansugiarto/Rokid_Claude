package com.rokid.relayhud

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.java_websocket.WebSocket as DownWs
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 手机 companion 的透明 WS 代理(bridge 模式 MVP)。
 * 眼镜连到本机 :port(无需中继 token);每个下游连接开一条到上游中继的专用 WS
 * (手机持 full token,走 Authorization 头),两向逐帧转发。
 *
 * 目的:证明 眼镜→手机→Claude 的流式链路,复用现有协议、眼镜端零改动。
 * 后续迭代:在此拦截 audio 帧 → 手机端 STT → 只上送文本(省蜂窝、更顺)。
 */
class BridgeServer(
    port: Int,
    private val upstreamUrl: String,
    private val token: String,
    private val stt: PhoneStt? = null,        // 有则手机本地转写,只上送文本
    private val lang: String = "en",          // 转写语言(眼镜的 audio 帧不带 lang)
    private val onStat: (clients: Int, upOk: Boolean) -> Unit,
) : WebSocketServer(InetSocketAddress(port)) {

    private val TAG = "RKBRIDGE"
    private val http = OkHttpClient.Builder()
        .pingInterval(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val upstreams = ConcurrentHashMap<DownWs, WebSocket>()

    override fun onStart() { Log.i(TAG, "bridge listening; upstream=$upstreamUrl") }

    override fun onOpen(down: DownWs, handshake: ClientHandshake) {
        val b = Request.Builder().url(upstreamUrl).addHeader("ngrok-skip-browser-warning", "true")
        if (token.isNotEmpty()) b.addHeader("Authorization", "Bearer $token")
        val up = http.newWebSocket(b.build(), object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                if (down.isOpen) down.send(text)          // 上游→眼镜
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { down.close() }
            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                Log.w(TAG, "upstream fail: ${t.message}"); onStat(upstreams.size, false); down.close()
            }
            override fun onOpen(ws: WebSocket, r: Response) { onStat(upstreams.size, true) }
        })
        upstreams[down] = up
        Log.i(TAG, "glasses connected (${upstreams.size})")
        onStat(upstreams.size, true)
    }

    override fun onMessage(down: DownWs, message: String) {
        // audio 帧:能在手机本地转写就别把 WAV 推过蜂窝 —— 只上送转写后的文本
        if (stt != null && stt.available() && message.contains("\"type\":\"audio\"")) {
            if (interceptAudio(down, message)) return
        }
        upstreams[down]?.send(message)                    // 眼镜→上游
    }

    /** @return true=已接管(本地转写中);false=解析失败,交回原路转发。 */
    private fun interceptAudio(down: DownWs, message: String): Boolean {
        val wavB64 = try {
            org.json.JSONObject(message).optString("wav").takeIf { it.isNotEmpty() } ?: return false
        } catch (_: Exception) { return false }

        val wav = try {
            android.util.Base64.decode(wavB64, android.util.Base64.NO_WRAP)
        } catch (_: Exception) { return false }

        val t0 = System.currentTimeMillis()
        stt!!.transcribe(wav, lang) { text ->
            val ms = System.currentTimeMillis() - t0
            if (text.isNullOrBlank()) {
                // 本地识别失败 → 回退:把原始音频交给中继的 whisper(功能不降级)
                Log.i(TAG, "stt fallback → upstream (wav ${wav.size}B, ${ms}ms)")
                upstreams[down]?.send(message)
            } else {
                Log.i(TAG, "stt local ok: ${wav.size}B wav → ${text.length} chars in ${ms}ms (cellular saved ~${wav.size / 1024}KB)")
                // 眼镜按协议期待 transcript(HUD 显示听到了什么),它再自行决定发 prompt
                if (down.isOpen) down.send(org.json.JSONObject()
                    .put("type", "transcript").put("text", text).put("sttMs", ms).toString())
            }
        }
        return true
    }

    override fun onClose(down: DownWs, code: Int, reason: String, remote: Boolean) {
        upstreams.remove(down)?.close(1000, null)
        Log.i(TAG, "glasses disconnected (${upstreams.size})")
        onStat(upstreams.size, true)
    }

    override fun onError(down: DownWs?, ex: Exception) { Log.w(TAG, "server error: ${ex.message}") }

    fun shutdown() {
        upstreams.values.forEach { it.close(1000, null) }
        upstreams.clear()
        try { stop(500) } catch (_: Exception) {}
    }
}
