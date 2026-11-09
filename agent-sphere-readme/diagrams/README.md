# README diagrams

Static PNG renders of the mermaid diagrams embedded in [`README.md`](../../README.md) and
[`README.zh-CN.md`](../../README.zh-CN.md). GitHub renders mermaid inline and shrinks it to the
column width, which makes dense diagrams hard to read and impossible to zoom; the PNGs can be
opened at full resolution instead.

## Layout

```text
diagrams/
├── src/en/*.mmd   # mermaid source, English
├── src/zh/*.mmd   # mermaid source, Chinese
├── en/*.png       # rendered images referenced by README.md
├── zh/*.png       # rendered images referenced by README.zh-CN.md
└── render.mjs     # local renderer (Chrome DevTools Protocol, no extra npm deps)
```

| # | Source file | README section |
|---|---|---|
| 01 | `01-business-capability-map` | 1.3 Business capability map |
| 02 | `02-conversational-path` | 1.4 Conversational path |
| 03 | `03-task-path-sequence` | 1.4 Task path |
| 04 | `04-capability-path` | 1.4 Capability path |
| 05 | `05-super-agent-path` | 1.4 Super Agent path |
| 06 | `06-im-collaboration-path` | 1.4 IM collaboration path |
| 07 | `07-application-landscape` | 2.1 Application landscape |
| 08 | `08-backend-modules` | 2.2 Backend modules |
| 09 | `09-static-component-relationships` | 2.3 Static component relationships |
| 10 | `10-chat-call-sequence` | 2.3 One `chat` call |
| 11 | `11-super-agent-sequence` | 2.3 Super Agent sequence |
| 12 | `12-im-message-sequence` | 2.3 IM message sequence |
| 13 | `13-deployment-topology` | 3.2 Deployment topology |
| 14 | `14-delivery-pipeline` | 3.6 Delivery pipeline |
| 15 | `15-er-model` | 4.3 ER model |
| 16 | `16-data-flow` | 4.4 Data flow |

## Re-render

Requirements: Node.js >= 22 and Google Chrome. The mermaid UMD bundle is reused from the admin UI
dependency (`agent-sphere-ui/node_modules/mermaid`); run `npm install` there once if it is missing.

```bash
# from the repository root
node agent-sphere-readme/diagrams/render.mjs                 # all languages
node agent-sphere-readme/diagrams/render.mjs --lang zh       # one language
node agent-sphere-readme/diagrams/render.mjs --only er-model # substring filter
```

Override the defaults with environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `CHROME_PATH` | auto-detected Chrome/Edge | Browser used for rendering |
| `MERMAID_JS` | `agent-sphere-ui/node_modules/mermaid/dist/mermaid.min.js` | mermaid UMD bundle |
| `RENDER_SCALE` | `2` | Device pixel ratio |
| `RENDER_MAX_WIDTH` | `2400` | Max output width in CSS px; wider diagrams are scaled down |

Edit the `.mmd` file, re-render, and keep the matching README image reference unchanged.
