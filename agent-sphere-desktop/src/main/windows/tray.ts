import { Menu, Tray, app, nativeImage } from 'electron';
import { paths } from '../config/paths';
import { logger } from '../logging/logger';
import type { RuntimeStatus } from '../../shared/types';

export interface TrayController {
  tray: Tray;
  update(status: RuntimeStatus): void;
  destroy(): void;
}

export function createTray(options: {
  onOpen(): void;
  onTogglePause(): void;
  onOpenLogs(): void;
  onCheckUpdates(): void;
  onQuit(): void;
}): TrayController {
  let image = nativeImage.createFromPath(paths.resource('tray.png'));
  if (image.isEmpty()) {
    logger.warn(`tray icon missing: ${paths.resource('tray.png')}`);
    image = nativeImage.createEmpty();
  }

  const tray = new Tray(image);
  tray.setToolTip(`AgentSphere ${app.getVersion()}`);
  tray.on('click', () => options.onOpen());

  let current: RuntimeStatus | undefined;

  const rebuild = (): void => {
    const state = current?.state ?? 'UNCONFIGURED';
    const status: Record<string, string> = {
      UNCONFIGURED: 'Not configured',
      CONNECTING: 'Connecting',
      ONLINE: 'Online',
      OFFLINE: 'Offline',
      PAUSED: 'Paused',
    };
    const version = app.getVersion();
    const menu = Menu.buildFromTemplate([
      { label: `AgentSphere ${version} · ${status[state] ?? state}`, enabled: false },
      { label: `Running tasks: ${current?.runningTasks ?? 0}`, enabled: false },
      { type: 'separator' },
      { label: 'Open window', click: options.onOpen },
      {
        label: state === 'PAUSED' ? 'Resume task intake' : 'Pause task intake',
        click: options.onTogglePause,
      },
      { label: 'Open log folder', click: options.onOpenLogs },
      { label: 'Check for updates', click: options.onCheckUpdates },
      { type: 'separator' },
      { label: 'Quit', click: options.onQuit },
    ]);
    tray.setContextMenu(menu);
    tray.setToolTip(`AgentSphere ${version} · ${status[state] ?? state}`);
  };

  rebuild();

  return {
    tray,
    update(status: RuntimeStatus) {
      current = status;
      rebuild();
    },
    destroy() {
      tray.destroy();
    },
  };
}
