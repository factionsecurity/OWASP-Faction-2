# Faction Client Portal — Technical Documentation

## Overview

Faction Client Portal is a Spring Boot REST API with a React frontend for managing security assessments. Assessments are driven by configurable Report Templates that define the document structure, custom CSS, a DOCX report file, and user-defined fields. This documentation covers the core technical flows.

---

## Contents

| Document | Description |
|----------|-------------|
| [Assessment Lifecycle](./assessment-lifecycle.md) | How assessments are created from templates, user assignment, and status workflow |
| [Permissions Reference](./permissions.md) | Required permissions for every service and endpoint |
| [API Keys](./api-keys.md) | Opaque API-key authentication: key types, the scope model, live authority resolution, and the management API |
| [User Defined Fields](./user-defined-fields.md) | How fields are scoped, snapshotted from templates, synced, and stored |
| [Inline Images](./inline-images.md) | How images are uploaded, embedded in rich-text editors, served, and garbage collected |
| [Server-Sent Events](./server-sent-events.md) | Real-time field locking and collaborative editing via SSE |
| [Email Integration](./email-integration.md) | @mention notifications, SMTP/IMAP configuration, reply-by-email, thread membership, and notification preferences |

---

## Architecture Overview

```
┌─────────────────────────────────────────────────┐
│                   React Frontend                 │
│  ┌──────────────┐  ┌────────────┐  ┌──────────┐ │
│  │ Report       │  │ Assessment │  │Dashboard │ │
│  │ Designer     │  │ Detail     │  │/ Lists   │ │
│  └──────────────┘  └────────────┘  └──────────┘ │
└───────────────────────┬─────────────────────────┘
                        │ REST + SSE (JWT Bearer)
┌───────────────────────▼─────────────────────────┐
│              Spring Boot API (:8080)             │
│  ┌──────────────────────────────────────────┐   │
│  │  Security Layer (JWT + @PreAuthorize)    │   │
│  └──────────────────────────────────────────┘   │
│  ┌──────────┐ ┌───────────┐ ┌────────────────┐  │
│  │Assessment│ │  Report   │ │ InlineImage    │  │
│  │ Service  │ │ Template  │ │ Service + GC   │  │
│  └──────────┘ │ Service   │ └────────────────┘  │
│               └───────────┘                     │
│  ┌─────────────────────┐  ┌───────────────────┐ │
│  │ AssessmentLock      │  │  StorageService   │ │
│  │ Service (SSE)       │  │  (MinIO / S3)     │ │
│  └─────────────────────┘  └───────────────────┘ │
└───────────────┬──────────────────┬──────────────┘
                │                  │
  ┌─────────────▼────┐   ┌─────────▼──────────┐
  │  MongoDB          │   │  MinIO Object Store │
  │  (collections)    │   │  (files & images)   │
  └───────────────────┘   └────────────────────┘
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| API Framework | Spring Boot 3, Spring Security |
| Authentication | JWT (stateless) |
| Authorization | Method-level `@PreAuthorize` with custom permission expressions |
| Database | MongoDB (via Spring Data MongoDB) |
| File Storage | MinIO (S3-compatible) via AWS SDK v2 |
| Real-time | Server-Sent Events (SSE) |
| Frontend | React 18, TypeScript, Vite |
| Rich Text | Toast UI Editor (Markdown/WYSIWYG) |
