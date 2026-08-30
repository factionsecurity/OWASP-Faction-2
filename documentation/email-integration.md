# Email Integration — Mentions, Notifications & Reply by Email

## Overview

Faction emails people when they are named in a discussion or when a thread they follow moves, and lets them answer straight from their inbox. A reply posted by email becomes a comment in the thread, attributed to its author and subject to that author's permissions.

Three moving parts:

- **Outbound** — SMTP, configured in the database and editable by an admin. One `EmailService` sends everything.
- **Inbound** — an IMAP poller reads a dedicated mailbox, matches each message to a thread, and appends a comment.
- **Membership** — an explicit subscriber list per application and per finding decides who is emailed, plus per-user opt-out.

Outbound works on its own. Inbound is opt-in and needs a dedicated mailbox.

---

## End-to-End Flow

```
Someone @mentions bob in a vulnerability comment
        │
        ├─ MentionQueueService.queueMentions()
        │     • skips self-mentions
        │     • skips a repeat of the same person on the same item within 10 min
        │     • snapshots the comment HTML and the target identity
        │     • schedules for now + 10s
        │
        │  ... drained every 15s by processDue()  → delivery lands at 10-25s
        │
        ├─ NotificationService.send(..., sendEmail=false)
        │     • records the in-app notification, pushes it over SSE
        │
        └─ MentionEmailSender  (on the mail executor)
              • issues an EmailReplyToken bound to bob + this thread
              • Reply-To: faction+<token>@example.com
              • body: the comment, a View in Faction button, an unsubscribe link
              │
              ▼
         bob replies from his mail client
              │
              ▼
        InboundEmailPoller  (ticks every 15s, honours the configured interval)
              │
              ├─ InboundMailParser  — find the token, extract the body, strip the quote
              ├─ InboundEmailProcessor — verify, authorize, append
              │     ✓ token exists, not expired, not revoked
              │     ✓ From matches the address the token was issued to
              │     ✓ author still has access, evaluated with their current roles
              │
              ├─ ✅ VulnerabilityService.addComment(...)  → appears in the thread
              └─ ❌ rejected → audited, and the sender is told why
```

---

## Configuration

Two separate admin pages, because outbound and inbound are independently useful — notification email without reply-by-email is a normal deployment.

### Outbound: Administration → Email Config

`/email-config`, `super_admin` (currently via the `canViewSsoConfig` permission — see [Known Limitations](#known-limitations)).

| Setting | Notes |
|---|---|
| Enabled | Saves on toggle, not on "Save Configuration" |
| Provider | Presets for Gmail, Outlook, Microsoft 365, Yahoo, SendGrid, Mailgun |
| Host / Port / Security | `STARTTLS` (default), `SSL_TLS`, or `NONE` |
| Username / Password | Password is AES-GCM encrypted with `SSO_ENCRYPTION_KEY` |
| From name / From email | Falls back to the SMTP username if the from address is blank |
| Logo | Base64, embedded in the email header |

There is a **Send test email** button. Use it before debugging anything else.

> **Without `SSO_ENCRYPTION_KEY` set, the SMTP password is stored in plaintext.** `EncryptionService.isConfigured()` returns false and the value is saved as-is.

### Inbound: Administration → Inbound Email

`/inbound-email-config`, `super_admin`. Off by default.

| Setting | Notes |
|---|---|
| Enabled | Saves on toggle |
| **Reply address** | The mailbox replies arrive at, e.g. `faction@example.com` |
| Provider / Host / Port / Security | `SSL_TLS` on 993 by default |
| Username / Password | Encrypted as above |
| Folder | `INBOX` by default |
| Processed folder | Optional. Blank means handled messages are only flagged as read |
| Poll interval | Seconds, minimum 15 |

**Test IMAP connection** connects, authenticates, opens the folder and reports its message counts. It reads nothing and changes nothing.

### Mailbox requirements

1. **Dedicated.** The poller marks messages read and may move them. Do not point it at a person's inbox.
2. **Plus-addressing.** The reply token travels as `faction+<token>@example.com`, so the mailbox must accept sub-addressing. Gmail, Google Workspace and most providers do; Microsoft 365 needs it enabled per-tenant.
3. **IMAP enabled**, and an app password where the provider requires one.

If the reply address is blank, mention emails become notify-only and omit the reply invitation entirely — the app never invites a reply it cannot receive.

---

## Where Mentions Work

The `@` picker is **opt-in** per editor, via `RichTextEditor`'s `mentions` prop, and enabled on exactly three surfaces:

| Surface | Replyable by email |
|---|---|
| Application comments | ✅ |
| Vulnerability comments | ✅ |
| Assessment notes (Notebook) | ❌ notify-only |

Both comment surfaces also keep a subscriber list, so a conversation continues without needing an @mention in every message. See [Thread Membership](#thread-membership).

It is deliberately **off** everywhere else — default vulnerability templates, the report designer, organization descriptions, engagement scope, vulnerability description/recommendation/details. Naming someone in reusable content should not notify them.

Assessment notes are a document body rather than a thread, so an emailed reply has nowhere to append. Those mentions get no reply token, and the email omits the reply invitation.

### Serialised form

A mention is one span, and it is the entire contract between frontend and backend:

```html
<span class="mention" data-username="bob" contenteditable="false">@bob</span>
```

`MentionQueueService.extractMentions()` greps for `data-username="…"`. Nothing else is inspected — a typed-out `@bob` in plain text notifies nobody.

In the markdown and split views (a CodeMirror instance, not the contenteditable) the same span is stored but **rendered as a chip**, so the source stays readable. `EditorView.atomicRanges` makes it delete as one unit; without that, a single Backspace shaves one character off the hidden markup and silently corrupts the mention.

---

## Thread Membership

Applications and vulnerabilities each carry an explicit subscriber list, shown above the comments as **On this conversation**, with *Assign me*, *Leave* and an *Add* typeahead.

Everyone listed is notified — in app and by email — when a comment is posted, and can reply to that email.

**You are added automatically when you:**

- are `@mentioned` in a comment, or
- write a comment.

The second matters: without it a thread is one-way. Bob mentions Alice, Alice is subscribed, Bob is not — so Alice replies and Bob never sees it.

Mentioned users are skipped by the thread email, because they are about to receive the richer mention email. Two emails for one comment reads as a bug.

Assessment notes have no list — a note is a document body, not a thread — so a mention there stays notify-only and its email offers no unsubscribe link.

### Endpoints

```
GET    /api/v1/applications/{id}/subscribers
POST   /api/v1/applications/{id}/subscribers/{username}
DELETE /api/v1/applications/{id}/subscribers/{username}

GET    /api/v1/assessments/{aid}/vulnerabilities/{id}/subscribers
POST   /api/v1/assessments/{aid}/vulnerabilities/{id}/subscribers/{username}
DELETE /api/v1/assessments/{aid}/vulnerabilities/{id}/subscribers/{username}
```

Gated on the same permissions as commenting: if you may take part in the discussion, you may follow it and bring colleagues in.

---

## Notification Preferences

Per-category, per-channel opt-out at **Profile → Notifications**.

| Category | Notification types |
|---|---|
| Mentions | `MENTION` |
| Assessment assignments | `ASSESSOR_ASSIGNED`, `ASSESSMENT_CREATED` |
| Retest assignments | `RETEST_ASSIGNED` |
| Comments on items you follow | `COMMENT_ADDED` |
| Other notifications | anything unmapped |

```
GET /api/v1/users/me/notification-preferences
PUT /api/v1/users/me/notification-preferences
```

Self-service only — the username comes from the authenticated principal, never the request body.

**An absent row means enabled.** Nothing is backfilled, so existing users are unaffected and a category added later defaults on. A failed preference lookup also defaults to sending: a settings read must never be why a notification is lost.

Muting *in app* skips recording the notification entirely rather than filtering at read time, so a muted category never accumulates unread counts.

---

## Inbound Processing

### Matching a message to a thread

Two mechanisms, tried in order:

1. **Plus-addressed recipient.** Every recipient header is checked — `To`, `Cc`, `Delivered-To`, `X-Original-To`, `X-Forwarded-To`, `Envelope-To` — because forwarding rules routinely rewrite `To`.
2. **`In-Reply-To` / `References`** matched against the `Message-ID` recorded when the email was sent. Covers providers that strip sub-addressing, and replies sent to the `From` address instead.

> The mailbox comparison is case-insensitive, but **the token is sliced from the original string**. Tokens are base64url and case-sensitive; lowercasing the address before extracting breaks every lookup.

### Authorization

Three independent layers.

| Layer | What it proves | Why it is not enough alone |
|---|---|---|
| **Reply token** | The sender received the email. 32 base64url chars from `SecureRandom`, bound to one recipient and one thread, 30-day expiry | — this is the real control |
| **Sender match** | `From` equals the token holder's address, case-insensitively | `From` is spoofable without DMARC enforcement |
| **Author's permissions** | The comment is written with an `Authentication` built from that user's **current** roles | — |

The third layer is easy to get wrong. Passing `null` is the codebase's convention for schedulers, and `AccessScopeService.resolveAssessmentScope` documents null as an "internal, non-HTTP caller", returning an **unrestricted** scope. An emailed reply is not an internal caller. A user who has lost access since being mentioned is denied, and the rejection is logged.

### Extracting the reply

`text/plain` is preferred over `text/html` — it is what quote stripping is reliable against, and it avoids the sanitising problem entirely.

Quoted history is removed by:

1. Cutting at the sentinel, which every replyable email carries **at the top**:
   ```
   ##- Please type your reply above this line -##
   ```
   A reply quotes the whole original below what the person typed, and the parser cuts at the first sentinel — so anything above it survives. At the bottom, the quote block and the buttons leaked into every reply.
2. Failing that, heuristics: `On <date>, X wrote:`, `-----Original Message-----`, a run of `_____`, leading `>`, and the `gmail_quote` / `divRplyFwdMsg` containers in HTML.

The heuristics are deliberately narrow and anchored. A greedy pattern eats the reply itself, and losing someone's message is worse than leaving a quote in it.

Inbound HTML is sanitised more strictly than outbound and **strips links entirely** — a live link in an ingested comment is a phishing vector rendered inside the app. Attachments are dropped, and the comment says how many.

Every ingested comment ends with *“Sent by email.”* so readers can tell where it came from.

### Rejections

| Reason | Sender told? |
|---|---|
| Sender does not match the token | ✅ |
| Token expired / revoked | ✅ |
| Author lost access | ✅ |
| No message text after quote stripping | ✅ |
| Message too large | ✅ |
| No token found | ❌ |
| Token holder no longer exists | ❌ |

The last two are silent on purpose. A shared mailbox collects spam, bounces and autoresponders whose `From` addresses are frequently forged, and answering those is backscatter.

Notices carry `Auto-Submitted: auto-replied` (RFC 3834), and are never sent to:

- a message that is itself automated — `Auto-Submitted` other than `no`, `Precedence: bulk|list|junk`, `X-Auto-Response-Suppress`, `X-Autoreply`, `List-Id`, or a null `Return-Path`
- our own mailbox, including its plus-addressed forms

> Automation detection runs **synchronously on the polling thread**. A `MimeMessage` from an IMAP folder cannot be read once the folder is closed or from another thread, and the notice sender is `@Async`. Passing the live message in made every header read throw, and the "unreadable means automated" fallback turned that into total silence — notices never sent at all.

### Retry semantics

| Outcome | Message flagged read? |
|---|---|
| `ACCEPTED` | yes |
| `REJECTED` | yes — a decision that will not change on retry |
| `ERROR` | **no** — left for the next poll |

A transient database failure must not discard someone's reply; junk must not be reprocessed every minute.

---

## Unsubscribing

Every thread email carries a **Remove me from this conversation** link and a `List-Unsubscribe` header.

- The **reply token doubles as the unsubscribe authority** — already unguessable, already bound to one recipient and one thread. There is no second token type.
- A token is therefore issued **even when reply-by-email is off**: every email about a thread must offer a way off it.
- **POST, not GET.** Mail clients and security scanners prefetch links, so a mutating GET would unsubscribe people who merely received the email. The link opens the public `/unsubscribe` page, which posts on a click. `List-Unsubscribe-Post` is deliberately absent for the same reason.
- Unsubscribing **revokes** the token: the same credential can post replies, so leaving a thread must not leave a live way back in.
- **Idempotent, and expiry is ignored.** An old email is still proof of receipt, and telling someone their unsubscribe failed is how a message gets marked as spam.

```
POST /api/v1/email/unsubscribe   { "token": "..." }      (unauthenticated)
```

---

## Data Model

| Table | Purpose |
|---|---|
| `email_config` | Singleton SMTP settings |
| `inbound_email_config` | Singleton IMAP settings, reply address, last poll result |
| `mention_queue` | Debounced mentions, with the comment snapshot and target identity |
| `email_reply_token` | Reply/unsubscribe token → recipient + thread, with the outbound `Message-ID` |
| `inbound_email_log` | Every inbound message considered, accepted or not |
| `notification_preference` | Per-user, per-category opt-out. Absent row means enabled |
| `vulnerabilities.subscribers` | JSON array of usernames following the discussion |
| `applications.subscribers` | the same, for an application's Discussion |

Comments themselves are **not** a table — they are `@JdbcTypeCode(SqlTypes.JSON)` lists on `Application.comments` and `Vulnerability.comments`. There is no comment id to foreign-key against, which is why the mention queue snapshots content and the reply token names a target type plus id.

---

## Threads & Timing

| Behaviour | Value |
|---|---|
| Mention debounce | 10s |
| Mention queue drain | every 15s |
| **Mention delivery** | **10–25s** |
| Dedup window (same person, same item) | 10 min |
| Inbound poll tick | 15s, honouring the configured interval (default 60s) |
| IMAP connect/read timeout | 15s |
| Reply token lifetime | 30 days |
| Comment thread refresh | every 20s while visible |

Mail never runs on the shared `@Scheduled` pool. That pool is single-threaded and carries the 2-second assessment lock sweep and the 30-second SSE heartbeat; a blocking send or a hung mailbox would stall both. Everything mail-related runs on `mailExecutor` (2–5 threads, `CallerRunsPolicy`).

Comment threads poll rather than using SSE: the vulnerability drawer renders in places where a user may not hold assessment-read, applications have no event stream, and the SSE emitter registry is in-memory so it would not fan out across instances anyway.

---

## Troubleshooting

**Start with the audit log.** It has a row for every inbound message.

```sql
SELECT received_at, from_address, status, reason, token_id, created_comment_id
FROM inbound_email_log ORDER BY received_at DESC LIMIT 20;
```

**Is the poller alive?**

```sql
SELECT enabled, last_polled_at, last_poll_error FROM inbound_email_config;
```

`last_poll_error` is also shown on the admin page.

**Did the mention even queue?**

```sql
SELECT mentioned_username, mentioned_by_username, processed, scheduled_for
FROM mention_queue ORDER BY created_at DESC LIMIT 10;
```

An empty table with mentions being written almost always means **self-mentions**, which are skipped by design.

### Common causes

| Symptom | Likely cause |
|---|---|
| No email at all | `email_config.enabled = false`, or the recipient has no address |
| Notification but no email | Recipient muted that category's email, or the address is undeliverable |
| Mentioning yourself does nothing | Self-mentions are skipped, as in Slack, GitHub and Jira |
| `Sender does not match…` | Replying from a different address than the notification went to. Common with shared mailboxes and aliases — tokens are **per recipient**, so replying to a colleague's copy will not work |
| Reply never appears | Check `inbound_email_log`; then whether the token's mailbox accepts plus-addressing |
| Mentions vanish in markdown view | Should not happen — `turndown` keeps `data-username` spans. If it recurs, that keep rule regressed |

Send failures log at `warn` with the recipient address, since a typo'd address is a far more common cause than a broken SMTP config.

---

## Security Notes

- **The token is the control, not the sender.** It is unguessable, single-recipient and expiring. `From` matching is a second factor only.
- **Emailed replies get their author's real permissions**, evaluated at ingest time, not at send time.
- **Inbound content is untrusted** and sanitised with a stricter policy than outbound, links removed.
- **Comment HTML is not sanitised on write** anywhere in the app — a pre-existing gap. Inbound is sanitised precisely because that gap exists.
- **SMTP and IMAP passwords are AES-GCM encrypted**, and fall back to plaintext if `SSO_ENCRYPTION_KEY` is unset.
- **Unsubscribe is unauthenticated by design**, on POST only, and revokes the token it used.

---

## Known Limitations

- **Emails are not threaded into one inbox conversation.** Each is a fresh `Message-ID` with no `In-Reply-To`, so clients file them separately. `email_reply_token.outbound_message_id` is persisted, so the data is there when this is picked up.
- **SSO users whose email is a UPN** (an Azure guest's `…#EXT#@…onmicrosoft.com`) cannot receive or reply to mention email, because it is not a deliverable mailbox. Accepted rather than fixed.
- **Notifications do not fan out across instances.** The SSE emitter registry is in-memory.
- **Email config borrows the SSO permission** (`canViewSsoConfig`) rather than having its own. The inbound page matches it for consistency.
- **No digest or batching.** Five rapid comments on a followed item send five emails.
- **Attachments on inbound replies are dropped**, with a note in the comment.

---

## Key Classes

| Class | Role |
|---|---|
| `service/email/EmailService` | The only way the app sends mail — transport, shell, headers |
| `service/email/MentionEmailSender` | The @mention email |
| `service/email/ThreadCommentEmailSender` | New comment to thread followers |
| `service/email/ReplyTokenService` | Issues tokens, builds the plus-addressed reply address |
| `service/email/InboundEmailPoller` | IMAP polling, flagging, moving |
| `service/email/InboundMailParser` | Token extraction, body selection, quote stripping — pure functions |
| `service/email/InboundEmailProcessor` | Verification, authorization, dispatch, audit |
| `service/email/RejectionNoticeSender` | Explains an actionable rejection; loop guards |
| `service/email/EmailUnsubscribeService` | Leaving a thread from an email link |
| `service/MentionQueueService` | Mention extraction, debounce, dedup, drain |
| `service/NotificationPreferenceService` | Per-category opt-out; absent means enabled |
| `service/InboundEmailConfigService` | IMAP settings and connections |
| `hooks/useCommentPolling` (frontend) | Keeps an open thread current |
