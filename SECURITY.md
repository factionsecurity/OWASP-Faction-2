# Security Policy

OWASP Faction stores penetration test findings — unfixed vulnerabilities in
other people's systems. A break in Faction is a break in every engagement it
holds, so we treat reports against it accordingly.

## Supported versions

Security fixes land on the latest 2.x release. Older lines are not patched;
upgrading is a `docker compose pull && docker compose up -d` away.

| Version | Supported          |
| ------- | ------------------ |
| 2.0.x   | :white_check_mark: |
| 1.x     | :x: (see [factionsecurity/faction](https://github.com/factionsecurity/faction)) |

## Reporting a vulnerability

**Do not open a public issue.** Report privately through GitHub Security
Advisories:

**https://github.com/factionsecurity/OWASP-Faction-2/security/advisories/new**

That form is private to you and the maintainers, works for anyone with a
GitHub account, and needs no key exchange. It is the channel we would rather
you used, because it keeps the report, the discussion and the eventual advisory
in one place.

If you cannot use it — no GitHub account, or you would rather not create one —
email **[develop@factionsecurity.com](mailto:develop@factionsecurity.com)**.
That reaches the maintainers directly. Plain SMTP is not end-to-end encrypted,
so if the finding is severe enough that you would rather not put the detail in
an email, send us a message saying so and we will arrange a channel.

Useful reports include:

- the version (the UI footer, or your `FACTION_VERSION`) and how it is deployed
- what an attacker gets: which role, whose data, from what starting position
- steps to reproduce, and a proof of concept if you have one
- anything you already know about a fix

## What happens next

| Stage | Target |
| ----- | ------ |
| Acknowledgement that a human has it | 3 working days |
| An assessment: accepted, needs more, or declined with reasoning | 14 days |
| Fix released for a confirmed vulnerability | 60 days, sooner where severity warrants |

If a report goes quiet past those windows, chase it in the advisory thread —
silence is a dropped ball on our side, not a decision.

We publish the fix as a [GitHub Security
Advisory](https://github.com/factionsecurity/OWASP-Faction-2/security/advisories)
once a release carrying it is out, and name you in it unless you would rather
we did not. Please hold public detail until then; we are not going to sit on a
report to keep it quiet, and we would rather users hear about it from us with a
patch in hand.

Reports that turn out not to be vulnerabilities still get an answer explaining
why, and often a documentation fix — "the docs made me deploy it wrong" is a
real finding.

## Scope

In scope: this repository and the images it publishes
(`factionsecurityllc/faction-*` on Docker Hub).

Out of scope, in the sense that we cannot fix them for you:

- deployments run without TLS in front of them, or with the default
  credentials from `SETUP.md` left in place
- the AI provider you configure — Faction anonymises PII before a call, but
  what your provider does with the request is between you and them
- findings that need an already-authenticated administrator, unless they cross
  a tenant or role boundary that Faction is meant to hold

## Hardening a deployment

`.env.example` and [`SETUP.md`](SETUP.md) cover the settings that matter:
generated secrets, rotating the seeded accounts, and putting a TLS terminator
in front of the app. [`documentation/permissions.md`](documentation/permissions.md)
documents the authorization model — every endpoint makes an explicit
authorization decision, and a test fails the build when one does not.
