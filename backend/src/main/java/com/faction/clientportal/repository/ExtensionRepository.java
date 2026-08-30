package com.faction.clientportal.repository;

import com.faction.clientportal.model.Extension;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for installed App Store extensions.
 */
@Repository
public interface ExtensionRepository extends JpaRepository<Extension, String> {

    /** All installed extensions, in App Store display order. */
    List<Extension> findByDeletedAtIsNullOrderByDisplayOrderAsc();

    Optional<Extension> findByIdAndDeletedAtIsNull(String id);

    /** Used to reject a re-upload of a JAR that is already installed. */
    Optional<Extension> findByHashAndDeletedAtIsNull(String hash);

    /** Installed extensions, for the App Store quota. */
    long countByDeletedAtIsNull();
}
