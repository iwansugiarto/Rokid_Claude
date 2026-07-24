import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, basename } from 'node:path';
import { type Lang } from './i18n';

export interface SessionEntry {
  id: string;        // 会话 uuid(jsonl 文件名)
  cwd: string;       // 会话所属项目目录(--resume 必须在这个 cwd 下)
  summary: string;   // 首条用户消息截断
  mtimeMs: number;
  label: string;     // 选择框展示文案
}

// 「列会话/切会话」意图(镜像 parseModelCommand;"new session" 由客户端先拦截,到不了这里)
const EN_HAS_SESSION = /\bsessions?\b/i;
const EN_VERB = /\b(list|open|resume|switch|show|pick|select|change)\b/i;
const ZH_HAS_SESSION = /会话|會話/;
const ZH_VERB = /(列出|列表|打开|恢复|切换|换|选|看)/;

function normalize(s: string): string {
  return s.trim().replace(/^[。,,!!.\s]+|[。,,!!.\s]+$/g, '').replace(/換/g, '换').trim();
}

/** 「切/列会话」意图 → 开选择框;仅提及 session 无动词(如 "what session is this")→ null 照常发 claude。 */
export function parseSessionCommand(text: string, lang: Lang = 'zh'): { kind: 'pick' } | null {
  const t = normalize(text);
  if (lang === 'en') return EN_HAS_SESSION.test(t) && EN_VERB.test(t) ? { kind: 'pick' } : null;
  return ZH_HAS_SESSION.test(t) && ZH_VERB.test(t) ? { kind: 'pick' } : null;
}

function firstUserText(file: string): { cwd?: string; text?: string } {
  let cwd: string | undefined;
  let text: string | undefined;
  let raw: string;
  try { raw = readFileSync(file, 'utf8'); } catch { return {}; }
  for (const line of raw.split('\n')) {
    if (!line.trim()) continue;
    let o: any;
    try { o = JSON.parse(line); } catch { continue; }
    if (!cwd && typeof o.cwd === 'string') cwd = o.cwd;
    if (!text && o.type === 'user') {
      const c = o.message?.content;
      if (typeof c === 'string') text = c;
      else if (Array.isArray(c)) text = c.find((b: any) => b?.type === 'text')?.text;
    }
    if (cwd && text) break;
  }
  return { cwd, text };
}

function ageLabel(ms: number): string {
  const m = Math.floor(ms / 60000);
  if (m < 60) return `${Math.max(m, 1)}m`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h`;
  return `${Math.floor(h / 24)}d`;
}

/**
 * 扫描 ~/.claude/projects 下所有会话,按最近活跃排序。
 * 只取 maxAgeDays 内的("活跃项目"),排除 excludeCwd(relay 自己的 sandbox),截前 limit 条。
 * 安全:只返回磁盘上真实存在的会话 id —— --resume 不存在的会话会绕过 --settings 权限 hook。
 */
export function listRecentSessions(
  projectsDir: string,
  opts: { excludeCwd?: string; maxAgeDays?: number; limit?: number; nowMs?: number } = {},
): SessionEntry[] {
  const { excludeCwd, maxAgeDays = 7, limit = 8 } = opts;
  const now = opts.nowMs ?? Date.now();
  const out: SessionEntry[] = [];
  let dirs: string[];
  try { dirs = readdirSync(projectsDir); } catch { return []; }
  for (const d of dirs) {
    const dir = join(projectsDir, d);
    let files: string[];
    try { files = readdirSync(dir).filter((f) => f.endsWith('.jsonl')); } catch { continue; }
    for (const f of files) {
      const path = join(dir, f);
      let mtimeMs: number;
      try { mtimeMs = statSync(path).mtimeMs; } catch { continue; }
      if (now - mtimeMs > maxAgeDays * 86400_000) continue;
      const { cwd, text } = firstUserText(path);
      if (!cwd || !text) continue;                    // 没有正文的空会话不展示
      if (excludeCwd && cwd === excludeCwd) continue;
      const summary = text.replace(/\s+/g, ' ').trim().slice(0, 40);
      out.push({
        id: f.replace(/\.jsonl$/, ''),
        cwd,
        summary,
        mtimeMs,
        label: `${summary} · ${ageLabel(now - mtimeMs)} · ${basename(cwd)}`,
      });
    }
  }
  out.sort((a, b) => b.mtimeMs - a.mtimeMs);
  return out.slice(0, limit);
}
