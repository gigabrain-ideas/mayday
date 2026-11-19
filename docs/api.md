# API and integration contracts

All endpoints use JSON. Base path: `/api/v1`. The contract tests in `src/test/kotlin/mayday/contract/ApiContractTest.kt` check this document.

## Authentication

| Method | Header | Use |
|---|---|---|
| Session | Cookie `mayday_session` + `X-Requested-With: mayday` on changes | Browser |
| API token | `Authorization: Bearer mdy_...` | Scripts, alert sources, MCP clients |
| Agent token | `Authorization: Bearer mdy_...` (owner = agent) | External agents |
| Delegated token | `Authorization: Bearer <JWT typ=obo>` | Agent on behalf of a person |
| SSO access token | `Authorization: Bearer <IdP JWT>` | Agent with the person's own SSO token |

Token exchange (RFC 8693):

```http
POST /oauth/token
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:token-exchange&subject_token=<user token>&actor_token=<agent token>
```

Response: `{"access_token": "...", "issued_token_type": "urn:ietf:params:oauth:token-type:access_token", "token_type": "Bearer", "expires_in": 3600}`.

## Errors

Every error has the same body: `{"error": "<code>", "message": "<text for people>"}`.

| Code | HTTP | Meaning |
|---|---|---|
| `unauthenticated` | 401 | No or invalid credentials |
| `forbidden` | 403 | RBAC or scope refuses the action |
| `locked` | 403 | A policy is enforced at a higher level. Send a change request. |
| `invalid` | 400 | Bad input |
| `not_found` | 404 | Unknown resource |
| `conflict` | 409 | Stale `version` or duplicate |
| `unavailable` | 503 | A dependency is not configured (for example no LLM provider) |

## Resources

| Method and path | Purpose |
|---|---|
| `GET /auth/config`, `POST /auth/dev-login`, `POST /auth/logout`, `GET /me`, `GET /whoami` | Session and identity |
| `GET /auth/login`, `GET /auth/callback` (no `/api/v1` prefix) | OIDC flow |
| `GET /incidents?open=&status=&severity=&team=&q=&from=&to=&cursor=&limit=` | List, newest first. `nextCursor` for the next page. |
| `POST /incidents` | Declare: `title`, `severity`, `teamIds`, optional `description`, `commanderId`, `impactStartedAt`, `discoveredAt`, `labels` |
| `GET /incidents/{ref}` | `ref` = key, number or id |
| `PATCH /incidents/{ref}` | Any of `title`, `description`, `severity`, `status`, `teamIds`, `commanderId`, `impactStartedAt`, `impactEndedAt`, `discoveredAt`, `labels`, `postmortemRequired`, plus `version` |
| `GET/POST /incidents/{ref}/timeline` | Read or add `{text, at?}` |
| `PUT /incidents/{ref}/postmortem`, `/resolution`, `/runbook` | Save documents |
| `GET/POST /incidents/{ref}/agent-runs` | List runs, or start `{action, agentId?, prompt?}` |
| `GET /alerts?status=&severity=&team=&q=&cursor=` | List |
| `POST /alerts` | Ingest `{title, team, severity?, source?, fingerprint?, status?, description?, labels?, externalUrl?}` |
| `POST /integrations/alertmanager?team=` | Prometheus Alertmanager webhook |
| `GET /alerts/{id}`, `POST /alerts/{id}/acknowledge`, `/resolve`, `/updates`, `/link`, `/agent-runs` | Alert actions |
| `GET /reports?team=&severity=&from=&to=` | Report |
| `GET/PUT/DELETE /settings?scope=&scopeId=&key=` | Policy |
| `GET/POST /change-requests`, `POST /change-requests/{id}/decision` | Change requests |
| `GET/POST/PATCH/DELETE /projects`, `/teams`, `GET/POST/PATCH /users` | Directory and users |
| `GET/POST/DELETE /tokens` | Personal API tokens. The secret is in the create response only. |
| `GET /agents`, `GET /agents/eligible?action=`, `POST/PUT/DELETE /agents/{id}`, `POST /agents/{id}/credentials` | Agents |
| `GET /agent-runs`, `GET /agent-runs/{id}`, `POST /agent-runs/claim`, `/{id}/progress`, `/{id}/complete`, `/{id}/cancel` | Runs |
| `GET/PUT /llm`, `GET/POST/PUT/DELETE /mcp-servers` | Agent infrastructure |
| `GET /events` | Server-Sent Events: `incident.*`, `alert.*`, `agent_run.updated`, `setting.changed`, ... |
| `POST /mcp` (no prefix) | MCP server, see [domains/mcp.md](domains/mcp.md) |
| `GET /healthz`, `/readyz`, `/metrics` (no prefix) | Operations. `/metrics` uses the Prometheus text format. |

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
2. Answer 2xx quickly. Mayday retries three times on network errors, 429 and 5xx.
3. Use `delegatedToken` (when present) for MCP and API calls. Otherwise use its own agent token.
4. Report with `report_agent_run` (MCP) or `POST /api/v1/agent-runs/{id}/complete` with `{success, output, error}`.

## Pull agents

```http
POST /api/v1/agent-runs/claim
Authorization: Bearer <agent token>

{"limit": 5}
```

Response: a list of `{run, delegatedToken, subject}`. Each run is handed out once.
