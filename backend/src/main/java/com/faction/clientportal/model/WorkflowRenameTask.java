package com.faction.clientportal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A vulnerability status rename on one workflow, rewritten across its findings in the background.
 * Durable, so a rename interrupted by a restart resumes, and so the workflow can show it in progress
 * and refuse edits to the status it is writing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "workflow_rename_tasks", indexes = {
    @Index(name = "idx_workflow_rename_tasks_workflow_state", columnList = "workflow_id, state")
})
public class WorkflowRenameTask {

    public enum State { RUNNING, DONE, FAILED }

    @Id
    private String id;

    @Column(name = "workflow_id", nullable = false)
    private String workflowId;

    @Column(name = "from_name", nullable = false)
    private String fromName;

    @Column(name = "to_name", nullable = false)
    private String toName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private State state;

    /** Findings rewritten so far. */
    @Builder.Default
    @Column(nullable = false)
    private long processed = 0;

    @Column(length = 2000)
    private String error;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public static WorkflowRenameTask start(String workflowId, String fromName, String toName) {
        return WorkflowRenameTask.builder()
                .id(UUID.randomUUID().toString())
                .workflowId(workflowId)
                .fromName(fromName)
                .toName(toName)
                .state(State.RUNNING)
                .startedAt(LocalDateTime.now())
                .build();
    }
}
