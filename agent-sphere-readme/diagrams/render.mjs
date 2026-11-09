#!/usr/bin/env node
/**
 * Render every mermaid source under diagrams/src/<lang>/*.mmd to a 2x PNG.
 *
 * Requirements:
 *   - Node.js >= 22 (uses the built-in fetch + WebSocket)
 *   - Google Chrome (set CHROME_PATH if it is not in the default location)
 *   - mermaid installed somewhere (default: ../../agent-sphere-ui/node_modules/mermaid)
 *
 * Usage (from the repository root):
 *   node agent-sphere-readme/diagrams/render.mjs                  # render en + zh
 *   node agent-sphere-readme/diagrams/render.mjs --lang en         # render one language
 *   node agent-sphere-readme/diagrams/render.mjs --only er-model   # substring filter
 *
 * Overrides:
 *   CHROME_PATH       absolute path to chrome.exe / chromium / msedge
 *   MERMAID_JS        absolute path to mermaid.min.js (UMD build)
 *   RENDER_SCALE      device scale factor, default 2
 *   RENDER_MAX_WIDTH  preferred max output width in CSS px (never below 1x), default 2400
 */

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const srcRoot = path.join(here, "src");
const scale = Number(process.env.RENDER_SCALE ?? 2);
const maxWidth = Number(process.env.RENDER_MAX_WIDTH ?? 2400);

const argv = process.argv.slice(2);
const langArg = valueOf("--lang");
const onlyArg = valueOf("--only");
const langs = langArg ? [langArg] : ["en", "zh"];

const mermaidJs =
  process.env.MERMAID_JS ??
  path.resolve(here, "../../agent-sphere-ui/node_modules/mermaid/dist/mermaid.min.js");

const chrome = findChrome();
if (!fs.existsSync(mermaidJs)) fail(`mermaid bundle not found: ${mermaidJs}`);
if (!fs.existsSync(chrome)) fail(`chrome not found: ${chrome} (set CHROME_PATH)`);

const htmlFile = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "mmd-render-")), "render.html");
fs.writeFileSync(htmlFile, renderHtml(mermaidJs), "utf8");

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), "mmd-chrome-"));
const browser = spawn(
  chrome,
  [
    "--headless=new",
    "--disable-gpu",
    "--hide-scrollbars",
    "--no-first-run",
    "--no-default-browser-check",
    "--allow-file-access-from-files",
    `--user-data-dir=${userDataDir}`,
    "--remote-debugging-port=0",
    "about:blank",
  ],
  { stdio: "ignore" },
);

let client;
try {
  const wsUrl = await devtoolsUrl(userDataDir);
  client = await connect(wsUrl);
  const { sessionId } = await client.attach();
  client.sessionId = sessionId;
  await client.send("Page.enable");
  await client.send("Runtime.enable");
  await client.send("Emulation.setDefaultBackgroundColorOverride", {
    color: { r: 255, g: 255, b: 255, a: 1 },
  });
  await client.send("Emulation.setDeviceMetricsOverride", {
    width: 3200,
    height: 2400,
    deviceScaleFactor: 1,
    mobile: false,
  });
  await client.send("Page.navigate", { url: pathToFileURL(htmlFile).href });
  await waitForReady(client);

  for (const lang of langs) {
    const srcDir = path.join(srcRoot, lang);
    const outDir = path.join(here, lang);
    if (!fs.existsSync(srcDir)) fail(`missing source directory: ${srcDir}`);
    fs.mkdirSync(outDir, { recursive: true });

    const files = fs
      .readdirSync(srcDir)
      .filter((f) => f.endsWith(".mmd"))
      .filter((f) => !onlyArg || f.includes(onlyArg))
      .sort();

    for (const file of files) {
      const name = path.basename(file, ".mmd");
      const code = fs.readFileSync(path.join(srcDir, file), "utf8");
      const result = await client.send("Runtime.evaluate", {
        expression: `window.__render(${JSON.stringify(code)})`,
        awaitPromise: true,
        returnByValue: true,
      });
      if (result.exceptionDetails) {
        fail(`${lang}/${file}: ${result.exceptionDetails.exception?.description ?? "render failed"}`);
      }
      const { width, height } = result.result.value;
      const pad = 24;
      const cssWidth = Math.ceil(width) + pad * 2;
      const cssHeight = Math.ceil(height) + pad * 2;
      const dpr = Math.max(1, Math.min(scale, maxWidth / cssWidth));

      await client.send("Emulation.setDeviceMetricsOverride", {
        width: cssWidth,
        height: cssHeight,
        deviceScaleFactor: dpr,
        mobile: false,
      });
      const shot = await client.send("Page.captureScreenshot", {
        format: "png",
        fromSurface: true,
        captureBeyondViewport: true,
        // deviceScaleFactor already applies the pixel ratio; clip.scale must stay 1.
        clip: { x: 0, y: 0, width: cssWidth, height: cssHeight, scale: 1 },
      });
      const buffer = Buffer.from(shot.data, "base64");
      fs.writeFileSync(path.join(outDir, `${name}.png`), buffer);
      console.log(
        `${lang}/${name}.png  ${cssWidth}x${cssHeight} @${dpr.toFixed(2)}x  ${(buffer.length / 1024).toFixed(0)}KB`,
      );
    }
  }
} finally {
  try {
    client?.close();
  } catch {}
  browser.kill();
  setTimeout(() => {
    try {
      fs.rmSync(userDataDir, { recursive: true, force: true });
      fs.rmSync(path.dirname(htmlFile), { recursive: true, force: true });
    } catch {}
  }, 500).unref();
}

function valueOf(flag) {
  const idx = argv.indexOf(flag);
  return idx === -1 ? undefined : argv[idx + 1];
}

function fail(message) {
  console.error(`error: ${message}`);
  process.exit(1);
}

function findChrome() {
  if (process.env.CHROME_PATH) return process.env.CHROME_PATH;
  const candidates =
    process.platform === "win32"
      ? [
          `${process.env.ProgramFiles}\\Google\\Chrome\\Application\\chrome.exe`,
          `${process.env["ProgramFiles(x86)"]}\\Google\\Chrome\\Application\\chrome.exe`,
          `${process.env.LOCALAPPDATA}\\Google\\Chrome\\Application\\chrome.exe`,
          `${process.env.ProgramFiles}\\Microsoft\\Edge\\Application\\msedge.exe`,
        ]
      : [
          "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
          "/usr/bin/google-chrome",
          "/usr/bin/chromium",
          "/usr/bin/chromium-browser",
        ];
  return candidates.find((p) => p && fs.existsSync(p)) ?? candidates[0];
}

async function devtoolsUrl(userDataDir) {
  const portFile = path.join(userDataDir, "DevToolsActivePort");
  for (let i = 0; i < 150; i++) {
    if (fs.existsSync(portFile)) {
      const [port, wsPath] = fs.readFileSync(portFile, "utf8").split("\n");
      if (port && wsPath) return `ws://127.0.0.1:${port.trim()}${wsPath.trim()}`;
    }
    await sleep(100);
  }
  throw new Error("Chrome did not expose a DevTools port");
}

function connect(wsUrl) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    const pending = new Map();
    let nextId = 1;

    ws.addEventListener("message", (event) => {
      const msg = JSON.parse(event.data);
      if (msg.id && pending.has(msg.id)) {
        const { ok, fail } = pending.get(msg.id);
        pending.delete(msg.id);
        msg.error ? fail(new Error(msg.error.message)) : ok(msg.result);
      }
    });
    ws.addEventListener("error", () => reject(new Error("DevTools socket error")));

    const send = (method, params = {}, sessionId) =>
      new Promise((ok, fail) => {
        const id = nextId++;
        pending.set(id, { ok, fail });
        ws.send(JSON.stringify({ id, method, params, sessionId }));
      });

    const client = {
      sessionId: undefined,
      send: (method, params) => send(method, params, client.sessionId),
      async attach() {
        const { targetId } = await send("Target.createTarget", { url: "about:blank" });
        const { sessionId } = await send("Target.attachToTarget", { targetId, flatten: true });
        return { sessionId };
      },
      close: () => ws.close(),
    };

    ws.addEventListener("open", () => resolve(client), { once: true });
  });
}

async function waitForReady(client) {
  for (let i = 0; i < 100; i++) {
    const { result } = await client.send("Runtime.evaluate", {
      expression: "document.readyState + '|' + (typeof window.mermaid)",
      returnByValue: true,
    });
    if (result.value === "complete|object") return;
    await sleep(100);
  }
  throw new Error("render page did not become ready");
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function renderHtml(mermaidJsPath) {
  return `<!doctype html>
<html>
<head>
<meta charset="utf-8" />
<style>
  html, body { margin: 0; padding: 0; background: #ffffff; }
  body { padding: 24px; display: inline-block; }
  #container svg { display: block; max-width: none !important; }
</style>
<script src="${mermaidJsPath}"></script>
<script>
  window.__render = async (code) => {
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'loose',
      theme: 'default',
      fontFamily: '"Segoe UI", "Microsoft YaHei", "PingFang SC", Arial, sans-serif',
      flowchart: { htmlLabels: true, useMaxWidth: false },
      sequence: { useMaxWidth: false },
      er: { useMaxWidth: false },
    });
    const container = document.getElementById('container');
    container.innerHTML = '';
    const { svg } = await mermaid.render('d' + Date.now(), code);
    container.innerHTML = svg;
    const el = container.querySelector('svg');
    const box = el.viewBox.baseVal;
    const width = box.width || el.getBoundingClientRect().width;
    const height = box.height || el.getBoundingClientRect().height;
    el.setAttribute('width', width);
    el.setAttribute('height', height);
    el.style.maxWidth = 'none';
    return { width, height };
  };
</script>
</head>
<body><div id="container"></div></body>
</html>`;
}
