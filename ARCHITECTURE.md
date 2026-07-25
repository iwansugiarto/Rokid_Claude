# Architecture

## Overview

Rokid Claude is a thin, transport-agnostic bridge between a wearable client and
[Claude Code](https://www.anthropic.com) running on your home Mac. An Android
client (the Rokid Glasses, or any Android phone) captures your voice and renders
progress on a monochrome-green HUD. It talks over a WebSocket to a small
Node/TypeScript **relay** on the Mac, which transcribes audio locally with
whisper.cpp and spawns `claude -p --output-format stream-json --verbose`,
streaming the agent's events back to the client in real time.

```
┌─────────────┐   audio(PCM)/prompt    ┌──────────────────────┐   spawn   ┌──────────────┐
│  Android    │ ─────────────────────► │  Relay (Node/TS)     │ ────────► │  claude -p   │
│  client     │   WebSocket (ws/wss)   │  :8787               │ stream-   │  (Claude     │
│  (glasses/  │ ◄───────────────────── │  whisper.cpp STT     │  json     │   Code CLI)  │
│   phone)    │   events / usage /     │  RunStore + replay   │ ◄──────── │              │
│  green HUD  │   permission prompts   │  PreToolUse hook     │           └──────────────┘
└─────────────┘                        └──────────────────────┘
```

The relay core knows nothing about glasses specifically — it speaks a small JSON
protocol over WebSocket, so the same backend serves a phone, the glasses, or a
web page.

## Components

### Relay — `relay/src/`

- **`server.ts`** — WebSocket + HTTP server. Owns the reconnect protocol, the
  permission bridge (`/permission` endpoint + `requestDecision`), session usage
  accumulation, the `usage` broadcast, and voice model-switch interception.
- **`claude-runner.ts`** — spawns the `claude` CLI (`buildArgs` constructs the
  argument list, including `--model` and the `--settings` permission hook) and
  parses its `stream-json` stdout line by line.
- **`events.ts`** — pure function translating one parsed `stream-json` line into
  zero or more `AgentEvent`s (`system`/`text`/`thinking`/`tool_use`/
  `tool_result`/`done`/`error`); extracts cost and token counts from the
  `result` event.
- **`run-store.ts`** — the source of truth for a session: an append-only event
  log with sequence numbers, enabling a reconnecting client to replay only the
  events it missed.
- **`transcribe.ts`** — calls `whisper-cli` (whisper.cpp) on a captured WAV in
  the connection's language; in Chinese it passes a Simplified-Chinese initial
  prompt so the model emits Simplified consistently, which keeps voice command
  matching reliable.
- **`i18n.ts`** — the per-connection language (zh/en): whisper language + prompt,
  permission/cancel strings, and tool-verb labels. Set from the client `hello`
  and updated live by `setLang`.
- **`permission.ts` + `permission-hook.mjs`** — the PreToolUse permission flow
  (see Permissions below).
- **`dictionary.ts`** — expands spoken shortcuts into longer prompts or slash
  commands (`relay/dictionary.<lang>.json`, editable live).
- **`model.ts`** — detects a "switch model" intent in a transcript and maps a
  model alias to a `claude --model` argument.
- **`auth.ts`** — `classifyConnection` grades each WebSocket upgrade to a
  capability: `full` (may use the session picker to resume any recent project),
  `sandbox` (confined to `relay/sandbox`), or rejected. It reads the token from
  `Authorization: Bearer` → `Sec-WebSocket-Protocol` → `?token=` (in that order),
  matching it against `ROKID_TOKEN` (full) and `ROKID_SANDBOX_TOKEN` (sandbox).
  Give the glasses the sandbox token so a low-trust device can't leave the
  sandbox; the cross-project escape is gated on `full` in `server.ts`.

### Android client — `android/app/src/main/java/com/rokid/relayhud/`

- **`MainActivity.kt`** — gesture handling (tap / double-tap / swipe), the
  context-aware tap state machine, screen-off logic, and message routing.
- **`HudScreen.kt`** — the Compose green HUD: streaming text, tool rows, the
  permission/model choice overlay, and the statusline.
- **`Protocol.kt`** — parses server→client messages (kotlinx.serialization).
- **`VoiceInput` / `SilenceDetector`** — microphone capture (AudioRecord) plus
  voice-activity detection to auto-stop on silence.
- **`Gestures` / `ChoiceState` / `Config`** — pure gesture mapping,
  choice-overlay state, and config parsing.
- **`RelayClient` / `RelayTransport` / `WebSocketTransport`** — `RelayClient`
  is the typed high-level API (sendPrompt/sendAudio/sendPhoto/…, plus the `hello`
  handshake). It talks only to a `RelayTransport` interface; `WebSocketTransport`
  is the OkHttp implementation (60s keepalive, idle pause/resume, token in an
  `Authorization` header). A future **`WifiDirectTransport`** would implement the
  same interface — letting the glasses run as a pure I/O peripheral bridged
  through a phone companion that holds the full token — without touching
  `RelayClient` or `MainActivity`.
- **`ScannerActivity` + `WifiQr` / `ConfigQr`** — a CameraX + ZXing QR scanner
  (reachable by single-tap when offline) that provisions Wi-Fi (`WIFI:` codes →
  `ACTION_WIFI_ADD_NETWORKS`) or writes `config.json` (`RCLAUDE:` config codes →
  serverUrl/token/lang, then reconnect). `WifiQr`/`ConfigQr` are pure parsers.
- **`Strings.kt` / `Commands.kt`** — all HUD copy and spoken-command matchers in
  zh/en, driven by `lang` and live-switchable by the "switch language" command.

## Data flow

A single voice command:

1. Tap → start recording. Silence (~1.8 s) or another tap stops it.
2. The client uploads the WAV (`audio`) to the relay.
3. The relay transcribes it with whisper.cpp and returns a `transcript`.
4. The client displays the transcript and sends it back as a `prompt`.
5. The relay spawns `claude -p`; its `stream-json` output is translated into
   `event`s streamed to the client, which renders them on the HUD.
6. On completion the relay emits `done` and a `usage` snapshot (model + session
   cost/tokens) for the statusline.

## WebSocket protocol

| Direction | Messages |
|---|---|
| client → server | `hello`, `prompt`, `audio`, `stop`, `newSession`, `permissionDecision`, `setLang` |
| server → client | `sync`, `event`, `runEnd`, `transcript`, `usage`, `permissionRequest`, `modelRequest` |

Reconnection is incremental: the client sends `hello { lastRunId, lastSeq }` and
the relay replays only events after `lastSeq` (sequence numbers are monotonic per
run), so a dropped connection resumes mid-task without losing output.

## Permissions

Under `claude -p`, the `--permission-prompt-tool` / MCP path does not gate tool
use. Instead the relay injects a **PreToolUse hook** via `--settings` (matching
`Bash|Write|Edit|MultiEdit|NotebookEdit`). Before a risky tool runs, the hook
POSTs to the relay's `/permission` endpoint; the relay checks its per-tool
session memory and, if needed, asks the glasses to render a choice overlay. The
user's gesture verdict (allow once / allow this kind / deny) flows back and the
hook returns allow or deny. The permission mode is `default`, so read-only tools
run without prompting.

## Remote access

For use outside the home, an [ngrok](https://ngrok.com) tunnel exposes the local
relay (`:8787`) on a fixed domain over TLS. The relay requires a token on every
connection, graded to full or sandbox capability (see `auth.ts` above).

**Security:** that token is equivalent to remote code execution on your Mac —
anyone who can reach the tunnel with the token can make Claude Code run commands.
Keep it secret, never commit it, and rely on the on-glasses permission
confirmation as a second line of defense. Never expose the relay publicly without
a token.
