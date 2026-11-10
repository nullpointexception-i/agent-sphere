import { app } from 'electron';
import { AppController } from './app';
import { paths } from './config/paths';
import { initializeLogger, logger } from './logging/logger';
import { installAppProtocol, registerAppScheme } from './protocol/appProtocol';

registerAppScheme();

const gotLock = app.requestSingleInstanceLock();
let controller: AppController | undefined;

if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => controller?.show());

  app.whenReady()
    .then(async () => {
      app.setAppUserModelId('top.buukle.agentsphere');
      initializeLogger();
      installAppProtocol(paths.rendererDir());
      controller = new AppController();
      await controller.start();
    })
    .catch((error: unknown) => {
      logger.error(`startup failed: ${String(error)}`);
      app.quit();
    });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });

  app.on('activate', () => controller?.show());

  app.on('before-quit', () => {
    controller?.prepareQuit();
    controller?.dispose();
  });
}
