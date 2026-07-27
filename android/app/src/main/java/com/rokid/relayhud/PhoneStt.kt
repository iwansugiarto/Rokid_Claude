package com.rokid.relayhud

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 手机端离线转写:把眼镜送来的 WAV 直接喂给系统 on-device recognizer(API 33+),
 * 于是只有【文本】走蜂窝上行到中继 —— 省流量、户外更跟手、手机射频负担更小。
 *
 * 不可用(设备不支持/无离线模型)时返回 null,桥回退到"把音频转发给中继让 whisper 转"。
 */
class PhoneStt(private val context: Context) {
    private val TAG = "RKSTT"
    private val main = Handler(Looper.getMainLooper())

    fun available(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** wav: 完整 WAV(16k 单声道 16bit)。cb 在主线程回调;null=失败,调用方回退。 */
    fun transcribe(wav: ByteArray, lang: String, cb: (String?) -> Unit) {
        if (!available()) { cb(null); return }
        main.post {
            try { start(wav, lang, cb) } catch (e: Exception) { Log.w(TAG, "stt error: ${e.message}"); cb(null) }
        }
    }

    private fun start(wav: ByteArray, lang: String, cb: (String?) -> Unit) {
        val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        var done = false
        val finish = { text: String? ->
            if (!done) {
                done = true
                try { rec.destroy() } catch (_: Exception) {}
                cb(text)
            }
        }

        // 用管道把 PCM 喂进识别器(去掉 44 字节 WAV 头)
        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val write = pipe[1]

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang == "en") "en-US" else "zh-CN")
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        }

        rec.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                Log.i(TAG, "on-device result chars=${text?.length ?: -1}")
                finish(text)
            }
            override fun onError(error: Int) { Log.w(TAG, "on-device error=$error"); finish(null) }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        rec.startListening(intent)

        // 另起线程把 PCM 写进管道(WAV 头 44 字节固定,VoiceInput 就是这么拼的)。
        // 按 ~实时速率分块喂:识别器面向流式输入,一次性灌满会被判成静默/空结果。
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                    val off = if (wav.size > 44) 44 else 0
                    val chunk = 3200                    // 16kHz*2B = 32000 B/s → 100ms/块
                    var i = off
                    while (i < wav.size) {
                        val n = minOf(chunk, wav.size - i)
                        out.write(wav, i, n); out.flush()
                        i += n
                        Thread.sleep(80)                // 略快于实时,兼顾时延与识别稳定
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "pipe write: ${e.message}") }
        }.start()

        // 兜底超时:识别器偶尔不回调
        main.postDelayed({ if (!done) { Log.w(TAG, "on-device timeout"); finish(null) } }, 15_000)
    }
}
