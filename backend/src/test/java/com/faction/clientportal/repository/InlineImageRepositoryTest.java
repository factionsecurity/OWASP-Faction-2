package com.faction.clientportal.repository;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.InlineImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class InlineImageRepositoryTest extends TestContainersConfig {

    @Autowired
    private InlineImageRepository inlineImageRepository;

    @BeforeEach
    void setUp() {
        inlineImageRepository.deleteAll();
    }

    // Regression test: InlineImageService assigns the entity's id itself (a UUID) before
    // the first save — it needs the value up front to build the storage key and response
    // URL. A pre-assigned, non-null id used to make Spring Data's default isNew() check
    // treat the entity as already existing, routing save() through merge() instead of
    // persist() and throwing StaleObjectStateException on what was actually a brand-new
    // insert. See InlineImage.isNew()/Persistable.
    @Test
    void save_withPreAssignedId_insertsNewRow() {
        String imageId = UUID.randomUUID().toString().replace("-", "");
        InlineImage image = InlineImage.builder()
                .id(imageId)
                .assessmentId("assessment-1")
                .storageKey("inline-images/assessment-1/" + imageId + "/photo.png")
                .originalFileName("photo.png")
                .contentType("image/png")
                .fileSize(1234L)
                .uploadedBy("tester")
                .uploadedAt(LocalDateTime.now())
                .build();

        InlineImage saved = inlineImageRepository.save(image);

        assertThat(saved.getId()).isEqualTo(imageId);
        assertThat(inlineImageRepository.findById(imageId)).isPresent();
    }
}
