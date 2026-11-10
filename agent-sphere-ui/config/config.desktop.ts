import { defineConfig } from '@umijs/max';

/**
 * Desktop build: the Electron main process serves this bundle from `app://bundle/`,
 * so keep the absolute public path and disable the dev proxy.
 */
export default defineConfig({
  outputPath: 'dist-desktop',
  proxy: {},
});
