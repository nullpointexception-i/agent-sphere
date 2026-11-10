import { safeStorage } from 'electron';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { paths } from './paths';
import type { DesktopSettings } from '../../shared/types';

export interface DesktopSecrets {
  deviceToken?: string;
}

export const DEFAULT_SERVER_URL =
  process.env.AS_DEFAULT_SERVER_URL || 'https://as.buukle.top';

function defaultSettings(): DesktopSettings {
  return {
    serverUrl: DEFAULT_SERVER_URL,
    webOrigin: DEFAULT_SERVER_URL,
    runtimeName: process.env.COMPUTERNAME || process.env.HOSTNAME || 'AgentSphere Desktop',
    language: 'system',
    startAtLogin: false,
    closeToTray: true,
    maxConcurrentTasks: 2,
    taskTimeoutSec: 600,
    approvalTimeoutSec: 120,
    autoApprove: {
      readOnly: true,
      network: false,
      fileWrite: false,
      shell: false,
    },
    telemetryEnabled: false,
  };
}

async function atomicWrite(file: string, data: string | Buffer): Promise<void> {
  await mkdir(path.dirname(file), { recursive: true });
  const tmp = `${file}.${process.pid}.tmp`;
  await writeFile(tmp, data);
  await rename(tmp, file);
}

export class SettingsStore {
  private settings: DesktopSettings = defaultSettings();
  private secrets: DesktopSecrets = {};

  async load(): Promise<void> {
    try {
      const raw = JSON.parse(await readFile(paths.settingsFile(), 'utf8')) as Partial<DesktopSettings>;
      this.settings = {
        ...defaultSettings(),
        ...raw,
        autoApprove: {
          ...defaultSettings().autoApprove,
          ...(raw.autoApprove ?? {}),
        },
      };
    } catch {
      this.settings = defaultSettings();
    }

    try {
      const encrypted = await readFile(paths.secretsFile());
      if (safeStorage.isEncryptionAvailable()) {
        this.secrets = JSON.parse(safeStorage.decryptString(encrypted)) as DesktopSecrets;
      } else {
        this.secrets = JSON.parse(encrypted.toString('utf8')) as DesktopSecrets;
      }
    } catch {
      this.secrets = {};
    }
  }

  get(): DesktopSettings {
    return this.settings;
  }

  getSecrets(): DesktopSecrets {
    return this.secrets;
  }

  isConfigured(): boolean {
    return Boolean(
      this.settings.serverUrl && this.secrets.deviceToken && this.settings.runtimeId,
    );
  }

  async update(patch: Partial<DesktopSettings>): Promise<DesktopSettings> {
    const next: DesktopSettings = {
      ...this.settings,
      ...patch,
      autoApprove: {
        ...this.settings.autoApprove,
        ...(patch.autoApprove ?? {}),
      },
    };
    if (!next.webOrigin) next.webOrigin = next.serverUrl;
    this.settings = next;
    await atomicWrite(paths.settingsFile(), JSON.stringify(next, null, 2));
    return next;
  }

  async updateSecrets(patch: Partial<DesktopSecrets>): Promise<void> {
    this.secrets = { ...this.secrets, ...patch };
    const json = JSON.stringify(this.secrets);
    const payload = safeStorage.isEncryptionAvailable()
      ? safeStorage.encryptString(json)
      : Buffer.from(json, 'utf8');
    await atomicWrite(paths.secretsFile(), payload);
  }
}
