# Identity and access

Package: `mayday.identity`. File: `Identity.kt`. Adapters: `adapters/auth` (OIDC, sessions).

## Purpose

Identity knows who makes a request and what that identity can do. It supports single sign-on (OIDC), API tokens, agent identities and on-behalf-of (delegated) access.

## Model

| Element | Rules |
|---|---|
| `User` | Email (unique), name, `orgRole` (VIEWER, MEMBER, ADMIN), team roles (MEMBER, ADMIN), project admin set, external id from SSO, disabled flag. |
| `ApiToken` | `mdy_` + random secret. Mayday stores only the SHA-256 hash. Owner is a user or an agent. Scopes: READ, WRITE, ADMIN, ALERTS_INGEST. Optional expiry. Only administrators get the ADMIN scope. |
| `Principal` | `actor` (who acts), `user` (whose RBAC applies), `agentRole`, `scopes`, optional `runId`. Delegated = actor is an agent and user is set. |
| Delegated token | HS256 JWT: `typ=obo`, `sub`=user id, `act.sub`=`agent:<id>`, `run`, `scope`, `exp`. A run token has the scopes READ and WRITE only, never ADMIN or ALERTS_INGEST. A token with a `run` claim works only while that run is not finished. |
| Session | Signed cookie, 12 hours by default. Stateless. |

## Authentication paths

1. Browser: OIDC authorization code flow with PKCE → session cookie. The browser sends the cookie with each gRPC-Web call.
2. `Authorization: Bearer mdy_...` (HTTP header or gRPC metadata `authorization`): API token of a user or agent.
3. `Authorization: Bearer <delegated JWT>`: agent on behalf of a user.
4. `Authorization: Bearer <IdP JWT>`: an access token from the configured issuer (validated with JWKS, audience = client id or `MAYDAY_OIDC_AUDIENCES`). This lets an agent act on behalf of the user with the user's own SSO token.
5. `POST /oauth/token` (RFC 8693 token exchange): agent token + user token → delegated token.
6. Development only: `MAYDAY_DEV_LOGIN=true` allows sign-in with an email.

The first user, and every email in `MAYDAY_ADMIN_EMAILS`, becomes an administrator.

## Access rules (`Access`)

- Read: READ scope.
- Write: WRITE scope and role MEMBER or higher.
- Admin: ADMIN scope and role ADMIN.
- Team admin: team role ADMIN, project admin of the team's project, or admin.

## Positive use cases

- An engineer signs in with the company OIDC provider. Mayday creates the user at the first login.
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

## Rebuild checklist

Pass `IdentityTest`, the credential, token and scope parts of `GrpcContractTest`, the sign-in and token-exchange parts of `HttpEdgesContractTest`, and the token part of `StoreContract`.
