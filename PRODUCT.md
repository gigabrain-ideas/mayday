# Mayday! Mayday!

Mayday-mayday is an AI-first, streamlined and easy-to-operate enterprise-grade incident management platform designed to be self-hosted in cloud environments or on bare-metal servers. It supports SSO and On-behalf-of auth scenarios.

At the core, as any other incident platform, it handles incidents and alerts. It has a web interface which is served and includes admin enterprise-level configuration options + incident reports pages for teams or organizations (select all or multiple teams and time periods + severity).

Incidents have a name, incident identifier (a monotonously increasing counter as incidents happen) and responder team(s). Each incident has a description of it, start/end of impact, discovery time, report time etc; A post-mortem can be obligatory or mandatory. Rules can be defined in the project or team settings, depending on the scope required (user configurable). Enterprise admins can enforce minimum settings or config that is not able to be changed by lesser roles (but there's a button to request change for the team/project/whatever scope).

The benefit of Mayday-mayday is its agentic-first approach. A few use-case scenarios supported:
- triggering of an alert can kick-off auto-analysis (or even correction) using an agent configured to act on the alert (configured within Mayday, push and pull configs supported)
- A human can manually trigger analysis or a patch/bugfix creation with a configured agent by a button press in Mayday. The agent, when manually triggered, must be given the token from the user triggering it to be able to act "on behalf of" them (respecting the RBAC of the user who triggered the analysis).
- Timelines for incidents and updates for alerts are auto-populated if an LLM provider is setup within the system config + tool gateway/integration for MCPs to be exposed to the internal incident management agent, or if an external agent is configured to take on that task and push the updates to the timeline using the provided MCP tools to that agent.
- Mayday-mayday exposes an MCP server allowing agents to read (always) and write (configurable in its settings). Admin functions must be RBAC'ed, so either the agent's identity must have that permission or the person using the agent must provide their identity.
- postmortems can be auto-populated and shown in the web UI based on the available data for the configured internal agent (if LLM provider is setup) or for an external agent.
- Humans or agents can spell out how they solved the issue inside a prompt/section on the page. They can dictate (voice) or write (keyboard/stylus like apple pencil). A runbook (or standard operating procedure) can be auto-generated from this at a press of a button (requires the internal agent or a configured handoff system for this).
- if sufficient tools are provided (via MCPs or APIs), runbooks can be OK-ed for agentic use, and an external agent or Mayday-mayday's internal one can act when alerts are triggered. If they fail or encounter uncharted territory (not matching the runbook), they raise to a human.

## Design and UX directions

Use shadcn. Wording is professional but friendly. Go grayscale (B&W) and introduce colors only to signal importance and severity. Red = Error, Green = solved / clear, Yellow = warning / needs attention. ASD-STE100 is used in technical texts and runbooks.

## Scale
Applicable and usable from the tiny startup to the Apple/Microsoft/Google/Amazon teams scale. Performant and scalable.
