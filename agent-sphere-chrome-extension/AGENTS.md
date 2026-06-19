# AgentSphere Chrome Extension

Manifest V3 Chrome extension that bridges the AgentSphere backend with the browser for automated operations. Backend `agent-sphere-common` holds the chrome-bridge DTOs (`ChromeCommandDTO`, `ChromeCallbackDTO`, `ChromePendingStore`).

## Current state (observable — verify if changed)

- `package.json` scripts `build`/`dev` call `node build.js`, but **`build.js` does not exist**.
- `src/lib` and `src/popup` contain only `git.keep` placeholders — empty.
- `dependencies` and `devDependencies` are empty (no node_modules needed).
- So `npm run build` and `npm run dev` currently FAIL.

The real extension source lives at the **repo root**:
`manifest.json`, `background.js` (MV3 service worker), `content.js`, `page-script.js` (web-accessible, injected), `popup.html`, `popup.js`, `icon-128.png`

Until a build step exists, edit root-level files directly and load as an unpacked extension from the repo root via `chrome://extensions`. Do not treat `src/` as source of truth.

## Permissions

`tabs`, `scripting`, `storage`, `activeTab`, `alarms`, `debugger`; host permissions `<all_urls>`. `page-script.js` is web-accessible to all URLs.

## No tests / linter / CI

Manually reload the unpacked extension and smoke-test before pushing.

## Git

GitHub flow: feature branch off `main` → PR.
