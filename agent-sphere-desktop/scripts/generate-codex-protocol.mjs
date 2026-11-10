import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';

const version = '0.167.0-alpha.3.2';
const outDir = path.resolve('protocol', `codex-${version}`);

function candidates() {
  const list = [];
  if (process.platform === 'win32') {
    const root = path.join(process.env.LOCALAPPDATA ?? '', 'OpenAI', 'Codex', 'bin');
    if (existsSync(root)) {
      for (const entry of readdirSync(root)) {
        const exe = path.join(root, entry, 'codex.exe');
        if (existsSync(exe)) list.push(exe);
      }
    }
    list.push(path.join(process.env.ProgramFiles ?? '', 'WindowsApps'));
  } else {
    list.push('/usr/local/bin/codex', '/opt/homebrew/bin/codex');
  }
  return list.filter((item) => item && existsSync(item) && !statSync(item).isDirectory());
}

const exe = process.env.CODEX_BIN || candidates()[0];
if (!exe) {
  console.error('codex binary not found; set CODEX_BIN to the codex executable');
  process.exit(1);
}

console.log(`generating Codex app-server protocol from ${exe}`);
execFileSync(exe, ['app-server', 'generate-ts', '--out', outDir], { stdio: 'inherit' });
console.log(`protocol types written to ${outDir}`);
