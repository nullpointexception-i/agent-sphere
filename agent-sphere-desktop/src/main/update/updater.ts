import { BrowserWindow, app, dialog } from 'electron';
import electronUpdater from 'electron-updater';
import { logger } from '../logging/logger';

// electron-updater is CommonJS: a named ESM import fails at runtime in the packaged app.
const { autoUpdater } = electronUpdater;

/** Wait a little after launch so the workspace is usable before the first check. */
const INITIAL_CHECK_DELAY_MS = 15_000;
const CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000;

export interface UpdaterController {
  check(manual?: boolean): Promise<void>;
  dispose(): void;
}

/** electron-updater wiring: silent background checks plus a tray-triggered manual check. */
export function createUpdater(window: () => BrowserWindow | null): UpdaterController {
  const packaged = app.isPackaged;
  let manual = false;
  let checking = false;
  let initialTimer: NodeJS.Timeout | undefined;
  let intervalTimer: NodeJS.Timeout | undefined;

  const show = async (
    message: string,
    detail?: string,
    type: 'info' | 'error' = 'info',
  ): Promise<void> => {
    const options = { type, title: 'AgentSphere', message, detail, buttons: ['OK'] };
    const parent = window();
    if (parent && !parent.isDestroyed()) {
      await dialog.showMessageBox(parent, options);
    } else {
      await dialog.showMessageBox(options);
    }
  };

  const check = async (manualCheck = false): Promise<void> => {
    if (!packaged) {
      if (manualCheck) {
        await show('当前为开发版本，自动更新仅在安装版可用');
      }
      return;
    }
    if (checking) return;
    checking = true;
    manual = manualCheck;
    try {
      logger.info(`checking for updates (manual=${manualCheck})`);
      await autoUpdater.checkForUpdates();
    } catch (error) {
      logger.warn(`update check failed: ${String(error)}`);
      if (manualCheck) {
        await show('检查更新失败', String(error), 'error');
      }
    } finally {
      checking = false;
    }
  };

  if (packaged) {
    autoUpdater.logger = logger;
    autoUpdater.autoDownload = true;
    autoUpdater.autoInstallOnAppQuit = true;

    autoUpdater.on('update-available', (info) => {
      logger.info(`update available: ${info.version}`);
    });
    autoUpdater.on('update-not-available', () => {
      logger.info('no update available');
      if (manual) {
        manual = false;
        void show('当前已是最新版本');
      }
    });
    autoUpdater.on('download-progress', (progress) => {
      logger.info(`update download: ${Math.round(progress.percent)}%`);
    });
    autoUpdater.on('update-downloaded', (info) => {
      logger.info(`update downloaded: ${info.version}`);
      void (async () => {
        const options = {
          type: 'info' as const,
          title: 'AgentSphere',
          message: `新版本 ${info.version} 已下载完成`,
          detail: '重启客户端后完成安装。',
          buttons: ['立即重启', '稍后'],
          defaultId: 0,
          cancelId: 1,
        };
        const parent = window();
        const result =
          parent && !parent.isDestroyed()
            ? await dialog.showMessageBox(parent, options)
            : await dialog.showMessageBox(options);
        if (result.response === 0) {
          autoUpdater.quitAndInstall(false, true);
        }
      })();
    });
    autoUpdater.on('error', (error) => {
      logger.warn(`auto update failed: ${String(error)}`);
      if (manual) {
        manual = false;
        void show('自动更新失败', String(error), 'error');
      }
    });

    initialTimer = setTimeout(() => void check(false), INITIAL_CHECK_DELAY_MS);
    intervalTimer = setInterval(() => void check(false), CHECK_INTERVAL_MS);
  }

  return {
    check,
    dispose() {
      if (initialTimer) clearTimeout(initialTimer);
      if (intervalTimer) clearInterval(intervalTimer);
      initialTimer = undefined;
      intervalTimer = undefined;
    },
  };
}
