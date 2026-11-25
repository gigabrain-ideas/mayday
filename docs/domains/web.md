# Web interface

Directory: `web/`. Stack: React, TypeScript, Vite, Tailwind CSS, shadcn-style components on Radix primitives, Lucide icons. The server serves the built files from the classpath (`static/`, copied from `web/dist` at `bootJar`) or from `MAYDAY_WEB_DIR`.

## Back-end access

- The interface talks to the back end with gRPC-Web on the same origin (`/mayday.v1.<Service>/<Method>`, port 8080). See [api.md](../api.md#grpc-web).
- `npm run gen` generates typed clients from `../proto` into `src/gen` (buf). The Docker and Makefile builds run it, so `proto/` must be next to `web/`.
- A generic converter maps protobuf messages to the domain JSON shape and back: field names are the same, enum prefixes are removed (`SEVERITY_SEV1` → `SEV1`), timestamps become ISO-8601 strings. Pages use the domain types and do not know protobuf.
- Authentication: the session cookie. gRPC-Web needs no `X-Requested-With` header, because its content type forces a CORS preflight. Sign-in and sign-out use the HTTP endpoints `/auth/login`, `/auth/dev-login` and `/auth/logout`.
- Errors: the client reads `mayday-error-code` from the trailers and shows the status message.

## Design rules

From [PRODUCT.md](../../PRODUCT.md), then [Ztyle](https://izzardry.github.io/ztyle/#layout):

- Grayscale. Color signals importance only: red = error or critical, yellow = warning or needs attention, green = solved. Each color has a second signal (icon or label).
- One monospace font (JetBrains Mono, bundled), tabular numbers, no ligatures. Labels and buttons in uppercase with wide tracking.
- Dotted halftone background. Floating translucent sidebar and cards. Radii: full for actions, 2xl for panels, lg for inputs.
- One primary button per screen. Secondary actions are outline or ghost.
- No "are you sure?" dialogs. Deletes and revocations show an Undo toast for 5 seconds before they run.
- Ease-out motion, 150 to 300 ms. Reduced motion is respected. Light and dark themes.
- Charts: severity is ordinal, so charts use one gray ramp (darkest = SEV1) with a legend and a hover tooltip.
- Professional and friendly text in ASD-STE100 style.

## Pages

| Route | Content |
|---|---|
| `/incidents` | Open or all, filters by severity and team, search, keyset "load more", "Declare incident" |
| `/incidents/:key` | Status, severity and teams; tabs Timeline, Resolution, Postmortem, Runbook, Agent runs; agent buttons; details with editable times; linked alerts |
| `/alerts`, `/alerts/:id` | Alert list and detail with acknowledge, resolve, updates, link to incident, agent buttons, escalation (status, pages per level, time of the next level, "Escalate now") |
| `/oncall?team=<slug>` | Who is on call now for each schedule; for one team: schedules with a 14-day plan (overrides in yellow, nobody on call in red), overrides (add, remove), members (team owners add, remove and promote; locked when identity provider groups manage the team). Team members can add only themselves as override. Escalation policies of the team: levels with schedules and people, minutes to acknowledge, repeat, team default (team owners create, change and delete). |
| `/reports` | Period presets and custom range, multi-select teams and severities, stat tiles, chart, team table, longest incidents |
| `/settings/*` | Policies (scoped, enforcement, request change), change requests, agents, LLM provider, MCP (server info and tool gateway), teams and projects (administrators link identity provider groups), users, API tokens (administrators can add the SCIM scope), audit (administrators: health of the audit stream and each sink, what is recorded, verify command) |

## Behavior

- Live updates: one `EventService.Subscribe` stream per browser tab. It reconnects when the stream ends. Pages reload only the data an event concerns. `ping` events keep the connection open. The client adds two signals: `live.connected` (first ping of each stream; events sent while it was down are lost, so lists and counts reload) and `live.disconnected`.
- The Alerts badge in the sidebar is the exact number of firing alerts (`CountAlerts`), not the length of a page. When the server does not answer or the live stream is down, the badge shows no number: an unknown count is never shown as a stale one.
- A list that fails to load shows "Mayday could not load the …" with the reason and "Try again". It never shows the empty state ("No alerts") in place of an error.
- Voice: a microphone button on each long text field uses the Web Speech API. Stylus input (for example Apple Pencil Scribble) works in all text fields.
- Keyboard: `g i`, `g a`, `g o`, `g r`, `g s` go to incidents, alerts, on-call, reports, settings. `Ctrl/⌘ + Enter` posts a timeline update.
- Viewers see no write controls.

## Positive use cases

- During an incident, the commander keeps the incident page open. Agent findings and teammates' notes appear without reload.
- A team admin opens Settings → Policies, sees a lock on a setting and sends a change request.
- An administrator opens Settings → Audit and sees that the Sentinel sink retries after an error and has lost no record.
- A member is sick. A colleague opens On-call, selects the team and adds themselves as override until Friday. The "now" card shows the colleague and an Override badge on all open browsers.

- A team owner opens On-call, creates the policy "Default" with Primary (5 minutes), Secondary (10 minutes) and themselves, and marks it as the team default.
- A responder opens a firing alert, sees that level 1 was paged 4 minutes ago and presses "Escalate now".

## Negative use cases

- The server stops while a tab is open. The Alerts page shows "Mayday could not load the alerts" and the badge shows no number. When the server is back, the stream reconnects and both reload.

- A session expires: the next call returns `UNAUTHENTICATED` and the login page appears.
- The browser has no speech recognition: the microphone button is hidden.
- A member opens `/settings/audit`: the Audit tab is not shown and the status call returns `PERMISSION_DENIED`.

## Rebuild checklist

`npm run build` (it runs `npm run gen` first) must pass type checking. The server side of the browser protocol is in `GrpcWebContractTest`. The main flows in the browser are in `UseCaseTest` (`make e2e`). Do not edit `src/gen`; change `proto/` and generate again.
