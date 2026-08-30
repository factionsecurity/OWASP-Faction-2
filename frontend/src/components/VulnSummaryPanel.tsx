import { useEffect, useRef, useState } from 'react';
import { vulnerabilitiesApi } from '../api';
import type { VulnerabilityTrendSummary } from '../types';
import { getCurrentUser, hasPermissionPattern } from '../utils/permissions';
import SeverityPillCard, { positiveEmpty } from './SeverityPillCard';
import './VulnSummaryPanel.css';

export interface VulnSummaryFilters {
  /** Scope the aggregate. Omit all for the caller's full permitted scope. Whatever is passed,
   *  the backend still restricts the aggregate by the user's vulnerability-read scope. */
  organizationIds?: string[];
  subOrganizationId?: string;
  applicationId?: string;
  assessmentId?: string;
  /** The vulnerabilities-list filters, so a summary rendered above that table tracks it.
   *  There is no `includeClosed` — each card below counts open rows by definition. */
  severities?: string[];
  statuses?: string[];
  search?: string;
  /** Opened-date range bounds (ISO date-time), matching the list's Opened Date filter. */
  openedFrom?: string;
  openedTo?: string;
}

/**
 * Self-fetching vulnerability summary: Open Findings / Tracked Open Findings / Outside SLA /
 * Within SLA / Exceptions, each a total over a per-severity stacked bar with legend counts.
 * Every prop is a filter the endpoint applies inside the caller's scope, so passing the
 * vulnerabilities table's own filters keeps the cards in step with the rows below them.
 */
export default function VulnSummaryPanel({
  organizationIds, subOrganizationId, applicationId, assessmentId, severities, statuses, search,
  openedFrom, openedTo,
}: VulnSummaryFilters) {
  const [summary, setSummary] = useState<VulnerabilityTrendSummary | undefined>(undefined);
  const [loading, setLoading] = useState(false);
  const reqRef = useRef(0);

  // Serialized so a new array identity on every parent render can't re-fire the effect
  // (and the request) forever.
  const statusKey = (statuses ?? []).join(',');
  const severityKey = (severities ?? []).join(',');
  const orgKey = (organizationIds ?? []).join(',');

  useEffect(() => {
    // The summary endpoint requires vulnerabilities:read — skip the guaranteed 403 for callers
    // without it (the cards then render their empty state).
    const user = getCurrentUser();
    if (!user || !hasPermissionPattern(user.authorities, /^vulnerabilities:read/)) return;
    const reqId = ++reqRef.current;
    setLoading(true);
    vulnerabilitiesApi.getSummary({
      subOrganizationId, applicationId, assessmentId, search, openedFrom, openedTo,
      organizationIds: orgKey ? orgKey.split(',') : undefined,
      severities: severityKey ? severityKey.split(',') : undefined,
      statuses: statusKey ? statusKey.split(',') : undefined,
    })
      .then(r => { if (reqId === reqRef.current) setSummary(r.data); })
      .catch(() => { if (reqId === reqRef.current) setSummary(undefined); })
      .finally(() => { if (reqId === reqRef.current) setLoading(false); });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orgKey, subOrganizationId, applicationId, assessmentId, severityKey, statusKey, search,
      openedFrom, openedTo]);

  return (
    <div className="vsp-grid">
      <SeverityPillCard
        title="Open Findings"
        counts={summary?.openFindings ?? {}}
        loading={loading}
        emptyContent={positiveEmpty('No Open Findings')}
      />
      <SeverityPillCard
        title="Tracked Open Findings"
        counts={summary?.trackedOpen ?? {}}
        loading={loading}
        emptyContent={positiveEmpty('No Open Issues')}
      />
      <SeverityPillCard
        title="Outside SLA"
        counts={summary?.pastDue ?? {}}
        loading={loading}
        emptyContent={positiveEmpty('Nothing Outside SLA')}
      />
      <SeverityPillCard title="Within SLA" counts={summary?.openOnTime ?? {}} loading={loading} />
      <SeverityPillCard title="Exceptions" counts={summary?.exceptions ?? {}} loading={loading} />
    </div>
  );
}
