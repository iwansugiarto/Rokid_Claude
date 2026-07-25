import { describe, it, expect } from 'vitest';
import type { IncomingMessage } from 'node:http';
import { classifyConnection } from '../src/auth';

/** 造一个最小 IncomingMessage:给 url + headers 即可。 */
function req(opts: { url?: string; auth?: string; proto?: string }): IncomingMessage {
  return {
    url: opts.url ?? '/',
    headers: {
      ...(opts.auth ? { authorization: opts.auth } : {}),
      ...(opts.proto ? { 'sec-websocket-protocol': opts.proto } : {}),
    },
  } as unknown as IncomingMessage;
}

describe('classifyConnection', () => {
  it('未配置任何 token(本地USB)→ full', () => {
    expect(classifyConnection(req({}), {})).toBe('full');
  });

  it('Authorization: Bearer 命中 full / sandbox', () => {
    const tokens = { full: 'F', sandbox: 'S' };
    expect(classifyConnection(req({ auth: 'Bearer F' }), tokens)).toBe('full');
    expect(classifyConnection(req({ auth: 'Bearer S' }), tokens)).toBe('sandbox');
  });

  it('Sec-WebSocket-Protocol 与 ?token= 作为回退', () => {
    const tokens = { full: 'F', sandbox: 'S' };
    expect(classifyConnection(req({ proto: 'S' }), tokens)).toBe('sandbox');
    expect(classifyConnection(req({ url: '/?token=F' }), tokens)).toBe('full');
  });

  it('token 优先级:header 高于 query', () => {
    const tokens = { full: 'F', sandbox: 'S' };
    expect(classifyConnection(req({ auth: 'Bearer S', url: '/?token=F' }), tokens)).toBe('sandbox');
  });

  it('错误 / 缺失 token → null(拒绝)', () => {
    const tokens = { full: 'F', sandbox: 'S' };
    expect(classifyConnection(req({ auth: 'Bearer wrong' }), tokens)).toBeNull();
    expect(classifyConnection(req({}), tokens)).toBeNull();
  });

  it('只配 full 时,sandbox 未启用', () => {
    expect(classifyConnection(req({ auth: 'Bearer F' }), { full: 'F' })).toBe('full');
    expect(classifyConnection(req({ auth: 'Bearer S' }), { full: 'F' })).toBeNull();
  });
});
