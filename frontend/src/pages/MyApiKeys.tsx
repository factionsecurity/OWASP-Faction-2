import { useEffect, useState, useCallback } from 'react';
import { Plus, Trash2, Copy, Check, ExternalLink } from 'lucide-react';
import { usePageTitle } from '../context/PageTitleContext';
import { apiKeysApi } from '../api';
import type { ApiKey, ApiKeyScope } from '../types';
import DataTable, { Column, PaginationInfo, SortState } from '../components/DataTable';
import { applyClientSort, SortAccessors } from '../utils/tableSort';
import {
  Modal,
  ConfirmDialog,
  Button,
  IconButton,
  ActionButtons,
  Badge,
  FormGroup,
  FormLabel,
  Input,
  FormHint,
  ErrorMessage,
  Toast,
} from '../components';
import Page from '../components/Page';
import { usePermissions } from '../utils/permissions';
import './MyApiKeys.css';

const PAGE_SIZE = 10;

function formatDate(value?: string): string {
  if (!value) return '—';
  const d = new Date(value);
  return isNaN(d.getTime()) ? '—' : d.toLocaleDateString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric',
  });
}

function scopeBadge(scope: ApiKeyScope) {
  if (scope === 'READ_ONLY') return <Badge variant="secondary">Read only</Badge>;
  return <Badge variant="info">Read &amp; write</Badge>;
}

export default function MyApiKeys() {
  const { setPageTitle } = usePageTitle();
  const { hasPermission } = usePermissions();
  const canCreate = hasPermission('apikeys:create:self');
  const canRevoke = hasPermission('apikeys:delete:self');

  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [toast, setToast] = useState<string | null>(null);

  // Client-side paging/search — a user's own key list is small and returned unpaginated.
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState<SortState | null>(null);
  const [pageSize, setPageSize] = useState(PAGE_SIZE);

  // Create dialog
  const [showCreate, setShowCreate] = useState(false);
  const [createName, setCreateName] = useState('');
  const [createScope, setCreateScope] = useState<ApiKeyScope>('READ_WRITE');
  const [createError, setCreateError] = useState('');
  const [creating, setCreating] = useState(false);

  // One-time secret reveal
  const [revealSecret, setRevealSecret] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  // Revoke confirmation
  const [revokeTarget, setRevokeTarget] = useState<ApiKey | null>(null);
  const [revoking, setRevoking] = useState(false);

  const load = useCallback(async () => {
    try {
      setLoading(true);
      const response = await apiKeysApi.listMine();
      setKeys(response.data || []);
    } catch (err: any) {
      setLoadError(err.response?.data?.message || 'Failed to load API keys');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    setPageTitle('API Keys');
    load();
  }, [setPageTitle, load]);

  const openCreate = () => {
    setCreateName('');
    setCreateScope('READ_WRITE');
    setCreateError('');
    setShowCreate(true);
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setCreateError('');
    setCreating(true);
    try {
      const response = await apiKeysApi.createMine({ name: createName.trim(), scope: createScope });
      setShowCreate(false);
      // Surface the plaintext exactly once — it is never retrievable again.
      setRevealSecret(response.data?.key ?? null);
      setCopied(false);
      await load();
    } catch (err: any) {
      setCreateError(err.response?.data?.message || 'Failed to create API key');
    } finally {
      setCreating(false);
    }
  };

  const handleRevoke = async () => {
    if (!revokeTarget) return;
    setRevoking(true);
    try {
      await apiKeysApi.revokeMine(revokeTarget.id);
      setRevokeTarget(null);
      setToast('API key revoked');
      await load();
    } catch (err: any) {
      setToast(err.response?.data?.message || 'Failed to revoke API key');
    } finally {
      setRevoking(false);
    }
  };

  const copySecret = async () => {
    if (!revealSecret) return;
    try {
      await navigator.clipboard.writeText(revealSecret);
      setCopied(true);
    } catch {
      setToast('Could not copy to clipboard — copy it manually');
    }
  };

  const handleSearchChange = useCallback((value: string) => {
    setSearch(value);
    setPage(0);
  }, []);

  const handlePageChange = useCallback((p: number) => setPage(p), []);
  const handlePageSizeChange = useCallback((s: number) => {
    setPageSize(s);
    setPage(0);
  }, []);

  // This list comes from an unpaginated endpoint, so it is filtered, sorted, and
  // paged here rather than by the server.
  const sortAccessors: SortAccessors<ApiKey> = {
    name: (k) => k.name,
    scope: (k) => k.scope,
    lastUsedAt: (k) => k.lastUsedAt,
    createdAt: (k) => k.createdAt,
  };
  const filtered = applyClientSort(
    keys.filter((k) => k.name.toLowerCase().includes(search.toLowerCase())),
    sort,
    sortAccessors,
  );
  const pageData = filtered.slice(page * pageSize, page * pageSize + pageSize);
  const pagination: PaginationInfo = {
    page,
    pageSize,
    total: filtered.length,
    totalPages: Math.max(1, Math.ceil(filtered.length / pageSize)),
  };

  const columns: Column<ApiKey>[] = [
    {
      header: 'Name',
      sortKey: 'name',
      accessor: 'name',
      render: (k) => <span className="font-medium">{k.name}</span>,
    },
    {
      header: 'Key',
      render: (k) => <code className="apikey-hint">{k.hint}…</code>,
    },
    {
      header: 'Scope',
      sortKey: 'scope',
      render: (k) => scopeBadge(k.scope),
    },
    {
      header: 'Last used',
      sortKey: 'lastUsedAt',
      render: (k) => <span className="text-sm text-muted">{k.lastUsedAt ? formatDate(k.lastUsedAt) : 'Never'}</span>,
    },
    {
      header: 'Created',
      sortKey: 'createdAt',
      render: (k) => <span className="text-sm text-muted">{formatDate(k.createdAt)}</span>,
    },
    {
      header: 'Actions',
      width: '80px',
      render: (k) =>
        canRevoke ? (
          <ActionButtons>
            <IconButton icon={Trash2} variant="delete" title="Revoke" onClick={() => setRevokeTarget(k)} />
          </ActionButtons>
        ) : null,
    },
  ];

  return (
    <Page className="my-api-keys-page">
      <div className="page-header">
        <div className="page-header-text">
          <p className="page-subtitle">
            Programmatic keys for calling the API as yourself. A key's access always mirrors your
            current permissions — <strong>Read &amp; write</strong> grants everything you can do,
            <strong> Read only</strong> grants the read-only slice. To change a key's scope, revoke it
            and create a new one. Browse the available endpoints in the{' '}
            <a
              className="apikey-docs-link"
              href="/swagger-ui/index.html"
              target="_blank"
              rel="noopener noreferrer"
            >
              API documentation <ExternalLink size={12} />
            </a>.
          </p>
        </div>
        {canCreate && (
          <Button variant="primary" icon={Plus} onClick={openCreate}>
            Create API Key
          </Button>
        )}
      </div>

      {loadError && <ErrorMessage>{loadError}</ErrorMessage>}

      <DataTable
        columns={columns}
        data={pageData}
        loading={loading}
        pagination={pagination}
        onPageChange={handlePageChange}
        onPageSizeChange={handlePageSizeChange}
        onSearchChange={handleSearchChange}
        searchPlaceholder="Search API keys"
        emptyMessage="You have no API keys yet."
        idAccessor="id"
        sort={sort}
        onSortChange={(next) => { setSort(next); setPage(0); }}
      />

      {/* Create */}
      <Modal
        isOpen={showCreate}
        onClose={() => setShowCreate(false)}
        title="Create API Key"
        size="md"
        closeOnOverlayClick={false}
        onSubmit={handleCreate}
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowCreate(false)}>
              Cancel
            </Button>
            <Button type="submit" variant="primary" disabled={creating || !createName.trim()}>
              {creating ? 'Creating…' : 'Create'}
            </Button>
          </>
        }
      >
        {createError && <ErrorMessage>{createError}</ErrorMessage>}

        <FormGroup>
          <FormLabel required>Name</FormLabel>
          <Input
            type="text"
            value={createName}
            onChange={(e) => setCreateName(e.target.value)}
            required
            autoFocus
            placeholder="e.g. CI pipeline"
          />
          <FormHint>A label to help you recognize this key later.</FormHint>
        </FormGroup>

        <FormGroup>
          <FormLabel required>Scope</FormLabel>
          <div className="scope-options">
            <label className={`scope-option ${createScope === 'READ_WRITE' ? 'selected' : ''}`}>
              <input
                type="radio"
                name="scope"
                checked={createScope === 'READ_WRITE'}
                onChange={() => setCreateScope('READ_WRITE')}
              />
              <span className="scope-option-body">
                <span className="scope-option-title">Read &amp; write</span>
                <span className="scope-option-desc">Everything you can currently do.</span>
              </span>
            </label>
            <label className={`scope-option ${createScope === 'READ_ONLY' ? 'selected' : ''}`}>
              <input
                type="radio"
                name="scope"
                checked={createScope === 'READ_ONLY'}
                onChange={() => setCreateScope('READ_ONLY')}
              />
              <span className="scope-option-body">
                <span className="scope-option-title">Read only</span>
                <span className="scope-option-desc">The read-only slice of your access.</span>
              </span>
            </label>
          </div>
        </FormGroup>
      </Modal>

      {/* One-time secret reveal */}
      <Modal
        isOpen={revealSecret !== null}
        onClose={() => setRevealSecret(null)}
        title="Copy your API key"
        size="md"
        closeOnOverlayClick={false}
        footer={
          <Button variant="primary" onClick={() => setRevealSecret(null)}>
            Done
          </Button>
        }
      >
        <div className="secret-warning">
          This is the only time the full key will be shown. Copy it now and store it securely — it
          cannot be retrieved again.
        </div>
        <div className="secret-reveal">
          <code className="secret-value">{revealSecret}</code>
          <Button variant="secondary" icon={copied ? Check : Copy} onClick={copySecret}>
            {copied ? 'Copied' : 'Copy'}
          </Button>
        </div>
      </Modal>

      {/* Revoke */}
      <ConfirmDialog
        isOpen={revokeTarget !== null}
        onClose={() => setRevokeTarget(null)}
        onConfirm={handleRevoke}
        title="Revoke API key"
        message={`Revoke "${revokeTarget?.name ?? ''}"? Any tool using this key will immediately lose access. This cannot be undone.`}
        confirmText="Revoke"
        variant="danger"
        isLoading={revoking}
      />

      {toast && <Toast message={toast} onDone={() => setToast(null)} />}
    </Page>
  );
}
