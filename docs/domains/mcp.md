# Tools and MCP

Packages: `mayday.tools` (`MaydayTools`), `mayday.adapters.mcp` (`McpServer`, `McpGateway`).

## Purpose

Mayday exposes its use cases as tools. External agents use them through the MCP server. The built-in agent uses the same tools directly. The tool gateway gives the built-in agent tools from other MCP servers (observability, Git, tickets).

## MCP server

- Transport: Streamable HTTP, `POST /mcp`, JSON-RPC 2.0, single messages and batches. `GET /mcp` answers 405 (no server stream). Protocol versions: 2025-11-25, 2025-06-18, 2025-03-26.
- Methods: `initialize`, `ping`, `tools/list`, `tools/call`, `resources/list` and `prompts/list` (empty).
- Authentication: `Authorization: Bearer` with any token type from [identity.md](identity.md).

## Tools

| Tool | Kind | Notes |
|---|---|---|
| `list_incidents`, `get_incident`, `list_alerts`, `get_alert`, `list_teams`, `get_settings`, `get_on_call` | read | Always available with READ scope |
| `add_schedule_override` | write | Needs `mcp.write_enabled` for the team. Team members add only themselves, team owners add any team member ([oncall.md](oncall.md)) |
| `create_incident`, `update_incident`, `add_timeline_entry`, `set_postmortem`, `save_runbook`, `save_resolution` | write | Needs WRITE, role MEMBER, and `mcp.write_enabled` for the incident teams. The built-in agent on an automatic run does not get `create_incident` ([agents.md](agents.md)) |
| `send_alert`, `acknowledge_alert`, `resolve_alert`, `add_alert_update` | write | Same as above, for the alert team |
| `claim_agent_runs`, `report_agent_run` | agent | Only for agent identities (pull agents) |
| `set_setting`, `list_users`, `set_user_role` | admin | Only when the principal is an administrator with ADMIN scope |

`tools/list` shows only the tools that the principal can call. A tool error returns `isError: true` with the error code and message, so agents can react.

## Tool gateway

The tool gateway gives the built-in agent the tools of remote MCP servers, for example a company tools gateway. Only the built-in agent sees these tools. Mayday's MCP server does not expose them again.

Configuration per server (`McpServerConfig`, administrators only):

| Field | Meaning |
|---|---|
| `name`, `url` | Display name and Streamable HTTP endpoint (http or https). The name slug is the tool prefix. |
| `headerName`, `credential` | Static mode: header and sealed secret. Default header `Authorization`. |
| `toolAllowlist` | Remote tool names to expose. Empty: all tools. |
| `useAgentIdentity` | True: send the identity headers of the LLM settings (token and custom headers) instead of the static credential. |
| `enabled` | False: the server is ignored. |

Behaviour (`McpGateway`, `McpClient`):

- Identity mode: before each HTTP request (`initialize`, `notifications/initialized`, `tools/list`, `tools/call`), the client gets new headers from `GatewayAuth.headers()` of `identity()`. These are the same headers as for the AI gateway: the custom headers and the token (`Authorization: Bearer` or `x-api-key`, per `AuthStyle`). The tools gateway is expected in the customer's cloud and accepts the same token as the AI gateway; Mayday has no per-server token settings. The client does not keep header values, so token rotation applies at once.
- Static mode: the client sends the static credential in `headerName`. No identity is used.
- Each request also has the W3C trace context headers (`traceparent`, `tracestate`) of the current span.
- Tool names: `<server slug with _>__<remote name>`, maximum 64 characters, for example `grafana_prod__query`. Descriptions start with `[<server name>]`.
- Tool list cache: two minutes per server. The cache key is the server configuration and the identity (custom headers, auth style, credential provider, `useAgentIdentity`). A different key starts a new client and a new MCP session. One refresh at a time per server.
- Sessions: the client keeps the `Mcp-Session-Id`. A mutex makes sure that only one `initialize` runs at a time. HTTP 404 on a request with a session id: the client starts a new session and sends the request once more. Blocking HTTP calls run on `Dispatchers.IO`.
- HTTP 401: identity mode calls `GatewayAuth.invalidate()` and sends the request once more. A second 401 is a tool error (`unauthorized`). Static mode does not retry.
- Grants: the tools gateway grants tools per identity. Mayday does not configure grants. A refused call answers a normal `tools/call` result with `isError: true` and a message. Mayday gives that message to the model unchanged, does not retry and does not hide the tool. The run continues.
- Mayday never reads an error message to decide what to do. Only the HTTP status, the JSON-RPC structure and `isError` decide. HTTP 403 and JSON-RPC errors are tool errors like any other transport error.
- HTTP 429: Mayday never retries. The server is paused for the cooldown that the response states (`statedCooldown`: `Retry-After`, `retry-after-ms`, RateLimit or provider reset headers, or a `retry_after` JSON field). Without one: 15 minutes. The pause is between 1 second and 24 hours. The call returns a tool error at once. During the pause, calls to that server fail without network I/O, and `tools()` returns the cached tools without a refresh.
- Failures of one server (network, HTTP 5xx, bad JSON) do not change the tools of other servers. The failed server gives no tools until the next successful refresh. Mayday logs the failure once until it changes or the server recovers.
- Spans (kind CLIENT): `mcp tools/list <server>` and `mcp tools/call <tool>`. Attributes: `mcp.method.name`, `mcp.session.id` (when known), `mayday.mcp.server`, `gen_ai.tool.name` (calls), `server.address`, `http.response.status_code`. On failure: `error.type` = `budget_exhausted`, `unauthorized` or `transport` (the span is failed), or `tool_error` for an `isError: true` result.

### Tool gateway positive use cases

- The company tools gateway accepts the workload identity token and the custom headers. The agent lists `observability__query_logs` and calls it. The trace shows the agent run, the `execute_tool` span and the `mcp tools/call query_logs` span, and the tools gateway joins the same trace.
- The token rotates every 5 minutes. Each request has the current token. No restart is necessary.
- An administrator changes a custom header (for example the agent registry id). The next `tools()` call lists the tools again with the new identity.

### Tool gateway negative use cases

- The identity has no grant for `restart_pod`. The gateway answers `isError: true` with its reason. The agent receives that reason as the tool result and reports it. The next call goes to the gateway again, because the gateway decides each call.
- The tools gateway answers 429 with `Retry-After: 300` (or `RateLimit: "budget";r=0;t=300`). One call goes out. All calls to that server in the next 5 minutes fail at once with a "do not retry" message.
- The tools gateway answers 429 without a cooldown. Calls to that server fail at once for 15 minutes.
- One MCP server is down. The tools of the other servers stay available.
- The token is expired (401). Mayday gets a new token and sends the request once more. If the new token also fails, the call is a tool error.

## Positive use cases

- Claude Code connects to Mayday's MCP server with a personal token and reads incident INC-42.
- An external agent with a delegated token adds a timeline entry. The entry shows the agent's name and "for <person>".

## Negative use cases

- `mcp.write_enabled` is false for the team: write tools return `forbidden` with an explanation.
- A member calls `set_setting`: the tool is not listed, and a direct call returns JSON-RPC error -32602.
- An unknown method: error -32601.

## Audit

`AuditedCatalog` records every tool call (MCP and built-in agent) as `tool:<name>` with the actor, the person an agent acts for, the run, the outcome and the identifiers in the arguments, never free text. The MCP server records a call to a tool the caller cannot see as `denied` with reason `not_allowed`. See [audit.md](audit.md).

## Rebuild checklist

Pass the MCP part of `HttpEdgesContractTest`, the pull-agent part of `GrpcContractTest`, the tool parts of `AgentTest` and `McpGatewayContractTest` (tool gateway: identity headers with token rotation, static mode, allowlist and prefixes, 401 retry, grants, 429 pause, failure isolation, session expiry, cache key).
