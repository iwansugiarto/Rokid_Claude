package com.rokid.relayhud

import android.util.Log

/**
 * 轻量端到端计时,打到 logcat tag "RKMETRIC"(用 scripts/measure-latency.sh 抓取汇总)。
 * 目的:分离户外"卡"的成因 —— 音频上行 vs STT vs Claude 首字 vs 总时,以及各段字节量。
 * 一次交互(说话→出结果)输出:
 *   RKMETRIC audio_bytes=.. audio_rt_ms=.. stt_ms=.. net_audio_ms=.. (音频往返里扣掉 STT = 纯网络)
 *   RKMETRIC first_token_ms=.. total_ms=.. transcript_chars=..
 */
object Metrics {
    private const val TAG = "RKMETRIC"
    private var audioSentAt = 0L
    private var audioBytes = 0
    private var promptSentAt = 0L
    private var firstTextAt = 0L
    private var transcriptChars = 0

    fun onAudioSent(base64Len: Int) {
        audioSentAt = now(); audioBytes = base64Len * 3 / 4   // base64 → 原始字节近似
    }

    fun onTranscript(chars: Int, sttMs: Long) {
        transcriptChars = chars
        if (audioSentAt == 0L) return
        val rt = now() - audioSentAt
        Log.i(TAG, "audio_bytes=$audioBytes audio_rt_ms=$rt stt_ms=$sttMs net_audio_ms=${rt - sttMs}")
        audioSentAt = 0L
    }

    fun onPromptSent() { promptSentAt = now(); firstTextAt = 0L }

    fun onFirstText() {
        if (promptSentAt == 0L || firstTextAt != 0L) return
        firstTextAt = now()
    }

    fun onRunEnd() {
        if (promptSentAt == 0L) return
        val end = now()
        val ftm = if (firstTextAt != 0L) firstTextAt - promptSentAt else -1
        Log.i(TAG, "first_token_ms=$ftm total_ms=${end - promptSentAt} transcript_chars=$transcriptChars")
        promptSentAt = 0L
    }

    private fun now() = android.os.SystemClock.elapsedRealtime()
}
