#!/bin/bash
# Kill-switch: eyeglasses lost/stolen → rotate the SANDBOX token so the old glasses
# are cut off from the relay immediately, wherever they are. The FULL token (trusted
# desktop/web clients) is left untouched. Double-click, or run from a terminal.
#
# After running, re-provision the glasses when recovered: push the new token into
# their config.json (adb push) or scan a fresh config QR.
set -euo pipefail
cd "$(dirname "$0")/.."          # repo root

ENV="relay/.remote.env"
if [ ! -f "$ENV" ]; then
  echo "✗ $ENV tidak ada — tidak ada token untuk dirotasi."
  read -n 1 -s -r -p "Tekan tombol apa saja untuk menutup…"; exit 1
fi

NEW="$(openssl rand -hex 24)"
if grep -q '^ROKID_SANDBOX_TOKEN=' "$ENV"; then
  # ganti nilai baris ROKID_SANDBOX_TOKEN (portable, tanpa sed -i)
  awk -v v="$NEW" '/^ROKID_SANDBOX_TOKEN=/{print "ROKID_SANDBOX_TOKEN=" v; next} {print}' "$ENV" > "$ENV.tmp" && mv "$ENV.tmp" "$ENV"
else
  echo "ROKID_SANDBOX_TOKEN=$NEW" >> "$ENV"
fi
echo "✓ sandbox token dirotasi (full token tak disentuh)."

# restart relay agar token lama langsung ditolak
LABEL="com.rokid.relay"
DOMAIN="gui/$(id -u)"
if launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1; then
  launchctl kickstart -k "$DOMAIN/$LABEL"
  echo "✓ relay (LaunchAgent) di-restart — koneksi glasses lama sekarang DITOLAK."
elif pgrep -f "tsx src/main.ts" >/dev/null 2>&1; then
  pkill -f "tsx src/main.ts" || true
  echo "⚠ relay foreground dihentikan — jalankan ulang (start.command / start-remote.command) untuk memuat token baru."
else
  echo "⚠ relay tidak sedang jalan — token baru akan aktif saat relay berikutnya start."
fi

echo ""
echo "──────────────────────────────────────────────"
echo "  Token sandbox BARU (untuk provision ulang glasses saat ketemu):"
echo "    $NEW"
echo ""
echo "  Provision ulang:"
echo "    1) Edit config.json → \"token\": \"$NEW\""
echo "    2) adb push config.json /sdcard/Android/data/com.rokid.relayhud/files/config.json"
echo "       (atau tampilkan config QR dan scan dari glasses)"
echo "──────────────────────────────────────────────"
