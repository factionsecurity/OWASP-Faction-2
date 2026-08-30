# Contributing to OWASP Faction

Thanks for helping. This is an OWASP project: the code is Apache-2.0 and the
issue tracker is the place to start.

## Sign-off

Every commit needs a `Signed-off-by` line certifying the
[Developer Certificate of Origin](https://developercertificate.org/):

```
git commit -s -m "Fix the thing"
```

There is no CLA. The DCO says you have the right to contribute what you are
contributing, which is all we need.

## Getting it running

```bash
docker compose -f docker-compose.test.yml up -d   # TimescaleDB
cd backend  && mvn spring-boot:run
cd frontend && npm install && npm run dev
```

`SETUP.md` covers this in more detail, including the default accounts.

## Before you open a pull request

```bash
cd backend  && mvn clean test
cd frontend && npx tsc --noEmit && npm run build
```

The suite is green on `main` and CI enforces it, so a failure is a real one.

Two checks catch most review comments before a human sees them:

- **Every endpoint must make an explicit authorization decision** —
  `@RequiresPermission`, or `@AuthenticatedOnly` when any signed-in user may
  call it. `EndpointAuthorizationArchitectureTest` will name yours if you
  forget, and it also insists every `Permission` enum value is enforced
  somewhere, so the roles screen never offers a permission that does nothing.
- **Every new or changed entity column needs a Flyway migration.** Tests run
  `ddl-auto: create-drop` and rebuild the schema from the entities, so they can
  never catch a missing one — green tests do not mean a running database will
  survive your change. `CLAUDE.md` has the migration conventions.

Backend changes need a test. `CLAUDE.md` also documents the frontend
conventions worth knowing: the `Page` layout wrapper, `RichTextEditor`, and
`ConfirmDialog` for anything destructive.

## Where features live

Some capabilities are marked with a ◆ in the UI and are not part of this
project. You will see the seam in the code: `EditionPolicy`, `@RequiresFeature`,
and interfaces such as `ReplyMailboxProvider`, `AiCallObserver` and
`ReportEncryptor`, each with an implementation here.

That seam is deliberate and worth respecting: this project must build and pass
its tests on its own, and it does. Patches are welcome anywhere in it,
including those interfaces.

## Reporting a vulnerability

Please do not open a public issue. `SECURITY.md` has the disclosure process.
