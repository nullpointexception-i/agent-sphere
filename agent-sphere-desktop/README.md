# AgentSphere Desktop

Electron client for AgentSphere. It packages the existing `agent-sphere-ui` bundle and
acts as a local runtime connector for opencode and Codex.

## Prerequisites

- Node.js 22+
- AgentSphere backend with the local-runtime API
- opencode Desktop 2.2.x (for the opencode driver)
- ChatGPT / Codex Desktop with Codex CLI `0.167.x` (for the Codex driver)

## Development

```bash
npm install
npm run icons        # generates resources/tray.png and resources/icon.png
npm run build:main   # bundles main + preload

# terminal 1: UI dev server
npm --prefix ../agent-sphere-ui run dev

# terminal 2: Electron against the dev server
npm run dev
```

The app loads `app://bundle/` in production and `AS_DEV_URL` in development.

## Build

```bash
npm run typecheck
npm run build:main
npm run build:ui       # writes ../agent-sphere-ui/dist-desktop
npm run package:win    # NSIS installer under release/
```

`electron-builder.yml` maps `../agent-sphere-ui/dist-desktop` to the `renderer`
resource.

## Configuration

Settings live in `userData/settings.json`; the device token is encrypted with
Electron `safeStorage` in `userData/secrets.bin`.

The first run opens `/desktop/setup` and asks for:

- AgentSphere server URL
- Local runtime ID
- One-time device token

The desktop only sends runtime data to the backend. Commands, workspace paths and
model choices stay on this machine.

## Runtime drivers

### opencode

- Finds the shared opencode service through `Service.discover()`.
- Never starts, stops or reconfigures the user's service.
- Creates one session per `(agent, thread)` and streams `session.text.delta`.

### Codex

- Finds the bundled `codex.exe` from ChatGPT/Codex Desktop (or `codex` on PATH).
- Spawns a private `codex app-server` over stdio JSON-RPC.
- Uses the existing `~/.codex` login and configuration.
- Maps app-server approvals to the local approval UI.

Protocol notes for `0.167.0-alpha.3.2`:

```bash
npm run generate:codex   # requires CODEX_BIN or an installed desktop app
```

Generated types are reference material; the driver uses a small hand-written subset
to avoid coupling the build to a user's Codex installation.

## Security model

- `contextIsolation`, `sandbox`, `nodeIntegration: false`
- device token never reaches the renderer
- server payloads contain no commands; commands and workspaces come from local profiles
- write/shell/network approvals are declined by default
- logs redact authorization headers and tokens
