# Incidents

Package: `mayday.incidents`. Files: `Incident.kt` (model and ports), `IncidentService.kt` (use cases).

## Purpose

An incident records a disruption from detection to postmortem. It is the place where humans and agents work together: they change status and severity, write the timeline, explain the resolution, write the postmortem and generate a runbook.

## Model

| Element | Rules |
|---|---|
| `Incident` (aggregate root) | `id`, monotonic `number`, `key` = prefix + number (for example `INC-42`). Title and at least one responder team are mandatory. Impact end cannot be before impact start. |
| Times | `impactStartedAt`, `discoveredAt`, `reportedAt` (set at creation), `acknowledgedAt` (first status other than INVESTIGATING), `resolvedAt` (first status RESOLVED or CLOSED), `impactEndedAt` (set at resolution when empty). |
| `IncidentStatus` | INVESTIGATING, IDENTIFIED, MONITORING (open); RESOLVED, CLOSED (closed). A reopen clears `resolvedAt`. |
| `Severity` | SEV1 (worst) to SEV5. |
| Postmortem | Mandatory when the severity is at or worse than the policy `postmortem.required_min_severity` for the incident teams (strictest team wins). Nobody can make a mandatory postmortem optional. A user can make an optional postmortem mandatory. `postmortemDueAt` = `resolvedAt` + `postmortem.due_days`. |
| `TimelineEntry` | Ordered by event time. Kinds: NOTE, STATUS, SEVERITY, FIELD, ALERT, AGENT, POSTMORTEM, RUNBOOK, RESOLUTION. Each entry has an `Actor` (user, agent, system) and `onBehalfOf` when an agent acted for a person. |
| `Runbook` | Title and Markdown body, plus who generated it. |
| `version` | Optimistic concurrency. A stale write fails with `conflict`. |

## Ports

- `IncidentRepository`: `nextNumber()` (never reuses a number), `get`, `byNumber`, `save(expectedVersion)`, keyset `list`, streaming `scan`.
- `TimelineRepository`: `append`, `list`.
- Uses `PolicyReader` (policy context) and `DirectoryService` (team validation).
- `IncidentAlerts`: used by `declare` (not by `declareFromAlert`) and by `update`. `raiseFor` saves one alert for each responder team before the incident is saved, so the incident is written once with its alert links. `raised` runs after the save and announces the alerts (escalation starts). `statusChanged` runs after each status change: the raised alerts are acknowledged when the incident leaves INVESTIGATING and resolved when it closes. The alerting context implements it. A failure in the listener does not stop the declaration.

## Use cases

`declare`, `declareFromAlert`, `get` (by id, key or number), `list`, `update` (records STATUS, SEVERITY and FIELD entries), `addEntry` (an agent note becomes kind AGENT), `savePostmortem`, `saveResolution`, `saveRunbook`, `linkAlert`, `systemEntry`.

## Positive use cases

- A member declares a SEV1 for the Checkout team. Mayday gives it the next key, marks the postmortem as mandatory and records the declaration and raises a linked alert for Checkout.
- A responder sets the status to RESOLVED. Mayday sets the resolution time, the impact end and the postmortem due date.
- A responder dictates the resolution notes. They press "Generate runbook". An agent writes the runbook in ASD-STE100 and saves it.
- An agent adds a timeline entry with an earlier event time. The timeline shows it at the correct place.

## Negative use cases

- A viewer tries to declare an incident or to add a note: `forbidden`.
- A user tries to make a mandatory postmortem optional: `invalid`.
- Two users change the same incident at the same time: the second write gets `conflict` and the interface reloads.
- An incident without a title or with an unknown team: `invalid` or `not_found`.

## Rebuild checklist

1. Keep the key format and the monotonic counter. Incident numbers never repeat, also after deletes or restarts.
2. Keep the timeline as append-only.
3. Pass `StoreContract` for incidents and timeline, and `IncidentTest`.
