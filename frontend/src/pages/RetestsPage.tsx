import { useCallback, useEffect, useState } from 'react';
import { SeverityBadge } from '../components';
import { useNavigate } from 'react-router-dom';
import { retestApi } from '../api';
import type { Retest } from '../types';
import DataTable, { Column, PaginationInfo, SortState } from '../components/DataTable';
import { applyClientSort, SortAccessors } from '../utils/tableSort';
import Page from '../components/Page';
import './RetestsPage.css';

const columns: Column<Retest>[] = [
  {
    header: 'Vulnerability',
    sortKey: 'vulnerabilityName',
    render: r => r.vulnerabilityName || r.vulnerabilityId,
  },
  {
    header: 'Assessment',
    sortKey: 'assessmentName',
    render: r => r.assessmentName || r.assessmentId,
  },
  {
    header: 'Severity',
    sortKey: 'vulnerabilitySeverity',
    render: r => r.vulnerabilitySeverity ? (
      <SeverityBadge severity={r.vulnerabilitySeverity} />
    ) : '-',
  },
  {
    header: 'Start Date',
    sortKey: 'scheduledStartDate',
    render: r => r.scheduledStartDate ? new Date(r.scheduledStartDate).toLocaleDateString() : '-',
  },
  {
    header: 'End Date',
    sortKey: 'scheduledEndDate',
    render: r => r.scheduledEndDate ? new Date(r.scheduledEndDate).toLocaleDateString() : '-',
  },
  {
    header: 'Assigned To',
    sortKey: 'assignedAssessorNames',
    render: r => r.assignedAssessorNames?.join(', ') || '-',
  },
];

// This list comes from an unpaginated endpoint, so sorting happens here rather than server-side.
const SORT_ACCESSORS: SortAccessors<Retest> = {
  vulnerabilityName: r => r.vulnerabilityName || r.vulnerabilityId,
  assessmentName: r => r.assessmentName || r.assessmentId,
  vulnerabilitySeverity: r => r.vulnerabilitySeverity,
  scheduledStartDate: r => r.scheduledStartDate,
  scheduledEndDate: r => r.scheduledEndDate,
  assignedAssessorNames: r => r.assignedAssessorNames?.join(', '),
};

const PAGE_SIZE = 15;

export default function RetestsPage() {
  const navigate = useNavigate();
  const [allRetests, setAllRetests] = useState<Retest[]>([]);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState<SortState | null>(null);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(PAGE_SIZE);

  useEffect(() => {
    setLoading(true);
    retestApi.getAll({ assignedToMe: true })
      .then(res => {
        if (res.success && res.data) {
          setAllRetests((res.data as Retest[]).filter(r => r.status === 'SCHEDULED'));
        }
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  const filtered = applyClientSort(allRetests.filter(r => {
    if (!search) return true;
    const q = search.toLowerCase();
    return (
      (r.vulnerabilityName || '').toLowerCase().includes(q) ||
      (r.assessmentName || '').toLowerCase().includes(q) ||
      (r.vulnerabilitySeverity || '').toLowerCase().includes(q) ||
      (r.assignedAssessorNames || []).some(n => n.toLowerCase().includes(q))
    );
  }), sort, SORT_ACCESSORS);

  const totalPages = Math.max(1, Math.ceil(filtered.length / pageSize));
  const pageData = filtered.slice(page * pageSize, (page + 1) * pageSize);

  const pagination: PaginationInfo = {
    page,
    pageSize,
    total: filtered.length,
    totalPages,
  };

  const handleSearchChange = useCallback((s: string) => {
    setSearch(s);
    setPage(0);
  }, []);

  const handlePageSizeChange = useCallback((ps: number) => {
    setPageSize(ps);
    setPage(0);
  }, []);

  return (
    <Page className="retests-page">
      <DataTable
        columns={columns}
        data={pageData}
        loading={loading}
        pagination={pagination}
        onPageChange={setPage}
        onPageSizeChange={handlePageSizeChange}
        onSearchChange={handleSearchChange}
        searchPlaceholder="Search retests"
        emptyMessage="No scheduled retests assigned to you."
        idAccessor="id"
        onRowClick={r => navigate(`/retests/${r.id}`)}
        sort={sort}
        onSortChange={next => { setSort(next); setPage(0); }}
      />
    </Page>
  );
}
