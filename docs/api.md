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
| `identity.proto` | `UserService` | `ListUsers`, `CreateUser`, `UpdateUser`, `ListTokens`, `IssueToken` (the secret is in this response only), `RevokeToken` |
| `directory.proto` | `DirectoryService` | `ListProjects`, `CreateProject`, `UpdateProject`, `DeleteProject`, `ListTeams`, `CreateTeam`, `UpdateTeam`, `DeleteTeam` |
| `incidents.proto` | `IncidentService` | `ListIncidents` (keyset: `cursor`, `next_cursor`), `DeclareIncident`, `GetIncident` (`ref` = key, number or id), `UpdateIncident` (with `version`), `ListTimeline`, `AddTimelineEntry`, `SavePostmortem`, `SaveResolution`, `SaveRunbook` |
| `alerting.proto` | `AlertService` | `ListAlerts`, `IngestAlert`, `GetAlert`, `AcknowledgeAlert`, `ResolveAlert`, `AddAlertUpdate`, `LinkAlert` |
| `reporting.proto` | `ReportService` | `GetReport` |
| `policy.proto` | `PolicyService` | `GetSettings`, `SetSetting`, `ClearSetting`, `ListChangeRequests`, `RequestChange`, `DecideChange` |
| `agents.proto` | `AgentService` | `ListAgents`, `ListEligibleAgents`, `GetAgent`, `CreateAgent`, `UpdateAgent`, `DeleteAgent`, `RotateAgentCredentials` (secrets in the response only), `GetLlm`, `UpdateLlm`, `ListMcpServers`, `SaveMcpServer`, `DeleteMcpServer` |
| `agents.proto` | `AgentRunService` | `ListRuns`, `GetRun`, `StartRun`, `CancelRun`, `ClaimRuns`, `ReportProgress`, `CompleteRun` |
| `events.proto` | `EventService` | `Subscribe` (server stream): `incident.*`, `alert.*`, `agent_run.updated`, `setting.changed`, and `ping` every 25 seconds |

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
| Delegated token | `authorization: Bearer <JWT typ=obo>` | Agent on behalf of a person |
| SSO access token | `authorization: Bearer <IdP JWT>` | Agent with the person's own SSO token |

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
| `unavailable` | `UNAVAILABLE` | 503 | A dependency is not configured (for example no LLM provider), or too many live streams |
| (unexpected error) | `INTERNAL` | 500 | The details are in the server log only |

HTTP edges return the body `{"error": "<code>", "message": "<text for people>"}`.

## gRPC-Web

- Path: `POST /mayday.v1.<Service>/<Method>` on port 8080. Content type `application/grpc-web` or `application/grpc-web+proto` (binary). Other content types (for example `application/grpc-web-text`) are not supported.
- Unary and server-streaming calls. The response is a stream of length-prefixed frames. The last frame has the trailers (`grpc-status`, `grpc-message`, `mayday-error-code`).
- The bridge forwards each call through an in-process channel to the gRPC server. Thus browsers and native clients use the same services, interceptors and authentication.
- No proxy (Envoy) is necessary. A reverse proxy must not buffer responses, because `EventService.Subscribe` is a long stream.

## HTTP endpoints

| Method and path | Purpose |
|---|---|
| `GET /auth/login?redirect=`, `GET /auth/callback` | OIDC flow with PKCE. Sets the session cookie. |
| `POST /auth/dev-login` `{email, name?}`, `GET /auth/dev-login?email=&redirect=` | Development sign-in (`MAYDAY_DEV_LOGIN=true`) |
| `POST /auth/logout` | Clears the session. Response: `{"logoutUrl": "..."}` |
| `POST /oauth/token` | RFC 8693 token exchange (below) |
| `POST /mcp` | MCP server, see [domains/mcp.md](domains/mcp.md). `GET /mcp` answers 405. |
| `POST /api/v1/alerts` | Alert intake: `{title, team, severity?, source?, fingerprint?, status?, description?, labels?, externalUrl?}`. 201 when new, 200 when deduplicated. |
| `POST /api/v1/integrations/alertmanager?team=` | Prometheus Alertmanager webhook. Response: `{"created": n, "updated": n}` |
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
  "run": { "id": "...", "action": "PATCH", "subjectKind": "INCIDENT", "subjectLabel": "INC-42", "prompt": "...", "onBehalfOf": "<user id>" },
  "maydayUrl": "https://mayday.example.com",
  "mcpUrl": "https://mayday.example.com/mcp",
  "delegatedToken": "<JWT or null for automatic runs>",
  "subject": { "kind": "incident", "incident": { }, "timeline": [ ] }
}
```

The receiver must:

1. Check the signature with a constant-time compare, and refuse timestamps older than 5 minutes.
2. Answer 2xx quickly. Mayday makes up to three attempts on network errors, 429 and 5xx.
3. Use `delegatedToken` (when present) for MCP and gRPC calls. Otherwise use its own agent token.
4. Report with the MCP tool `report_agent_run`, or with `AgentRunService.ReportProgress` and `AgentRunService.CompleteRun` `{id, success, output, error}`.

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
