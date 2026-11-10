import { protocol } from 'electron';
import { existsSync, statSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import path from 'node:path';

export const APP_SCHEME = 'app';
export const APP_HOST = 'bundle';

const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.map': 'application/json; charset=utf-8',
};

export function registerAppScheme(): void {
  protocol.registerSchemesAsPrivileged([
    {
      scheme: APP_SCHEME,
      privileges: {
        standard: true,
        secure: true,
        supportFetchAPI: true,
        corsEnabled: true,
        stream: true,
      },
    },
  ]);
}

export function installAppProtocol(rendererDir: string): void {
  const root = path.resolve(rendererDir);
  protocol.handle(APP_SCHEME, async (request) => {
    try {
      const url = new URL(request.url);
      if (url.hostname !== APP_HOST) {
        return new Response('Not found', { status: 404 });
      }

      let pathname = decodeURIComponent(url.pathname);
      if (!pathname || pathname === '/') pathname = '/index.html';

      const target = path.resolve(root, `.${pathname}`);
      if (!target.startsWith(root + path.sep) && target !== root) {
        return new Response('Forbidden', { status: 403 });
      }

      const file = existsSync(target) && statSync(target).isFile()
        ? target
        : path.join(root, 'index.html');

      if (!existsSync(file)) {
        return new Response(
          'Renderer bundle is missing. Run `npm run build:ui` first.',
          { status: 500, headers: { 'content-type': 'text/plain; charset=utf-8' } },
        );
      }

      const data = await readFile(file);
      return new Response(data, {
        status: 200,
        headers: {
          'content-type': MIME[path.extname(file).toLowerCase()] ?? 'application/octet-stream',
          'cache-control': 'no-cache',
        },
      });
    } catch (error) {
      return new Response(`Protocol error: ${String(error)}`, { status: 500 });
    }
  });
}
