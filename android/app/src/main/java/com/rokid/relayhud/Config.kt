package com.rokid.relayhud

import android.content.Context
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * app 连接配置。
 * - mode="client"(默认):眼镜/手机作为普通客户端,连 serverUrl(可为手机 bridge 或中继)。
 * - mode="bridge":手机companion。本机开一个本地 WS 服务(bridgePort),把眼镜的连接
 *   转发到上游中继 serverUrl(手机持 full token)。眼镜因此无需持中继 token。
 * token 为空=本地直连不鉴权;lang=zh|en 决定全 app 语言。
 */
data class AppConfig(
    val serverUrl: String,
    val token: String,
    val lang: String = "zh",
    val mode: String = "client",
    val bridgePort: Int = 8788,
)

val DEFAULT_CONFIG = AppConfig(serverUrl = "ws://localhost:8787", token = "", lang = "zh")

/** 解析 adb 推来的 config.json;为空/非法时回退本地默认。 */
fun parseConfig(json: String?): AppConfig {
    if (json.isNullOrBlank()) return DEFAULT_CONFIG
    return try {
        val o = Json.parseToJsonElement(json).jsonObject
        val url = o["serverUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: DEFAULT_CONFIG.serverUrl
        val tok = o["token"]?.jsonPrimitive?.contentOrNull ?: ""
        val lang = if (o["lang"]?.jsonPrimitive?.contentOrNull == "en") "en" else "zh"
        val mode = if (o["mode"]?.jsonPrimitive?.contentOrNull == "bridge") "bridge" else "client"
        val port = o["bridgePort"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 8788
        AppConfig(url, tok, lang, mode, port)
    } catch (_: Exception) {
        DEFAULT_CONFIG
    }
}

/** 读 adb push 进来的 config.json(外部文件目录);不存在/失败回退默认。多 Activity 共用。 */
fun loadAppConfig(ctx: Context): AppConfig = try {
    val f = java.io.File(ctx.getExternalFilesDir(null), "config.json")
    if (f.exists()) parseConfig(f.readText()) else DEFAULT_CONFIG
} catch (_: Exception) { DEFAULT_CONFIG }

/** 把 token 作为查询串拼到 ws(s) 地址上;token 为空则原样返回。 */
fun buildWsUrl(host: String, token: String): String {
    if (token.isEmpty()) return host
    val enc = URLEncoder.encode(token, "UTF-8")
    return "$host/?token=$enc"
}
