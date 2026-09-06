# Quick Start

Two ways to run OWASP Faction 2:

- **[Run a release](#run-a-release)** — `docker compose up -d`. A complete install
  (application, database, object storage) that needs nothing on the host but
  Docker. This is what you want for evaluating it, or for running it for real.
- **[Run from source](#run-from-source)** — `mise run up`. For working on the code.

---

## Run a release

### Prerequisites

Docker is the only requirement. The published images cover `linux/amd64` and
`linux/arm64`, so an Apple Silicon Mac, an Intel laptop and a cloud server all
pull the same tag.

```bash
docker --version            # 20.10 or newer
docker compose version      # v2 or newer
```

`docker compose` (a space, a Docker plugin) — not `docker-compose`, the retired
standalone tool. The v1 tool does not understand this file.

### Start it

The installer does everything in this section for you — prerequisites, images,
and a `.env` with generated secrets:

```bash
curl -fsSL https://raw.githubusercontent.com/factionsecurity/OWASP-Faction-2/main/install.sh | bash
```

It respects `FACTION_DIR`, `FACTION_VERSION`, `HTTP_PORT` and `PUBLIC_URL`,
starts nothing, and will not overwrite an existing `.env`. To do it by hand
instead:

```bash
curl -LO https://raw.githubusercontent.com/factionsecurity/OWASP-Faction-2/main/docker-compose.yml
curl -LO https://raw.githubusercontent.com/factionsecurity/OWASP-Faction-2/main/.env.example
cp .env.example .env
```

Then set the three secrets in `.env` — `JWT_SECRET`, `DATABASE_PASSWORD` and
`STORAGE_SECRET_KEY`. They ship with no defaults on purpose: Compose refuses to
start until you fill them in, rather than bringing your install up with a
password that is published in a public repository. `openssl rand -base64 48`
generates a good `JWT_SECRET`.

```bash
docker compose up -d
```

Open <http://localhost:8080> and sign in as `admin` / `admin123`.

**Change that password before anyone else can reach the install.** It is seeded
on first start and documented here, so it is not a secret.

First start takes a few minutes — it pulls the images, runs every database
migration, and seeds the default roles, users and vulnerability categories. Watch
it get there:

```bash
docker compose ps          # all four services should reach "healthy"
docker compose logs -f backend
```

The application is served over plain HTTP. Put it behind a reverse proxy that
terminates TLS before exposing it beyond a private network.

---

## Versions

### Which version am I running?

Three ways, in increasing order of how much they prove:

```bash
# What the running application reports — the authoritative answer.
curl -s http://localhost:8080/api/v1/status
# {"status":"ok","version":"2.0.1","startedAt":"...","uptime":"..."}
```

The same string appears in the **bottom right of the UI**, next to the copyright.

```bash
# What Compose thinks it started. Useful when the app will not come up at all.
docker compose images
```

If the version reads `dev`, you are running an image built from source rather
than a published release — see [Troubleshooting](#the-version-says-dev).

### Choosing a version

`.env` controls it:

```bash
FACTION_VERSION=latest      # default: track the newest full release
FACTION_VERSION=2.0.1       # or pin an exact version
```

`latest` never points at a pre-release, so tracking it will not move you onto a
beta. Pinning is the better choice for anything you depend on: an upgrade then
happens when you edit this line, not whenever you happen to run `pull`.

Version tags have no `v` prefix — the release tagged `v2.1.0` publishes as
`2.1.0`. Every published version is listed under
[Releases](https://github.com/factionsecurity/OWASP-Faction-2/releases).

### Upgrading

```bash
docker compose pull && docker compose up -d
```

`pull` is the step that does the work. **`docker compose restart` upgrades
nothing** — it restarts the containers you already have, from the images already
on disk. Likewise, `docker compose up -d` on its own will not fetch a newer
image for a tag it already has locally.

If you pinned `FACTION_VERSION`, edit it to the new version first; `pull` then
fetches that tag.

Your data lives in the `db_data` and `minio_data` volumes and is untouched by an
upgrade — the containers are replaced, the volumes are not. New database
migrations run automatically on the first start of the new version, which is why
that start is slower than usual.

### Rolling back

Set `FACTION_VERSION` back to the older version and run
`docker compose pull && docker compose up -d`.

This works when nothing has changed schema. Migrations only run forwards — there
are no down migrations — so an older application can meet a database newer than
it expects and fail on the columns it does not know about. Take a database
backup before upgrading anything you care about, and restore that backup if you
need to go back across a release that migrated.

---

## Run from source

For development. [mise](https://mise.jdx.dev/) pins the Java and Node versions
and provides the tasks; Docker still runs the database, object storage and PII
analyser.

```bash
mise run up
```

That brings up the containers, the backend on <http://localhost:8080> and the
frontend dev server on <http://localhost:3000>. First run is slow while Maven
downloads dependencies and Docker pulls images.

```bash
mise tasks                  # everything available

mise run backend-up         # backend only (starts the containers first)
mise run frontend-up        # frontend only
mise run database-up        # containers only
mise run database-down      # stop them

mise run backend-test       # backend suite (Testcontainers — needs Docker)
mise run frontend-test      # Playwright end-to-end tests
mise run test-all           # both

mise run backend-build      # mvn clean install
mise run frontend-build     # production frontend bundle
mise run backend-lint
mise run frontend-lint
```

A source build is stamped `dev` rather than a version — only the release
pipeline stamps a real one. See [README.md](README.md) for the equivalent
commands without mise, and [TESTING.md](TESTING.md) for the containerised UI
test environment.

---

## Troubleshooting

### Compose exits immediately complaining about a variable

```
error while interpolating services.backend.environment.JWT_SECRET: required
variable JWT_SECRET is missing a value: set JWT_SECRET in .env — see .env.example
```

Working as intended — one of the three secrets in `.env` is still blank. Fill it
in. Also check you are running from the directory holding `.env`; Compose reads
it from the working directory, not from wherever `docker-compose.yml` lives.

### `no matching manifest for linux/arm64/v8`

The version you pinned predates multi-architecture images and exists only for
`amd64`. Move to `2.0.1` or newer, which publish both.

### The version says `dev`

The running image was built locally rather than pulled, so no version was
stamped into it. A likely cause is a stale local image under the same tag —
Compose will use `…:latest` from disk without checking whether the registry has
a newer one. Force the fetch:

```bash
docker compose pull && docker compose up -d
```

### Port 8080 is already in use

Set `HTTP_PORT` in `.env` to something free and run `docker compose up -d`
again. It only affects the host side, so nothing else needs changing.

### A service never becomes healthy

```bash
docker compose ps                  # which one is stuck
docker compose logs backend        # and why
```

The backend has a generous 120-second start period because its first run
executes every migration. Genuine failures are usually the database refusing the
password (`DATABASE_PASSWORD` changed after the volume was created) or a
migration erroring — both are named explicitly in that log.

### Start over

Deletes the containers **and all data**, then rebuilds from scratch:

```bash
docker compose down -v
docker compose up -d
```

Omit `-v` to keep your data and only recreate the containers.
