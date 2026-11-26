# Testing

Mayday uses contract tests at each port and boundary, and domain tests for each bounded context.

```mermaid
flowchart LR
  SC[StoreContract] --> M[MemoryStore]
  SC --> J[MemoryStore + journal]
  SC --> P[PostgresStore]
  GC[GrpcContractTest] --> G[gRPC services on a network port]
  WC[GrpcWebContractTest] --> B[gRPC-Web bridge on the HTTP port]
  EC[HttpEdgesContractTest] --> E[HTTP edges, MCP, static files, probes]
  PJ[ProtoJsonContractTest] --> C[ProtoJson converter and proto files]
  HC[WebhookContractTest] --> WH[WebhookDispatcher]
  SCT[ScimContractTest] --> SC[SCIM 2.0 endpoints]
  OGS[OidcGroupSyncContractTest] --> KC[Keycloak container + OIDC adapter]
  ASC[AuditSinkContract] --> AS1[StdoutSink, FileSink]
  ASC --> AS2[HttpSink, LokiSink, SentinelSink<br/>against a local HTTP destination]
  ALC[AuditLogContractTest] --> AL[AuditLog: queue, chain, retries]
  AST[AuditStreamContractTest] --> AE[Real server: gRPC, gRPC-Web, HTTP, MCP, agent records]
  DT[Domain tests] --> S[Services with fakes]
  UC[UseCaseTest] --> UI[Web interface in Chromium<br/>against the real server]
```

## Suites

| Suite | File | What it proves |
|---|---|---|
| `StoreContract` | `contract/StoreContract.kt` | Behavior that every storage adapter must have: monotonic numbers, optimistic concurrency, keyset pagination, filters, ordering, unique emails, atomic and concurrent run claims |
| `MemoryStoreContractTest`, `JournaledMemoryStoreContractTest`, `PostgresStoreContractTest` | `contract/StoreAdapters.kt` | Each adapter passes the contract. The journal test also checks restart recovery. |
| `GrpcContractTest` | `contract/GrpcContractTest.kt` | Native gRPC: credentials (bearer token, session cookie, none), status codes and the `mayday-error-code` trailer, `locked` = `FAILED_PRECONDITION`, incident list and patch semantics, timeline and documents, alerts, policy and change requests, directory and user RBAC, token and agent secrets shown once, delegated runs, pull agents, reports, `EventService.Subscribe`, token scopes |
| `GrpcWebContractTest` | `contract/GrpcWebContractTest.kt` | gRPC-Web bridge: message and trailer frames, both content types, errors as trailer frames, 404 for unknown methods, 400 for malformed frames, other content types not routed, streamed frames before the stream ends |
| `HttpEdgesContractTest` | `contract/HttpEdgesContractTest.kt` | HTTP error shape, dev login cookie and safe redirects, logout, token exchange, MCP JSON-RPC and RBAC, CSRF rule (`X-Requested-With`), admin scope in token exchange with `SetAgentAdmin`, alert intake 201/200, Alertmanager format, single-page-app fallback with security headers, health, readiness and metrics |
| `ProtoJsonContractTest` | `contract/ProtoJsonContractTest.kt` | Domain → proto → domain loses no data. Secrets (token hashes) never reach the proto. Each domain enum value has a proto value. Each proto field has a domain property with the same JSON name. Bad input is `invalid`. |
| `WebhookContractTest` | `contract/WebhookContractTest.kt` | The webhook signature and payload that push agents rely on |
| `ScimContractTest` | `contract/ScimContractTest.kt` | SCIM 2.0 with Entra and Okta request shapes: discovery, token rules (401 without token or with a cookie, 403 without SCIM scope or for a non-administrator), user create after a filter miss, 409 `uniqueness`, PATCH with string booleans, value objects and email paths, groups as teams, member add and remove, `excludedAttributes=members`, PUT, deprovisioning, `invalidFilter` |
| `OidcGroupSyncContractTest` | `contract/OidcGroupSyncContractTest.kt` | A real OpenID Connect provider (Keycloak in a container): access tokens with the groups claim create teams with owner groups, give roles, and follow group changes made with the Keycloak admin API, including a user without groups (Keycloak leaves the claim out) |
| `LlmProviderContractTest` (`AnthropicProviderContractTest`, `OpenAiProviderContractTest`) | `contract/LlmProviderContractTest.kt` | Both LLM adapters against a fake AI gateway: one auth header in the configured style, agent ID and fixed headers, `traceparent` on every call, token rotation, 401 → one refresh, 429 → `BudgetExhausted` with exactly one request, bounded 5xx retries, tool round trip, base URL with path prefix |
| `CredentialsContractTest` | `contract/CredentialsContractTest.kt` | OAuth 2.0 client secret and workload identity assertion grants, shared cache, single-flight and early refresh, invalidate, token file, no secrets in errors or spans |
| `TokenProxyContractTest` | `contract/TokenProxyContractTest.kt` | Token proxy sidecar: identity headers added and client credentials removed, 429 passed through once, 401 → one refresh, streaming |
| `McpGatewayContractTest` | `contract/McpGatewayContractTest.kt` | Tools gateway client: identity headers per request, static mode, 401 refresh, a grant refusal (`isError: true`) reaches the model unchanged, no decision on error messages, 429 pause without network I/O, isolation of a broken server, session expiry |
| `TelemetryContractTest` | `contract/TelemetryContractTest.kt` | OpenTelemetry adapter: nesting across suspension and dispatchers, typed attributes, errors and cancellation, W3C headers, run links |
| `LlmSettingsContractTest` | `contract/LlmSettingsContractTest.kt` | `MAYDAY_LLM_*` values win and lock their fields, generic OAuth 2.0 variables, invalid values, token parameter validation |
| `AuditLogContractTest` | `contract/AuditLogContractTest.kt` | The audit stream port: numbered and chained records, detection of changed, removed and forged records, arrival order does not matter, reads switch, a full queue reports `audit.dropped` with the exact count, a failing sink is retried without holding up the others, `emit` does not block, one chain per instance, the `audit-verify` exit codes |
| `AuditSinkContract` (`StdoutSinkContractTest`, `FileSinkContractTest`, `HttpSinkContractTest`, `LokiSinkContractTest`, `SentinelSinkContractTest`) | `contract/AuditSinkContractTest.kt` | Each sink delivers every record unchanged and in order (the chain verifies at the destination), special characters survive, a refused delivery throws; file rotation; HTTP headers, NDJSON and size limits; Loki push path, tenant, auth, low cardinality labels and nanosecond timestamps; Sentinel DCR stream URL, Entra bearer token, `TimeGenerated`, token refresh after 401 |
| `AuditStreamContractTest` | `contract/AuditStreamContractTest.kt` | The real server: denied and anonymous gRPC calls with caller, credential, source and reason; tokens issued and revoked without the secret; policy values before and after; agent admin opt-in and reset; token exchange and MCP tool calls with agent and on-behalf-of; refused hidden tools; built-in agent tool calls with the run; HTTP intake and sign-in; gRPC-Web source; spoofed source headers ignored; a client that closes its live stream is not a failure; status RPC for administrators only; the whole stream verifies |
| `PolicyReplicaTest` | `DomainTest.kt` | Two instances on one store and event bus: direct changes and approved change requests clear the settings cache of the other instance |
| `PolicyTest`, `IncidentTest`, `AlertTest`, `IdentityTest`, `AgentTest` | `DomainTest.kt` | The rules in each product brief, including negative cases |
| `RotationTest`, `OnCallTest`, `TeamMembersTest` | `OnCallTest.kt` | Rotation math (order, wrap, before start, daylight saving, windows), override precedence and cuts, schedule and override RBAC (owners vs members), integrity rules, leaving a team |
| Alert count | `StoreContract`, `GrpcContractTest` | `count` is exact with every filter and beyond one page (130 firing alerts); `CountAlerts` follows ingest and acknowledge, needs authentication, and refuses an unknown team |
| `EscalationTest` | `EscalationTest.kt` | Policy RBAC (owners vs members), validation, administrator limit on the delay, one default per team, paging the person on call and overrides, next level after the delay, repeat and exhaustion, severity threshold, a level that reaches nobody, "Escalate now", reopen, policy deletion, parallel ticks send each page once |
| `GroupClaimTest`, `GroupSyncTest`, `ProvisioningTest` | `GroupSyncTest.kt` | Groups claim parsing (list, nested path, string, missing claim, overage), automatic teams with filter and owner suffix, reconcile rules, manual changes locked by links, rotation exit on group loss, SCIM groups and users |
| `DelegationLimitsTest`, `UntrustedSubjectDataTest`, `AlertIngestScopeTest`, `AgentAdminExchangeTest`, `AgentAdminOptInResetTest` | `SecurityTest.kt` | Delegated run tokens have no ADMIN scope and stop when the run is finished; token exchange keeps ADMIN only with the organization setting and the user's opt-in, agents cannot opt in for the user, and turning the organization setting off clears every opt-in; alert content reaches the model only as untrusted data, apart from the task; ingest-only tokens send alerts only for the teams of their owner |
| `AgentIndependenceTest`, `BudgetExhaustedTest`, `GatewaySettingsTest` | `AgentGatewayTest.kt` | Alerts and incidents work with no agent, a hanging agent or a failing agent; 429 makes one call and pauses the agent; settings and identity headers |
| `TracingTest`, `TracingServerTest` | `TracingTest.kt` | Agent run trace tree and stored trace ID; server spans for HTTP, gRPC and gRPC-Web; no export without an endpoint |
| `UseCaseTest` | `e2e/UseCaseTest.kt` | The main flows of work in the web interface. See [Use-case tests](#use-case-tests). |

`ServerContract` (`contract/ServerSupport.kt`) starts the real Spring Boot application on random HTTP and gRPC ports, with memory storage, `FakeLlm` and `RecordingDispatcher`. Both servers listen on `127.0.0.1` only. On a wildcard address, another local server (an IDE, a fake gateway in another test) could bind `127.0.0.1` on the same port and receive the test calls; the gRPC client then failed with "First received frame was not SETTINGS". Each subclass runs the check `no other loopback listener can take the ports of the test servers`. Test HTTP calls have a 30 s timeout, so a broken server fails a test instead of stopping the run. Each subclass sets its own `spring.grpc.server.inprocess.name`.

Test doubles (`TestSupport.kt`): `FakeLlm` (scripted tool calls and answers), `RecordingDispatcher` (records webhooks, can fail on request), `RecordingPager` (records escalation pages), `TestClock` (moved by hand) and `RecordingSink` (keeps every audit batch; `ServerContract` and `Fixture` use it).

## Run

```bash
make test
```

`make test` needs a running Docker engine. `OidcGroupSyncContractTest` starts one Keycloak container. `PostgresStoreContractTest` starts one disposable PostgreSQL container per test run with [Testcontainers](https://java.testcontainers.org/). Each test uses its own schema and closes its pool. The container stops when the run ends.

| Variable | Effect |
|---|---|
| `MAYDAY_TEST_DATABASE_URL`, `MAYDAY_TEST_DATABASE_USER`, `MAYDAY_TEST_DATABASE_PASSWORD` | Use this disposable database. No container starts. |
| `MAYDAY_TEST_POSTGRES_IMAGE` | Container image (default `postgres:18-alpine`, same as `compose.yaml`) |
| `MAYDAY_TEST_KEYCLOAK_IMAGE` | Container image for `OidcGroupSyncContractTest` (default `quay.io/keycloak/keycloak:26.0.7`). The realm is `src/test/resources/keycloak/mayday-realm.json`. |

Without Docker and without `MAYDAY_TEST_DATABASE_URL`, `PostgresStoreContractTest` fails. The contract is not optional.

## Use-case tests

`UseCaseTest` drives the built web interface (`web/dist`) in Chromium with [Playwright for Java](https://playwright.dev/java/). `BrowserContract` starts the real server like `ServerContract` does and serves `web/dist`. The tests have the JUnit tag `e2e`. `make test` does not run them.

| # | Use case | Result |
|---|---|---|
| 1 | An administrator creates a team in Settings → Teams & projects. On-call for the team shows "No members". | Passes |
| 2 | An administrator adds three users in Settings → Users, creates a team and adds the three users in On-call → Members. | Passes |
| 3 | A monitoring tool sends an alert to `POST /api/v1/alerts` with a token. The alert is in Alerts as Firing, with its source. | Passes |
| 4 | A member declares an incident. Mayday raises an alert for the incident. The incident shows it, and the alert links back to the incident. | Passes |
| 5 | A responder sets the status of a new incident from Investigating to Identified. Details shows the Acknowledged time and the time to acknowledge, and the raised alert is acknowledged. | Passes |
| 6 | On-call with "All teams" shows who is on call now for the schedules of every team. | Passes |
| 7 | An automated agent acknowledges an alert. The alert shows "By an agent · waiting for a person" and the Acknowledge button. A person acknowledges and the button goes away. | Passes |

Negative cases stay in the contract and domain tests. Use-case tests show that the flows work end to end.

```bash
make e2e
```

`make e2e` builds the web interface, then runs `./gradlew e2e`. After a first build, `./gradlew e2e` is enough. Needs Java. The first run downloads the Playwright browsers to the user cache.

| Variable | Effect |
|---|---|
| `HEADED=1` (`MAYDAY_E2E_HEADED=true`) | Opens a visible browser, so you can see each step. |
| `SLOWMO=<ms>` (`MAYDAY_E2E_SLOWMO`) | Delay before each browser action. Default 400 ms when headed, 0 when headless. |

```bash
make e2e HEADED=1 SLOWMO=800
```

A failed test keeps a Playwright trace with screenshots and DOM snapshots in `build/e2e/`. Open it at https://trace.playwright.dev or with `npx playwright show-trace <file>`.

A new use case: add a test to `UseCaseTest` (or a new subclass of `BrowserContract` with its own `spring.grpc.server.inprocess.name`). Find elements by role, placeholder or text, as a user does. `card(title)` finds a card by its header title; the interface has no landmark role for cards.

## A new adapter

1. Implement the port.
2. Add a subclass of the contract (for example `class RedisStoreContractTest : StoreContract()`).
3. The adapter is ready when the contract passes.

A new driving adapter (for example another protocol) gets its own contract suite on top of `ServerContract`.
