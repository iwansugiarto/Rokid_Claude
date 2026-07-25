import { describe, it, expect } from 'vitest';
import { cleanWhisperOutput, whisperArgs } from '../src/transcribe';

describe('cleanWhisperOutput', () => {
  it('trim + 压缩空白', () => {
    expect(cleanWhisperOutput('  在沙盒里  建个文件  ')).toBe('在沙盒里 建个文件');
  });
  it('丢弃 [BLANK_AUDIO] 等方括号行', () => {
    expect(cleanWhisperOutput('[BLANK_AUDIO]')).toBe('');
    expect(cleanWhisperOutput('一行\n[BLANK_AUDIO]\n二行')).toBe('一行 二行');
  });
  it('丢弃圆括号 / 音符等非语音标记行', () => {
    expect(cleanWhisperOutput('(silence)')).toBe('');
    expect(cleanWhisperOutput('[ Silence ]')).toBe('');
    expect(cleanWhisperOutput('(wind blowing)')).toBe('');
    expect(cleanWhisperOutput('♪ ♪')).toBe('');
    expect(cleanWhisperOutput('(music)\ntake photo\n[BLANK_AUDIO]')).toBe('take photo');
  });
  it('不误伤含括号的正常句子', () => {
    expect(cleanWhisperOutput('open the file (the new one)')).toBe('open the file (the new one)');
  });
  it('空 → 空', () => {
    expect(cleanWhisperOutput('')).toBe('');
    expect(cleanWhisperOutput('\n  \n')).toBe('');
  });
});

describe('whisperArgs', () => {
  it('zh:-l zh + 简体引导词', () => {
    const a = whisperArgs('/x.wav', '/m.bin', 'zh');
    expect(a.slice(0, 6)).toEqual(['-m', '/m.bin', '-f', '/x.wav', '-l', 'zh']);
    expect(a).toContain('--prompt');
  });
  it('en:-l en 且无引导词', () => {
    const a = whisperArgs('/x.wav', '/m.bin', 'en');
    expect(a[a.indexOf('-l') + 1]).toBe('en');
    expect(a).not.toContain('--prompt');
  });
});
