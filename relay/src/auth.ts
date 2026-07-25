import type { IncomingMessage } from 'node:http';

export type Capability = 'full' | 'sandbox';
export interface Tokens { full?: string; sandbox?: string }

/** 从请求里取 token:优先 Authorization: Bearer(眼镜走这条,不进日志)→ Sec-WebSocket-Protocol → ?token=(浏览器镜像回退)。 */
function extractToken(req: IncomingMessage): string | undefined {
  const auth = req.headers['authorization'];
  if (typeof auth === 'string' && auth.startsWith('Bearer ')) return auth.slice(7).trim();
  const proto = req.headers['sec-websocket-protocol'];
  if (typeof proto === 'string' && proto.trim()) return proto.split(',')[0].trim();
  try {
    const u = new URL(req.url ?? '', 'http://localhost');
    return u.searchParams.get('token') ?? undefined;
  } catch {
    return undefined;
  }
}

/**
 * 判定连接能力:
 *  - 未配置任何 token(本地 USB 模式)→ 'full'(向后兼容:无鉴权即全权)
 *  - token 命中 full → 'full';命中 sandbox → 'sandbox';都不中 → null(拒绝)
 * sandbox 连接只能在 sandbox 里跑,不能用会话选择器逃逸到别的项目。
 */
export function classifyConnection(req: IncomingMessage, tokens: Tokens): Capability | null {
  if (!tokens.full && !tokens.sandbox) return 'full';
  const t = extractToken(req);
  if (!t) return null;
  if (tokens.full && t === tokens.full) return 'full';
  if (tokens.sandbox && t === tokens.sandbox) return 'sandbox';
  return null;
}
