# Directory

Package: `mayday.directory`. File: `Directory.kt`.

## Purpose

The directory holds projects and teams. Teams are responders for incidents and owners of alerts. Projects group teams and share settings.

## Model

- `Project`: id, unique slug, name, description.
- `Team`: id, unique slug, name, optional project, description.
- Slugs: lowercase letters, digits and dashes, at most 63 characters. Mayday makes a slug from the name when none is given.
- Lookups accept the id or the slug.

## Rules

- Only administrators create, change or delete projects and teams.
- A project with teams cannot be deleted.

## Positive use cases

- An administrator creates the project Payments and the teams Checkout and Billing in it. Both teams inherit the project settings.

## Negative use cases

- A duplicate slug: `conflict`.
- An invalid slug: `invalid`.
- Delete a project that still has teams: `conflict`.

## Rebuild checklist

Pass the team part of `StoreContract`.
