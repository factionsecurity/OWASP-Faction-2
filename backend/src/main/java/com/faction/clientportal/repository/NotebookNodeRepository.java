package com.faction.clientportal.repository;

import com.faction.clientportal.model.NotebookNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for NotebookNode entity.
 */
@Repository
public interface NotebookNodeRepository extends JpaRepository<NotebookNode, String> {

    /** Top-level roots for an application, ordered by orderIndex */
    List<NotebookNode> findByApplicationIdAndParentIdIsNullAndDeletedAtIsNullOrderByOrderIndexAsc(String applicationId);

    /** Children of a node, ordered by orderIndex */
    List<NotebookNode> findByParentIdAndDeletedAtIsNullOrderByOrderIndexAsc(String parentId);

    /** Find a single non-deleted node by id */
    Optional<NotebookNode> findByIdAndDeletedAtIsNull(String id);

    /** Root node(s) owned by a given assessment */
    List<NotebookNode> findByAssessmentIdAndParentIdIsNullAndDeletedAtIsNull(String assessmentId);

    /** Count of non-deleted children for a node */
    long countByParentIdAndDeletedAtIsNull(String parentId);

    /** All non-deleted nodes for an application (used for search) */
    List<NotebookNode> findByApplicationIdAndDeletedAtIsNull(String applicationId);
}
