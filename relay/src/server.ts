import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { readFile, writeFile, unlink, mkdir, readdir, stat } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { tmpdir } from 'node:os';
import { WebSocketServer, WebSocket } from 'ws';
import { runClaude, type RunHandle } from './claude-runner';
import { RunStore } from './run-store';
import { transcribe } from './transcribe';
import { classifyConnection, type Capability } from './auth';
import { decide, summarize } from './permission';
import { expandPrompt, loadDictionary } from './dictionary';
import { parseModelCommand, modelArg, type ModelAlias } from './model';
import { parseSessionCommand, listRecentSessions, type SessionEntry } from './sessions';
import { tr, normalizeLang, type Lang } from './i18n';

type RunnerFn = (opts: { prompt: string; cwd: string; sessionId?: string; model?: string }) => RunHandle;
type TranscriberFn = (wavPath: string, modelPath: string, lang: Lang) => Promise<string>;

export interface ServerOptions {
  sandboxDir: string;
  webDir: string;
  stateDir: string;
  modelPath: string;
  token?: string;         // 兼容旧调用:等价 tokenFull
  tokenFull?: string;     // 全权 token(可用会话选择器,跨项目)
  tokenSandbox?: string;  // 受限 token(只能 sandbox,眼镜低信任路径用这个)
  dictionaryDir?: string;
  projectsDir?: string;   // ~/.claude/projects;不传则禁用会话切换
  runner?: RunnerFn;
  transcriber?: TranscriberFn;
}

const PHOTO_TTL_MS = 3 * 60_000;  // 拍照后 3 分钟内不说话则作废
const PHOTO_KEEP = 5;             // sandbox/photos 只保留最近 5 张
const MAX_PAYLOAD = 12 * 1024 * 1024;  // 单帧上限(照片 base64 ~8MB;拒绝异常大帧)

/** 把照片目录裁到最近 keep 张,删掉更旧的(有界磁盘占用)。失败静默。 */
async function prunePhotos(dir: string, keep: number): Promise<void> {
  try {
    const files = (await readdir(dir)).filter((f) => f.endsWith('.jpg'));
    if (files.length <= keep) return;
    const withTime = await Promise.all(
      files.map(async (f) => ({ f, t: (await stat(join(dir, f))).mtimeMs })),
    );
    withTime.sort((a, b) => b.t - a.t);
    await Promise.all(withTime.slice(keep).map((x) => unlink(join(dir, x.f)).catch(() => {})));
  } catch { /* 目录不存在等,忽略 */ }
}

const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
};

export function createRelayServer(opts: ServerOptions) {
  const runner = opts.runner ?? runClaude;
  const transcriber = opts.transcriber ?? transcribe;
  const store = new RunStore(opts.stateDir);
  let current: RunHandle | null = null;
  let currentRunId: string | null = null;

  const allowedSet = new Set<string>();
  const pending = new Map<string, (choice: string) => void>();
  const clients = new Set<(msg: unknown) => void>();
  const broadcast = (msg: unknown) => { for (const send of clients) send(msg); };

  let lang: Lang = 'zh';                      // 本连接语言(hello 时由客户端 config 传入)
  let pendingPhoto: { rel: string; at: number } | null = null;  // 待附加照片(相对路径+时间戳),下一条 prompt 消费;超 TTL 作废
  let activeCwd: string | null = null;        // 选定会话所属项目;之后的 run 都在这里跑(newSession 归位 sandbox)
  let resumeTarget: string | null = null;     // 选定会话 id,仅首个 run 用 --resume 接上,之后走 store 链
  let currentModel: string | null = null;   // 最近一次 system 事件的真实模型(全 id)
  let selectedModel: ModelAlias | null = null;  // 用户语音选定的别名(opus/sonnet/haiku),开跑前即显示
  let sessionCostUsd = 0;
  let sessionTokens = 0;

  function usageSnapshot() {
    return { type: 'usage', model: selectedModel ?? currentModel, costUsd: sessionCostUsd, tokens: sessionTokens };
  }
  function broadcastUsage() { broadcast(usageSnapshot()); }

  function applyModel(model: ModelAlias) { selectedModel = model; broadcastUsage(); }

  /** 发 modelRequest,等用户在眼镜选,超时=取消。复用 pending(由 permissionDecision 兑现)。 */
  function requestModelChoice(current: ModelAlias | null, timeoutMs = 60000): Promise<string> {
    const id = `model-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const cancel = tr(lang).modelCancel;
    const options = ['opus', 'sonnet', 'haiku', cancel];
    const currentIdx = current ? options.indexOf(current) : 0;
    return new Promise((resolve) => {
      let done = false;
      const finish = (choice: string) => { if (done) return; done = true; pending.delete(id); resolve(choice); };
      pending.set(id, finish);
      broadcast({ type: 'modelRequest', id, options, current: currentIdx < 0 ? 0 : currentIdx, timeoutChoice: cancel });
      setTimeout(() => finish(cancel), timeoutMs);
    });
  }

  /** 发 sessionRequest(可恢复会话列表),等用户在眼镜滑选。超时=取消。复用 pending(由 permissionDecision 兑现)。 */
  function requestSessionChoice(sessions: SessionEntry[], timeoutMs = 60000): Promise<SessionEntry | null> {
    const id = `sess-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const cancel = tr(lang).modelCancel;
    const options = [...sessions.map((s) => s.label), cancel];
    return new Promise((resolve) => {
      let done = false;
      const finish = (choice: string) => {
        if (done) return;
        done = true; pending.delete(id);
        resolve(sessions.find((s) => s.label === choice) ?? null);
      };
      pending.set(id, finish);
      broadcast({ type: 'sessionRequest', id, options, current: 0, timeoutChoice: cancel });
      setTimeout(() => finish(cancel), timeoutMs);
    });
  }

  /** 发 permissionRequest 给眼镜,等 permissionDecision 或 timeoutMs 超时→拒绝。allowKey 由眼镜原样回传用于记忆。 */
  function requestDecision(
    req: { tool: string; summary: string; command: string; allowKey?: string },
    timeoutMs = 60000,
  ): Promise<string> {
    const id = `perm-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const t = tr(lang);
    const options = [t.permOnce, t.permKind, t.permDeny];
    return new Promise((resolve) => {
      let done = false;
      const finish = (choice: string) => { if (done) return; done = true; pending.delete(id); resolve(choice); };
      pending.set(id, finish);
      broadcast({ type: 'permissionRequest', id, tool: req.tool, summary: req.summary, options, allowKey: req.allowKey ?? '', timeoutChoice: t.permDeny });
      setTimeout(() => finish(t.permDeny), timeoutMs);
    });
  }

  const http = createServer(async (req: IncomingMessage, res: ServerResponse) => {
    if (req.method === 'POST' && req.url === '/permission') {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', async () => {
        try {
          const { tool, input } = JSON.parse(body || '{}');
          const { summary, command } = summarize(tool, input ?? {}, lang);
          // 记忆按"工具类型"(本会话):"这类都允许"后,同类工具(如所有 Write)免确认,利于批量。
          if (decide(tool, allowedSet) === 'allow') { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ allow: true })); return; }
          const choice = await requestDecision({ tool, summary, command, allowKey: tool });
          const t = tr(lang);
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ allow: choice === t.permOnce || choice === t.permKind }));
        } catch { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ allow: false })); }
      });
      return;
    }
    const urlPath = (req.url === '/' || !req.url) ? '/index.html' : req.url.split('?')[0];
    const safe = normalize(urlPath).replace(/^(\.\.[/\\])+/, '');
    const filePath = join(opts.webDir, safe);
    try {
      const body = await readFile(filePath);
      res.writeHead(200, { 'Content-Type': MIME[extname(filePath)] ?? 'application/octet-stream' });
      res.end(body);
    } catch { res.writeHead(404).end('not found'); }
  });

  async function startRun(prompt: string): Promise<void> {
    const run = store.startRun(prompt);
    currentRunId = run.id;
    const resumeId = resumeTarget ?? run.sessionId;
    resumeTarget = null;   // 只在接上会话的首个 run 用;之后 store 链会跟着 system 事件走
    const handle = runner({ prompt, cwd: activeCwd ?? opts.sandboxDir, sessionId: resumeId, model: selectedModel ? modelArg(selectedModel) : undefined });
    current = handle;
    const runStart = Date.now();
    let firstTextAt = 0;
    let respChars = 0;
    try {
      for await (const event of handle.events) {
        store.appendEvent(run.id, event);
        if (event.type === 'text') { if (!firstTextAt) firstTextAt = Date.now(); respChars += (event.delta?.length ?? 0); }
        if (event.type === 'system' && event.model) { currentModel = event.model; broadcastUsage(); }
        if (event.type === 'done') {
          if (typeof event.costUsd === 'number') sessionCostUsd += event.costUsd;
          sessionTokens += (event.tokensIn ?? 0) + (event.tokensOut ?? 0);
          broadcastUsage();
        }
      }
      const last = store.eventsSince(run.id, 0).at(-1)?.event;
      store.finishRun(run.id, last?.type === 'error' ? 'error' : 'done');
      const now = Date.now();
      process.stderr.write(`[timing] run firstTokenMs=${firstTextAt ? firstTextAt - runStart : -1} totalMs=${now - runStart} respChars=${respChars}\n`);
    } catch (err) {
      store.appendEvent(run.id, { type: 'error', message: String(err) });
      store.finishRun(run.id, 'error');
    } finally {
      if (currentRunId === run.id) { current = null; currentRunId = null; }
    }
  }

  async function handleAudio(ws: WebSocket, wavBase64: string): Promise<void> {
    let text = '';
    const audioBytes = Buffer.byteLength(wavBase64, 'base64');
    const tmp = join(tmpdir(), `rokid-audio-${Date.now()}-${Math.random().toString(36).slice(2)}.wav`);
    const t0 = Date.now();
    try {
      await writeFile(tmp, Buffer.from(wavBase64, 'base64'));
      text = await transcriber(tmp, opts.modelPath, lang);
    } catch { text = ''; }
    finally { await unlink(tmp).catch(() => {}); }
    const sttMs = Date.now() - t0;
    // sttMs 让客户端从"发音频→收转写"总时里扣掉 STT,隔离出纯网络耗时(测量用,附加字段无害)
    process.stderr.write(`[timing] audio bytes=${audioBytes} sttMs=${sttMs} chars=${text.length}\n`);
    if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'transcript', text, sttMs }));
  }

  const tokens = { full: opts.tokenFull ?? opts.token, sandbox: opts.tokenSandbox };
  const wss = new WebSocketServer({ server: http, maxPayload: MAX_PAYLOAD });
  wss.on('connection', (ws, req) => {
    const capability: Capability | null = classifyConnection(req, tokens);
    if (!capability) { ws.close(1008, 'unauthorized'); return; }
    const send = (msg: unknown) => { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg)); };
    clients.add(send);
    const sentMax = new Map<string, number>();
    let live = false;
    const queue: Array<['event', { runId: string; seq: number; event: unknown }] | ['end', { runId: string; status: string }]> = [];

    const rawSendEvent = (runId: string, seq: number, event: unknown) => {
      if (ws.readyState !== WebSocket.OPEN) return;
      if (seq <= (sentMax.get(runId) ?? 0)) return;
      sentMax.set(runId, seq);
      ws.send(JSON.stringify({ type: 'event', runId, seq, event }));
    };
    const rawSendEnd = (runId: string, status: string) => {
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'runEnd', runId, status }));
    };
    const onEvent = (m: { runId: string; seq: number; event: unknown }) => {
      if (live) rawSendEvent(m.runId, m.seq, m.event); else queue.push(['event', m]);
    };
    const onRunEnd = (m: { runId: string; status: string }) => {
      if (live) rawSendEnd(m.runId, m.status); else queue.push(['end', m]);
    };
    store.on('event', onEvent);
    store.on('runEnd', onRunEnd);

    ws.on('message', (data) => {
      let msg: { type?: string; prompt?: string; lastRunId?: string; lastSeq?: number; wav?: string; jpeg?: string; id?: string; choice?: string; allowKey?: string; lang?: string };
      try { msg = JSON.parse(data.toString()); } catch { return; }

      if (msg.type === 'hello') {
        lang = normalizeLang(msg.lang);
        const cur = store.getCurrent();
        ws.send(JSON.stringify({ type: 'sync', sessionId: cur.sessionId, currentRun: cur.currentRun }));
        ws.send(JSON.stringify(usageSnapshot()));
        if (cur.currentRun) {
          const since = msg.lastRunId === cur.currentRun.id ? (msg.lastSeq ?? 0) : 0;
          for (const e of store.eventsSince(cur.currentRun.id, since)) rawSendEvent(cur.currentRun.id, e.seq, e.event);
          if (cur.currentRun.status !== 'running') rawSendEnd(cur.currentRun.id, cur.currentRun.status);
        }
        for (const item of queue) {
          if (item[0] === 'event') rawSendEvent(item[1].runId, item[1].seq, item[1].event);
          else rawSendEnd(item[1].runId, item[1].status);
        }
        queue.length = 0;
        live = true;
        return;
      }
      if (msg.type === 'prompt' && msg.prompt) {
        if (opts.projectsDir && parseSessionCommand(msg.prompt, lang)) {
          // 只有全权连接能跨项目;sandbox 连接(眼镜低信任路径)被挡在 sandbox 内
          if (capability !== 'full') { send({ type: 'transcript', text: tr(lang).sandboxOnly }); return; }
          const sessions = listRecentSessions(opts.projectsDir, { excludeCwd: opts.sandboxDir });
          void requestSessionChoice(sessions).then((s) => {
            if (!s) return;
            activeCwd = s.cwd;
            resumeTarget = s.id;
          });
          return;
        }
        const cmd = parseModelCommand(msg.prompt, lang);
        if (cmd?.kind === 'pick') {
          void requestModelChoice(selectedModel).then((choice) => {
            if (choice === 'opus' || choice === 'sonnet' || choice === 'haiku') applyModel(choice);
          });
          return;
        }
        const dict = opts.dictionaryDir ? loadDictionary(join(opts.dictionaryDir, `dictionary.${lang}.json`)) : {};
        let text = expandPrompt(msg.prompt, dict);
        if (pendingPhoto) {
          if (Date.now() - pendingPhoto.at > PHOTO_TTL_MS) {
            // 拍了照却隔太久才说话:作废,不静默粘到无关的 prompt 上
            pendingPhoto = null;
            send({ type: 'transcript', text: tr(lang).photoExpired });
            return;
          }
          text = `Look at the image file ${pendingPhoto.rel} first, then: ${text}`;
          pendingPhoto = null;
        }
        void startRun(text);
        return;
      }
      if (msg.type === 'photo' && typeof msg.jpeg === 'string') {
        const jpeg = msg.jpeg;
        void (async () => {
          try {
            const dir = join(opts.sandboxDir, 'photos');
            await mkdir(dir, { recursive: true });
            const rel = `./photos/photo-${Date.now()}.jpg`;
            await writeFile(join(opts.sandboxDir, rel), Buffer.from(jpeg, 'base64'));
            pendingPhoto = { rel, at: Date.now() };
            await prunePhotos(dir, PHOTO_KEEP);   // 有界:只留最近 N 张,刚写的最新故安全
            send({ type: 'photoAck', file: rel });
          } catch {
            send({ type: 'photoAck', file: '' });
          }
        })();
        return;
      }
      if (msg.type === 'audio' && typeof msg.wav === 'string') { void handleAudio(ws, msg.wav); return; }
      if (msg.type === 'stop') { current?.stop(); return; }
      if (msg.type === 'newSession') {
        store.newSession(); allowedSet.clear(); pendingPhoto = null;
        activeCwd = null; resumeTarget = null;   // 归位 sandbox
        currentModel = null; selectedModel = null; sessionCostUsd = 0; sessionTokens = 0;
        broadcastUsage();
        return;
      }
      if (msg.type === 'setLang' && typeof msg.lang === 'string') {
        lang = normalizeLang(msg.lang);
        return;
      }
      if (msg.type === 'permissionDecision' && msg.id && typeof msg.choice === 'string') {
        if (msg.choice === tr(lang).permKind && msg.allowKey) allowedSet.add(msg.allowKey);
        pending.get(msg.id)?.(msg.choice);
        return;
      }
    });

    ws.on('close', () => { store.off('event', onEvent); store.off('runEnd', onRunEnd); clients.delete(send); });
  });

  return { http, wss, store, requestDecision, allowedSet };
}
