# OpenSSF Best Practices — answer sheet

Every URL the [OpenSSF Best Practices passing
badge](https://www.bestpractices.dev/en/criteria/0) asks for, with the
criterion it answers. Fill the form at
<https://www.bestpractices.dev/en/projects/new> by pasting from here.

Keep this file current: a criterion whose URL 404s fails the badge's automated
re-check, and the badge re-checks.

## Project identity

| Form field | Value |
| --- | --- |
| Badge project | https://www.bestpractices.dev/en/projects/14465 (registered; the badge is in the README) |
| Project name | OWASP Faction 2 |
| Project homepage URL | https://owasp.org/www-project-faction/ |
| Project source repository URL | https://github.com/factionsecurity/OWASP-Faction-2 |
| Programming languages | Java, TypeScript |
| CPE / package | `factionsecurityllc/faction-backend`, `factionsecurityllc/faction-frontend` on Docker Hub |

## Basics

| Criterion | URL to give |
| --- | --- |
| `description_good` | https://github.com/factionsecurity/OWASP-Faction-2#readme |
| `interact` | https://github.com/factionsecurity/OWASP-Faction-2/issues |
| `contribution` | https://github.com/factionsecurity/OWASP-Faction-2/blob/main/CONTRIBUTING.md |
| `contribution_requirements` | https://github.com/factionsecurity/OWASP-Faction-2/blob/main/CONTRIBUTING.md#before-you-open-a-pull-request |
| `floss_license`, `license_location` | https://github.com/factionsecurity/OWASP-Faction-2/blob/main/LICENSE (Apache-2.0, plus [NOTICE](https://github.com/factionsecurity/OWASP-Faction-2/blob/main/NOTICE)) |
| `documentation_basics` | https://github.com/factionsecurity/OWASP-Faction-2/blob/main/documentation/README.md |
| `documentation_interface` | https://github.com/factionsecurity/OWASP-Faction-2#api-documentation — OpenAPI 3 is served by the running app at `/v3/api-docs` with Swagger UI at `/swagger-ui/index.html`; the permission required by every endpoint is documented at https://github.com/factionsecurity/OWASP-Faction-2/blob/main/documentation/permissions.md |
| `sites_https` | Both the homepage and the repository are HTTPS-only; GitHub and owasp.org redirect HTTP and send HSTS. |
| `discussion` | https://github.com/factionsecurity/OWASP-Faction-2/issues (and the OWASP project page's channels) |
| `english` | Repository, issues, and documentation are in English. |
| `maintained` | Active — see https://github.com/factionsecurity/OWASP-Faction-2/commits/main |

## Change control

| Criterion | URL / answer |
| --- | --- |
| `repo_public` | https://github.com/factionsecurity/OWASP-Faction-2 |
| `repo_track`, `repo_interim`, `repo_distributed` | Git on GitHub; every change is a commit on a public branch. |
| `version_unique`, `version_semver` | Semantic versioning; the release tag is also the Docker Hub tag and the version the UI footer renders — one string, resolved once in `.github/workflows/release.yml`. |
| `version_tags` | https://github.com/factionsecurity/OWASP-Faction-2/tags |
| `release_notes` | https://github.com/factionsecurity/OWASP-Faction-2/blob/main/CHANGELOG.md (per-release notes also at https://github.com/factionsecurity/OWASP-Faction-2/releases) |
| `release_notes_vulns` | The CHANGELOG carries a **Security** heading per release, and fixed vulnerabilities are published at https://github.com/factionsecurity/OWASP-Faction-2/security/advisories |

## Reporting

| Criterion | URL / answer |
| --- | --- |
| `report_process`, `report_tracker` | https://github.com/factionsecurity/OWASP-Faction-2/issues |
| `report_archive` | https://github.com/factionsecurity/OWASP-Faction-2/issues?q=is%3Aissue (public and searchable, open and closed) |
| `report_responses`, `enhancement_responses` | Answer from the tracker's own history — the badge wants "most reports in the last 2–12 months got a response". |
| `vulnerability_report_process` | https://github.com/factionsecurity/OWASP-Faction-2/blob/main/SECURITY.md |
| `vulnerability_report_private` | https://github.com/factionsecurity/OWASP-Faction-2/security/advisories/new — GitHub private vulnerability reporting (enabled), private to reporter and maintainers, no key exchange needed. Alternative private channel: develop@factionsecurity.com |
| `vulnerability_report_response` | SECURITY.md commits to acknowledgement in 3 working days and an assessment in 14. |

## Quality

| Criterion | URL / answer |
| --- | --- |
| `build`, `build_common_tools`, `build_floss_tools` | Maven and npm, both FLOSS: https://github.com/factionsecurity/OWASP-Faction-2/blob/main/CONTRIBUTING.md#getting-it-running |
| `installation_common` | https://github.com/factionsecurity/OWASP-Faction-2/blob/main/DOCKER_QUICK_START.md and https://github.com/factionsecurity/OWASP-Faction-2/blob/main/SETUP.md |
| `test`, `test_invocation` | https://github.com/factionsecurity/OWASP-Faction-2/blob/main/TESTING.md — `mvn clean test` in `backend/`, `npm test` in `frontend/` |
| `test_continuous_integration` | https://github.com/factionsecurity/OWASP-Faction-2/actions/workflows/ci.yml — every push to main and every pull request runs the backend suite (Testcontainers), the frontend type check and build, and a DCO check |
| `test_policy`, `tests_are_added` | "Every backend feature addition or change requires a test" — https://github.com/factionsecurity/OWASP-Faction-2/blob/main/CLAUDE.md#backend-testing and https://github.com/factionsecurity/OWASP-Faction-2/blob/main/CONTRIBUTING.md |
| `tests_documented_added` | Same two documents; CI enforces the suite. |
| `warnings`, `warnings_fixed`, `warnings_strict` | `npx tsc --noEmit` runs in CI and a type error fails the build; `EndpointAuthorizationArchitectureTest` fails the build when an endpoint makes no explicit authorization decision. |

## Security

| Criterion | URL / answer |
| --- | --- |
| `know_secure_design` | The project is an OWASP project written by penetration testers; authorization is designed as a single enforced model — https://github.com/factionsecurity/OWASP-Faction-2/blob/main/documentation/permissions.md |
| `know_common_errors` | Same; the maintainers work on the OWASP Top 10 professionally, and the product categorises findings by it. |
| `crypto_published`, `crypto_call`, `crypto_floss` | No home-grown cryptography. Spring Security and the JDK do the work. |
| `crypto_password_storage` | BCrypt, per-password salt, via Spring Security's `BCryptPasswordEncoder` (`backend/src/main/java/com/faction/clientportal/config/SecurityBeansConfig.java`). |
| `crypto_random` | `java.security.SecureRandom` everywhere a value must be unguessable; `.env` secrets are generated with `openssl rand -base64 48` by `install.sh`. |
| `crypto_keylength`, `crypto_working`, `crypto_weaknesses`, `crypto_pfs` | TLS is terminated by the operator's reverse proxy — https://github.com/factionsecurity/OWASP-Faction-2/blob/main/SETUP.md |
| `delivery_mitm` | Images are pulled from Docker Hub over HTTPS with content-addressed digests, and the source is cloned over HTTPS or SSH from GitHub — https://github.com/factionsecurity/OWASP-Faction-2/blob/main/DOCKER_QUICK_START.md |
| `delivery_unsigned` | Nothing is delivered over an unencrypted channel. |
| `vulnerabilities_fixed_60_days`, `vulnerabilities_critical_fixed` | Committed to in https://github.com/factionsecurity/OWASP-Faction-2/blob/main/SECURITY.md (60 days, sooner where severity warrants). |
| `no_leaked_credentials` | `.env` is git-ignored; `.env.example` holds placeholders and `install.sh` generates real secrets at install time. |

## Analysis

| Criterion | URL / answer |
| --- | --- |
| `static_analysis`, `static_analysis_common_vulnerabilities` | CodeQL with the `security-and-quality` suite over Java and TypeScript — https://github.com/factionsecurity/OWASP-Faction-2/blob/main/.github/workflows/codeql.yml |
| `static_analysis_often` | On every push to main, every pull request, and weekly. |
| `static_analysis_fixed` | Results and their state: https://github.com/factionsecurity/OWASP-Faction-2/security/code-scanning |
| `dynamic_analysis` | The Playwright end-to-end suite exercises a running instance — https://github.com/factionsecurity/OWASP-Faction-2/blob/main/TESTING.md |
| `dynamic_analysis_enable_assertions` | Test runs enable assertions; the backend suite runs against real TimescaleDB and object storage via Testcontainers rather than mocks. |

## Still to do

These need a click in GitHub or on the badge site — nothing in this repository
can set them:

1. **Turn on secret scanning and push protection** — repository *Settings →
   Code security*. Not required at passing level, but it answers
   `no_leaked_credentials` with evidence rather than assertion, and push
   protection stops the leak that would cost the badge later.
2. **Fill in the criteria** at https://www.bestpractices.dev/en/projects/14465/edit
   using the tables above. The project is registered and the badge is already
   in the README; the answers are what is outstanding.

3. **Answer `report_responses` and `enhancement_responses` honestly** from the
   tracker — they are about maintainer behaviour over the last few months, not
   about a document.
4. **Consider signing releases** (`delivery_unsigned` is satisfied without it,
   but cosign signatures on the published images are the natural next step and
   are required at silver).
