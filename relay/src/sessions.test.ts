import { describe, it, expect } from 'vitest';
import { mkdtempSync, mkdirSync, writeFileSync, utimesSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { listRecentSessions, parseSessionCommand } from './sessions';

function fakeSession(projectsDir: string, slug: string, id: string, cwd: string, text: string, ageMs: number, now: number) {
  const dir = join(projectsDir, slug);
  mkdirSync(dir, { recursive: true });
  const file = join(dir, `${id}.jsonl`);
  writeFileSync(file, [
    JSON.stringify({ type: 'queue-operation', sessionId: id }),
    JSON.stringify({ type: 'user', cwd, message: { role: 'user', content: text } }),
  ].join('\n') + '\n');
  const t = (now - ageMs) / 1000;
  utimesSync(file, t, t);
}

describe('parseSessionCommand', () => {
  it('matches switch/list intent per language, ignores plain mentions', () => {
    expect(parseSessionCommand('list sessions', 'en')).toEqual({ kind: 'pick' });
    expect(parseSessionCommand('Resume a session.', 'en')).toEqual({ kind: 'pick' });
    expect(parseSessionCommand('切换会话', 'zh')).toEqual({ kind: 'pick' });
    expect(parseSessionCommand('看会话列表', 'zh')).toEqual({ kind: 'pick' });
    expect(parseSessionCommand('what session is this', 'en')).toBeNull();
    expect(parseSessionCommand('list files in this folder', 'en')).toBeNull();
    expect(parseSessionCommand('这个会话是什么', 'zh')).toBeNull();
  });
});

describe('listRecentSessions', () => {
  it('returns fresh sessions sorted by recency, excluding sandbox and stale ones', () => {
    const now = Date.now();
    const projects = mkdtempSync(join(tmpdir(), 'rokid-projects-'));
    fakeSession(projects, '-Users-x-app', 'aaa', '/Users/x/app', 'fix login bug', 2 * 3600_000, now);
    fakeSession(projects, '-Users-x-web', 'bbb', '/Users/x/web', 'add dark mode', 30 * 60_000, now);
    fakeSession(projects, '-sandbox', 'ccc', '/tmp/sandbox', 'sandbox chatter', 60_000, now);
    fakeSession(projects, '-Users-x-old', 'ddd', '/Users/x/old', 'ancient work', 30 * 86400_000, now);

    const list = listRecentSessions(projects, { excludeCwd: '/tmp/sandbox', nowMs: now });
    expect(list.map((s) => s.id)).toEqual(['bbb', 'aaa']);   // 最近在前;sandbox 与 30 天前的被滤掉
    expect(list[0].cwd).toBe('/Users/x/web');
    expect(list[0].label).toContain('add dark mode');
    expect(list[0].label).toContain('web');
    expect(list[0].label).toContain('30m');
  });

  it('caps the list and survives junk files', () => {
    const now = Date.now();
    const projects = mkdtempSync(join(tmpdir(), 'rokid-projects-'));
    for (let i = 0; i < 12; i++) fakeSession(projects, '-p', `s${i}`, '/p', `task ${i}`, i * 60_000, now);
    writeFileSync(join(projects, '-p', 'garbage.jsonl'), 'not json at all\n');
    const list = listRecentSessions(projects, { nowMs: now });
    expect(list).toHaveLength(8);
    expect(list[0].id).toBe('s0');
  });
});
