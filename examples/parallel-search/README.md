# Parallel Search MCP

[中文](README-cn.md)

This optional example connects AgentSphere's existing HTTP MCP capability to
[Parallel Search MCP](https://docs.parallel.ai/integrations/mcp/search-mcp).
The `/mcp` endpoint offers anonymous `web_search` and `web_fetch` for exploration
and light use. No Parallel API key is needed; anonymous usage has lower rate
limits. AgentSphere authentication is still required. Model inference is separate.
Existing capabilities and model/provider defaults are unchanged.

Start AgentSphere using the repository's [quick start](../../QUICK_START.md).
You need an AgentSphere account with MCP create/test permissions, curl, and jq.
Run these commands from the repository root against your own running backend:

```bash
export AGENT_SPHERE_URL=http://localhost:8080
export AGENT_SPHERE_TOKEN='<your AgentSphere login token>'

# Create a new capability. Save the returned id; rerunning creates another entry.
MCP_ID=$(curl --fail-with-body -sS "$AGENT_SPHERE_URL/api/v1/capability/mcp" \
  -H "Authorization: Bearer $AGENT_SPHERE_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @examples/parallel-search/mcp.json | jq -er '.id')

# Discover the current tools and their input schemas.
curl --fail-with-body -sS "$AGENT_SPHERE_URL/api/v1/capability/mcp/$MCP_ID/tools" \
  -H "Authorization: Bearer $AGENT_SPHERE_TOKEN" | jq

# Reuse one random session id for related search/fetch calls.
PARALLEL_SESSION_ID=$(cat /proc/sys/kernel/random/uuid) # Linux; on macOS use uuidgen
jq -n --arg sid "$PARALLEL_SESSION_ID" \
  '{objective:"Find the official MCP transport documentation", search_queries:["MCP Streamable HTTP transport documentation"], session_id:$sid}' \
  | curl --fail-with-body -sS "$AGENT_SPHERE_URL/api/v1/capability/mcp/$MCP_ID/tools/web_search/call" \
      -H "Authorization: Bearer $AGENT_SPHERE_TOKEN" \
      -H 'Content-Type: application/json' --data-binary @- | jq

# Fetch a specific page when search excerpts are insufficient.
jq -n --arg sid "$PARALLEL_SESSION_ID" \
  '{urls:["https://modelcontextprotocol.io/specification/2025-11-25/basic/transports"], objective:"Explain Streamable HTTP transport", session_id:$sid}' \
  | curl --fail-with-body -sS "$AGENT_SPHERE_URL/api/v1/capability/mcp/$MCP_ID/tools/web_fetch/call" \
      -H "Authorization: Bearer $AGENT_SPHERE_TOKEN" \
      -H 'Content-Type: application/json' --data-binary @- | jq
```

Results use MCP's `content` array; inspect `isError` even when the AgentSphere
HTTP response is successful. Search results include source URLs and excerpts;
fetch returns extracted page content. Respect rate-limit errors and retry later.
The commands exercise the direct capability API and do not require an LLM.

In **Capabilities → MCP**, the equivalent fields are the values in `mcp.json`:
server type `http` selects Streamable HTTP (not legacy SSE). `authConfig` is a
JSON **string** in the API payload; in the UI's header configuration field, enter
`{"User-Agent":"agent-sphere/1.0.0"}`. This identifies AgentSphere's outgoing
MCP requests without adding an Authorization header to Parallel. Your
AgentSphere login token authenticates requests to your backend only.

## Use from an agent session

Creating an MCP entry alone does not add it to an agent. Choose an existing
instance with a configured model route that supports tool calls, then bind the
capability to that instance. Your account needs `instance:capability:bind` in
addition to the MCP permissions. Set `INSTANCE_ID` to your own instance's id:

```bash
export INSTANCE_ID='<your existing instance id>'
jq -n --argjson iid "$INSTANCE_ID" --argjson mid "$MCP_ID" \
  '{instanceId:$iid, capabilityType:"mcp", capabilityId:$mid, status:"ENABLED"}' \
  | curl --fail-with-body -sS "$AGENT_SPHERE_URL/api/v1/instance/instance-capabilities" \
      -H "Authorization: Bearer $AGENT_SPHERE_TOKEN" \
      -H 'Content-Type: application/json' --data-binary @- | jq
```

Alternatively, add Parallel under the instance's capability settings. Start a
new chat for that instance and ask it to search for the official MCP Streamable
HTTP documentation, fetch a relevant source returned by search, and explain the
transport with that source URL. The runtime discovers the MCP schemas and gives
them to the selected model. Internally, model tool names are `mcp_<id>_<index>`;
`ContextPreparer` maps them to `web_search` and `web_fetch`, and `ToolExecutor`
calls the saved server configuration. `SessionRunner` passes the MCP result into
the next model request. Tool selection and answer quality depend on your model;
inspect the chat's tool results to confirm both calls. A disabled binding does
not expose these MCP tools. Existing model selections and permissions still apply.

## Regression and optional live validation

With Java 21 and Maven, run from `agent-sphere/`:

```bash
mvn install -DskipTests
mvn test
mvn -pl agent-sphere-bootstrap test -Dtest=ParallelSearchAgentLoopTest
# Explicit network opt-in: real anonymous Parallel search and fetch.
mvn -pl agent-sphere-bootstrap test -Dtest=ParallelSearchAgentLoopTest -Dparallel.live=true
```

`ParallelSearchAgentLoopTest` loads `mcp.json` through the real MCP service,
creates an instance binding through the real binding service, and uses
`ContextPreparer`, `SessionRunner`, `KernelLlmService` and `ToolExecutor` without
replacing their dispatch logic. Mapper persistence, Redis state, the model
provider and ancillary session services (including input, history and run storage)
are fixtures. The controlled model chooses a fetch URL from the preceding
search result and builds its final reply from the fetch result; assertions check
tool-call IDs and the assistant/tool message sequence at the next model boundary.
The local HTTP fixture observes the endpoint, User-Agent and absence of credentials
on discovery/search/fetch, and a disabled-binding test checks no MCP requests occur.
The opt-in test uses the shipped anonymous endpoint and the same agent loop.
It validates runtime wiring, not an external model's reasoning or answer quality,
and does not exercise HTTP authentication or durable database/Redis persistence.
No Parallel key is loaded from the environment or saved configuration. Live tests
can fail on network errors or anonymous rate limits; these failures are not a pass.

The backend's existing source-scanning tests exclude paths containing `target`;
use a checkout whose absolute directory path does not contain that word for the
full suite. The repository has no PR test CI, so run these checks locally.
