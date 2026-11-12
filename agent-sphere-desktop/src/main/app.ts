import { BrowserWindow, Notification, app, shell } from 'electron';
import { cp, mkdir } from 'node:fs/promises';
import path from 'node:path';
import type {
  BootstrapConfig,
  DesktopInfo,
  RuntimeStatus,
  TaskView,
} from '../shared/types';
import { paths } from './config/paths';
import { ProfileStore } from './config/profiles';
import { SettingsStore } from './config/settings';
import { DriverRegistry } from './runtime/drivers/registry';
import { ApprovalBroker } from './runtime/approval';
import { RuntimeManager } from './runtime/manager';
import { LocalApiClient } from './api/client';
import { createMainWindow } from './windows/mainWindow';
import { type TrayController, createTray } from './windows/tray';
import { registerIpc } from './ipc';
import { IPC } from './ipc/channels';
import { logger } from './logging/logger';

export class AppController {
  private readonly settings = new SettingsStore();
  private readonly profiles = new ProfileStore();
  private drivers!: DriverRegistry;
  private approvals!: ApprovalBroker;
  private runtime!: RuntimeManager;
  private window: BrowserWindow | null = null;
  private tray: TrayController | null = null;
  private quitting = false;

  async start(): Promise<void> {
    await this.settings.load();
    await this.profiles.load();

    this.drivers = new DriverRegistry(this.settings);
    this.approvals = new ApprovalBroker(this.settings, (request) => {
      this.send(IPC.eventApproval, request);
      if (Notification.isSupported()) {
        new Notification({
          title: request.title,
          body: request.detail.slice(0, 200),
        }).show();
      }
    });
    const api = new LocalApiClient(this.settings);
    this.runtime = new RuntimeManager(
      {
        api,
        settings: this.settings,
        profiles: this.profiles,
        drivers: this.drivers,
        approvals: this.approvals,
      },
      (status) => this.onRuntimeStatus(status),
      (task) => this.send(IPC.eventTask, task),
      (chunk) => this.send(IPC.eventChunk, chunk),
    );

    registerIpc({
      settings: this.settings,
      profiles: this.profiles,
      drivers: this.drivers,
      runtime: this.runtime,
      approvals: this.approvals,
      bootstrap: () => this.bootstrap(),
      info: () => this.info(),
      window: () => this.window,
      reloadWindow: () => this.loadHome(),
      openLogs: async () => {
        await shell.openPath(paths.logsDir());
      },
      exportLogs: () => this.exportLogs(),
    });

    this.window = createMainWindow('/workspace');
    this.window.on('close', (event) => {
      if (!this.quitting && this.settings.get().closeToTray) {
        event.preventDefault();
        this.window?.hide();
      }
    });
    this.window.on('closed', () => {
      this.window = null;
    });

    this.tray = createTray({
      onOpen: () => this.show(),
      onTogglePause: () => this.togglePause(),
      onOpenLogs: () => {
        void shell.openPath(paths.logsDir());
      },
      onQuit: () => app.quit(),
    });

    applyLoginItem(this.settings.get().startAtLogin);
    void this.runtime.start();
    logger.info(`AgentSphere Desktop ${app.getVersion()} started`);
  }

  show(): void {
    if (!this.window) {
      this.window = createMainWindow('/workspace');
    }
    if (this.window.isMinimized()) this.window.restore();
    this.window.show();
    this.window.focus();
  }

  prepareQuit(): void {
    this.quitting = true;
  }

  private loadHome(): void {
    if (!this.window || this.window.isDestroyed()) return;
    const devUrl = process.env.AS_DEV_URL;
    void this.window.loadURL(
      devUrl ? `${devUrl.replace(/\/+$/, '')}/` : 'app://bundle/workspace',
    );
  }

  dispose(): void {
    this.quitting = true;
    this.runtime?.dispose();
    this.tray?.destroy();
  }

  private bootstrap(): BootstrapConfig {
    const settings = this.settings.get();
    const origin = settings.serverUrl.replace(/\/+$/, '');
    return {
      desktop: true,
      apiOrigin: origin,
      webOrigin: settings.webOrigin || origin,
      appVersion: app.getVersion(),
      platform: process.platform,
      configured: this.settings.isConfigured(),
    };
  }

  private info(): DesktopInfo {
    const settings = this.settings.get();
    return {
      appVersion: app.getVersion(),
      electronVersion: process.versions.electron ?? '',
      platform: process.platform,
      arch: process.arch,
      serverUrl: settings.serverUrl,
      runtimeId: this.settings.getRuntimeId(),
      configured: this.settings.isConfigured(),
    };
  }

  private onRuntimeStatus(status: RuntimeStatus): void {
    this.tray?.update(status);
    this.send(IPC.eventRuntime, status);
  }

  private send(channel: string, payload: TaskView | RuntimeStatus | unknown): void {
    if (!this.window || this.window.isDestroyed()) return;
    if (this.window.webContents.isDestroyed()) return;
    this.window.webContents.send(channel, payload);
  }

  private togglePause(): void {
    const status = this.runtime.getStatus();
    if (status.state === 'PAUSED') this.runtime.resume();
    else this.runtime.pause();
  }

  private async exportLogs(): Promise<string> {
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    const target = path.join(paths.diagnosticsDir(), stamp);
    await mkdir(target, { recursive: true });
    await cp(paths.logsDir(), path.join(target, 'logs'), { recursive: true }).catch(() => undefined);
    await cp(paths.settingsFile(), path.join(target, 'settings.json')).catch(() => undefined);
    logger.info(`diagnostics exported to ${target}`);
    return target;
  }
}

function applyLoginItem(openAtLogin: boolean): void {
  if (process.platform === 'linux') return;
  app.setLoginItemSettings({ openAtLogin });
}
