# R8/ProGuard 规则(release 精简)。只保留反射/序列化真正需要的部分。

# --- kotlinx.serialization:生成的 serializer 靠反射查找 ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.rokid.relayhud.** {
    *** Companion;
}
-keepclasseswithmembers class com.rokid.relayhud.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- OkHttp / Okio:平台相关可选类,缺失属正常 ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Java-WebSocket(bridge 本地服务端):内部用反射/SSL 可选依赖 ---
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**
-dontwarn org.slf4j.**

# --- ZXing(二维码扫描) ---
-dontwarn com.google.zxing.**

# --- 我们自己的协议/配置数据类:字段名参与 JSON 解析 ---
-keep class com.rokid.relayhud.AppConfig { *; }
-keep class com.rokid.relayhud.WifiQr { *; }
