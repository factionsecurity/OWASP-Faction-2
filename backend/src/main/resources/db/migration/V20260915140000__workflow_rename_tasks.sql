-- Assessment workflows (phase 4a): background vulnerability status renames.
--
-- A new table with no data to carry over, so no table-exists guard: on a fresh database this creates it
-- before Hibernate starts (which then finds it matching the WorkflowRenameTask entity), and on an existing
-- installation it adds it. Idempotent.
CREATE TABLE IF NOT EXISTS workflow_rename_tasks (
    id          varchar(255) PRIMARY KEY,
    workflow_id varchar(255) NOT NULL,
    from_name   varchar(255) NOT NULL,
    to_name     varchar(255) NOT NULL,
    state       varchar(255) NOT NULL,
    processed   bigint       NOT NULL DEFAULT 0,
    error       varchar(2000),
    started_at  timestamp(6) NOT NULL,
    finished_at timestamp(6)
);

CREATE INDEX IF NOT EXISTS idx_workflow_rename_tasks_workflow_state
    ON workflow_rename_tasks (workflow_id, state);
