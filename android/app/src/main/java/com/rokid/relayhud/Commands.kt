package com.rokid.relayhud

/** 新会话 / 退出 的语音口令集(含 whisper 常见同音变体),按语言匹配。 */
private val NEW_SESSION_ZH = setOf(
    "新会话", "新对话", "开新对话", "新的对话", "开始新对话",
    "新绘画", "新绘话", "新汇话", "新对画", "心会话",
)
private val NEW_SESSION_EN = setOf("new session", "new chat", "new conversation", "start over", "reset")
private val EXIT_ZH = setOf("退出", "关闭", "退下", "推出", "退出程序", "关掉")
private val EXIT_EN = setOf("exit", "quit", "close", "exit app", "quit app")
// 打开系统 WiFi 面板的口令(随时可用的 WiFi 入口,含 whisper 常见变体)
private val WIFI_ZH = setOf("网络", "无线网", "wifi", "wi-fi", "网路", "连网", "wifi设置", "打开wifi", "无线网络")
private val WIFI_EN = setOf("wifi", "wi-fi", "wifi settings", "network", "open wifi", "wi fi")
// 切换界面语言的口令:中英合一、语言无关(最需切换者正被困在另一种语言里)。
// 整句精确匹配避免「把代码语言换成 Python」这类误触;真机上把听错的变体往里补。
private val LANG_SWITCH = setOf(
    "切换语言", "切语言", "换语言", "换个语言", "语言切换", "切换中英文", "中英切换",
    "switch language", "change language", "switch lang", "toggle language", "language switch",
)

// 拍照口令:拍一张给 Claude 看(含 whisper 常见变体)。
private val PHOTO_ZH = setOf("拍照", "拍个照", "拍张照", "照相", "拍照片", "看这个", "看看这个", "拍一张")
private val PHOTO_EN = setOf(
    "take photo", "take a photo", "take picture", "take a picture",
    "photo", "capture photo", "look at this", "take a pic",
)

private fun norm(t: String) = t.trim().trim('。', '，', '!', '！', '.', ' ').lowercase()

fun matchesNewSession(text: String, lang: String): Boolean =
    (if (lang == "en") NEW_SESSION_EN else NEW_SESSION_ZH).contains(norm(text))

fun matchesExit(text: String, lang: String): Boolean =
    (if (lang == "en") EXIT_EN else EXIT_ZH).contains(norm(text))

fun matchesWifi(text: String, lang: String): Boolean =
    (if (lang == "en") WIFI_EN else WIFI_ZH).contains(norm(text))

fun matchesLangSwitch(text: String): Boolean = LANG_SWITCH.contains(norm(text))

fun matchesPhoto(text: String, lang: String): Boolean =
    (if (lang == "en") PHOTO_EN else PHOTO_ZH).contains(norm(text))
