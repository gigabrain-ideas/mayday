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
| `/alerts`, `/alerts/:id` | Alert list and detail with acknowledge, resolve, updates, link to incident, agent buttons |
| `/reports` | Period presets and custom range, multi-select teams and severities, stat tiles, chart, team table, longest incidents |
| `/settings/*` | Policies (scoped, enforcement, request change), change requests, agents, LLM provider, MCP (server info and tool gateway), teams and projects, users, API tokens |

## Behavior

- Live updates: one `EventService.Subscribe` stream per browser tab. It reconnects when the stream ends. Pages reload only the data an event concerns. `ping` events keep the connection open.
- Voice: a microphone button on each long text field uses the Web Speech API. Stylus input (for example Apple Pencil Scribble) works in all text fields.
- Keyboard: `g i`, `g a`, `g r`, `g s` go to incidents, alerts, reports, settings. `Ctrl/⌘ + Enter` posts a timeline update.
- Viewers see no write controls.

## Positive use cases

- During an incident, the commander keeps the incident page open. Agent findings and teammates' notes appear without reload.
- A team admin opens Settings → Policies, sees a lock on a setting and sends a change request.

## Negative use cases

- A session expires: the next call returns `UNAUTHENTICATED` and the login page appears.
- The browser has no speech recognition: the microphone button is hidden.

## Rebuild checklist

`npm run build` (it runs `npm run gen` first) must pass type checking. The server side of the browser protocol is in `GrpcWebContractTest`. Do not edit `src/gen`; change `proto/` and generate again.
