# Web interface

Directory: `web/`. Stack: React, TypeScript, Vite, Tailwind CSS, shadcn-style components on Radix primitives, Lucide icons. The server serves the built files from the classpath or from `MAYDAY_WEB_DIR`.

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

- Live updates: one `EventSource` to `/api/v1/events`. Pages reload only the data an event concerns.
- Voice: a microphone button on each long text field uses the Web Speech API. Stylus input (for example Apple Pencil Scribble) works in all text fields.
- Keyboard: `g i`, `g a`, `g r`, `g s` go to incidents, alerts, reports, settings. `Ctrl/⌘ + Enter` posts a timeline update.
- All mutating requests send `X-Requested-With: mayday`.
- Viewers see no write controls.

## Positive use cases

- During an incident, the commander keeps the incident page open. Agent findings and teammates' notes appear without reload.
- A team admin opens Settings → Policies, sees a lock on a setting and sends a change request.

## Negative use cases

- A session expires: the next request returns 401 and the login page appears.
- The browser has no speech recognition: the microphone button is hidden.

## Rebuild checklist

`npm run build` must pass type checking. Keep the API client in `src/lib/api.ts` aligned with [api.md](../api.md).
