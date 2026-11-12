import { app } from 'electron';
import path from 'node:path';

export const paths = {
  userData(): string {
    return app.getPath('userData');
  },
  settingsFile(): string {
    return path.join(paths.userData(), 'settings.json');
  },
  secretsFile(): string {
    return path.join(paths.userData(), 'secrets.bin');
  },
  profilesFile(): string {
    return path.join(paths.userData(), 'profiles.json');
  },
  logsDir(): string {
    return path.join(paths.userData(), 'logs');
  },
  diagnosticsDir(): string {
    return path.join(paths.userData(), 'diagnostics');
  },
  preload(): string {
    return path.join(app.getAppPath(), 'dist', 'preload', 'index.cjs');
  },
  rendererDir(): string {
    if (process.env.AS_RENDERER_DIR) return process.env.AS_RENDERER_DIR;
    if (app.isPackaged) return path.join(process.resourcesPath, 'renderer');
    return path.resolve(app.getAppPath(), '..', 'agent-sphere-ui', 'dist-desktop');
  },
  resource(...parts: string[]): string {
    const root = app.isPackaged
      ? process.resourcesPath
      : path.join(app.getAppPath(), 'resources');
    return path.join(root, ...parts);
  },
};
