# Directory

Package: `mayday.directory`. File: `Directory.kt`.

## Purpose

The directory holds projects and teams. Teams are responders for incidents and owners of alerts. Projects group teams and share settings.

## Model

- `Project`: id, unique slug, name, description.
- `Team`: id, unique slug, name, optional project, description, `memberGroups`, `ownerGroups` (identity provider group keys; see [provisioning.md](provisioning.md)).
- Slugs: lowercase letters, digits and dashes, at most 63 characters. Mayday makes a slug from the name when none is given.
- Lookups accept the id or the slug.

## Rules

- Only administrators create, change or delete projects and teams, and link identity provider groups to teams.
- Team membership is on the user (`User.teams`, see [identity.md](identity.md)). Team owners (team role ADMIN) manage it, unless identity provider groups manage the team.
- `freeSlug(name)` gives the slug for automatic teams: `payments`, then `payments-2` and so on.
- A project with teams cannot be deleted.

## Positive use cases

- An administrator creates the project Payments and the teams Checkout and Billing in it. Both teams inherit the project settings.
- An administrator links the Entra group of the Checkout engineers to the team Checkout.

## Negative use cases

- A duplicate slug: `conflict`.
- An invalid slug: `invalid`.
- Delete a project that still has teams: `conflict`.

## Rebuild checklist

Pass the team part of `StoreContract` (including group links) and the directory parts of `GrpcContractTest`.
