import { useEffect, useState } from 'react';
import { Eye } from 'lucide-react';
import { assessmentsApi, vulnerabilitiesApi } from '../api';
import type { Assessment, Vulnerability } from '../types';
import DataTable, { Column, PaginationInfo, SortState } from '../components/DataTable';
import { applyClientSort, SortAccessors } from '../utils/tableSort';
import { Badge, SeverityBadge, IconButton, ActionButtons } from '../components';
import VulnerabilityDetailDrawer from '../components/VulnerabilityDetailDrawer';

const PAGE_SIZE = 15;

interface Props {
  assessment: Assessment;
}

export default function AssessmentHistorySection({ assessment }: Props) {
  const [vulns, setVulns] = useState<Vulnerability[]>([]);
  const [assessmentMap, setAssessmentMap] = useState<Record<string, Assessment>>({});
  const [loading, setLoading] = useState(false);

  const [search, setSearch] = useState('');
  const [showClosed, setShowClosed] = useState(true);
  const [tablePage, setTablePage] = useState(0);
  const [sort, setSort] = useState<SortState | null>(null);

  const [selectedVuln, setSelectedVuln] = useState<Vulnerability | null>(null);
  const [selectedAssessment, setSelectedAssessment] = useState<Assessment | null>(null);

  useEffect(() => {
    loadHistory();
  }, [assessment.id]);

  const loadHistory = async () => {
    setLoading(true);
    try {
      const res = await assessmentsApi.getAll(0, 1000, assessment.applicationId);
      const siblings = (res.data || []).filter(a => a.id !== assessment.id);

      const aMap: Record<string, Assessment> = {};
      for (const a of siblings) aMap[a.id] = a;
      setAssessmentMap(aMap);

      const results = await Promise.all(
        siblings.map(a =>
          vulnerabilitiesApi.getAll(a.id, 0, 1000)
            .then(r => r.data || [])
            .catch(() => [] as Vulnerability[])
        )
      );
      setVulns(results.flat().filter(v => !!v.openedAt));
    } finally {
      setLoading(false);
    }
  };

  const filtered = vulns.filter(v => {
    if (!showClosed && v.closedAt) return false;
    if (search) {
      const q = search.toLowerCase();
      const a = assessmentMap[v.assessmentId];
      if (
        !v.name.toLowerCase().includes(q) &&
        !(v.assetLocation || '').toLowerCase().includes(q) &&
        !(a?.name || '').toLowerCase().includes(q)
      ) return false;
    }
    return true;
  });

  // Every row is already loaded here, so sorting is applied client-side.
  const sortAccessors: SortAccessors<Vulnerability> = {
    name: (v) => v.name,
    severity: (v) => v.severity,
    status: (v) => v.status,
    assessmentName: (v) => assessmentMap[v.assessmentId]?.name,
    openedAt: (v) => v.openedAt,
    closedAt: (v) => v.closedAt,
  };
  const sorted = applyClientSort(filtered, sort, sortAccessors);

  const pagination: PaginationInfo = {
    page: tablePage,
    pageSize: PAGE_SIZE,
    total: filtered.length,
    totalPages: Math.ceil(filtered.length / PAGE_SIZE),
  };

  const page = sorted.slice(tablePage * PAGE_SIZE, (tablePage + 1) * PAGE_SIZE);

  const columns: Column<Vulnerability>[] = [
    {
      header: 'Name',
      sortKey: 'name',
      render: (v) => (
        <div>
          <div className="font-medium">{v.name}</div>
          {v.assetLocation && <div className="text-sm text-muted">{v.assetLocation}</div>}
        </div>
      ),
    },
    {
      header: 'Severity',
      sortKey: 'severity',
      render: (v) => (
        <SeverityBadge severity={v.severity} />
      ),
    },
    {
      header: 'Status',
      sortKey: 'status',
      render: (v) => v.closedAt
        ? <Badge variant="secondary">Closed</Badge>
        : <Badge variant="success">Open</Badge>,
    },
    {
      header: 'Assessment',
      sortKey: 'assessmentName',
      render: (v) => {
        const a = assessmentMap[v.assessmentId];
        if (!a) return <span>-</span>;
        const date = a.startDate
          ? new Date(a.startDate).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
          : null;
        return <span>{date ? `${a.name}: ${date}` : a.name}</span>;
      },
    },
    {
      header: 'Opened',
      sortKey: 'openedAt',
      render: (v) => v.openedAt ? new Date(v.openedAt).toLocaleDateString() : '-',
    },
    {
      header: 'Closed',
      sortKey: 'closedAt',
      render: (v) => v.closedAt ? new Date(v.closedAt).toLocaleDateString() : '-',
    },
    {
      header: 'Actions',
      render: (v) => (
        <ActionButtons>
          <IconButton
            icon={Eye}
            onClick={() => { setSelectedVuln(v); setSelectedAssessment(assessmentMap[v.assessmentId] || null); }}
            title="View Details"
            variant="edit"
          />
        </ActionButtons>
      ),
    },
  ];

  const showClosedToggle = (
    <label className="app-vulns-show-closed">
      <input
        type="checkbox"
        checked={showClosed}
        onChange={e => { setShowClosed(e.target.checked); setTablePage(0); }}
      />
      Show Closed
    </label>
  );

  return (
    <section id="history" className="content-section">
      <DataTable
        columns={columns}
        data={page}
        loading={loading}
        pagination={pagination}
        onPageChange={p => setTablePage(p)}
        onPageSizeChange={() => {}}
        onSearchChange={q => { setSearch(q); setTablePage(0); }}
        searchPlaceholder="Search vulnerabilities"
        emptyMessage="No vulnerabilities found in previous assessments"
        idAccessor="id"
        headerChildren={showClosedToggle}
        sort={sort}
        onSortChange={next => { setSort(next); setTablePage(0); }}
      />

      <VulnerabilityDetailDrawer
        vulnerability={selectedVuln}
        assessment={selectedAssessment}
        onClose={() => { setSelectedVuln(null); setSelectedAssessment(null); }}
        hideComments
      />
    </section>
  );
}
