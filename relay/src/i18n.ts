export type Lang = 'zh' | 'en';

/** 任意输入归一成 Lang;非 'en' 一律 'zh'(向后兼容旧配置/旧客户端)。 */
export function normalizeLang(s: unknown): Lang {
  return s === 'en' ? 'en' : 'zh';
}

export interface Tr {
  whisperLang: string;
  whisperPrompt: string | null;
  permOnce: string;
  permKind: string;
  permDeny: string;
  modelCancel: string;
  sandboxOnly: string;
  verb(tool: string): string;
}

const ZH: Tr = {
  whisperLang: 'zh',
  whisperPrompt: '以下是普通话句子的简体中文转写。',
  permOnce: '允许一次', permKind: '这类都允许', permDeny: '拒绝',
  modelCancel: '取消',
  sandboxOnly: '⚠️ 此设备仅限沙盒,不能切换到其他项目会话',
  verb: (t) => (t === 'Bash' ? '运行' : t === 'Write' || t === 'Edit' ? '写' : t),
};

const EN: Tr = {
  whisperLang: 'en',
  whisperPrompt: null,
  permOnce: 'Allow once', permKind: 'Allow this kind', permDeny: 'Deny',
  modelCancel: 'Cancel',
  sandboxOnly: '⚠️ This device is sandbox-only; session switch not allowed',
  verb: (t) => (t === 'Bash' ? 'Run' : t === 'Write' || t === 'Edit' ? 'Write' : t),
};

export function tr(lang: Lang): Tr {
  return lang === 'en' ? EN : ZH;
}
