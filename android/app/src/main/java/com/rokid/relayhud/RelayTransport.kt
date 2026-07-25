package com.rokid.relayhud

/**
 * 中继传输抽象:RelayClient 只依赖这个接口,不关心底层是 WebSocket 还是(将来)Wi-Fi Direct/蓝牙。
 * 回调由 RelayClient 装配;实现方须在主线程回调(与现有 UI 代码假设一致)。
 */
interface RelayTransport {
    fun connect()
    fun pause()              // 闲置省电:断开且不自动重连
    fun resume()             // 从闲置恢复
    fun close()
    fun sendRaw(json: String): Boolean

    var onOpen: () -> Unit                 // 连上瞬间(RelayClient 用来发 hello)
    var onText: (String) -> Unit           // 收到一帧文本
    var onStatus: (String, Boolean) -> Unit // (状态文案, 是否已连接)
}
