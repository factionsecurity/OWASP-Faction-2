import { createContext, useContext } from 'react';
import type { ReactNode } from 'react';
import { useWorkflows } from '../hooks/useWorkflow';
import type { Workflow } from '../types';

interface WorkflowsContextValue {
  workflows: Workflow[];
  loading: boolean;
  error: string;
  /** Re-reads after a workflow is created, edited, archived or deleted. */
  reload: () => void;
}

const WorkflowsContext = createContext<WorkflowsContextValue | undefined>(undefined);

/**
 * Holds the workflow list for every screen that renders rows from more than one workflow —
 * replaces the nine near-identical per-screen fetches of the old global status config.
 *
 * <p>Fetches including archived workflows. An archived workflow still owns assessments and
 * findings, so a row on one still needs its colours, names and stages resolved; a screen
 * that offers an "include archived" filter excludes them itself when building its own list
 * (see the admin workflows page) — this provider always holds the full set.
 *
 * <p>Mounted only for an authenticated session (see App.tsx): the endpoint requires a
 * signed-in user, and the sign-in page has no use for workflow data.
 */
export function WorkflowsProvider({ children }: { children: ReactNode }) {
  const { workflows, loading, error, reload } = useWorkflows(true);
  return (
    <WorkflowsContext.Provider value={{ workflows, loading, error, reload }}>
      {children}
    </WorkflowsContext.Provider>
  );
}

/**
 * Unlike its siblings in this directory, this context throws when there is no provider
 * instead of falling back to a permissive default. Those siblings mount unconditionally,
 * so "no provider" is structurally unreachable wherever their hooks are called — a
 * permissive default there is just dead code. WorkflowsProvider is mounted conditionally
 * (only for an authenticated session; see App.tsx), so the no-provider case is real: a
 * component rendered outside that gate, today or after a future refactor, would otherwise
 * silently see an empty workflow list — indistinguishable from an installation that
 * legitimately has none — instead of a traceable error.
 */
export function useWorkflowsContext(): WorkflowsContextValue {
  const context = useContext(WorkflowsContext);
  if (!context) throw new Error('useWorkflowsContext must be used inside a WorkflowsProvider');
  return context;
}
