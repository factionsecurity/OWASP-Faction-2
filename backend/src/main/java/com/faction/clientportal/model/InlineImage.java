package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "inline_images", indexes = {
    @Index(name = "idx_inline_images_assessmentid", columnList = "assessment_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InlineImage implements Persistable<String> {

    @Id
    private String id;

    /** Null for a {@link InlineImageScope#LIBRARY} image, which belongs to a template instead. */
    private String assessmentId;

    /** Who owns this image, and therefore who may load it. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    private InlineImageScope scope = InlineImageScope.ASSESSMENT;

    private String storageKey;
    private String originalFileName;
    private String contentType;
    private Long fileSize;
    private String uploadedBy;
    private LocalDateTime uploadedAt;

    // InlineImageService always assigns `id` itself before the first save (it needs the
    // value up front to build the storage key and response URL), so Spring Data's default
    // isNew() check ("is the id null?") can't tell a brand-new row from an existing one —
    // it sees a non-null id and routes save() through merge() instead of persist(), which
    // then fails with StaleObjectStateException because no such row exists yet. Persistable
    // lets us say "new" explicitly instead of inferring it from the id.
    @Transient
    @Builder.Default
    @EqualsAndHashCode.Exclude
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }
}
