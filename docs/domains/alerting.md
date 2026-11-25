# Alerting

Package: `mayday.alerting`. File: `Alerting.kt`.

## Purpose

Alerting takes signals from monitoring tools, removes duplicates, opens incidents for severe alerts and starts automatic agent work.

## Model

| Element | Rules |
|---|---|
| `Alert` | `fingerprint`, `source`, `title`, `severity`, `status` (FIRING, ACKNOWLEDGED, RESOLVED), `teamId`, `labels`, `count`, `firstSeenAt`, `lastSeenAt`, optional `incidentId`, list of `updates`. |
| Fingerprint | Given by the source, or SHA-256 of source, title, team and sorted labels. |
| Deduplication | A firing signal with the fingerprint of an open alert increments `count` and keeps the worst severity. A resolved alert that fires again inside `alerts.dedup_window_minutes` reopens. Otherwise Mayday creates a new alert. |
| Resolve signal | Resolves the open alert. With no open alert, it does nothing (or records a resolved alert when the fingerprint is new). |
| Automatic incident | A new alert at or worse than `alerts.auto_incident_min_severity` opens an incident for its team and links both. |

## Ports

- `AlertRepository`: `get`, `latestByFingerprint`, `save(expectedVersion)`, keyset `list`, `scan`.
- `AlertAutomation`: called after a new alert. The agents context implements it.
- `AlertLifecycle`: `opened` after a new alert and after a reopened alert, `closed` after acknowledge and resolve. The escalation context implements it ([escalation.md](escalation.md)). A failure there never blocks the alert.

## Interfaces

- `POST /api/v1/alerts` with `AlertSignal` JSON (plain HTTP, for monitoring tools).
- `POST /api/v1/integrations/alertmanager` (Prometheus Alertmanager webhook format). The team comes from the `team` label or the `?team=` parameter. The `severity` label maps: critical/page/fatal/p1/sev1 → SEV1, high/error/major/p2/sev2 → SEV2, warning/warn/medium/p3/sev3 → SEV3, low/minor/p4/sev4 → SEV4, info/informational/p5/sev5 → SEV5, other or none → SEV3.
- gRPC `AlertService` (`IngestAlert`, list, acknowledge, resolve, updates, link). See [api.md](../api.md).
- MCP tool `send_alert`.

## Positive use cases

- Alertmanager sends the same alert every minute. Mayday keeps one alert and counts the repeats.
- A critical alert fires. Mayday opens an incident, adds an ALERT entry to its timeline and starts the analysis agent.
- A responder adds an update to an alert that has an incident. The update also appears on the incident timeline (when `timeline.auto_populate` is on).

## Negative use cases

- A token with only `read` scope sends an alert: `forbidden`.
- A token with only `alerts_ingest` sends an alert for a team that its owner is not in (not a team member, not admin of the team's project, not an administrator): `forbidden`. A leaked ingest token cannot open incidents for other teams. Agent tokens and tokens with `write` are not limited. For one Alertmanager that serves many teams, an administrator creates the token.
- A signal for an unknown team: `not_found`.
- A flapping alert inside the window does not create a new incident each time.

## Rebuild checklist

Pass `StoreContract` for alerts, `AlertTest`, `AlertIngestScopeTest`, the alert parts of `GrpcContractTest` and `HttpEdgesContractTest`. Keep the Alertmanager format.
