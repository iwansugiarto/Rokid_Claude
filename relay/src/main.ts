import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { createRelayServer } from './server';
import { runClaude } from './claude-runner';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, '..');

const PORT = 8787;
const token = process.env.ROKID_TOKEN;

// 写一份含 PreToolUse hook 的 settings:写文件/Bash 类工具放行决定外包给中继(中继再问眼镜)。
const hookScript = join(__dirname, 'permission-hook.mjs');
const settingsPath = join(tmpdir(), 'rokid-perm-settings.json');
writeFileSync(settingsPath, JSON.stringify({
  // 只读类联网工具静默放行(改不了 Mac,headless 下也不该卡确认)。
  permissions: { allow: ['WebSearch', 'WebFetch'] },
  hooks: {
    PreToolUse: [{
      matcher: 'Bash|Write|Edit|MultiEdit|NotebookEdit',
      hooks: [{ type: 'command', command: `node ${hookScript}` }],
    }],
  },
}));

// sandbox 是 gitignore 的运行目录:不存在时 spawn claude 会报误导性的 ENOENT(缺的是 cwd 不是二进制)
mkdirSync(join(root, 'sandbox'), { recursive: true });

const { http } = createRelayServer({
  sandboxDir: join(root, 'sandbox'),
  webDir: join(root, 'web'),
  stateDir: join(root, 'state'),
  modelPath: join(root, 'models', 'ggml-small.bin'),
  token,
  dictionaryDir: root,
  runner: (o) => runClaude({ ...o, settingsPath }),
});

http.listen(PORT, () => {
  console.log(`Rokid relay 已启动: http://localhost:${PORT}`);
  console.log(`鉴权: ${token ? '已开启 (ROKID_TOKEN)' : '未开启 (本地直连)'}`);
  console.log(`sandbox: ${join(root, 'sandbox')} | state: ${join(root, 'state')}`);
});
