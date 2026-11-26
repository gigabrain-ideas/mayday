# Alerting

Package: `mayday.alerting`. File: `Alerting.kt`.

## Purpose

Alerting takes signals from monitoring tools, removes duplicates, opens incidents for severe alerts and starts automatic agent work.

## Model

| Element | Rules |
|---|---|
| `Alert` | `fingerprint`, `source`, `title`, `severity`, `status` (FIRING, ACKNOWLEDGED, RESOLVED), `teamId`, `labels`, `count`, `firstSeenAt`, `lastSeenAt`, optional `incidentId`, list of `updates`. An automated agent can acknowledge; a person (or an agent for a person) can acknowledge again after it, and only that stops the escalation. |
| Fingerprint | Given by the source, or SHA-256 of source, title, team and sorted labels. |
| Deduplication | A firing signal with the fingerprint of an open alert increments `count` and keeps the worst severity. A resolved alert that fires again inside `alerts.dedup_window_minutes` reopens. Otherwise Mayday creates a new alert. |
| Resolve signal | Resolves the open alert. With no open alert, it does nothing (or records a resolved alert when the fingerprint is new). |
| Automatic incident | A new alert at or worse than `alerts.auto_incident_min_severity` opens an incident for its team and links both. |
| Alert for a declared incident | A person or an agent declares an incident: Mayday raises one alert for each responder team. Source `mayday`, title `<key>: <incident title>`, the incident severity, label `incident=<key>`, linked to the incident. Escalation pages for these alerts as for any new alert. They start no agent automation, because the incident already does. Mayday saves the alerts first and then the incident with its links, in one write (version 1). A failed alert for one team does not stop the alerts for the other teams; the failure is on the trace. The raised alerts follow the incident: the first status other than INVESTIGATING acknowledges them (the escalation stops), RESOLVED or CLOSED resolves them. A reopen does not fire them again. A team added to the incident later gets an alert only while nobody is on the incident: the incident is still not acknowledged and none of its alerts is acknowledged. When somebody is on it, or the incident is closed, the added team is not paged. A team that already has an alert linked to the incident gets no second one. The incident is written once with the new links. When that write loses to a concurrent update (`conflict`), Mayday resolves the new alerts as withdrawn, so no alert stays firing without a link. An incident that an alert opens raises no second alert. |

## Ports

- `AlertRepository`: `get`, `latestByFingerprint`, `save(expectedVersion)`, keyset `list`, `scan`.
- `AlertAutomation`: called after a new alert. The agents context implements it.
- `AlertLifecycle`: `opened` after a new alert and after a reopened alert, `closed` after acknowledge and resolve. The escalation context implements it ([escalation.md](escalation.md)). A failure there never blocks the alert.
- Implements `IncidentAlerts` (incidents context): raises the alerts for a declared incident and for teams added while nobody is on it, and makes them follow its status.

## Interfaces

- `POST /api/v1/alerts` with `AlertSignal` JSON (plain HTTP, for monitoring tools).
- `POST /api/v1/integrations/alertmanager` (Prometheus Alertmanager webhook format). The team comes from the `team` label or the `?team=` parameter. The `severity` label maps: critical/page/fatal/p1/sev1 → SEV1, high/error/major/p2/sev2 → SEV2, warning/warn/medium/p3/sev3 → SEV3, low/minor/p4/sev4 → SEV4, info/informational/p5/sev5 → SEV5, other or none → SEV3.
- gRPC `AlertService` (`IngestAlert`, list, exact count with the same filter, acknowledge, resolve, updates, link). See [api.md](../api.md).
- MCP tool `send_alert`.

## Positive use cases

- Alertmanager sends the same alert every minute. Mayday keeps one alert and counts the repeats.
- A critical alert fires. Mayday opens an incident, adds an ALERT entry to its timeline and starts the analysis agent.
- A member declares an incident for Checkout and SRE. Both teams get a firing alert linked to the incident, and the timeline shows two ALERT entries.
- The commander sets the incident to IDENTIFIED. The raised alerts are acknowledged and nobody is paged again. At RESOLVED they are resolved.
- Nobody has acknowledged the incident yet. The commander adds SRE as a responder team. SRE gets a firing alert linked to the incident, and its on-call person is paged.
- A responder adds an update to an alert that has an incident. The update also appears on the incident timeline (when `timeline.auto_populate` is on).

## Negative use cases

- A token with only `read` scope sends an alert: `forbidden`.
- A token with only `alerts_ingest` sends an alert for a team that its owner is not in (not a team member, not admin of the team's project, not an administrator): `forbidden`. A leaked ingest token cannot open incidents for other teams. Agent tokens and tokens with `write` are not limited. For one Alertmanager that serves many teams, an administrator creates the token.
- A signal for an unknown team: `not_found`.
- A flapping alert inside the window does not create a new incident each time.
- A critical alert opens an incident. The incident does not raise a second alert.
- A viewer tries to declare an incident: `forbidden`, and no alert is raised.
- The incident is acknowledged, or a person acknowledged one of its alerts. The commander adds SRE: SRE gets no alert and nobody is paged, because somebody is already on the incident. An acknowledgement by an automated agent does not count: then SRE is paged.
- A team is removed from an incident and added again: it gets no second alert.

## Rebuild checklist

Pass `StoreContract` for alerts, `AlertTest`, `AlertIngestScopeTest`, the alert parts of `GrpcContractTest` and `HttpEdgesContractTest`. Keep the Alertmanager format.
