# Inline Images

## Overview

Rich-text (RICH_TEXT) fields in the Assessment Detail page support embedding images directly into the content. Images are stored in MinIO, tracked in the database, and **streamed back through an authenticated API endpoint** — the browser never contacts MinIO. A nightly garbage collection job removes any images that are no longer referenced in any field content.

These images are screenshots of findings, so the serving endpoint is authenticated and scoped to the owning assessment. It used to be a public endpoint protected only by an unguessable image id.

---

## Upload Flow

Images are uploaded via the backend rather than directly to MinIO. The frontend passes the file as a multipart upload to the API, and the API stores the bytes in MinIO and returns a short embed URL.

```
User drags/pastes image into Toast UI Editor
  │
  ▼
Editor fires addImageBlobHook(blob, callback)
  │
  ▼
Frontend calls:
  POST /api/v1/assessments/{assessmentId}/inline-images
  Content-Type: multipart/form-data
  Body: file=<image bytes>, filename=chart.png
  │
  ▼
InlineImageController
  └─ @PreAuthorize(File Write Auth)
  │
  ▼
InlineImageService.uploadImage(assessmentId, filename, contentType, bytes, userId)
  │
  ├─ Generate imageId = UUID (no hyphens)
  ├─ Build key = "inline-images/{assessmentId}/{imageId}/{filename}"
  ├─ storageService.uploadBytes(key, bytes, contentType)
  ├─ Save InlineImage record to MongoDB:
  │     { id, assessmentId, storageKey, originalFileName,
  │       contentType, fileSize, uploadedBy, uploadedAt }
  │
  └─ Return { imageId, url: "/api/v1/inline-images/{imageId}" }
  │
  ▼
Editor inserts:  ![filename](/api/v1/inline-images/{imageId})
```

The returned URL is a **permanent short-link** pointing to the API. The bytes are streamed from MinIO by the backend on each request, after the caller's access to the owning assessment is checked.

---

## Storage Layout

All files in MinIO share a single bucket (configured via `${storage.bucket}`). The key pattern for inline images is:

```
inline-images/{assessmentId}/{imageId}/{originalFilename}
```

Example:
```
inline-images/assessment-abc123/f8e2d1c0b9a7/screenshot.png
```

The `imageId` is a UUID without hyphens, making the URL unguessable and acting as an access control layer — only someone who saw the image URL in the editor can request it.

---

## Serving Images

The serve endpoint is **public** (no authentication required). This is intentional — the UUID is effectively a capability token, and the image URL only ever appears inside a field value that is itself protected by assessment read permissions.

```
GET /api/v1/inline-images/{imageId}
  │
  ▼
InlineImageController.serve(imageId)
  │
  ├─ Fetch InlineImage record from MongoDB by imageId
  ├─ InlineImage not found → 404
  │
  ▼
InlineImageController.serve(imageId)
  ├─ accessScopeService.checkAssessmentAccess(auth, image.assessmentId)
  │     → 403 unless the caller can read the owning assessment
  │
  ├─ inlineImageService.openImage(imageId)
  │     → storageService.openStream(image.storageKey)
  │
  └─ FileStreamResponse.inlineImage(...)
        → streams the bytes, with nosniff + a locked-down CSP, and
          forces a download for anything that is not a raster image
  │
  ▼
Browser renders the image; MinIO is never contacted directly
```

---

## Reference Tracking

When an assessor saves a field value, the system parses the Markdown content to extract all embedded image IDs and updates a reference index. This index is what the garbage collector uses to decide which images are still in use.

### InlineImageRef Collection

```
Collection: inline_image_refs
Compound index: (assessmentId, fieldId)
Single-field index: imageId

{
  id:           "ref-uuid",
  imageId:      "f8e2d1c0b9a7",
  assessmentId: "assessment-abc123",
  fieldId:      "field-snapshot-id-001",
  updatedAt:    "2025-03-10T14:22:00"
}
```

One document per (assessmentId, fieldId, imageId) triple. If a field references three images, there are three ref documents for that field.

### Update Process (called on every field save)

```
InlineImageService.updateRefsForField(assessmentId, fieldId, newContent)
  │
  ├─ Extract image IDs from content using regex:
  │     /api/v1/inline-images/([a-zA-Z0-9]+)
  │     → currentIds = { "f8e2d1c0b9a7", "a1b2c3d4e5f6" }
  │
  ├─ Fetch existing refs for this (assessmentId, fieldId)
  │     → existingIds = { "f8e2d1c0b9a7", "999oldimage" }
  │
  ├─ Add new refs (in currentIds but not in existingIds):
  │     → save InlineImageRef for "a1b2c3d4e5f6"
  │
  └─ Remove stale refs (in existingIds but not in currentIds):
        → delete InlineImageRef for "999oldimage"
              (image will be orphaned and collected by GC)
```

### Reference Tracking Diagram

```
Field Content (before edit):
  "![chart](/api/v1/inline-images/AAA)
   ![graph](/api/v1/inline-images/BBB)"

  Refs: { AAA → field-001 }
        { BBB → field-001 }

Field Content (after edit — user deletes the graph):
  "![chart](/api/v1/inline-images/AAA)"

  updateRefsForField():
    currentIds  = { AAA }
    existingIds = { AAA, BBB }

    → delete ref for BBB
    → BBB is now unreferenced (orphaned)

  Refs after: { AAA → field-001 }

  Next GC run (if BBB is > 24 hours old):
    → delete InlineImage record for BBB from MongoDB
    → delete object from MinIO
```

---

## Garbage Collection

Orphaned images are cleaned up by a scheduled job that runs nightly.

### Schedule

```
Cron: "0 0 2 * * ?"  →  2:00 AM every day
```

### Grace Period

Images uploaded within the last **24 hours** are never deleted, even if they have no refs yet. This prevents a race condition where an image is uploaded but the field save (which creates the refs) has not yet happened.

### GC Algorithm

```
InlineImageGcJob.run()
  │
  ├─ threshold = now() - 24 hours
  │
  ├─ candidates = all InlineImage records with uploadedAt < threshold
  │
  └─ for each candidate image:
       │
       ├─ hasRefs = (count of InlineImageRef where imageId == image.id) > 0
       │
       ├─ hasRefs == true  → KEEP (still referenced in a field)
       │
       └─ hasRefs == false → DELETE:
              storageService.deleteObject(image.storageKey)  ← MinIO
              inlineImageRepository.delete(image)            ← MongoDB
```

### GC Diagram

```
MongoDB: inline_images                MongoDB: inline_image_refs
──────────────────────────            ──────────────────────────
imageId: "AAA"  uploadedAt: -48h  ←── ref exists (field-001)  → KEEP
imageId: "BBB"  uploadedAt: -72h      no ref                  → DELETE
imageId: "CCC"  uploadedAt:  -1h      no ref                  → KEEP (grace period)
imageId: "DDD"  uploadedAt: -26h  ←── ref exists (field-002)  → KEEP

                            ▼ GC run at 2 AM ▼

Only "BBB" is deleted:
  MinIO:   DELETE inline-images/assessment-xyz/BBB/graph.png
  MongoDB: DELETE inline_images { id: "BBB" }
```

### Assessment Deletion

When an assessment is soft-deleted, all of its InlineImageRef records are immediately removed:

```java
inlineImageService.deleteRefsForAssessment(assessmentId);
// → inlineImageRefRepository.deleteByAssessmentId(assessmentId)
```

This orphans all images belonging to that assessment. The next nightly GC run will find them (if they are older than 24 hours) and delete them from MinIO and MongoDB.

---

## Full Lifecycle Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         INLINE IMAGE LIFECYCLE                          │
└─────────────────────────────────────────────────────────────────────────┘

  1. UPLOAD
  ─────────
  User pastes image → Editor hook → POST /inline-images
    → MinIO: inline-images/{asmtId}/{imgId}/{file}
    → MongoDB: inline_images { imageId, storageKey, ... }
    → Editor inserts: ![alt](/api/v1/inline-images/{imgId})

  2. REFERENCE CREATED
  ────────────────────
  User saves field → updateRefsForField()
    → MongoDB: inline_image_refs { imageId, assessmentId, fieldId }

  3. SERVED
  ─────────
  Browser renders Markdown → GET /api/v1/inline-images/{imgId}
    → API checks assessment access, looks up storageKey
    → Streams the bytes from MinIO through the response

  4. DEREFERENCED
  ───────────────
  User removes image from content → saves field → updateRefsForField()
    → InlineImageRef for imgId is deleted
    → imgId is now orphaned

  5. GARBAGE COLLECTED
  ─────────────────────
  2:00 AM nightly job:
    → Find InlineImages with uploadedAt > 24h ago and no refs
    → Delete from MinIO and MongoDB
```
