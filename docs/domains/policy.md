# Policy

Package: `mayday.policy`. File: `Policy.kt`.

## Purpose

Policy holds the configurable rules: postmortem obligations, alert behavior, paging limits, agent automation, MCP write access and the incident key prefix. Settings exist at organization, project and team level. Administrators can enforce a value so that lower levels cannot weaken it. Lower roles can request a change.

## Model

| Element | Rules |
|---|---|
| `SettingDef` | Key, label, description, type (BOOL, INT, SEVERITY, STRING), default, allowed scopes, strictness direction. Catalog: `SettingCatalog`. |
| `SettingValue` | Value at one scope (`ORG`, `PROJECT`, `TEAM`), with `enforcement` NONE, MINIMUM or LOCKED and an `exception` flag. |
| Resolution | Walk organization → project of the team → team. The most specific accepted value wins. A LOCKED value above blocks lower values. A MINIMUM value above accepts only lower values that are at least as strict. An approved exception is always accepted. |
| Strictness | HIGHER (more severities covered is stricter), LOWER (smaller number is stricter), TRUE, FALSE, NONE (MINIMUM acts as LOCKED). |
| Multi-team | For an incident with many teams, the strictest team value applies. |
| `ChangeRequest` | Scope, key, proposed value, reason, status PENDING → APPROVED or REJECTED. Approval stores the value with `exception = true` and publishes `setting.changed`, like a direct change. |

## Settings

| Key | Default | Strict direction |
|---|---|---|
| `postmortem.required_min_severity` | SEV2 | covers more severities |
| `postmortem.due_days` | 7 | fewer days |
| `alerts.auto_incident_min_severity` | SEV2 | covers more severities |
| `alerts.dedup_window_minutes` | 60 | none |
| `agents.auto_analysis` | true | none |
| `agents.auto_remediation` | false | false |
| `agents.patch_requires_approval` | true. A patch run of an agent waits in AWAITING_APPROVAL until a person approves or rejects it ([agents.md](agents.md)). | true |
| `timeline.auto_populate` | true | none |
| `mcp.write_enabled` | false | false |
| `agents.exchange_admin` | false (organization only). Turning it off clears every user's opt-in, see [identity.md](identity.md). | false |
| `incident.key_prefix` | INC (organization only) | none |
| `escalation.page_min_severity` | SEV3 | covers more severities ([escalation.md](escalation.md)) |
| `escalation.max_ack_minutes` | 30 | fewer minutes ([escalation.md](escalation.md)) |

## Ports

- `OrgSettingListener`: called after an organization-level value is set, removed or approved through a change request, on the instance that made the change. Identity implements it.

## Cache

- Each instance caches all values. `setting.changed` and `events.resync` clear the cache.
- PostgreSQL sends `events.resync` to the instance after its LISTEN connection connects again: changes made while it was gone are not lost.
- The cache keeps the generation it was read at. A change that arrives during a read makes that read stale, and the next read loads again.
- Negative: an administrator turns off `agents.auto_remediation` while one replica reconnects. That replica uses the new value after the reconnect, not the old one until a restart.

## Permissions

- Organization level: administrators.
- Project level: administrators and project admins.
- Team level: administrators, team admins and admins of the team's project.
- Enforcement and change-request decisions: administrators only.
- Change requests: any member.

## Positive use cases

- The administrator sets "postmortem from SEV2" as MINIMUM. The Payments project sets SEV3. Both are valid.
- A team admin tries SEV1 (weaker). Mayday refuses with `locked`. The interface offers "Request change". The administrator approves it as an exception for that team only.

## Negative use cases

- A team admin tries to lock a setting: `forbidden`.
- A member tries to change a team setting: `forbidden`.
- A value out of range or of the wrong type: `invalid`.
- `incident.key_prefix` at team level: `invalid`.
- `agents.exchange_admin` at project or team level: `invalid`.

## Audit

Policy records `setting.set` and `setting.removed` (value, previous value, enforcement) and `change_request.created`, `.approved`, `.rejected` to the audit stream. See [audit.md](audit.md).

## Rebuild checklist

Pass `PolicyTest` and `PolicyReplicaTest` (a direct change and an approved change request reach a second instance). Keep the resolution order and the meaning of MINIMUM. Invalidate caches on `setting.changed` and `events.resync`; pass `PolicyCacheTest` (a change during a read wins) and `PgEventBusContractTest`.
