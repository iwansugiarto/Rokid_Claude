#!/bin/bash
# 测量 Rokid Claude 一次语音交互的端到端耗时分解(区分网络路径:家里 WiFi vs 手机热点/蜂窝)。
#
# 用法:
#   scripts/measure-latency.sh <label>
# 例:
#   scripts/measure-latency.sh wifi-home        # 眼镜连家里 WiFi 时跑
#   scripts/measure-latency.sh hotspot-cellular # 眼镜连手机热点(手机走蜂窝)时跑
#
# 跑法:执行本脚本后,在眼镜上【单击说话 → 说一句指令(如 "list files") → 停】,
# 等结果出来。脚本抓 logcat 里的 RKMETRIC 行并汇总。Ctrl+C 结束。
#
# 分解含义:
#   audio_bytes   本次音频上行字节(手机侧 STT 可省掉的蜂窝流量)
#   net_audio_ms  音频往返里扣掉 STT 后的纯网络耗时(上行为主)
#   stt_ms        中继侧 whisper 转写耗时(在 Mac,和网络无关)
#   first_token_ms 提交到 Claude 首字(网络往返 + 模型首字)
#   total_ms      提交到本轮结束
set -euo pipefail
LABEL="${1:-run}"
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb)"
SERIAL="${RK_SERIAL:-1901092546052315}"   # 默认眼镜;RK_SERIAL 可覆盖

echo "── measure [$LABEL] · device $SERIAL ──"
echo "眼镜网络现状:"
"$ADB" -s "$SERIAL" shell cmd wifi status 2>/dev/null \
  | grep -oE "connected to \"[^\"]+\"|RSSI: -?[0-9]+|Link speed: [0-9]+Mbps|Frequency: [0-9]+MHz" | sed 's/^/   /'
echo ""
echo "▶ 现在在眼镜上说一句指令。抓取中(Ctrl+C 结束)…"
echo ""
"$ADB" -s "$SERIAL" logcat -c 2>/dev/null || true
"$ADB" -s "$SERIAL" logcat -s RKMETRIC:I 2>/dev/null | while IFS= read -r line; do
  metric="${line#*RKMETRIC: }"
  printf "[%s] %s\n" "$LABEL" "$metric"
done
