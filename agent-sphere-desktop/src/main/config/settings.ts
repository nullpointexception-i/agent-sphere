import { safeStorage } from 'electron';
import { randomUUID } from 'node:crypto';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { paths } from './paths';
import type { DesktopEnvironment, DesktopSettings } from '../../shared/types';

export interface DesktopSecrets {
  /** Legacy single-server token; migrated into {@link deviceTokens} on first load. */
  deviceToken?: string;
  /** Device token per normalized server origin. */
  deviceTokens?: Record<string, string>;
}

export const DEFAULT_SERVER_URL =
  process.env.AS_DEFAULT_SERVER_URL || 'https://as.buukle.top';

/** Keep a short list: enough to jump between local and online, not a full history. */
export const MAX_RECENT_SERVERS = 5;

/**
 * Normalize a user-entered server address to its origin.
 * Drops trailing slashes and any path so every lookup uses the same storage key.
 */
export function normalizeServerUrl(value: string): string {
  const url = new URL(value.trim());
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error('server URL must start with http:// or https://');
  }
  return url.origin;
}

export function pushRecentServer(
  origin: string,
  recent: string[] | undefined,
): string[] {
  return [origin, ...(recent ?? []).filter((item) => item !== origin)].slice(
    0,
    MAX_RECENT_SERVERS,
  );
}

function defaultSettings(): DesktopSettings {
  return {
    serverUrl: DEFAULT_SERVER_URL,
    webOrigin: DEFAULT_SERVER_URL,
    deviceKey: randomUUID(),
    environments: {},
    recentServers: [DEFAULT_SERVER_URL],
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
    let storedDeviceKey: string | undefined;
    try {
      const raw = JSON.parse(await readFile(paths.settingsFile(), 'utf8')) as Partial<DesktopSettings>;
      storedDeviceKey = raw.deviceKey;
      const defaults = defaultSettings();
      this.settings = {
        ...defaults,
        ...raw,
        environments: { ...(raw.environments ?? {}) },
        recentServers: [...(raw.recentServers ?? defaults.recentServers ?? [])],
        autoApprove: {
          ...defaults.autoApprove,
          ...(raw.autoApprove ?? {}),
        },
      };
    } catch {
      this.settings = defaultSettings();
    }

    // Older installs have no device key; persist one so the backend can keep
    // one runtime per physical device across restarts.
    if (!storedDeviceKey) {
      this.settings.deviceKey = randomUUID();
      await atomicWrite(paths.settingsFile(), JSON.stringify(this.settings, null, 2));
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

    await this.migrateBindings();
  }

  /**
   * Move the pre-environment flat binding onto the active server so upgrading users
   * keep their runtime without re-binding.
   */
  private async migrateBindings(): Promise<void> {
    const origin = this.activeOrigin();
    let settingsChanged = false;
    let secretsChanged = false;

    if (this.settings.runtimeId && !this.settings.environments?.[origin]?.runtimeId) {
      this.settings.environments = {
        ...(this.settings.environments ?? {}),
        [origin]: { runtimeId: this.settings.runtimeId },
      };
      settingsChanged = true;
    }
    if (!(this.settings.recentServers ?? []).includes(origin)) {
      this.settings.recentServers = pushRecentServer(
        origin,
        this.settings.recentServers,
      );
      settingsChanged = true;
    }
    if (this.secrets.deviceToken && !this.secrets.deviceTokens?.[origin]) {
      this.secrets.deviceTokens = {
        ...(this.secrets.deviceTokens ?? {}),
        [origin]: this.secrets.deviceToken,
      };
      // Drop the flat token: with per-environment lookups it would otherwise leak
      // into any environment that has no binding yet.
      this.secrets.deviceToken = undefined;
      secretsChanged = true;
    }

    if (settingsChanged) {
      await atomicWrite(paths.settingsFile(), JSON.stringify(this.settings, null, 2));
    }
    if (secretsChanged) {
      await this.writeSecrets();
    }
  }

  get(): DesktopSettings {
    return this.settings;
  }

  getSecrets(): DesktopSecrets {
    return this.secrets;
  }

  activeOrigin(): string {
    try {
      return normalizeServerUrl(this.settings.serverUrl);
    } catch {
      return DEFAULT_SERVER_URL;
    }
  }

  getRuntimeId(origin: string = this.activeOrigin()): number | undefined {
    const scoped = this.settings.environments?.[origin]?.runtimeId;
    if (scoped !== undefined) return scoped;
    return origin === this.activeOrigin() ? this.settings.runtimeId : undefined;
  }

  getDeviceToken(origin: string = this.activeOrigin()): string | undefined {
    return (
      this.secrets.deviceTokens?.[origin] ??
      (origin === this.activeOrigin() ? this.secrets.deviceToken : undefined)
    );
  }

  recentServers(): string[] {
    return this.settings.recentServers ?? [];
  }

  isConfigured(): boolean {
    return Boolean(
      this.settings.serverUrl && this.getDeviceToken() && this.getRuntimeId(),
    );
  }

  async update(patch: Partial<DesktopSettings>): Promise<DesktopSettings> {
    const next: DesktopSettings = {
      ...this.settings,
      ...patch,
      environments: { ...(this.settings.environments ?? {}) },
      recentServers: [...(this.settings.recentServers ?? [])],
      autoApprove: {
        ...this.settings.autoApprove,
        ...(patch.autoApprove ?? {}),
      },
    };
    if (patch.serverUrl) {
      const origin = normalizeServerUrl(patch.serverUrl);
      next.serverUrl = origin;
      next.webOrigin = patch.webOrigin
        ? normalizeServerUrl(patch.webOrigin)
        : origin;
      next.recentServers = pushRecentServer(origin, next.recentServers);
      next.runtimeId = next.environments?.[origin]?.runtimeId;
    } else if (patch.webOrigin) {
      next.webOrigin = normalizeServerUrl(patch.webOrigin);
    }
    if (!next.webOrigin) next.webOrigin = next.serverUrl;
    this.settings = next;
    await atomicWrite(paths.settingsFile(), JSON.stringify(next, null, 2));
    return next;
  }

  /**
   * Bind a runtime to the given server. Each environment keeps its own id/token so
   * switching servers does not clobber the other one.
   */
  async setRuntimeBinding(
    runtimeId: number,
    deviceToken: string,
    origin: string = this.activeOrigin(),
  ): Promise<void> {
    const environments: Record<string, DesktopEnvironment> = {
      ...(this.settings.environments ?? {}),
      [origin]: { runtimeId },
    };
    this.settings = {
      ...this.settings,
      runtimeId,
      environments,
      recentServers: pushRecentServer(origin, this.settings.recentServers),
    };
    await atomicWrite(paths.settingsFile(), JSON.stringify(this.settings, null, 2));
    await this.writeSecrets({
      deviceTokens: {
        ...(this.secrets.deviceTokens ?? {}),
        [origin]: deviceToken,
      },
    });
  }

  async updateSecrets(patch: Partial<DesktopSecrets>): Promise<void> {
    this.secrets = { ...this.secrets, ...patch };
    await this.writeSecrets();
  }

  private async writeSecrets(patch?: Partial<DesktopSecrets>): Promise<void> {
    if (patch) {
      this.secrets = { ...this.secrets, ...patch };
    }
    const json = JSON.stringify(this.secrets);
    const payload = safeStorage.isEncryptionAvailable()
      ? safeStorage.encryptString(json)
      : Buffer.from(json, 'utf8');
    await atomicWrite(paths.secretsFile(), payload);
  }
}
