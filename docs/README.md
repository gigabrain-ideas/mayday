# Documentation

| Document | Content |
|---|---|
| [architecture.md](architecture.md) | Bounded contexts, ports and adapters, interaction diagrams, scale, security |
| [c4.md](c4.md) | C4 model: system context, containers, components, code |
| [domains/](domains/) | One product brief per context. Each brief is enough to rebuild the context. |
| [api.md](api.md) | gRPC services, gRPC-Web, HTTP endpoints, errors, webhook and pull contracts |
| [testing.md](testing.md) | Contract tests (gRPC, gRPC-Web, HTTP edges, ProtoJson, store, webhook, audit stream and sinks) and domain tests |
| [deployment.md](deployment.md) | Ports, configuration, probes, metrics, local run, Docker, Helm, Terraform, enterprise AI gateway, sizing |
| [observability.md](observability.md) | OpenTelemetry traces, agent run traces, metrics |

Product briefs: [incidents](domains/incidents.md), [alerting](domains/alerting.md), [agents](domains/agents.md), [gateway identity](domains/gateway-identity.md), [policy](domains/policy.md), [identity](domains/identity.md), [directory](domains/directory.md), [on-call](domains/oncall.md), [provisioning](domains/provisioning.md), [reporting](domains/reporting.md), [tools and MCP](domains/mcp.md), [audit](domains/audit.md), [web interface](domains/web.md).

API contract: [`proto/mayday/v1`](../proto/mayday/v1).
