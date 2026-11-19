# Architecture

Mayday is one deployable service. It uses domain-driven design (DDD) for the model and hexagonal architecture (ports and adapters) for the boundaries. You can replace each adapter without a change to the domain.

## Principles

- The domain has no framework code. It uses the Kotlin standard library, the JDK, `kotlinx-coroutines` and `kotlinx-serialization` only. Spring and gRPC types stay in the adapters.
- Ports and services are `suspend` functions. Blocking work (JDBC, LLM and identity-provider calls) runs on the IO dispatcher.
- Each bounded context owns its model, its use cases (services) and its ports (interfaces).
- Adapters implement ports: storage, gRPC, HTTP edges, MCP, LLM, webhooks, identity provider.
- `App.kt` builds the application from `Ports` without a framework. Tests use it directly.
- Spring Boot is the runtime and the composition root only (`MaydayApplication.kt`): configuration, the Netty servers, gRPC, metrics. It is the only place that knows all adapters.
- The API contract is in `proto/mayday/v1/*.proto`. The front end and other clients use it through gRPC or gRPC-Web.
- Each port has a contract test suite. Each adapter must pass it (see [testing.md](testing.md)).
- The service is stateless when it uses PostgreSQL. Sessions are signed cookies. Live events use PostgreSQL `LISTEN/NOTIFY`. You can run many replicas.

## Context map

```mermaid
flowchart LR
  subgraph Core
    INC[Incidents]
    ALR[Alerting]
    AGT[Agents]
  end
  subgraph Supporting
    POL[Policy]
    IDN[Identity and access]
    DIR[Directory]
    REP[Reporting]
  end
  TOOLS[Tools: use cases as tools]

  ALR -- opens incidents, adds timeline entries --> INC
  ALR -- AlertAutomation port --> AGT
  AGT -- writes through --> TOOLS
  TOOLS --> INC
  TOOLS --> ALR
  TOOLS --> POL
  INC -- PolicyReader --> POL
  ALR -- PolicyReader --> POL
  AGT -- PolicyReader --> POL
  IDN -- AgentIdentityLookup --> AGT
  POL --> DIR
  INC --> DIR
  REP -- reads --> INC
  REP -- reads --> ALR
```

| Context | Package | Responsibility | Brief |
|---|---|---|---|
| Incidents | `mayday.incidents` | Incident aggregate, timeline, postmortem, resolution, runbook | [incidents.md](domains/incidents.md) |
| Alerting | `mayday.alerting` | Alert intake, deduplication, automatic incidents | [alerting.md](domains/alerting.md) |
| Agents | `mayday.agents` | Agent configuration, runs, delegation, built-in agent, tool gateway, LLM port | [agents.md](domains/agents.md) |
| Policy | `mayday.policy` | Scoped settings, enforcement, change requests | [policy.md](domains/policy.md) |
| Identity and access | `mayday.identity` | Users, roles, tokens, principals, on-behalf-of | [identity.md](domains/identity.md) |
| Directory | `mayday.directory` | Projects and teams | [directory.md](domains/directory.md) |
| Reporting | `mayday.reporting` | Metrics over incidents and alerts | [reporting.md](domains/reporting.md) |
| Tools | `mayday.tools` | Use cases as tools for MCP and the built-in agent | [mcp.md](domains/mcp.md) |
| Web interface | `web/` | React and shadcn-style interface | [web.md](domains/web.md) |

## Ports and adapters

```mermaid
flowchart TB
  subgraph Driving adapters
    GRPC[gRPC services, port 9090<br/>adapters/grpc/Services]
    GWEB[gRPC-Web bridge, port 8080<br/>adapters/grpc/GrpcWeb]
    EDGE[HTTP edges: sign-in, token exchange, alert intake<br/>adapters/http/Edges]
    MCPS[MCP server<br/>adapters/mcp/McpServer]
    OIDC[OIDC login<br/>adapters/auth]
  end
  GWEB -- in-process channel --> GRPC
  subgraph Application
    SVC[Services of each context]
  end
  subgraph Driven adapters
    MEM[Memory + journal<br/>adapters/memory]
    PG[PostgreSQL<br/>adapters/postgres]
    LLM[Anthropic<br/>adapters/llm]
    WH[Webhook dispatcher<br/>adapters/dispatch]
    GW[MCP client gateway<br/>adapters/mcp/McpGateway]
    BUS[Event bus<br/>in-process or PgEventBus]
  end
  GRPC --> SVC
  EDGE --> SVC
  MCPS --> SVC
  OIDC --> SVC
  SVC -- repositories --> MEM
  SVC -- repositories --> PG
  SVC -- LlmProvider --> LLM
  SVC -- AgentDispatcher --> WH
  SVC -- ToolCatalog --> GW
  SVC -- EventBus --> BUS
```

| Port | Defined in | Adapters |
|---|---|---|
| `*Repository` (13 ports) | each context | `MemoryStore`, `PostgresStore` |
| `EventBus` | `shared` | `InProcessEventBus`, `PgEventBus` |
| `LlmProvider`, `LlmProviderFactory` | `agents` | `AnthropicProvider`; tests use `FakeLlm` |
| `AgentDispatcher` | `agents` | `WebhookDispatcher`; tests use `RecordingDispatcher` |
| `ToolCatalog` | `agents` | `MaydayTools`, `McpGateway`, `CompositeCatalog` |
| `AgentIdentityLookup` | `identity` | `AgentService` |
| `AlertAutomation` | `alerting` | `AgentService` |
| `PolicyReader` | `policy` | `PolicyService` |

| Driving adapter | Port | Clients |
|---|---|---|
| gRPC services (`adapters/grpc/Services.kt`) | 9090, h2c (TLS optional) | Scripts, services, pull agents, `grpcurl` |
| gRPC-Web bridge (`adapters/grpc/GrpcWeb.kt`) | 8080, `/mayday.v1.<Service>/<Method>` | The web interface. The bridge sends each call through an in-process channel to the same gRPC services, so interceptors and authentication are the same. No Envoy is necessary. |
| HTTP edges (`adapters/http/Edges.kt`) | 8080 | Browser sign-in, RFC 8693 token exchange, alert intake for monitoring tools |
| MCP server (`/mcp`) | 8080 | External agents |
| Static files and operations (`adapters/http/Web.kt`, Actuator) | 8080 | Browsers, probes, Prometheus |

`ProtoJson` converts between protobuf messages and the domain model through the JSON names. A proto field has the JSON name of the domain property (`team_ids` = `teamIds`). Thus one generic converter serves all services, and fields that are not in the proto (for example token hashes) never leave the server.

## Key interactions

### An alert fires and an agent analyzes it

```mermaid
sequenceDiagram
  participant Src as Alert source
  participant API as HTTP edge
  participant AL as AlertService
  participant POL as PolicyService
  participant INC as IncidentService
  participant AG as AgentService
  participant LLM as LLM provider
  participant T as MaydayTools
  Src->>API: POST /api/v1/alerts (token with alerts_ingest)
  API->>AL: ingest(signal)
  AL->>AL: deduplicate by fingerprint
  AL->>POL: alerts.auto_incident_min_severity
  AL->>INC: declareFromAlert()
  AL->>AG: onAlertCreated(alert)
  AG->>POL: agents.auto_analysis
  AG->>AG: create run (identity of the agent)
  AG->>LLM: task + tools
  LLM-->>AG: tool_use add_timeline_entry
  AG->>T: call(principal of agent)
  T->>INC: addEntry()
  LLM-->>AG: final report
  AG->>INC: timeline entry with the report
```

### A person starts an agent (on behalf of)

```mermaid
sequenceDiagram
  participant U as Person (browser)
  participant API as gRPC-Web
  participant AG as AgentService
  participant ID as IdentityService
  participant WH as Webhook dispatcher
  participant X as External agent
  participant M as MCP server
  U->>API: AgentRunService.StartRun {subject_ref: INC-42, action: PATCH}
  API->>AG: trigger(principal of person)
  AG->>ID: delegate(user, agent, run, 1 h)
  ID-->>AG: signed token (sub=user, act=agent)
  AG->>WH: dispatch(run, delegated token, snapshot)
  WH->>X: POST signed webhook
  X->>M: tools/call with Bearer delegated token
  M->>ID: authenticate -> principal (user RBAC, actor = agent)
  M-->>X: result
  X->>M: tools/call report_agent_run (agent token)
```

### Live updates across replicas

Each service publishes a `DomainEvent`. `PgEventBus` sends it to local listeners and to `pg_notify`. The other replicas receive it with `LISTEN` and give it to their `EventService.Subscribe` streams. Each browser holds one gRPC-Web stream and reloads only the data that changed.

## Scale

Targets: 50,000 to 100,000 concurrent users, 1,000 to 10,000 incidents and alerts each day.

- Netty event loops serve HTTP and gRPC. Each call runs as a Kotlin coroutine, so a waiting call or an open event stream costs a few kilobytes, not a thread.
- Each `EventService.Subscribe` stream has a bounded buffer of 256 events. When it is full, the oldest event is dropped: a slow client loses events, not server memory. `MAYDAY_MAX_LIVE_CONNECTIONS` limits the streams per instance; above the limit, `Subscribe` returns `UNAVAILABLE`.
- JDBC calls run on the IO dispatcher with a HikariCP pool (`MAYDAY_DATABASE_POOL`). Blocking work never runs on an event loop.
- List queries use keyset pagination (`number` for incidents, `(first_seen_at, id)` for alerts) and indexes.
- Report queries stream rows with a server-side cursor. They do not load all rows into memory.
- Writes use optimistic concurrency (`version`) instead of locks. Pull agents claim runs with `FOR UPDATE SKIP LOCKED`.
- Static assets have content hashes and immutable cache headers. HTTP responses above 1 KiB use gzip. gRPC messages are binary protobuf.
- Settings resolution uses an in-memory cache. A `setting.changed` event clears it on all replicas.

## Security

- Authentication: OIDC with PKCE, JWT validation with JWKS (RS256, ES256), API tokens (stored as SHA-256), delegated tokens (HS256).
- gRPC authentication: metadata `authorization: Bearer <token>`, or the session cookie for gRPC-Web. One interceptor reads the credentials. Each service resolves the principal and applies RBAC.
- CSRF: gRPC-Web requests need the content type `application/grpc-web+proto`. A cross-site page cannot send it without a CORS preflight, and Mayday allows no cross-origin requests. Cookie sessions on the HTTP edges need the `X-Requested-With: mayday` header for changes.
- Native gRPC is plaintext h2c by default. Use TLS at the ingress, or set `spring.grpc.server.ssl.*`.
- Secrets at rest (LLM key, webhook secrets, MCP credentials) use AES-256-GCM with a key from `MAYDAY_SECRET_KEY`.
- Webhooks are signed with HMAC-SHA256 over the timestamp and the body.
- The built-in agent uses the same tools as external agents. RBAC applies to each call.
