import { useCallback, useEffect, useState } from 'react';
import { workflowsApi } from '../api';
import type { Workflow } from '../types';

/** Default Workflow's id. Assessments and types without a workflow id are on it. */
export const DEFAULT_WORKFLOW_ID = 'default';

/**
 * One workflow, loaded fresh whenever the id changes. With no id nothing is fetched and the result is
 * null. An id that no longer exists falls back to Default Workflow, as the server does for an
 * assessment whose workflow is gone. The previous workflow stays in place until the next one arrives.
 */
export function useWorkflow(workflowId: string | null | undefined): Workflow | null {
  const [workflow, setWorkflow] = useState<Workflow | null>(null);

  useEffect(() => {
    if (!workflowId) {
      setWorkflow(null);
      return;
    }
    let cancelled = false;
    workflowsApi.get(workflowId)
      .then((res) => { if (!cancelled) setWorkflow(res.data ?? null); })
      .catch(async (err) => {
        if (cancelled || err?.response?.status !== 404 || workflowId === DEFAULT_WORKFLOW_ID) return;
        try {
          const res = await workflowsApi.get(DEFAULT_WORKFLOW_ID);
          if (!cancelled) setWorkflow(res.data ?? null);
        } catch {
          // Leave it empty; the screen shows what it can without a workflow.
        }
      });
    return () => { cancelled = true; };
  }, [workflowId]);

  return workflow;
}

/** Every workflow (optionally including archived ones), Default Workflow first. */
export function useWorkflows(includeArchived = false) {
  const [workflows, setWorkflows] = useState<Workflow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      const res = await workflowsApi.list(includeArchived);
      setWorkflows(res.data ?? []);
      setError('');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load workflows');
    } finally {
      setLoading(false);
    }
  }, [includeArchived]);

  useEffect(() => { reload(); }, [reload]);

  return { workflows, loading, error, reload };
}
