# Changelog

Notable changes to OWASP Faction 2, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Every release is also tagged and published on the [releases
page](https://github.com/factionsecurity/OWASP-Faction-2/releases). Security
fixes are called out under a **Security** heading here and, where a
vulnerability was reported against a released version, in a [security
advisory](https://github.com/factionsecurity/OWASP-Faction-2/security/advisories).

## [Unreleased]

### Added
- A first-run installer (`install.sh`) that checks prerequisites, pulls the
  published images, and generates `.env` secrets with `openssl rand`
  ([#17](https://github.com/factionsecurity/OWASP-Faction-2/pull/17),
  [#19](https://github.com/factionsecurity/OWASP-Faction-2/pull/19)).
- Scheduling: assessor availability, a findable Create button, and a readable
  calendar ([#18](https://github.com/factionsecurity/OWASP-Faction-2/pull/18)).
- A published security policy, code of conduct, changelog, and CodeQL static
  analysis over both languages — see
  [documentation/openssf-best-practices.md](documentation/openssf-best-practices.md).

### Fixed
- Release images are published as multi-architecture manifests covering
  `linux/amd64` and `linux/arm64`, so one tag serves an Intel server and an
  Apple Silicon laptop ([#16](https://github.com/factionsecurity/OWASP-Faction-2/pull/16)).

### Security
- No security fixes in this range.

## [2.0.1] — 2026-09-06

### Fixed
- One version string reaches both Docker Hub and the UI footer, instead of a
  `v`-prefixed tag and an unprefixed one drifting apart
  ([#15](https://github.com/factionsecurity/OWASP-Faction-2/pull/15)).
- The release workflow uses the Docker Hub secret names the repository
  actually has ([#14](https://github.com/factionsecurity/OWASP-Faction-2/pull/14)).

### Security
- No security fixes in this release.

## 2.0.0 — 2026-08-30

First release of the Faction 2 line (not separately tagged; it is the history
leading to 2.0.1): a Spring Boot API and React frontend,
published as two Docker images and run with `docker compose`.

### Added
- **Assessments** — scheduling, checklists, surveys, notebooks, campaigns, and
  a calendar view.
- **Findings** — CVSS scoring, a reusable default-vulnerability library, OWASP
  Top 10 categories, exceptions, comments, and evidence attachments.
- **Peer review** — a review queue with tracked changes and diffs.
- **Retests** — scheduling, activity logs, and per-stage remediation tracking.
- **Reporting** — DOCX templates through a visual designer, rendered to DOCX
  and PDF, with `${pageBreak}` and `${assetLocation}` template variables.
- **AI assistance** — bring your own provider (OpenAI, Anthropic, Azure
  OpenAI, OpenRouter, or any OpenAI-compatible endpoint), with PII
  anonymisation before anything leaves the network.
- **Extensions** — a JAR-based App Store SDK for hooking into vulnerability,
  report, and assessment events.
- **Notifications** — in-app and email, with per-user preferences, reply by
  email, and unsubscribe.
- Mermaid diagrams, content templates, rich paste, and code blocks in the rich
  text editor.
- Per-installation naming for severities and organizations
  ([#8](https://github.com/factionsecurity/OWASP-Faction-2/pull/8),
  [#10](https://github.com/factionsecurity/OWASP-Faction-2/pull/10)).

### Security
- Every endpoint makes an explicit authorization decision, enforced by
  `EndpointAuthorizationArchitectureTest` — the build fails when one does not.
- Disabling a user now actually terminates their access
  ([#4](https://github.com/factionsecurity/OWASP-Faction-2/pull/4)).
- A 500 leaking behaviour to unrestricted callers listing sub-organizations is
  fixed ([#9](https://github.com/factionsecurity/OWASP-Faction-2/pull/9)).
- Platform hardening across image lifecycle and admin configuration
  ([#6](https://github.com/factionsecurity/OWASP-Faction-2/pull/6)).

[Unreleased]: https://github.com/factionsecurity/OWASP-Faction-2/compare/2.0.1...HEAD
[2.0.1]: https://github.com/factionsecurity/OWASP-Faction-2/releases/tag/2.0.1
