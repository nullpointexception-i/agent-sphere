import { copyFile } from 'node:fs/promises';
import path from 'node:path';

/**
 * The desktop app/tray icons are renders of the product logo.
 *
 * Source: agent-sphere-ui/public/logo.svg
 * Outputs (committed): resources/icon.png (1024x1024), resources/tray.png (32x32)
 *
 * Keep the source SVG next to the binaries for traceability. Re-render the PNGs
 * with a browser (canvas.drawImage on the SVG) whenever the logo changes.
 */
await copyFile(
  path.resolve('..', 'agent-sphere-ui', 'public', 'logo.svg'),
  path.resolve('resources', 'logo.svg'),
);
console.log('synced resources/logo.svg; icon.png/tray.png are committed renders');
