# API and integration contracts

The API contract is protobuf: `proto/mayday/v1/*.proto`, package `mayday.v1`. Gradle generates the server stubs (grpc-java and grpc-kotlin). The web build generates the browser client from the same files. The contract tests check this document (see [testing.md](testing.md)).

| Port | Protocol | Use |
|---|---|---|
| 9090 (`MAYDAY_GRPC_PORT`) | gRPC over HTTP/2, plaintext h2c. TLS with `spring.grpc.server.ssl.*` or at the ingress. | Scripts, services, pull agents, `grpcurl` |
| 8080 (`PORT`) | gRPC-Web at `/mayday.v1.<Service>/<Method>`, and the HTTP endpoints below | Web interface, MCP clients, monitoring tools, webhooks |

gRPC reflection and the standard gRPC health service (`grpc.health.v1.Health`) are on.

## Services

| Proto file | Service | Methods |
|---|---|---|
| `identity.proto` | `AuthService` | `GetAuthConfig`, `GetMe`, `WhoAmI` |
| `identity.proto` | `UserService` | `ListUsers`, `CreateUser`, `UpdateUser`, `ListTokens`, `IssueToken` (the secret is in this response only), `RevokeToken`, `SetAgentAdmin` (the caller's own `allow_agent_admin`; agents get `PERMISSION_DENIED`) |
| `directory.proto` | `DirectoryService` | `ListProjects`, `CreateProject`, `UpdateProject`, `DeleteProject`, `ListTeams`, `CreateTeam`, `UpdateTeam` (`replace_groups` sets `member_groups` and `owner_groups`, administrators), `DeleteTeam`, `ListTeamMembers`, `SetTeamMember` (team owners; role unspecified removes; `conflict` when identity provider groups manage the team) |
| `oncall.proto` | `OnCallService` | `ListSchedules`, `GetSchedule`, `CreateSchedule`, `UpdateSchedule` (with `version`), `DeleteSchedule`, `ListOverrides`, `CreateOverride`, `DeleteOverride`, `ListShifts` (rotation with overrides, window at most 93 days), `GetOnCall`. See [oncall.md](domains/oncall.md) |
| `escalation.proto` | `EscalationService` | `ListPolicies`, `GetPolicy`, `CreatePolicy`, `UpdatePolicy` (with `version`), `DeletePolicy` (team owners), `GetEscalation` (by alert id), `EscalateNow` (WRITE). See [escalation.md](domains/escalation.md) |
| `runbooks.proto` | `RunbookService` | `ListRunbooks`, `GetRunbook`, `SaveRunbook` (create with empty `id`; update with `version`), `ApproveRunbook` (team owners as a person, with the `version` they read), `RevokeRunbook` (team owners). See [runbooks.md](domains/runbooks.md) |
| `incidents.proto` | `IncidentService` | `ListIncidents` (keyset: `cursor`, `next_cursor`), `DeclareIncident`, `GetIncident` (`ref` = key, number or id), `UpdateIncident` (with `version`), `ListTimeline`, `AddTimelineEntry`, `SavePostmortem`, `SaveResolution`, `SaveRunbook` |
| `alerting.proto` | `AlertService` | `ListAlerts`, `CountAlerts` (exact number for the same filter; the sidebar badge), `IngestAlert`, `GetAlert`, `AcknowledgeAlert`, `ResolveAlert`, `AddAlertUpdate`, `LinkAlert`. `Alert.resolved_by` and `Alert.resolved_under_runbook` show who resolved it and under which approved runbook; an automated agent resolve with no runbook keeps paging |
| `reporting.proto` | `ReportService` | `GetReport` |
| `policy.proto` | `PolicyService` | `GetSettings`, `SetSetting`, `ClearSetting`, `ListChangeRequests`, `RequestChange`, `DecideChange` |
| `agents.proto` | `AgentService` | `ListAgents`, `ListEligibleAgents`, `GetAgent`, `CreateAgent`, `UpdateAgent`, `DeleteAgent`, `RotateAgentCredentials` (secrets in the response only), `GetLlm`, `UpdateLlm`, `ListMcpServers`, `SaveMcpServer`, `DeleteMcpServer` |
| `agents.proto` | `AgentRunService` | `ListRuns`, `GetRun`, `StartRun`, `CancelRun`, `ClaimRuns`, `ReportProgress`, `CompleteRun`, `ApproveRun`, `RejectRun` |
| `audit.proto` | `AuditService` | `GetAuditStatus` (administrators): instance, reads switch, accepted and lost records, per sink delivered, pending, failures, lost and last error. See [audit.md](domains/audit.md) |
| `events.proto` | `EventService` | `Subscribe` (server stream): `incident.*`, `alert.*`, `agent_run.updated`, `setting.changed`, `oncall.changed`, `escalation.changed`, `runbook.*`, `alert.paged`, `alert.page_failed`, `alert.escalation_exhausted`, `events.resync` (live updates may have missed events: reload), and `ping` every 25 seconds |

Conventions (`common.proto`):

- A field has the JSON name of the domain property (`team_ids` = `teamIds`).
- An enum value has the enum type as prefix (`SEVERITY_SEV1` = `SEV1`). Value 0 (`*_UNSPECIFIED`) means "not set".
- Times are `google.protobuf.Timestamp`. Free-form objects (labels, run subjects) are `google.protobuf.Struct` or `map<string, string>`.
- In update messages, an unset field keeps its value.

## Authentication

gRPC and gRPC-Web read the credentials from the metadata (HTTP headers):

| Method | Metadata | Use |
|---|---|---|
| Session | Cookie `mayday_session` | Browser (gRPC-Web). Native gRPC accepts it too. |
| API token | `authorization: Bearer mdy_...` | Scripts, alert sources, MCP clients |
| Agent token | `authorization: Bearer mdy_...` (owner = agent) | External agents |
| Delegated token | `authorization: Bearer <JWT typ=obo>` | Agent on behalf of a person. From token exchange: the user's scopes without ADMIN, unless `agents.exchange_admin` and the user's `allowAgentAdmin` are on. |
| SSO access token | `authorization: Bearer <IdP JWT>` | Agent with the person's own SSO token. Its groups claim updates team membership like a sign-in. |
| SCIM token | `Authorization: Bearer mdy_...` with the SCIM scope (administrators only) | `/scim/v2` only. See [provisioning.md](domains/provisioning.md) |

A call without valid credentials gets `UNAUTHENTICATED`. `AuthService.GetAuthConfig` needs no credentials.

## Errors

gRPC calls return a status code. The trailer `mayday-error-code` has the domain code. The status message is text for people.

| Domain code | gRPC status | HTTP (edges) | Meaning |
|---|---|---|---|
| `unauthenticated` | `UNAUTHENTICATED` | 401 | No or invalid credentials |
| `forbidden` | `PERMISSION_DENIED` | 403 | RBAC or scope refuses the action |
| `locked` | `FAILED_PRECONDITION` | 403 | A policy is enforced at a higher level. Send a change request. |
| `invalid` | `INVALID_ARGUMENT` | 400 | Bad input |
| `not_found` | `NOT_FOUND` | 404 | Unknown resource |
| `conflict` | `ABORTED` | 409 | Stale `version` or duplicate |
| `unavailable` | `UNAVAILABLE` | 503 | A dependency is not configured (for example no LLM provider), the agent is paused after HTTP 429, or too many live streams |
| `resource_exhausted` | `RESOURCE_EXHAUSTED` | 429 | The AI gateway refused a call because the agent budget or rate limit is used up. Do not retry before the pause ends. |
| (unexpected error) | `INTERNAL` | 500 | The details are in the server log only |

HTTP edges return the body `{"error": "<code>", "message": "<text for people>"}`.

## LLM provider and tool gateway

`AgentService.GetLlm` returns `LlmView`. `AgentService.UpdateLlm` takes `LlmInput` and needs an administrator.

| Field | Meaning |
|---|---|
| `provider` | `anthropic` or `openai` (OpenAI-compatible API at `base_url`) |
| `model`, `base_url`, `effort`, `enabled` | Model, provider or AI gateway URL (`http` or `https`), effort (`low` to `max`), on or off |
| `auth_style` | `AUTH_STYLE_API_KEY` (header `x-api-key`) or `AUTH_STYLE_BEARER` (`Authorization: Bearer`). The default follows the provider. |
| `credential` | `CREDENTIAL_MODE_NONE`, `_STATIC` (`api_key`), `_OAUTH2`, `_FILE` (`token_file`) |
| `issuer`, `token_url` | OAuth 2.0: issuer for metadata discovery, or the token endpoint (wins) |
| `grant` | `GRANT_TYPE_CLIENT_CREDENTIALS` (default) or `GRANT_TYPE_TOKEN_EXCHANGE` (RFC 8693) |
| `client_id`, `client_secret`, `client_auth` | OAuth 2.0 client. `CLIENT_AUTH_BASIC` (default) or `CLIENT_AUTH_POST` for the secret |
| `assertion_file` | Workload identity token file: client assertion, or subject token for token exchange |
| `scope`, `token_params` | Scope and extra token request fields (for example `audience`, `resource`) |
| `headers` | Custom headers for each gateway call, for example an agent registry id |
| `has_api_key`, `has_client_secret` | `true` when a secret is set. Mayday never returns secrets. |
| `ready` | The internal agent can call the provider |
| `managed_fields` | Field names (lowerCamelCase) that the environment sets. `UpdateLlm` refuses them with `INVALID_ARGUMENT`. |
| `paused_until`, `pause_reason` | Set after the gateway answered HTTP 429. The agent makes no LLM call before this time. A successful `UpdateLlm` ends the pause. |

`UpdateLlm` rules:

- An unset field keeps its value. An empty `api_key` or `client_secret` removes the secret.
- `headers` change only when `replace_headers` is `true`, and `token_params` only when `replace_token_params` is `true`. Then the map replaces all entries. An empty map removes them.
- An invalid URL, header name, provider or effort, or a reserved token parameter (`grant_type`, `client_id`, `client_secret`, `client_assertion`, `client_assertion_type`, `subject_token`, `scope`) gives `INVALID_ARGUMENT`. Mayday then changes nothing.
- Fields 15 and 16 of `LlmInput` and 16 and 17 of `LlmView` (agent ID header and value) are removed and reserved. Use `headers`.

`McpServerView` and `SaveMcpServerRequest` have `use_agent_identity`: Mayday sends the identity headers of the LLM settings (token and custom headers) to the MCP server instead of the static credential. Field 9 (`scope`) is removed and reserved.

`AgentRun.trace_id` is the OpenTelemetry trace of the run. It is empty when tracing is off. `AgentRun.runbook_id` is the approved runbook that an automatic run follows. `Me.trace_url_template` is the value of `MAYDAY_TRACE_URL_TEMPLATE`. Replace `{traceId}` to get a link to the trace.

## gRPC-Web

- Path: `POST /mayday.v1.<Service>/<Method>` on port 8080. Content type `application/grpc-web` or `application/grpc-web+proto` (binary). Other content types (for example `application/grpc-web-text`) are not supported.
- Unary and server-streaming calls. The response is a stream of length-prefixed frames. The last frame has the trailers (`grpc-status`, `grpc-message`, `mayday-error-code`).
- The bridge forwards each call through an in-process channel to the gRPC server. Thus browsers and native clients use the same services, interceptors and authentication.
- No proxy (Envoy) is necessary. A reverse proxy must not buffer responses, because `EventService.Subscribe` is a long stream.

## HTTP endpoints

| Method and path | Purpose |
|---|---|
| `GET /auth/login?redirect=`, `GET /auth/callback` | OIDC flow with PKCE. Sets the session cookie. The groups claim updates team membership. |
| `/scim/v2/Users`, `/scim/v2/Groups`, `/scim/v2/ServiceProviderConfig`, `/scim/v2/ResourceTypes`, `/scim/v2/Schemas` | SCIM 2.0 (RFC 7644) for identity providers. Errors use the SCIM error schema. See [provisioning.md](domains/provisioning.md) |
| `POST /auth/dev-login` `{email, name?}`, `GET /auth/dev-login?email=&redirect=` | Development sign-in (`MAYDAY_DEV_LOGIN=true`) |
| `POST /auth/logout` | Clears the session. Response: `{"logoutUrl": "..."}` |
| `POST /oauth/token` | RFC 8693 token exchange (below) |
| `POST /mcp` | MCP server, see [domains/mcp.md](domains/mcp.md). `GET /mcp` answers 405. |
| `POST /api/v1/alerts` | Alert intake: `{title, team, severity?, source?, fingerprint?, status?, description?, labels?, externalUrl?}`. 201 when new, 200 when deduplicated. |
| `POST /api/v1/integrations/alertmanager?team=` | Prometheus Alertmanager webhook. Response: `{"created": n, "updated": n, "rejected": n, "errors": [{"index", "error", "message"}]}`. A bad alert is skipped and listed in `errors`; when every alert is bad, the error of the first one. |
| `GET /healthz`, `/livez`, `/readyz` | Health, liveness and readiness probes |
| `GET /metrics` | Prometheus text format (Micrometer) |

Cookie sessions on mutating HTTP endpoints need the header `X-Requested-With: mayday`. Bearer tokens do not.

## Token exchange

```http
POST /oauth/token
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:token-exchange&subject_token=<user token>&actor_token=<agent token>
```

Response: `{"access_token": "...", "issued_token_type": "urn:ietf:params:oauth:token-type:access_token", "token_type": "Bearer", "expires_in": 3600}`.

## Agent webhook

Mayday sends `POST <endpointUrl>` for each run of a push agent.

Headers:

```
Content-Type: application/json
X-Mayday-Event: agent_run.requested
X-Mayday-Delivery: <run id>
X-Mayday-Timestamp: <unix seconds>
X-Mayday-Signature: sha256=<hex HMAC-SHA256(secret, timestamp + "." + body)>
```

Body:

```json
{
  "event": "agent_run.requested",
  "run": { "id": "...", "action": "PATCH", "subjectKind": "INCIDENT", "subjectLabel": "INC-42", "prompt": "...", "onBehalfOf": "<user id>", "requiresApproval": true },
  "maydayUrl": "https://mayday.example.com",
  "mcpUrl": "https://mayday.example.com/mcp",
  "delegatedToken": "<JWT or null for automatic runs>",
  "subject": { "kind": "incident", "incident": { }, "timeline": [ ] }
}
```

The receiver must:

1. Check the signature with a constant-time compare, and refuse timestamps older than 5 minutes.
2. Answer 2xx quickly. Mayday makes up to three attempts on network errors, 429 and 5xx.
3. Use `delegatedToken` (when present) for MCP and gRPC calls. Otherwise use its own agent token. The delegated token has the scopes `read` and `write` and stops working when the run is finished.
4. Report with the MCP tool `report_agent_run`, or with `AgentRunService.ReportProgress` and `AgentRunService.CompleteRun` `{id, success, output, error}`.
5. When `run.requiresApproval` is true (policy `agents.patch_requires_approval`), do not merge or deploy the patch. A successful report moves the run to `AWAITING_APPROVAL` and ends the delegated token. A person approves (`AgentRunService.ApproveRun`) or rejects (`AgentRunService.RejectRun {id, reason}`) it in Mayday. Apply the patch only after the run is `SUCCEEDED` with `approvedBy` set.
6. When `run.runbookId` is set, follow `subject.runbook`: do only its steps and call only its `allowedTools`. Resolve the alert that started the run (`run.alertId` in the webhook) only when the runbook allows `resolve_alert`; only then does paging stop with no person. When a step fails or the runbook does not cover the case, complete the run with `success: false` and the reason: Mayday raises the alert to a person.

## Page webhook

With `MAYDAY_PAGING_WEBHOOK_URL`, Mayday sends `POST <url>` for each escalation page that reaches at least one person. Headers: `X-Mayday-Event: alert.paged`, `X-Mayday-Timestamp`, `X-Mayday-Signature` (with `MAYDAY_PAGING_WEBHOOK_SECRET`, computed as for the agent webhook) and the headers of `MAYDAY_PAGING_WEBHOOK_HEADERS`. Any answer other than 2xx, or no answer, marks the page `failed` and publishes `alert.page_failed`. Mayday does not send it again.

```json
{
  "alertId": "...", "alertTitle": "CheckoutHighErrorRate", "alertUrl": "https://mayday.example.com/alerts/...", "severity": "SEV2",
  "teamId": "...", "policyId": "...", "policyName": "Default", "level": 2, "cycle": 0, "manual": false,
  "recipients": [{ "id": "...", "name": "Lee Backend", "email": "lee@example.com" }]
}
```

Mayday sends each page once and does not retry it. A failed delivery does not stop the escalation: the next level is paged on time. The receiver answers 2xx and delivers the message (chat, SMS, push, phone call).

## Pull agents

The agent claims queued runs with its agent token: `AgentRunService.ClaimRuns {limit}` (gRPC) or the MCP tool `claim_agent_runs`. The response is a list of `{run, delegated_token, subject}`. Each run is handed out once. The agent reports with `ReportProgress` and `CompleteRun`, or with `report_agent_run`.

## grpcurl

```bash
grpcurl -plaintext localhost:9090 list
```

```bash
grpcurl -plaintext localhost:9090 grpc.health.v1.Health/Check
```

```bash
grpcurl -plaintext -H "authorization: Bearer $MAYDAY_TOKEN" -d '{"ref": "INC-42"}' localhost:9090 mayday.v1.IncidentService/GetIncident
```

```bash
grpcurl -plaintext -H "authorization: Bearer $AGENT_TOKEN" -d '{"limit": 5}' localhost:9090 mayday.v1.AgentRunService/ClaimRuns
```

```bash
grpcurl -plaintext -H "authorization: Bearer $MAYDAY_TOKEN" localhost:9090 mayday.v1.EventService/Subscribe
```

`grpcurl` uses the standard protobuf JSON mapping: field names in lowerCamelCase, full enum names (`"SEVERITY_SEV1"`).
