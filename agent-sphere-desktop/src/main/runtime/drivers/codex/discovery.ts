import { execFile } from 'node:child_process';
import { existsSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { promisify } from 'node:util';
import type { CodexDiscovery } from './types';
import type { DriverStatus } from '../../../../shared/types';

const exec = promisify(execFile);
const SUPPORTED_PREFIX = '0.167.';

async function run(binary: string, args: string[], timeout = 15_000): Promise<string> {
  try {
    const result = await exec(binary, args, { timeout, windowsHide: true });
    return `${result.stdout}\n${result.stderr}`.trim();
  } catch (error) {
    const detail = error as { stdout?: string; stderr?: string; message?: string };
    return `${detail.stdout ?? ''}\n${detail.stderr ?? ''}\n${detail.message ?? ''}`.trim();
  }
}

function executableAt(file: string): string | undefined {
  try {
    if (existsSync(file) && statSync(file).isFile()) return file;
  } catch {
    // ignore unreadable paths
  }
  return undefined;
}

function windowsCandidates(): string[] {
  const result: string[] = [];
  const root = path.join(process.env.LOCALAPPDATA ?? '', 'OpenAI', 'Codex', 'bin');
  try {
    if (existsSync(root)) {
      const entries = readdirSync(root)
        .map((name) => path.join(root, name, 'codex.exe'))
        .filter((file) => executableAt(file))
        .map((file) => ({ file, mtime: statSync(file).mtimeMs }))
        .sort((left, right) => right.mtime - left.mtime);
      result.push(...entries.map((entry) => entry.file));
    }
  } catch {
    // ignore discovery failures
  }

  const programFiles = process.env.ProgramFiles ?? '';
  const windowsApps = path.join(programFiles, 'WindowsApps');
  try {
    if (existsSync(windowsApps)) {
      for (const entry of readdirSync(windowsApps)) {
        if (!entry.startsWith('OpenAI.Codex_')) continue;
        const exe = path.join(windowsApps, entry, 'app', 'resources', 'codex.exe');
        const found = executableAt(exe);
        if (found) result.push(found);
      }
    }
  } catch {
    // ignore access-denied paths
  }

  return result;
}

async function pathCandidates(): Promise<string[]> {
  const result: string[] = [];
  try {
    const command = process.platform === 'win32' ? 'where' : 'which';
    const output = await run(command, ['codex'], 5_000);
    for (const line of output.split(/\r?\n/)) {
      const trimmed = line.trim();
      if (trimmed && executableAt(trimmed)) result.push(trimmed);
    }
  } catch {
    // ignore
  }
  return result;
}

export async function discoverCodex(preferred?: string): Promise<CodexDiscovery> {
  const candidates: string[] = [];
  if (preferred) {
    const found = executableAt(preferred);
    if (found) candidates.push(found);
  }
  if (process.platform === 'win32') candidates.push(...windowsCandidates());
  else candidates.push('/opt/homebrew/bin/codex', '/usr/local/bin/codex');
  candidates.push(...(await pathCandidates()));

  const binary = [...new Set(candidates)].find(Boolean);
  if (!binary) {
    return {
      status: {
        kind: 'CODEX',
        state: 'MISSING',
        message: 'codex not found. Install ChatGPT/Codex Desktop or Codex CLI.',
      },
    };
  }

  const versionOutput = await run(binary, ['--version']);
  const version = versionOutput.match(/(\d+\.\d+\.\d+(?:[-+][^\s]+)?)/)?.[1];
  if (!version) {
    return {
      binary,
      status: {
        kind: 'CODEX',
        state: 'ERROR',
        path: binary,
        message: versionOutput.slice(0, 300) || 'Unable to read the codex version',
      },
    };
  }

  const loginOutput = await run(binary, ['login', 'status']);
  const loggedIn = /logged in/i.test(loginOutput) && !/not logged in/i.test(loginOutput);
  if (!loggedIn) {
    return {
      binary,
      version,
      loggedIn,
      status: {
        kind: 'CODEX',
        state: 'AUTH_REQUIRED',
        path: binary,
        version,
        message: 'Sign in with Codex Desktop or run `codex login` first.',
      },
    };
  }

  if (!version.startsWith(SUPPORTED_PREFIX)) {
    return {
      binary,
      version,
      loggedIn,
      status: {
        kind: 'CODEX',
        state: 'VERSION_MISMATCH',
        path: binary,
        version,
        message: `Codex ${SUPPORTED_PREFIX}x is recommended; found ${version}. Continuing anyway.`,
      },
    };
  }

  return {
    binary,
    version,
    loggedIn,
    status: {
      kind: 'CODEX',
      state: 'READY',
      path: binary,
      version,
    },
  };
}
