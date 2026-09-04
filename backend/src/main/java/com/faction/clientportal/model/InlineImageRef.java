package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "inline_image_refs", indexes = {
    @Index(name = "idx_inline_image_refs_imageid", columnList = "image_id"),
    @Index(name = "idx_inline_image_refs_assessment_field", columnList = "assessment_id, field_id"),
    // updateRefsForSharedField looks up by field alone — a note or template has no single
    // assessment — and Postgres cannot use the composite above for that, so without this it is a
    // sequential scan on every note, content template and default vulnerability save.
    @Index(name = "idx_inline_image_refs_fieldid", columnList = "field_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InlineImageRef {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String imageId;

    private String assessmentId;
    private String fieldId;
    private LocalDateTime updatedAt;
}
