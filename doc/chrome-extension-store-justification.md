# Chrome Web Store — Permission Justifications (English)

For the **Privacy practices** tab of the AgentSphere Chrome extension listing.

---

## offscreen

> `offscreen` is used to maintain a persistent, always-on connection to the AgentSphere backend. The extension receives automation commands (navigate, click, type, execute JS) over a server-sent-events (SSE) stream. In Manifest V3, the background service worker is automatically suspended after a short idle period, which would drop the stream and cause queued commands to be missed. The offscreen document is immune to service-worker suspension, so it hosts the long-lived SSE stream and relays each incoming command to the service worker for execution. Without it, the extension cannot reliably receive commands while the worker is asleep. The offscreen document contains no UI, captures no content, and only communicates with the extension's own service worker via `chrome.runtime` messages.

## tabGroups

> `tabGroups` is used to visually organize the tabs the AI agent operates on. Every time the agent opens or navigates a tab (including tabs it follows via `target="_blank"` or `window.open`), the tab is moved into a single, clearly labeled "AgentSphere" tab group with a distinct color. This gives the user immediate visibility into which tabs the agent is using, and lets them collapse or review all of them in one place. Grouping is purely a presentation convenience for the user; the extension does not read, modify, or share tab-group contents or any other tab data with third parties.

---

## Short single-sentence variants (for compact fields)

- **offscreen**: Hosts a long-lived server-sent-events (SSE) command stream so automation commands from the AgentSphere backend are not lost when the Manifest V3 service worker is suspended; contains no UI and captures no content.
- **tabGroups**: Groups the tabs the AI agent operates on into one labeled "AgentSphere" tab group so the user can see and manage them at a glance; presentation only, no data read or shared.

---

## For the privacy policy (PRIVACY.md, Permissions section)

- `offscreen` — used to keep an always-on SSE command stream that outlives service-worker suspension; no UI, no capture.
- `tabGroups` — groups agent-controlled tabs into one labeled "AgentSphere" group for user visibility only; no tab-group data is read, stored, or shared.
