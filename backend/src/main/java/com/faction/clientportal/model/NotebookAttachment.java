package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Metadata for a file attached to a notebook node.
 * The actual file content is stored in MinIO/S3; only metadata is stored in MongoDB.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotebookAttachment {

    /** UUID assigned when the upload target is allocated */
    private String id;

    /** Original file name as provided by the uploader */
    private String fileName;

    /** MIME content type */
    private String contentType;

    /** File size in bytes */
    private Long fileSize;

    /** Object key in MinIO (e.g. "notebooks/{nodeId}/{fileId}/{fileName}") */
    private String storageKey;

    /** User ID of the uploader */
    private String uploadedById;

    /** Display name of the uploader */
    private String uploadedByName;

    /** Timestamp when the metadata was confirmed (upload completed) */
    private LocalDateTime uploadedAt;
}
