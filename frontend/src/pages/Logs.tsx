import { useEffect, useState } from 'react';
import { useEdition } from '../context/EditionContext';
import type { FeatureKey } from '../types';
import { usePageTitle } from '../context/PageTitleContext';
import { auditLogsApi } from '../api';
import type { AiRequestLog } from '../types';
import DataTable, { sortParam } from '../components/DataTable';
import type { Column, SortState } from '../components/DataTable';
import { Modal } from '../components';
import Page from '../components/Page';
import RetestActivityLog from './RetestActivityLog';
import { CheckCircle2, XCircle, ShieldCheck } from 'lucide-react';
import './Logs.css';

// Log categories — each tab owns its own table; more user-action logs can be added here.
// `feature` marks a tab that only exists in builds carrying that capability; the rest of
// the page, and the Logs section itself, stay regardless.
type LogTab = 'ai' | 'retests';
const TABS: { key: LogTab; label: string; feature?: FeatureKey }[] = [
  { key: 'ai', label: 'AI Requests', feature: 'ai_observability' },
  { key: 'retests', label: 'Retests' },
];

const ACTION_LABELS: Record<string, string> = {
  EXECUTE_PROMPT: 'Prompt',
  ASK: 'Ask AI',
  SUGGEST_TITLE: 'Suggest Title',
};

function formatTime(iso: string): string {
  const d = new Date(iso);
  return isNaN(d.getTime()) ? iso : d.toLocaleString();
}

export default function Logs() {
  const { setPageTitle } = usePageTitle();
  const { hasFeature } = useEdition();

  const tabs = TABS.filter(t => !t.feature || hasFeature(t.feature));
  // Opening on a tab this build does not have would show an empty page, so the default is
  // whichever tab is actually first here rather than a hardcoded one.
  const [activeTab, setActiveTab] = useState<LogTab>(tabs[0].key);

  const [logs, setLogs] = useState<AiRequestLog[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState<SortState | null>(null);
  const [pageSize, setPageSize] = useState(25);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(0);

  const [selected, setSelected] = useState<AiRequestLog | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  useEffect(() => {
    setPageTitle('Logs');
  }, []);

  useEffect(() => {
    if (activeTab === 'ai') loadAiLogs();
  }, [activeTab, page, pageSize, sort]);

  const loadAiLogs = async () => {
    setLoading(true);
    try {
      const res = await auditLogsApi.getAiLogs(page, pageSize, undefined, undefined, sortParam(sort));
      setLogs(res.data || []);
      setTotal(res.pagination?.totalElements ?? 0);
      setTotalPages(res.pagination?.totalPages ?? 0);
    } catch {
      setLogs([]);
    } finally {
      setLoading(false);
    }
  };

  const openDetail = async (row: AiRequestLog) => {
    setSelected(row);
    setDetailLoading(true);
    try {
      const res = await auditLogsApi.getAiLog(row.id);
      if (res.data) setSelected(res.data);
    } catch {
      // keep the row-level data we already have
    } finally {
      setDetailLoading(false);
    }
  };

  const columns: Column<AiRequestLog>[] = [
    { header: 'Time', sortKey: 'createdAt', render: r => <span className="logs-time">{formatTime(r.createdAt)}</span> },
    { header: 'User', accessor: 'username', sortKey: 'username' },
    { header: 'Action', sortKey: 'action', render: r => ACTION_LABELS[r.action] || r.action },
    { header: 'Provider', sortKey: 'providerName', render: r => r.providerName ? `${r.providerName}${r.model ? ` · ${r.model}` : ''}` : '—' },
    { header: 'Prompt', sortKey: 'promptName', render: r => r.promptName || '—' },
    {
      header: 'Masked',
      sortKey: 'anonymizationEnabled',
      render: r => r.anonymizationEnabled
        ? <span className="logs-chip logs-chip--on"><ShieldCheck size={12} /> On</span>
        : <span className="logs-chip">Off</span>,
    },
    {
      header: 'Result',
      sortKey: 'success',
      render: r => r.success
        ? <span className="logs-result logs-result--ok"><CheckCircle2 size={14} /> OK</span>
        : <span className="logs-result logs-result--err"><XCircle size={14} /> Failed</span>,
    },
  ];

  return (
    <Page className="logs-page">
      <div className="logs-tabs">
        {tabs.map(t => (
          <button
            key={t.key}
            className={`logs-tab${activeTab === t.key ? ' logs-tab--active' : ''}`}
            onClick={() => setActiveTab(t.key)}
          >
            {t.label}
          </button>
        ))}
      </div>

      {activeTab === 'retests' && <RetestActivityLog />}

      {activeTab === 'ai' && <>
      <p className="logs-intro">
        Every request sent to the AI provider is recorded here, including the exact payload transmitted,
        so you can verify what data did — and did not — leave the system. Records are retained for 30 days.
        Enable logging under Administration → AI Configuration.
      </p>

      <DataTable<AiRequestLog>
        columns={columns}
        data={logs}
        loading={loading}
        pagination={{ page, pageSize, total, totalPages }}
        onPageChange={setPage}
        onPageSizeChange={(s) => { setPageSize(s); setPage(0); }}
        onSearchChange={() => { /* server-side text search not enabled for logs */ }}
        searchable={false}
        searchPlaceholder="Search logs"
        idAccessor="id"
        onRowClick={openDetail}
        emptyMessage="No AI requests logged yet. Enable logging in AI Configuration."
        sort={sort}
        onSortChange={(next) => {
          // Re-sorting reshuffles the whole log, so the current page number is
          // meaningless afterwards — go back to the first page.
          setSort(next);
          setPage(0);
        }}
      />
      </>}

      <Modal
        isOpen={!!selected}
        onClose={() => setSelected(null)}
        title="AI Request Detail"
        size="xl"
      >
        {selected && (
          <div className="logs-detail">
            <div className="logs-detail-grid">
              <div><span className="logs-detail-label">Time</span>{formatTime(selected.createdAt)}</div>
              <div><span className="logs-detail-label">User</span>{selected.username || '—'}</div>
              <div><span className="logs-detail-label">Action</span>{ACTION_LABELS[selected.action] || selected.action}</div>
              <div><span className="logs-detail-label">Provider</span>{selected.providerName || '—'}{selected.model ? ` · ${selected.model}` : ''}</div>
              <div><span className="logs-detail-label">Prompt</span>{selected.promptName || '—'}</div>
              <div><span className="logs-detail-label">Masking</span>{selected.anonymizationEnabled ? 'On' : 'Off'}</div>
              <div><span className="logs-detail-label">Duration</span>{selected.durationMs} ms</div>
              <div><span className="logs-detail-label">Result</span>{selected.success ? 'Success' : (selected.errorMessage || 'Failed')}</div>
              <div><span className="logs-detail-label">Assessment</span>{selected.assessmentId || '—'}</div>
              <div><span className="logs-detail-label">Vulnerability</span>{selected.vulnerabilityId || '—'}</div>
            </div>

            <h4 className="logs-detail-heading">
              Request sent to provider {selected.anonymizationEnabled && <span className="logs-chip logs-chip--on"><ShieldCheck size={11} /> masked</span>}
            </h4>
            <pre className="logs-payload">{detailLoading ? 'Loading…' : (selected.requestPayload || '(not captured)')}</pre>

            <h4 className="logs-detail-heading">Response from provider</h4>
            <pre className="logs-payload">{detailLoading ? 'Loading…' : (selected.responseContent || '(none)')}</pre>
          </div>
        )}
      </Modal>
    </Page>
  );
}
