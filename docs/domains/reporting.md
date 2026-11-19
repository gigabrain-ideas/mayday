# Reporting

Package: `mayday.reporting`. File: `Reporting.kt`.

## Purpose

Reports show how a team or an organization handles incidents over a period: counts, response times and postmortem compliance.

## Input

`ReportQuery`: teams (none = all), `from`, `to` (at most two years), severities (none = all). The interface offers 7, 30, 90 days and a custom range, plus multi-select for teams and severities.

## Output

| Metric | Definition |
|---|---|
| Incidents, open, resolved | Incidents reported in the period |
| By severity | Count per SEV |
| MTTA | Mean of `acknowledgedAt − reportedAt` |
| MTTR | Mean of `resolvedAt − (impactStartedAt or reportedAt)` |
| MTTD | Mean of `discoveredAt − impactStartedAt` |
| Postmortems | Required, published, overdue (due date passed and not published) |
| By team | Incidents, open, alerts, MTTR per team |
| By day | Incidents per day and severity, alerts per day (UTC days) |
| Longest | Ten incidents with the longest duration (open incidents count until now) |

## Rules

- A multi-team incident counts once in totals and once for each selected team in the team table.
- Reports stream rows (`scan`). They do not load all incidents into memory.

## Positive use cases

- A director selects the Payments teams and the last quarter and sees MTTR per team.

## Negative use cases

- `to` before `from`, or a period longer than two years: `invalid`.

## Rebuild checklist

Keep the metric definitions. `ApiContractTest` checks the report endpoint.
