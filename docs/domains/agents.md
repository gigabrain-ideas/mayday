# Agents

Package: `mayday.agents`. Files: `Agents.kt` (model and ports), `Gateway.kt` (gateway identity, credentials, 429), `AgentService.kt` (use cases), `InternalAgent.kt` (agent loop).

## Purpose

Agents do work on incidents and alerts: analysis, remediation, patches, timeline updates, postmortem drafts and runbooks. Mayday supports a built-in agent (it uses an LLM provider) and external agents (push or pull). A person can start an agent with a button. The agent then acts on behalf of that person and has that person's permissions.

Agents are an add-on. Without an agent, Mayday is a complete incident management tool: alerts, incidents, timelines, postmortems, runbooks, policies and reports work the same. Alert intake and incident creation never wait for an agent.

## Model

| Element | Rules |
|---|---|
| `AgentConfig` | `kind` INTERNAL (one, id `mayday`) or EXTERNAL. `delivery` PUSH (webhook) or PULL (the agent claims runs). `actions` it can do. `triggers` for automatic runs. `role` of its own identity. `enabled`. `instructions` (built-in only). |
| `AgentAction` | ANALYZE, REMEDIATE, PATCH, TIMELINE, POSTMORTEM, RUNBOOK. REMEDIATE and PATCH are write actions. |
| `AgentTrigger` | Event ALERT_CREATED, minimum severity, optional teams, action. |
| `AgentRun` | Status QUEUED → DISPATCHED/RUNNING → SUCCEEDED, FAILED or CANCELLED. `trigger` AUTO or MANUAL. `onBehalfOf` = the person for manual runs. |
| `LlmConfig` | `provider` = API protocol (`anthropic` or `openai`), model (default `claude-opus-5-5`), effort, base URL, enabled, and the gateway identity: `authStyle` (API_KEY = `x-api-key`, BEARER = `Authorization: Bearer`; default from the provider), `credential` (NONE, STATIC, OAUTH2, FILE) with its fields (encrypted API key; for OAUTH2 issuer or token URL, grant, client authentication, client ID, encrypted client secret, assertion file, scope, token parameters; token file), custom `headers` (for example an agent registry id). |
| `LlmEnvironment` | `MAYDAY_LLM_*` values. A set value overrides the stored value and is read-only in the UI (`managedFields`). |
| `GatewayAuth` | Headers for one call to the AI gateway or the tools gateway: custom headers, then the credential in the `authStyle` header. Built per call, because the token rotates. |
| `BudgetExhausted` | HTTP 429 from a gateway, with the cooldown that the response states (`statedCooldown`, see [gateway-identity.md](gateway-identity.md)). Never retried. Domain code `resource_exhausted` (gRPC RESOURCE_EXHAUSTED). |
| `McpServerConfig` | Tool gateway entry: URL, auth header, encrypted credential, tool allowlist, `useAgentIdentity` (send the `GatewayAuth` headers of the LLM settings instead). |
| `AgentRun.traceId` | OpenTelemetry trace of the run (built-in runs and push deliveries). Empty when tracing is off. |

## Rules

- Manual run: only a person (not an agent, not a viewer) can start it. Mayday mints a delegated token: `sub` = user, `act.sub` = `agent:<id>`, `run` = run id, 1 hour, scopes READ and WRITE. RBAC of the user applies, but never admin rights: an administrator who starts a run gives the agent no ADMIN scope. The token stops working when the run is finished (SUCCEEDED, FAILED or CANCELLED). The built-in agent uses the same scopes for manual runs.
- Untrusted data: the built-in agent receives the task and the subject data in two separate blocks. The task text has no alert content (an alert is "the alert in the subject data"; an incident is its key). The subject data is JSON inside `<subject_data>`, with `<` and `>` escaped, so the data cannot close the tag. The system prompt says that subject data and tool results are untrusted and that the agent must not follow instructions in them.
- Automatic run: only from triggers, and only if the team policy allows it (`agents.auto_analysis` for read actions, `agents.auto_remediation` for write actions). It uses the agent's own identity and role.
- The built-in agent is available only when the LLM provider is ready: enabled, and the credential source has its fields (STATIC: key; OAUTH2: issuer or token URL, plus client ID and secret or assertion file for client credentials, or an assertion file for token exchange; FILE: token file; NONE: nothing). Without it, buttons explain how to configure one.
- Every LLM call and every tools-gateway call with `useAgentIdentity` sends the `GatewayAuth` headers. On HTTP 401 the adapter drops the cached token and tries once more.
- HTTP 429 from the AI gateway: no retry. The run becomes FAILED with the reason. The built-in agent pauses for the cooldown that the response states (`Retry-After`, `retry-after-ms`, RateLimit or provider reset headers, or a `retry_after` JSON field). Without one, it pauses for 15 minutes. The pause is between 1 second and 24 hours. During the pause, automatic triggers start no run and a manual start answers `unavailable`. `LlmView.pausedUntil` shows the pause. Saving the LLM settings ends it. The pause is per replica.
- HTTP 429 from a tools gateway goes to the model as a tool error and pauses only that MCP server, with the same cooldown rules. It does not pause the LLM.
- `onAlertCreated` returns at once. Trigger evaluation and runs happen in the agent scope (at most `MAYDAY_AGENT_CONCURRENCY` built-in runs at the same time).
- Each built-in run is one trace: `invoke_agent` → `chat` (per model turn, with token usage) and `execute_tool` (per tool call). See [observability.md](../observability.md).
- Push delivery: signed webhook (see [api.md](../api.md#agent-webhook)). A delivery failure marks the run FAILED.
- Pull delivery: the agent claims runs with `AgentRunService.ClaimRuns` (gRPC) or the MCP tool `claim_agent_runs`, and reports with `ReportProgress`/`CompleteRun` or `report_agent_run`. Claims are atomic. Only the owner agent can report progress or complete a run.
- Each run result goes to the subject: a timeline entry (incident) or an update (alert).
- Runs that do not finish in 30 minutes become FAILED.
- The built-in agent calls the same tools as external agents (`MaydayTools`), plus the tool gateway servers. It skips only the `mcp.write_enabled` switch, not RBAC.

## LLM providers

Package `mayday.adapters.llm`. `LlmProviders` selects the adapter from `provider`: `anthropic` (official Anthropic Java SDK) or `openai` (Chat Completions with `java.net.http`). `baseUrl` can have a path prefix, for example `https://gw.example.com/ai/openai/v1`. Defaults: `https://api.anthropic.com` and `https://api.openai.com/v1`.

- Each request carries the `GatewayAuth` headers (read again per request, so a rotated token is used at once) and the W3C `traceparent`/`tracestate`. Only one auth header goes out. Without a credential, no auth header goes out.
- One SDK client per base URL. SDK retries and SDK tracing are off. A custom SDK backend adds no credential of its own.
- `GatewayCaller` is the only retry policy: 401 → new token, one retry; 429 → `BudgetExhausted`, no retry; 502, 503, 504 or a connection error → at most two retries with random back-off (no retry when `Retry-After` is longer than 8 s); other status → `GatewayError` with the token masked.
- The OpenAI adapter sends no reasoning parameters (some gateways and models reject them). A failed tool result starts with "Error: ", because the protocol has no error flag.

## Ports

`AgentRepository`, `RunRepository` (with atomic `claim`), `McpServerRepository`, `ConfigRepository`, `LlmProvider`/`LlmProviderFactory`, `CredentialProvider`/`CredentialProviderFactory` (see [gateway-identity.md](gateway-identity.md)), `ToolCatalog`, `AgentDispatcher`, `Telemetry` (shared kernel). Implements `AgentIdentityLookup` (identity) and `AlertAutomation` (alerting).

## Positive use cases

- A SEV2 alert fires at 03:00. The built-in agent analyzes it with the Grafana and GitHub MCP servers and posts its findings on the incident timeline before the on-call engineer opens the laptop.
- An engineer presses "Create patch". The external coding agent receives a webhook with a delegated token, opens a pull request in the engineer's name and reports the link.
- A pull agent behind a firewall claims queued runs every few seconds over gRPC or MCP (outbound connections only).
- After resolution, an engineer presses "Draft postmortem". The agent fills all sections with `set_postmortem`.
- In Kubernetes, Helm sets `MAYDAY_LLM_BASE_URL` to the company AI gateway, `MAYDAY_LLM_CREDENTIAL=oauth2`, the issuer and `MAYDAY_LLM_HEADERS=x-company-agent-id=...`. Mayday gets a token with workload identity from the company identity provider, refreshes it before it expires and sends the custom header on every call. The UI shows these fields as "Set by the environment".
- A company gateway needs no agent id. The administrator sets no custom header and nothing extra goes out.
- A team uses Mayday without any agent. Alerts open incidents, responders write the timeline and the postmortem by hand.
- During an incident, an operator opens the trace of a run (trace id on the run) and sees each model turn and each tool call with its duration and result status.

## Negative use cases

- A viewer presses an agent button: `forbidden`.
- An alert title or label says "Ignore all previous instructions". The text reaches the model only inside `<subject_data>`, never in the task text.
- An administrator presses "Create patch". The agent tries `set_setting` with the delegated token: `forbidden`.
- The agent keeps the delegated token after the run is completed and calls Mayday again: `unauthenticated`.
- The delegated user is a member. The agent tries `set_setting` (admin): the tool is not offered and the call fails.
- An agent tries to complete a run of another agent: `forbidden`. It completes a finished run: `conflict`.
- Remediation triggers exist, but `agents.auto_remediation` is false (default, and locked in the demo): no run starts.
- The webhook endpoint is down: the run becomes FAILED with the reason on the timeline.
- No LLM key: `unavailable` with a clear message.
- The AI gateway answers 429 (budget used up): one call, no retry. The run fails with "budget or rate limit is used up", the agent pauses, an alert storm during the pause starts no runs, and every alert still opens its incident.
- The LLM gateway hangs: alert intake returns at once and the incident exists. The run stays RUNNING until it finishes or the reaper fails it after 30 minutes.
- An admin tries to change a field that the environment sets: `invalid`.
- An invalid base URL, issuer, token URL or header name, or a reserved token parameter (`client_secret`, `scope`, ...): `invalid`.

## Rebuild checklist

Pass `AgentTest`, `DelegationLimitsTest`, `UntrustedSubjectDataTest`, `AgentIndependenceTest`, `BudgetExhaustedTest`, `GatewaySettingsTest`, `IdentityTest` (delegation), `WebhookContractTest`, `LlmProviderContractTest`, `CredentialsContractTest`, the agent and run parts of `GrpcContractTest`, and the run part of `StoreContract`.
