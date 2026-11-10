import { build } from 'esbuild';
import { rm, mkdir } from 'node:fs/promises';

await rm('dist', { recursive: true, force: true });
await mkdir('dist/main', { recursive: true });
await mkdir('dist/preload', { recursive: true });

const shared = {
  bundle: true,
  platform: 'node',
  target: 'node22',
  sourcemap: true,
  packages: 'external',
  external: ['electron'],
  logLevel: 'info',
  define: {
    'process.env.NODE_ENV': JSON.stringify(process.env.NODE_ENV ?? 'production'),
  },
};

await build({
  ...shared,
  entryPoints: ['src/main/index.ts'],
  outfile: 'dist/main/index.js',
  format: 'esm',
});

await build({
  ...shared,
  entryPoints: ['src/preload/index.ts'],
  outfile: 'dist/preload/index.cjs',
  format: 'cjs',
});
