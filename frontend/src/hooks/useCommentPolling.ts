import { useEffect, useRef } from 'react';

/**
 * Keeps a comment thread up to date while it is on screen.
 *
 * Comments arrive from three places the local tab knows nothing about: another user
 * commenting, a system comment written by a workflow, and — since reply-by-email — the
 * inbound mail poller, which lands a comment up to ~90 seconds after it was sent with no
 * browser action anywhere. Without this, a thread only refreshes when *you* post.
 *
 * Polling rather than SSE, deliberately:
 * - The vulnerability drawer renders inside RemediationPage and ManagerDashboard, where a
 *   user may not hold assessment-read; subscribing to `/assessments/{id}/events` there
 *   would 403 on every open.
 * - Applications have no event stream at all, so SSE would cover only half the surface.
 * - The SSE emitter registry is in-memory, so it would not fan out across instances.
 *
 * The cost is one small GET per interval per open thread, and only while the tab is
 * visible — a hidden tab polls nothing.
 */
export function useCommentPolling(options: {
  /** Polling stops entirely when false — e.g. the drawer is closed or comments hidden. */
  enabled: boolean;
  /** Skipped for this tick when true, so a poll cannot race the user's own submit. */
  paused?: boolean;
  intervalMs?: number;
  /** Fetches and applies the latest comments. Errors are the caller's to swallow. */
  refresh: () => Promise<void>;
}) {
  const { enabled, paused = false, intervalMs = 20_000, refresh } = options;

  // Held in a ref so a new closure each render doesn't restart the interval.
  const refreshRef = useRef(refresh);
  refreshRef.current = refresh;
  const pausedRef = useRef(paused);
  pausedRef.current = paused;

  // Guards against overlapping requests when a response is slower than the interval.
  const inFlight = useRef(false);

  useEffect(() => {
    if (!enabled) return;

    const tick = async () => {
      if (pausedRef.current || inFlight.current) return;
      if (typeof document !== 'undefined' && document.hidden) return;
      inFlight.current = true;
      try {
        await refreshRef.current();
      } finally {
        inFlight.current = false;
      }
    };

    const timer = setInterval(tick, intervalMs);

    // Catch up immediately on return to the tab, rather than making the user wait out
    // the remainder of an interval that ticked while the tab was hidden.
    const onVisible = () => { if (!document.hidden) tick(); };
    document.addEventListener('visibilitychange', onVisible);

    return () => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [enabled, intervalMs]);
}
