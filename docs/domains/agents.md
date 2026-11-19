# Agents

Package: `mayday.agents`. Files: `Agents.kt` (model and ports), `AgentService.kt` (use cases), `InternalAgent.kt` (agent loop).

## Purpose

Agents do work on incidents and alerts: analysis, remediation, patches, timeline updates, postmortem drafts and runbooks. Mayday supports a built-in agent (it uses an LLM provider) and external agents (push or pull). A person can start an agent with a button. The agent then acts on behalf of that person and has that person's permissions.

## Model

| Element | Rules |
|---|---|
| `AgentConfig` | `kind` INTERNAL (one, id `mayday`) or EXTERNAL. `delivery` PUSH (webhook) or PULL (the agent claims runs). `actions` it can do. `triggers` for automatic runs. `role` of its own identity. `enabled`. `instructions` (built-in only). |
| `AgentAction` | ANALYZE, REMEDIATE, PATCH, TIMELINE, POSTMORTEM, RUNBOOK. REMEDIATE and PATCH are write actions. |
| `AgentTrigger` | Event ALERT_CREATED, minimum severity, optional teams, action. |
| `AgentRun` | Status QUEUED → DISPATCHED/RUNNING → SUCCEEDED, FAILED or CANCELLED. `trigger` AUTO or MANUAL. `onBehalfOf` = the person for manual runs. |
| `LlmConfig` | Provider, model (default `claude-opus-5-5`), effort, encrypted API key or `ANTHROPIC_API_KEY`, optional base URL, enabled. |
| `McpServerConfig` | Tool gateway entry: URL, auth header, encrypted credential, tool allowlist. |

## Rules

- Manual run: only a person (not an agent, not a viewer) can start it. Mayday mints a delegated token: `sub` = user, `act.sub` = `agent:<id>`, `run` = run id, 1 hour. RBAC of the user applies.
- Automatic run: only from triggers, and only if the team policy allows it (`agents.auto_analysis` for read actions, `agents.auto_remediation` for write actions). It uses the agent's own identity and role.
- The built-in agent is available only when the LLM provider is ready. Without it, buttons explain how to configure one.
- Push delivery: signed webhook (see [api.md](../api.md#agent-webhook)). A delivery failure marks the run FAILED.
- Pull delivery: the agent calls `claim`. Claims are atomic. Only the owner agent can report progress or complete a run.
- Each run result goes to the subject: a timeline entry (incident) or an update (alert).
- Runs that do not finish in 30 minutes become FAILED.
- The built-in agent calls the same tools as external agents (`MaydayTools`), plus the tool gateway servers. It skips only the `mcp.write_enabled` switch, not RBAC.

## Ports

`AgentRepository`, `RunRepository` (with atomic `claim`), `McpServerRepository`, `ConfigRepository`, `LlmProvider`/`LlmProviderFactory`, `ToolCatalog`, `AgentDispatcher`. Implements `AgentIdentityLookup` (identity) and `AlertAutomation` (alerting).

## Positive use cases

- A SEV2 alert fires at 03:00. The built-in agent analyzes it with the Grafana and GitHub MCP servers and posts its findings on the incident timeline before the on-call engineer opens the laptop.
- An engineer presses "Create patch". The external coding agent receives a webhook with a delegated token, opens a pull request in the engineer's name and reports the link.
- A pull agent behind a firewall claims queued runs every few seconds over HTTPS.
- After resolution, an engineer presses "Draft postmortem". The agent fills all sections with `set_postmortem`.

## Negative use cases

- A viewer presses an agent button: `forbidden`.
- The delegated user is a member. The agent tries `set_setting` (admin): the tool is not offered and the call fails.
- An agent tries to complete a run of another agent: `forbidden`. It completes a finished run: `conflict`.
- Remediation triggers exist, but `agents.auto_remediation` is false (default, and locked in the demo): no run starts.
- The webhook endpoint is down: the run becomes FAILED with the reason on the timeline.
- No LLM key: `unavailable` with a clear message.

## Rebuild checklist

Pass `AgentTest`, `IdentityTest` (delegation), `WebhookContractTest` and the run part of `StoreContract`.
