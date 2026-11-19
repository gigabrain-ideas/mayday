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
| `list_incidents`, `get_incident`, `list_alerts`, `get_alert`, `list_teams`, `get_settings` | read | Always available with READ scope |
| `create_incident`, `update_incident`, `add_timeline_entry`, `set_postmortem`, `save_runbook`, `save_resolution` | write | Needs WRITE, role MEMBER, and `mcp.write_enabled` for the incident teams |
| `send_alert`, `acknowledge_alert`, `resolve_alert`, `add_alert_update` | write | Same as above, for the alert team |
| `claim_agent_runs`, `report_agent_run` | agent | Only for agent identities (pull agents) |
| `set_setting`, `list_users`, `set_user_role` | admin | Only when the principal is an administrator with ADMIN scope |

`tools/list` shows only the tools that the principal can call. A tool error returns `isError: true` with the error code and message, so agents can react.

## Tool gateway

- Administrators add MCP servers (URL, header, credential, allowlist).
- `McpGateway` connects with the Streamable HTTP client, lists tools (cached for two minutes) and prefixes names with the server slug (`grafana__query`).
- Only the built-in agent sees gateway tools. They are not re-exposed through Mayday's MCP server.

## Positive use cases

- Claude Code connects to Mayday's MCP server with a personal token and reads incident INC-42.
- An external agent with a delegated token adds a timeline entry. The entry shows the agent's name and "for <person>".

## Negative use cases

- `mcp.write_enabled` is false for the team: write tools return `forbidden` with an explanation.
- A member calls `set_setting`: the tool is not listed, and a direct call returns JSON-RPC error -32602.
- An unknown method: error -32601.

## Rebuild checklist

Pass the MCP part of `ApiContractTest` and the tool parts of `AgentTest`.
