# Landing page

Directory: `site/`. A static sales page for GitHub Pages (`<owner>.github.io/<repository>/`). Mayday is a commercial product: the page sells a guided evaluation and links to sales, not to the repository. Plain HTML, CSS and a small script. No build step, no framework, no tracking, no third-party requests.

## Files

| File | Content |
|---|---|
| `site/index.html` | All sections and an inline Lucide icon sprite |
| `site/style.css` | Design tokens copied from `web/src/index.css`, then page styles |
| `site/main.js` | Theme toggle, tabs (arrow keys), copy buttons, entrance on scroll |
| `site/fonts/` | JetBrains Mono, latin subset, variable weight (SIL OFL) |
| `site/screenshots` | Symbolic link to `docs/screenshots`. The README and the page use the same images. |
| `.github/workflows/pages.yml` | Checks and deploys the page on each push to `main` that changes `site/` or the screenshots |
| `Makefile` `site-check` | Fails when the page shows internal or forbidden names |

## Deployment

1. In the repository on GitHub, open Settings → Pages and set Source to **GitHub Actions**.
2. Push to `main`. The workflow runs `make site-check`, copies `site/` with the screenshots (it follows the link), and deploys. It stops when the check fails.

For a user or organization site (`<owner>.github.io` without a path), name the repository `<owner>.github.io`. All paths in the page are relative, so both forms work.

Local preview:

```bash
python3 -m http.server 4173 --directory site
```

"Request an evaluation" and "Contact sales" link to `#` (no target yet). Set the target when the sales channel is decided.

## Internal names are forbidden

The page is for customers. It never shows internal names or links into the repository. `make site-check` enforces this locally and in the deploy workflow, and blocks:

- decision record names and numbers (for example "ADR" with a number), "decision records", "product brief";
- repository instruction and documentation files and paths (`PRODUCT.md`, `AGENTS.md`, `docs/`), test class names, source file names;
- repository links (`github.com`);
- "factory" and "shop floor".

Name the capability in customer words (for example "identity model", "audit stream schema"). Documents go to customers in the evaluation package, not as links. Do not weaken or bypass the check; change its list only with the product owner's approval.

## Design rules

The same rules as the web interface ([web.md](domains/web.md#design-rules)): grayscale, color only for severity and state with a second signal, one monospace font, uppercase labels with wide tracking, dotted halftone background, floating translucent surfaces, full radius for actions, ease-out motion of 150 to 300 ms. One primary button per view.

The page follows the device:

- Theme: the system light or dark setting, also when it changes while the page is open. The toggle keeps a choice in `mayday.theme` (the same key as the web interface) until the system setting changes again.
- Size: one column on phones; two columns from 900 px; larger text and width from 1680 px and 2400 px. No horizontal scrolling at any width (grid children have `min-width: 0`; code samples scroll inside their card).

Copy: positive statements only. Say what Mayday does. Never pair a statement with its negative ("X, not Y", "without X", "never more", "nothing it does not") and never compare with a "without Mayday" case.

Audience: software and IT operations. The page does not mention factories or the shop floor.

## Audience

CISOs, directors of engineering and CTOs at companies whose revenue runs on software. They know architecture, cloud and identity well, lead 100+ people and need uptime. A marketing review and a sales review (2026-11-26) shaped the page: lead with the identity and audit model, answer the security review on the page, give each buyer a reason, and give an evaluation path.

## Sections

| Order | Section | Source of truth |
|---|---|---|
| 1 | Hero: "AI agents on every incident, with your rights and your audit trail." Proof strip. | README, [identity](domains/identity.md), [audit](domains/audit.md), architecture.md "Scale" |
| 2 | Tools strip | README, [audit](domains/audit.md) sinks (Splunk and Elastic through the HTTP sink) |
| 3 | Security model: four identity cards, decoded token (`details`), audit evidence with ISO/IEC 27001:2022 controls, audit stream sample, open standards | [identity](domains/identity.md), [agents](domains/agents.md) "Untrusted data", [audit](domains/audit.md), architecture.md "Security" |
| 4 | Budget-aware agents (HTTP 429 cooldown) | [agents](domains/agents.md), [gateway identity](domains/gateway-identity.md#http-429-cooldown) |
| 5 | Why Mayday: one card each for the CISO, the engineering leader, the CTO | All of the above, [reporting](domains/reporting.md), [escalation](domains/escalation.md) |
| 6 | SEV1 example, five steps (same times as the hero) | README "Example: a SEV1 with an agent" |
| 7 | Lifecycle tabs | [alerting](domains/alerting.md), [agents](domains/agents.md), [on-call](domains/oncall.md), [reporting](domains/reporting.md) |
| 8 | Architecture: design target, scale facts, storage modes, "Our team installs it with you" | architecture.md "Scale", [deployment.md](deployment.md) |
| 9 | Guided evaluation in an afternoon: four steps, what we need from you, what you have at the end | [deployment.md](deployment.md), [identity](domains/identity.md), [audit](domains/audit.md) `audit-verify` |
| 10 | Due diligence (documents in the evaluation, no links) and "Tested at every boundary" | [testing.md](testing.md), docs index |
| 11 | FAQ (`details`) | The sources above, [api.md](api.md) |
| 12 | Contact: "Request an evaluation" and "Contact sales" (link to `#` until the sales channel is decided) | Sales |

Rules for claims: scale numbers are a "design target". The page sells a guided evaluation and does not invite visitors to install Mayday alone. It claims no open source license, customers, certifications, prices or SLAs.

When one of these sources changes, change the page in the same commit.

## Positive use cases

- An engineering leader requests an evaluation, prepares the five items, and sees Mayday live in the company cluster at the end of the afternoon.
- A security reviewer reads the decoded delegated token, the two opt-ins, the audit chain and the identity brief.
- A platform owner reads that agents pause for the stated cooldown on HTTP 429 and continue with new work after it.
- A CISO reads the security model, opens the decoded token and forwards the due diligence links to the security team.
- A visitor with dark mode sees the dark theme. The toggle keeps the choice.
- A visitor with reduced motion sees no entrance or pulse animation.

## Negative use cases

- JavaScript is off: all content is visible, the first tab of each tab list shows, theme follows the system.
- The page claims no customers, numbers or certifications that Mayday does not have.
- A screenshot changes in `docs/screenshots`: the page shows the new one at the next deploy with no edit.
