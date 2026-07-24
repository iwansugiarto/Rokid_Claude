import { describe, it, expect } from 'vitest';
import { WebSocket } from 'ws';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createRelayServer } from './server';

/** 起一个 relay,注入会记录被调语言的假转写器和记录 prompt 的空跑 runner。 */
function makeServer() {
  const langSeen: string[] = [];
  const prompts: string[] = [];
  const transcriber = async (_wav: string, _model: string, lang: 'zh' | 'en') => {
    langSeen.push(lang);
    return '';
  };
  const dir = mkdtempSync(join(tmpdir(), 'rokid-test-'));
  const srv = createRelayServer({
    sandboxDir: dir, webDir: dir, stateDir: dir, modelPath: 'unused',
    transcriber,
    runner: (o) => { prompts.push(o.prompt); return { events: (async function* () {})(), stop() {} }; },
  });
  return { srv, langSeen, prompts, dir };
}

function connect(port: number): Promise<WebSocket> {
  return new Promise((resolve) => {
    const ws = new WebSocket(`ws://localhost:${port}`);
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
