import { describe, it, expect } from 'vitest';
import { WebSocket } from 'ws';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createRelayServer } from './server';

/** 起一个 relay,注入会记录被调语言的假转写器和记录调用参数的空跑 runner。 */
function makeServer(projectsDir?: string, auth?: { tokenFull?: string; tokenSandbox?: string }) {
  const langSeen: string[] = [];
  const prompts: string[] = [];
  const calls: Array<{ prompt: string; cwd: string; sessionId?: string }> = [];
  const transcriber = async (_wav: string, _model: string, lang: 'zh' | 'en') => {
    langSeen.push(lang);
    return '';
  };
  const dir = mkdtempSync(join(tmpdir(), 'rokid-test-'));
  const srv = createRelayServer({
    sandboxDir: dir, webDir: dir, stateDir: dir, modelPath: 'unused',
    projectsDir, ...auth,
    transcriber,
    runner: (o) => {
      prompts.push(o.prompt);
      calls.push({ prompt: o.prompt, cwd: o.cwd, sessionId: o.sessionId });
      return { events: (async function* () {})(), stop() {} };
    },
  });
  return { srv, langSeen, prompts, calls, dir };
}

function connect(port: number, token?: string): Promise<WebSocket> {
  return new Promise((resolve) => {
    const ws = token
      ? new WebSocket(`ws://localhost:${port}`, { headers: { authorization: `Bearer ${token}` } })
      : new WebSocket(`ws://localhost:${port}`);
    ws.on('open', () => resolve(ws));
  });
}

/** 等下一条指定 type 的服务端消息。 */
function waitFor(ws: WebSocket, type: string): Promise<any> {
  return new Promise((resolve) => {
    const h = (raw: Buffer) => {
      const m = JSON.parse(raw.toString());
      if (m.type === type) { ws.off('message', h); resolve(m); }
    };
    ws.on('message', h);
  });
}

describe('setLang', () => {
  it('switches the whisper language for subsequent audio', async () => {
    const { srv, langSeen } = makeServer();
    await new Promise<void>((r) => srv.http.listen(0, r));
    const port = (srv.http.address() as any).port;
    const ws = await connect(port);
    const wav = Buffer.from('x').toString('base64');

    ws.send(JSON.stringify({ type: 'hello', lang: 'zh' }));
    ws.send(JSON.stringify({ type: 'audio', wav }));
    await waitFor(ws, 'transcript');
    expect(langSeen).toEqual(['zh']);

    ws.send(JSON.stringify({ type: 'setLang', lang: 'en' }));
    ws.send(JSON.stringify({ type: 'audio', wav }));
    await waitFor(ws, 'transcript');
    expect(langSeen).toEqual(['zh', 'en']);

    ws.close();
    await new Promise<void>((r) => srv.http.close(() => r()));
  });
});

describe('photo attach', () => {
  it('saves the photo, acks, and prefixes only the next prompt', async () => {
    const { srv, prompts, dir } = makeServer();
    await new Promise<void>((r) => srv.http.listen(0, r));
    const port = (srv.http.address() as any).port;
    const ws = await connect(port);
    ws.send(JSON.stringify({ type: 'hello', lang: 'en' }));

    const jpeg = Buffer.from('fake-jpeg-bytes').toString('base64');
    ws.send(JSON.stringify({ type: 'photo', jpeg }));
    const ack = await waitFor(ws, 'photoAck');
    expect(ack.file).toMatch(/^\.\/photos\/photo-\d+\.jpg$/);
    const saved = await import('node:fs/promises').then((fs) => fs.readFile(join(dir, ack.file)));
    expect(saved.toString()).toBe('fake-jpeg-bytes');

    ws.send(JSON.stringify({ type: 'prompt', prompt: 'what is this?' }));
    await new Promise((r) => setTimeout(r, 100));
    expect(prompts).toHaveLength(1);
    expect(prompts[0]).toBe(`Look at the image file ${ack.file} first, then: what is this?`);

    // 照片只附加一次:下一条 prompt 不再带前缀
    ws.send(JSON.stringify({ type: 'prompt', prompt: 'and now?' }));
    await new Promise((r) => setTimeout(r, 100));
    expect(prompts[1]).toBe('and now?');

    ws.close();
    await new Promise<void>((r) => srv.http.close(() => r()));
  });

  it('expires a stale pending photo instead of attaching it', async () => {
    const { srv, prompts } = makeServer();
    await new Promise<void>((r) => srv.http.listen(0, r));
    const port = (srv.http.address() as any).port;
    const ws = await connect(port);
    ws.send(JSON.stringify({ type: 'hello', lang: 'en' }));

    ws.send(JSON.stringify({ type: 'photo', jpeg: Buffer.from('x').toString('base64') }));
    await waitFor(ws, 'photoAck');

    // 快进系统时间 4 分钟(> PHOTO_TTL_MS 3 分钟)
    const realNow = Date.now;
    Date.now = () => realNow() + 4 * 60_000;
    try {
      ws.send(JSON.stringify({ type: 'prompt', prompt: 'what is this?' }));
      const notice = await waitFor(ws, 'transcript');
      expect(notice.text).toMatch(/expired/i);
      await new Promise((r) => setTimeout(r, 50));
      expect(prompts).toHaveLength(0);   // 过期照片不触发 run
    } finally {
      Date.now = realNow;
    }

    ws.close();
    await new Promise<void>((r) => srv.http.close(() => r()));
  });

  it('keeps at most PHOTO_KEEP photos on disk', async () => {
    const { readdir } = await import('node:fs/promises');
    const { srv, dir } = makeServer();
    await new Promise<void>((r) => srv.http.listen(0, r));
    const port = (srv.http.address() as any).port;
    const ws = await connect(port);
    ws.send(JSON.stringify({ type: 'hello', lang: 'en' }));

    for (let i = 0; i < 8; i++) {
      ws.send(JSON.stringify({ type: 'photo', jpeg: Buffer.from(`p${i}`).toString('base64') }));
      await waitFor(ws, 'photoAck');
    }
    const files = (await readdir(join(dir, 'photos'))).filter((f) => f.endsWith('.jpg'));
    expect(files.length).toBeLessThanOrEqual(5);

    ws.close();
    await new Promise<void>((r) => srv.http.close(() => r()));
  });

  it('newSession clears a pending photo', async () => {
    const { srv, prompts } = makeServer();
    await new Promise<void>((r) => srv.http.listen(0, r));
    const port = (srv.http.address() as any).port;
    const ws = await connect(port);
    ws.send(JSON.stringify({ type: 'hello', lang: 'en' }));

    ws.send(JSON.stringify({ type: 'photo', jpeg: Buffer.from('x').toString('base64') }));
    await waitFor(ws, 'photoAck');
    ws.send(JSON.stringify({ type: 'newSession' }));
    await new Promise((r) => setTimeout(r, 50));
    ws.send(JSON.stringify({ type: 'prompt', prompt: 'plain' }));
    await new Promise((r) => setTimeout(r, 100));
    expect(prompts[0]).toBe('plain');

    ws.close();
    await new Promise<void>((r) => srv.http.close(() => r()));
  });
});

describe('session picker', () => {
  it('lists sessions, resumes the picked one in its cwd, newSession returns to sandbox', async () => {
    const { mkdirSync, writeFileSync } = await import('node:fs');
    const projects = mkdtempSync(join(tmpdir(), 'rokid-proj-'));
    const pdir = join(projects, '-Users-x-app');
    mkdirSync(pdir, { recursive: true });
    writeFileSync(join(pdir, 'sess-1.jsonl'),
      JSON.stringify({ type: 'user', cwd: '/Users/x/app', message: { role: 'user', content: 'fix login bug' } }) + '\n');

    const { srv, calls, dir } = makeServer(projects);
    await new Promise<void>((r) => srv.http.listen(0, r));
    const port = (srv.http.address() as any).port;
    const ws = await connect(port);
    ws.send(JSON.stringify({ type: 'hello', lang: 'en' }));

    ws.send(JSON.stringify({ type: 'prompt', prompt: 'list sessions' }));
    const req = await waitFor(ws, 'sessionRequest');
    expect(req.options.some((o: string) => o.includes('fix login bug') && o.includes('app'))).toBe(true);
    expect(calls).toHaveLength(0);   // 只开选择框,不跑 run

    const label = req.options.find((o: string) => o.includes('fix login bug'));
    ws.send(JSON.stringify({ type: 'permissionDecision', id: req.id, choice: label, allowKey: '' }));
    await new Promise((r) => setTimeout(r, 50));

    ws.send(JSON.stringify({ type: 'prompt', prompt: 'what were we doing?' }));
    await new Promise((r) => setTimeout(r, 100));
    expect(calls).toHaveLength(1);
    expect(calls[0].cwd).toBe('/Users/x/app');
    expect(calls[0].sessionId).toBe('sess-1');

    ws.send(JSON.stringify({ type: 'newSession' }));
    await new Promise((r) => setTimeout(r, 50));
    ws.send(JSON.stringify({ type: 'prompt', prompt: 'back home' }));
    await new Promise((r) => setTimeout(r, 100));
    expect(calls[1].cwd).toBe(dir);            // 归位 sandbox
    expect(calls[1].sessionId).toBeUndefined();

    ws.close();
    await new Promise<void>((r) => srv.http.close(() => r()));
  });
});

describe('capability gating', () => {
  async function withProjects() {
    const { mkdirSync, writeFileSync } = await import('node:fs');
    const projects = mkdtempSync(join(tmpdir(), 'rokid-proj-'));
    const pdir = join(projects, '-Users-x-app');
    mkdirSync(pdir, { recursive: true });
    writeFileSync(join(pdir, 'sess-1.jsonl'),
      JSON.stringify({ type: 'user', cwd: '/Users/x/app', message: { role: 'user', content: 'fix login bug' } }) + '\n');
    return projects;
  }

  it('sandbox token cannot open the session picker; run stays in sandbox', async () => {
    const projects = await withProjects();
    const { srv, calls, dir } = makeServer(projects, { tokenFull: 'F', tokenSandbox: 'S' });
    await new Promise<void>((r) => srv.http.listen(0, r));
    const port = (srv.http.address() as any).port;
    const ws = await connect(port, 'S');
    ws.send(JSON.stringify({ type: 'hello', lang: 'en' }));

    ws.send(JSON.stringify({ type: 'prompt', prompt: 'list sessions' }));
    const notice = await waitFor(ws, 'transcript');
    expect(notice.text).toMatch(/sandbox-only/i);

    // 逃逸被挡后普通 prompt 仍在 sandbox 跑
    ws.send(JSON.stringify({ type: 'prompt', prompt: 'do work here' }));
    await new Promise((r) => setTimeout(r, 100));
    expect(calls).toHaveLength(1);
    expect(calls[0].cwd).toBe(dir);

    ws.close();
    await new Promise<void>((r) => srv.http.close(() => r()));
  });

  it('full token can still open the picker', async () => {
    const projects = await withProjects();
    const { srv } = makeServer(projects, { tokenFull: 'F', tokenSandbox: 'S' });
    await new Promise<void>((r) => srv.http.listen(0, r));
    const port = (srv.http.address() as any).port;
    const ws = await connect(port, 'F');
    ws.send(JSON.stringify({ type: 'hello', lang: 'en' }));
    ws.send(JSON.stringify({ type: 'prompt', prompt: 'list sessions' }));
    const req = await waitFor(ws, 'sessionRequest');
    expect(req.options.some((o: string) => o.includes('fix login bug'))).toBe(true);
    ws.close();
    await new Promise<void>((r) => srv.http.close(() => r()));
  });

  it('wrong token is rejected at handshake', async () => {
    const { srv } = makeServer(undefined, { tokenFull: 'F' });
    await new Promise<void>((r) => srv.http.listen(0, r));
    const port = (srv.http.address() as any).port;
    const closed = await new Promise<number>((resolve) => {
      const ws = new WebSocket(`ws://localhost:${port}`, { headers: { authorization: 'Bearer nope' } });
      ws.on('close', (code) => resolve(code));
      ws.on('error', () => {});
    });
    expect(closed).toBe(1008);
    await new Promise<void>((r) => srv.http.close(() => r()));
  });
});

describe('broadcast fan-out', () => {
  it('sends permissionRequest to all connected clients, any one can answer', async () => {
    const { srv } = makeServer();
    await new Promise<void>((r) => srv.http.listen(0, r));
    const port = (srv.http.address() as any).port;
    const wsA = await connect(port);
    const wsB = await connect(port);
    wsA.send(JSON.stringify({ type: 'hello', lang: 'zh' }));
    wsB.send(JSON.stringify({ type: 'hello', lang: 'zh' }));
    await new Promise((r) => setTimeout(r, 50));

    const gotA = waitFor(wsA, 'permissionRequest');
    const gotB = waitFor(wsB, 'permissionRequest');

    // 触发权限请求(/permission 会等裁决后才响应,所以先别 await)
    const resP = fetch(`http://localhost:${port}/permission`, {
      method: 'POST',
      body: JSON.stringify({ tool: 'Bash', input: { command: 'ls' } }),
    });

    const [reqA, reqB] = await Promise.all([gotA, gotB]);
    expect(reqA.id).toBe(reqB.id);   // 同一请求扇出给两端

    // 任一客户端回裁决即兑现
    wsA.send(JSON.stringify({ type: 'permissionDecision', id: reqA.id, choice: '允许一次', allowKey: 'Bash' }));
    const body = await (await resP).json();
    expect(body.allow).toBe(true);

    wsA.close();
    wsB.close();
    await new Promise<void>((r) => srv.http.close(() => r()));
  });
});
