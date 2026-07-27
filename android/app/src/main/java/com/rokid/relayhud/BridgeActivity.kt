package com.rokid.relayhud

import android.content.Intent
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
    private val clients = mutableIntStateOf(0)
    private val upOk = mutableStateOf(false)
    private val sttReady = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)  // 测试期常亮,避免 Activity 被回收
        val cfg = loadAppConfig(this)
        val ip = localIp() ?: "?"

        // 本地转写需要麦克风权限(即使音频来自眼镜,系统识别器仍要求该权限)
        if (PhoneStt(this).available() &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
        }
        // 桥跑在前台服务里:退后台/息屏继续转发。本页只是状态显示。
        startForegroundService(Intent(this, BridgeService::class.java))
        clients.intValue = BridgeService.clientCount
        upOk.value = BridgeService.upstreamOk
        sttReady.value = BridgeService.sttOn
        BridgeService.onStat = { n, ok ->
            runOnUiThread { clients.intValue = n; upOk.value = ok; sttReady.value = BridgeService.sttOn }
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
                Text(
                    if (sttReady.value) "🎙 STT on-device: aktif (audio tak lewat seluler)"
                    else "🎙 STT on-device: tak tersedia (audio diteruskan ke relay)",
                    color = if (sttReady.value) Color(0xFF00FF88) else Color(0xFFFFAA33), fontSize = 13.sp,
                )
            }
        }
    }

    // 桥归前台服务管,关本页不影响转发;用通知里的 Stop 才真正停桥。
    override fun onDestroy() {
        super.onDestroy()
        BridgeService.onStat = null
    }

    private fun localIp(): String? =
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.startsWith("192.168") == true }
            ?.hostAddress
}

@androidx.compose.runtime.Composable
private fun Spacer() = androidx.compose.foundation.layout.Spacer(Modifier.padding(6.dp))
