import { BrowserWindow, shell } from 'electron';
import { paths } from '../config/paths';
import { APP_HOST, APP_SCHEME } from '../protocol/appProtocol';

export function createMainWindow(initialPath = '/index.html'): BrowserWindow {
  const window = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1024,
    minHeight: 700,
    show: false,
    autoHideMenuBar: true,
    backgroundColor: '#f5f5f7',
    webPreferences: {
      preload: paths.preload(),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: false,
    },
  });

  window.once('ready-to-show', () => window.show());

  const devUrl = process.env.AS_DEV_URL;
  if (devUrl) {
    void window.loadURL(`${devUrl.replace(/\/+$/, '')}${initialPath}`);
  } else {
    void window.loadURL(`${APP_SCHEME}://${APP_HOST}${initialPath}`);
  }

  const isAllowed = (url: string): boolean => {
    try {
      const parsed = new URL(url);
      if (parsed.protocol === `${APP_SCHEME}:` && parsed.hostname === APP_HOST) return true;
      if (devUrl && url.startsWith(devUrl)) return true;
      return false;
    } catch {
      return false;
    }
  };

  window.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('https://') || url.startsWith('http://')) {
      void shell.openExternal(url);
    }
    return { action: 'deny' };
  });

  window.webContents.on('will-navigate', (event, url) => {
    if (!isAllowed(url)) {
      event.preventDefault();
      if (url.startsWith('https://') || url.startsWith('http://')) {
        void shell.openExternal(url);
      }
    }
  });

  return window;
}
