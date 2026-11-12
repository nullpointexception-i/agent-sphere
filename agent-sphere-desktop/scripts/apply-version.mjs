import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/** Release tags look like v1.0.219-alpha; Windows NSIS and semver ordering need 1.0.219. */
const VERSION_PATTERN = /^v?(\d+\.\d+\.\d+)/;

const tag = (process.argv[2] ?? '').trim();
const match = tag.match(VERSION_PATTERN);
if (!match) {
  console.log(`apply-version: "${tag}" is not a release tag, keeping package version`);
  process.exit(0);
}

const version = match[1];
const packageFile = fileURLToPath(new URL('../package.json', import.meta.url));
const pkg = JSON.parse(readFileSync(packageFile, 'utf8'));

if (pkg.version === version) {
  console.log(`apply-version: already ${version}`);
  process.exit(0);
}

pkg.version = version;
writeFileSync(packageFile, `${JSON.stringify(pkg, null, 2)}\n`);
console.log(`apply-version: ${pkg.name} -> ${version}`);
