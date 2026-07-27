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
        upstreams[down]?.send(message)                    // 眼镜→上游
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
