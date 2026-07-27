package com.rokid.relayhud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 手机 companion 的 bridge 状态屏。启动本地 WS 代理(BridgeServer),显示:
 * 本机可被眼镜连接的地址、上游中继、已连眼镜数。手机有大屏,信息一目了然。
 */
class BridgeActivity : ComponentActivity() {
    // 进程级单例:Activity 重建(旋转/回收)不重启服务器,否则会踢掉眼镜连接
    companion object {
        @Volatile private var shared: BridgeServer? = null
        @Volatile var clientCount = 0
        @Volatile var upstreamOk = false
    }
    private val clients = mutableIntStateOf(0)
    private val upOk = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)  // 测试期常亮,避免 Activity 被回收
        val cfg = loadAppConfig(this)
        val ip = localIp() ?: "?"

        // 只在首次创建时起服务器;之后的 Activity 重建复用同一个实例
        clients.intValue = clientCount; upOk.value = upstreamOk
        if (shared == null) {
            shared = BridgeServer(cfg.bridgePort, cfg.serverUrl, cfg.token) { n, ok ->
                clientCount = n; upstreamOk = ok
                runOnUiThread { clients.intValue = n; upOk.value = ok }
            }.also { it.isReuseAddr = true; it.start() }
        }

        setContent {
            val n by clients
            val ok by upOk
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text("🦟 Rokid Claude · Bridge", color = Color(0xFF00FF88), fontSize = 20.sp)
                Spacer(); Text("眼镜请连到 / point glasses at:", color = Color(0xFF88CCAA), fontSize = 13.sp)
                Text("ws://$ip:${cfg.bridgePort}", color = Color(0xFF00FF88), fontSize = 22.sp, fontFamily = FontFamily.Monospace)
                Spacer(); Text("上游中继 / upstream:", color = Color(0xFF88CCAA), fontSize = 13.sp)
                Text(cfg.serverUrl, color = Color(0xFFCCFFEE), fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                Spacer()
                Text(if (ok) "● upstream terhubung" else "○ upstream menunggu…",
                    color = if (ok) Color(0xFF00FF88) else Color(0xFFFFAA33), fontSize = 15.sp)
                Text("眼镜已连 / glasses connected: $n", color = Color(0xFF00FF88), fontSize = 15.sp)
            }
        }
    }

    // 注意:不在 onDestroy 关服务器 —— 桥要在 Activity 重建/息屏后继续替眼镜转发。
    // 需要停止时由用户显式退出(返回键 finishAndRemoveTask)。
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) { shared?.shutdown(); shared = null; clientCount = 0; upstreamOk = false }
    }

    private fun localIp(): String? =
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.startsWith("192.168") == true }
            ?.hostAddress
}

@androidx.compose.runtime.Composable
private fun Spacer() = androidx.compose.foundation.layout.Spacer(Modifier.padding(6.dp))
