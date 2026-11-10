import log from 'electron-log/main';
import path from 'node:path';
import { paths } from '../config/paths';

let initialized = false;

export function initializeLogger(): void {
  if (initialized) return;
  initialized = true;
  log.transports.file.resolvePathFn = () => path.join(paths.logsDir(), 'desktop.log');
  log.transports.file.maxSize = 10 * 1024 * 1024;
  log.transports.file.level = 'info';
  log.transports.console.level = 'info';
  log.errorHandler.startCatching();
}

export const logger = log.scope('desktop');

const SECRET_PATTERN = /(authorization|bearer|token|api[-_]?key|secret)\s*[:=]\s*[^\s,;]+/gi;

export function redact(value: unknown): string {
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  return (text ?? '').replace(SECRET_PATTERN, '$1: ***');
}
