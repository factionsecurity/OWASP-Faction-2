// Built-in vulnerability lifecycle statuses. Custom statuses from the workflow
// config are appended to these wherever a status picker is shown. The retest
// workflow sets the three retest statuses automatically: scheduling a retest
// moves the vulnerability to "In Retest", completing one to "Passed Retest" or
// "Failed Retest".
export const DEFAULT_VULN_STATUSES = [
  'None',
  'Open',
  'Closed',
  'Past Due',
  'Exception',
  'In Retest',
  'Passed Retest',
  'Failed Retest',
];

export type VulnStatusBadgeVariant = 'success' | 'danger' | 'warning' | 'info' | 'secondary';

export function vulnStatusBadgeVariant(status?: string | null): VulnStatusBadgeVariant {
  switch (status || 'None') {
    case 'Open':          return 'success';
    case 'Past Due':      return 'danger';
    case 'In Retest':     return 'info';
    case 'Passed Retest': return 'success';
    case 'Failed Retest': return 'danger';
    default:              return 'secondary';
  }
}

/**
 * Whether a finding counts as closed.
 *
 * Mirrors the definition the backend queries use (`v.status = 'Closed' OR v.closed_at IS NOT NULL`
 * in VulnerabilityRepositoryImpl): a closed date alone closes a finding, because the remediation
 * flow can stamp one directly — completing the terminal stage ("Production" by default) closes
 * through the status path, but an edit that sets only the closed date leaves the status untouched.
 * Testing the status alone leaves those findings looking open on one screen and closed on another.
 */
export function isVulnClosed(v: { status?: string | null; closedAt?: string | null }): boolean {
  return v.status === 'Closed' || !!v.closedAt;
}
