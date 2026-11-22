import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ClipboardCheck } from 'lucide-react';
import { peerReviewsApi } from '../api';
import type { PeerReview, PeerReviewStatus } from '../types';
import DataTable, { Column, PaginationInfo, SortState, sortParam } from '../components/DataTable';
import { Badge } from '../components';
import { usePageTitle } from '../context/PageTitleContext';
import Page from '../components/Page';
import './PeerReviewQueue.css';

const STATUS_COLORS: Record<PeerReviewStatus, 'warning' | 'info' | 'success'> = {
  PENDING: 'warning',
  IN_REVIEW: 'info',
  COMPLETED: 'success',
};

export default function PeerReviewQueue() {
  const navigate = useNavigate();
  const { setPageTitle } = usePageTitle();

  const [reviews, setReviews] = useState<PeerReview[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [pagination, setPagination] = useState<PaginationInfo>({
    page: 0,
    pageSize: 20,
    total: 0,
    totalPages: 0,
  });

  useEffect(() => {
    setPageTitle('Peer Review Queue');
    return () => setPageTitle(null);
  }, []);

  const [sort, setSort] = useState<SortState | null>(null);

  useEffect(() => {
    loadQueue(pagination.page, pagination.pageSize);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pagination.page, pagination.pageSize, sort]);

  const loadQueue = async (page: number, size: number) => {
    setLoading(true);
    setError('');
    try {
      const res = await peerReviewsApi.getQueue(page, size, sortParam(sort));
      if (res.success && res.data) {
        setReviews(res.data);
        if (res.pagination) {
          setPagination(prev => ({
            ...prev,
            total: res.pagination!.totalElements,
            totalPages: res.pagination!.totalPages,
          }));
        }
      } else {
        setError(res.message || 'Failed to load queue');
      }
    } catch {
      setError('Failed to load peer review queue');
    } finally {
      setLoading(false);
    }
  };

  const columns: Column<PeerReview>[] = [
    {
      header: 'Assessment',
      sortKey: 'assessmentName',
      render: (r) => r.assessmentName || r.assessmentId,
    },
    {
      header: 'Submitted By',
      sortKey: 'submittedByName',
      render: (r) => r.submittedByName || r.submittedByUserId || '—',
    },
    {
      header: 'Submitted At',
      sortKey: 'createdAt',
      render: (r) =>
        r.createdAt ? new Date(r.createdAt).toLocaleDateString() : '—',
    },
    {
      header: 'Reviewer',
      sortKey: 'reviewedByName',
      render: (r) => r.reviewedByName || r.reviewedByUserId || '—',
    },
    {
      header: 'Status',
      sortKey: 'status',
      render: (r) => (
        <Badge variant={STATUS_COLORS[r.status]}>
          {r.status.replace('_', ' ')}
        </Badge>
      ),
    },
  ];

  return (
    <Page className="peer-review-queue">
      <div className="page-header">
        <div className="page-header-title">
          <ClipboardCheck size={20} />
          <h2>Peer Review Queue</h2>
        </div>
      </div>

      {error && <div className="alert alert-danger">{error}</div>}

      <DataTable
        columns={columns}
        data={reviews}
        loading={loading}
        pagination={pagination}
        onPageChange={(page) => setPagination(prev => ({ ...prev, page }))}
        onPageSizeChange={(size) => setPagination(prev => ({ ...prev, pageSize: size, page: 0 }))}
        onSearchChange={() => {}}
        searchable={false}
        searchPlaceholder="Search peer reviews"
        onRowClick={(r) => navigate(`/peer-reviews/${r.id}`)}
        emptyMessage="No reviews pending"
        idAccessor="id"
        sort={sort}
        onSortChange={(next) => {
          // Re-sorting reshuffles the whole queue, so the current page number is
          // meaningless afterwards — go back to the first page.
          setSort(next);
          setPagination(prev => ({ ...prev, page: 0 }));
        }}
      />
    </Page>
  );
}
