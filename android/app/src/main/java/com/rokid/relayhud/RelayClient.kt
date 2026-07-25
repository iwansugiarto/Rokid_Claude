package com.rokid.relayhud

import org.json.JSONObject

/**
 * 中继高层客户端:类型化收发 + hello 握手,底层传输由 RelayTransport 注入(默认 WebSocket)。
 * 换 Wi-Fi Direct/蓝牙只需换 transport 实现,本类与 MainActivity 都不用动。
 */
class RelayClient(
    private val transport: RelayTransport,
    lang: String,
    private val onMessage: (ServerMessage) -> Unit,
    private val onStatus: (String, Boolean) -> Unit,
) {
    private var lang = lang                   // 可变:setLang 更新,重连 hello 用最新值
    private val s get() = strings(this.lang)

    /** 便捷构造:WebSocket 传输(host + token 分离,token 走 Authorization 头)。 */
    constructor(
        url: String,
        token: String,
        lang: String,
        onMessage: (ServerMessage) -> Unit,
        onStatus: (String, Boolean) -> Unit,
    ) : this(WebSocketTransport(url, token), lang, onMessage, onStatus)

    init {
        transport.onOpen = { transport.sendRaw("""{"type":"hello","lang":"${this.lang}"}""") }
        transport.onText = { text -> onMessage(parseServerMessage(text)) }
        transport.onStatus = { _, isConn -> onStatus(if (isConn) s.connected else s.disconnected, isConn) }
    }

    fun connect() = transport.connect()
    fun pause() = transport.pause()
    fun resume() = transport.resume()
    fun close() = transport.close()

    private fun send(json: String) { transport.sendRaw(json) }

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
}
