# AgentSphere Chrome Extension — Privacy Policy

Effective date: 2026-08-06

## Privacy Policy

The **AgentSphere** Chrome extension ("the Extension") connects the AgentSphere AI
orchestration platform to your browser so an AI agent can perform operations on your
behalf (e.g. navigating pages, clicking, filling forms, extracting content).

### Data we collect

- **Authentication credentials**: a login token issued by your own AgentSphere
  backend is stored locally in `chrome.storage.local` and used to authenticate
  requests to that backend. It is never sent anywhere else.
- **Page content, URLs, and screenshots**: when you instruct the AI agent to operate
  on a page, the relevant page URL/content and screenshots are transmitted **only** to
  the AgentSphere backend that you configured for the extension.
- **Configuration**: backend / frontend URLs that you enter in the extension popup,
  stored locally in `chrome.storage.local`.

### How the data is used

Data is used **exclusively** to provide the extension's core function: bridging your
AgentSphere AI backend with the pages you choose to operate on. The extension only
sends data when you actively use it with an active session.

### Data sharing

- **No third-party sharing.** Data is sent only to the AgentSphere backend you
  configure.
- **No selling** of any data.
- **No advertising or tracking.** The extension does not display ads and does not
  perform analytics or behavioral tracking.
- The extension itself does **not** host or retain your data; storage and retention
  are governed by your own AgentSphere deployment.

### Permissions

- `tabs` / `scripting` / `webNavigation` / `debugger` / `activeTab` / `alarms` are used
  solely to execute browser operations that the AI agent requests at your command.
- The extension **does not request all-website access** (`<all_urls>`) at install or as an
  optional permission. At install it only requests the known AgentSphere sites
  (`as.buukle.top`, `bole.buukle.top`, `localhost`). For the page you are currently
  viewing, `activeTab` provides temporary access when you interact with the extension.
  Any **other** site is authorized by you individually from the popup
  ("Authorize access to current page"), each through the browser's permission dialog.

### Contact

For questions about this policy, contact the operator of your AgentSphere deployment
or open an issue at <https://github.com/nullpointexception-i/agent-sphere>.
