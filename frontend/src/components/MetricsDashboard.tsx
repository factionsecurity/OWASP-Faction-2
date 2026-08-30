import { AssessmentMetrics } from '../types';

interface MetricsDashboardProps {
  metrics: AssessmentMetrics | null;
  onStatusClick?: (status: string) => void;
  loading?: boolean;
}

interface MetricCard {
  label: string;
  value: number;
  colorClass: string;
  status: string | null;
}

export default function MetricsDashboard({
  metrics,
  onStatusClick,
  loading = false,
}: MetricsDashboardProps) {
  if (loading) {
    return (
      <div className="metrics-dashboard">
        <div className="stats-grid">
          {[...Array(5)].map((_, i) => (
            <div key={i} className="stat-card">
              <div className="placeholder-glow w-100">
                <span className="placeholder col-6 mb-2"></span>
                <span className="placeholder col-8"></span>
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (!metrics) {
    return null;
  }

  const metricCards: MetricCard[] = [
    {
      label: 'Total Assessments',
      value: metrics.totalCount,
      colorClass: 'info',
      status: null,
    },
    {
      label: 'Draft',
      value: metrics.draftCount,
      colorClass: 'secondary',
      status: 'DRAFT',
    },
    {
      label: 'In Progress',
      value: metrics.inProgressCount,
      colorClass: 'primary',
      status: 'IN_PROGRESS',
    },
    {
      label: 'On Hold',
      value: metrics.onHoldCount,
      colorClass: 'warning',
      status: 'ON_HOLD',
    },
    {
      label: 'Pending Review',
      value: metrics.pendingReviewCount,
      colorClass: 'info',
      status: 'PENDING_REVIEW',
    },
    {
      label: 'Completed',
      value: metrics.completedCount,
      colorClass: 'success',
      status: 'COMPLETED',
    },
    {
      label: 'Approved',
      value: metrics.approvedCount,
      colorClass: 'success',
      status: 'APPROVED',
    },
    {
      label: 'Archived',
      value: metrics.archivedCount,
      colorClass: 'secondary',
      status: 'ARCHIVED',
    },
    {
      label: 'Past Due',
      value: metrics.pastDueCount,
      colorClass: 'danger',
      status: 'past_due',
    },
  ];

  return (
    <div className="metrics-dashboard">
      <div className="stats-grid">
        {metricCards.map((card) => (
          <div
            key={card.label}
            className={`stat-card ${onStatusClick && card.status ? 'clickable' : ''}`}
            onClick={() => onStatusClick && card.status && onStatusClick(card.status)}
          >
            <div className={`stat-icon ${card.colorClass}`}>
              <span className="stat-number">{card.value}</span>
            </div>
            <div className="stat-info">
              <p className="stat-label">{card.label}</p>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
