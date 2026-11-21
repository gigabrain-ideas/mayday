# Identity and access

Package: `mayday.identity`. File: `Identity.kt`. Adapters: `adapters/auth` (OIDC, sessions).

## Purpose

Identity knows who makes a request and what that identity can do. It supports single sign-on (OIDC), API tokens, agent identities and on-behalf-of (delegated) access.

## Model

| Element | Rules |
|---|---|
| `User` | Email (unique), name, `orgRole` (VIEWER, MEMBER, ADMIN), team roles (MEMBER, ADMIN; ADMIN = team owner), project admin set, external id from SSO, disabled flag, `allowAgentAdmin` (default false), `idpGroups` (last groups claim), `scimGroups` (SCIM group keys), `scimExternalId`. Groups never leave the server. |
| `ApiToken` | `mdy_` + random secret. Mayday stores only the SHA-256 hash. Owner is a user or an agent. Scopes: READ, WRITE, ADMIN, ALERTS_INGEST, SCIM. Optional expiry. Only administrators get the ADMIN and SCIM scopes. Token exchange never passes SCIM on. |
| `Principal` | `actor` (who acts), `user` (whose RBAC applies), `agentRole`, `scopes`, optional `runId`. Delegated = actor is an agent and user is set. |
| Delegated token | HS256 JWT: `typ=obo`, `sub`=user id, `act.sub`=`agent:<id>`, `run`, `scope`, `exp`. A run token has the scopes READ and WRITE only, never ADMIN or ALERTS_INGEST. A token with a `run` claim works only while that run is not finished. |
| Session | Signed cookie, 12 hours by default. Stateless. |

## Authentication paths

1. Browser: OIDC authorization code flow with PKCE → session cookie. The browser sends the cookie with each gRPC-Web call.
2. `Authorization: Bearer mdy_...` (HTTP header or gRPC metadata `authorization`): API token of a user or agent.
3. `Authorization: Bearer <delegated JWT>`: agent on behalf of a user.
4. `Authorization: Bearer <IdP JWT>`: an access token from the configured issuer (validated with JWKS, audience = client id or `MAYDAY_OIDC_AUDIENCES`). This lets an agent act on behalf of the user with the user's own SSO token.
5. `POST /oauth/token` (RFC 8693 token exchange): agent token + user token → delegated token. The delegated token has the scopes of the user token without ADMIN. It keeps ADMIN only when the organization setting `agents.exchange_admin` is true and the user has `allowAgentAdmin`.
6. Development only: `MAYDAY_DEV_LOGIN=true` allows sign-in with an email.

The first user, and every email in `MAYDAY_ADMIN_EMAILS`, becomes an administrator.

## Admin rights through token exchange

- The organization setting `agents.exchange_admin` (organization level only, administrators only) allows it. Default: false.
- Each administrator turns `allowAgentAdmin` on for themselves (`UserService.SetAgentAdmin`, Settings → API tokens). Default: false.
- Only the person changes their own value, with their session or their own token. A delegated or agent principal gets `forbidden`, so an agent cannot give itself admin rights.
- Only administrators can turn it on, and only while the organization setting is true. Anyone can turn it off.
- When the organization setting becomes false (set to false, or the organization value is removed, also through an approved change request), Mayday sets `allowAgentAdmin` to false for every user. After the organization allows it again, each administrator must opt in again. Policy calls the `OrgSettingListener` port on the instance that made the change; identity implements it with `UserRepository.withAgentAdmin()`.
- Other organization changes, and saving `true` again, keep the opt-ins.
- Run tokens (agent buttons) never have ADMIN. These settings change token exchange only.

## Team members

- `ListTeamMembers`: everyone with READ.
- `SetTeamMember`: team owners (team admin, project admin, administrator) add, remove and promote. `UpdateUser` with `replace_teams` stays for administrators.
- Identity provider groups win. When a team has member groups, adding or removing members by hand is a `conflict`. When it has owner groups, changing the owner role by hand is a `conflict`. Both rules apply to `UpdateUser` too.
- Each removal (by hand or by group sync) calls the `TeamMembershipListener` port. On-call implements it: the person leaves the team's rotations.
- `upsertSso` takes the groups of the sign-in and calls the `GroupMembershipSync` port. Provisioning implements it ([provisioning.md](provisioning.md)).

## Access rules (`Access`)

- Read: READ scope.
- Write: WRITE scope and role MEMBER or higher.
- Admin: ADMIN scope and role ADMIN.
- Team admin: team role ADMIN, project admin of the team's project, or admin.

## Positive use cases

- An engineer signs in with the company OIDC provider. Mayday creates the user at the first login.
- A team owner adds a new colleague to the team and makes a senior engineer a second owner.
- An Alertmanager uses a token with only ALERTS_INGEST.
- A coding agent exchanges its token and the engineer's token for a delegated token and acts as the engineer.

## Negative use cases

- A revoked or expired token: `unauthenticated`.
- A token with the wrong signature or from another issuer: `unauthenticated`.
- A disabled user: `forbidden`.
- An administrator starts a run. The agent tries an admin action with the delegated token: `forbidden` (the token has no ADMIN scope).
- An agent uses a delegated token after its run is finished: `unauthenticated`.
- An agent tries to create tokens for a user, or to start another agent: `forbidden`.
- An administrator tries to remove their own admin role: `conflict`.
- A team member adds someone to the team: `forbidden`. A team owner changes the members of another team: `forbidden`.
- A team owner adds a member to a team that identity provider groups manage: `conflict`.
- An administrator exchanges their token while `agents.exchange_admin` or their own `allowAgentAdmin` is false: the delegated token has no ADMIN scope.
- An administrator turns on `allowAgentAdmin` while the organization does not allow it: `forbidden`.
- A member turns on `allowAgentAdmin`: `forbidden`.
- An agent with a delegated token calls `SetAgentAdmin`: `forbidden`.
- The organization turns `agents.exchange_admin` off and on again. Earlier opt-ins are gone; the exchanged token has no ADMIN until the administrator opts in again.

## Rebuild checklist

Pass `IdentityTest`, `TeamMembersTest`, `AgentAdminExchangeTest`, `AgentAdminOptInResetTest`, the agent admin part of `StoreContract`, `DelegationLimitsTest`, the credential, token and scope parts of `GrpcContractTest`, the sign-in and token-exchange parts of `HttpEdgesContractTest`, and the token part of `StoreContract`.
