package com.faction.clientportal.repository;

import com.faction.clientportal.model.WorkflowRenameTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface WorkflowRenameTaskRepository extends JpaRepository<WorkflowRenameTask, String> {

    List<WorkflowRenameTask> findByState(WorkflowRenameTask.State state);

    /** Same, oldest first: {@code resumeInterruptedRenames} restarts tasks in the order they started. */
    List<WorkflowRenameTask> findByStateOrderByStartedAtAsc(WorkflowRenameTask.State state);

    List<WorkflowRenameTask> findByWorkflowIdAndState(String workflowId, WorkflowRenameTask.State state);

    @Transactional
    void deleteByWorkflowId(String workflowId);
}
