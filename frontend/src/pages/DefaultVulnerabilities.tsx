import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Edit2, Trash2, Plus, Eye, EyeOff, Archive, ArchiveRestore, Download } from 'lucide-react';
import { defaultVulnerabilitiesApi } from '../api';
import type { DefaultVulnerability, DefaultVulnerabilityImportResult, VulnerabilitySeverity } from '../types';
import DataTable, { Column, PaginationInfo, SortState, sortParam } from '../components/DataTable';
import {
  ConfirmDialog,
  Modal,
  Button,
  IconButton,
  ActionButtons,
  Badge,
  ErrorMessage,
} from '../components';
import { usePermissions } from '../utils/permissions';
import Page from '../components/Page';

const severityBadgeVariant = (s: VulnerabilitySeverity) => {
  switch (s) {
    case 'CRITICAL': return 'danger';
    case 'HIGH': return 'warning';
    case 'MEDIUM': return 'info';
    case 'LOW': return 'success';
    default: return 'secondary';
  }
};

export default function DefaultVulnerabilities() {
  const { permissions } = usePermissions();
  const navigate = useNavigate();

  const [defaultVulns, setDefaultVulns] = useState<DefaultVulnerability[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showArchived, setShowArchived] = useState(false);
  const [sort, setSort] = useState<SortState | null>(null);
  const [pagination, setPagination] = useState<PaginationInfo>({ page: 0, pageSize: 25, total: 0, totalPages: 0 });
  const [dvToDelete, setDvToDelete] = useState<string | null>(null);
  const [showImportConfirm, setShowImportConfirm] = useState(false);
  const [importing, setImporting] = useState(false);
  const [importResult, setImportResult] = useState<DefaultVulnerabilityImportResult | null>(null);
  const [importError, setImportError] = useState('');

  useEffect(() => {
    loadDefaultVulns();
  }, [pagination.page, pagination.pageSize, showArchived, sort]);

  const loadDefaultVulns = async () => {
    try {
      setLoading(true);
      const response = await defaultVulnerabilitiesApi.getAll(
        pagination.page, pagination.pageSize, sortParam(sort) ?? 'order,asc', showArchived);
      if (response.data) {
        setDefaultVulns(response.data);
        setPagination(prev => ({
          ...prev,
          total: response.pagination?.totalElements || 0,
          totalPages: response.pagination?.totalPages || 0,
        }));
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load default vulnerabilities');
    } finally {
      setLoading(false);
    }
  };

  const handleToggleArchive = async (dv: DefaultVulnerability) => {
    try {
      if (dv.archived) {
        await defaultVulnerabilitiesApi.unarchive(dv.id);
      } else {
        await defaultVulnerabilitiesApi.archive(dv.id);
      }
      await loadDefaultVulns();
    } catch (err: any) {
      alert(err.response?.data?.message || 'Failed to update archive status');
    }
  };

  const confirmDelete = async () => {
    if (!dvToDelete) return;
    try {
      await defaultVulnerabilitiesApi.delete(dvToDelete);
      await loadDefaultVulns();
    } catch (err: any) {
      alert(err.response?.data?.message || 'Failed to delete default vulnerability');
    } finally {
      setDvToDelete(null);
    }
  };

  const handleImport = async () => {
    setShowImportConfirm(false);
    try {
      setImporting(true);
      setImportError('');
      const response = await defaultVulnerabilitiesApi.import();
      if (response.data) {
        setImportResult(response.data);
        await loadDefaultVulns();
      }
    } catch (err: any) {
      setImportError(err.response?.data?.message || 'Failed to import default vulnerabilities');
    } finally {
      setImporting(false);
    }
  };

  const handlePageChange = useCallback((page: number) => setPagination(prev => ({ ...prev, page })), []);
  const handlePageSizeChange = useCallback((pageSize: number) => setPagination(prev => ({ ...prev, pageSize, page: 0 })), []);
  const handleSearchChange = useCallback((_search: string) => {
    setPagination(prev => ({ ...prev, page: 0 }));
  }, []);

  const columns: Column<DefaultVulnerability>[] = [
    { header: 'Name', accessor: 'name', sortKey: 'name' },
    {
      header: 'Severity',
      sortKey: 'severity',
      render: (dv) => (
        <Badge variant={severityBadgeVariant(dv.severity)}>
          {dv.severity}
        </Badge>
      ),
    },
    { header: 'Likelihood', sortKey: 'likelihood', render: (dv) => dv.likelihood || '-' },
    { header: 'Impact', sortKey: 'impact', render: (dv) => dv.impact || '-' },
    { header: 'Category', render: (dv) => dv.vulnerabilityCategory?.name || '-' },
    {
      header: 'CVSS',
      sortKey: 'cvssScore31',
      render: (dv) => {
        const s31 = dv.cvssScore31 != null ? dv.cvssScore31.toFixed(1) : null;
        const s40 = dv.cvssScore40 != null ? dv.cvssScore40.toFixed(1) : null;
        if (!s31 && !s40) return '-';
        return (
          <span style={{ fontSize: '0.8rem', lineHeight: 1.4 }}>
            {s31 && <span>3.1: {s31}</span>}
            {s31 && s40 && <br />}
            {s40 && <span>4.0: {s40}</span>}
          </span>
        );
      },
    },
    {
      header: 'Status',
      sortKey: 'archivedAt',
      render: (dv) => (
        <Badge variant={dv.archived ? 'secondary' : 'success'}>
          {dv.archived ? 'Archived' : 'Active'}
        </Badge>
      ),
    },
    {
      header: 'Actions',
      render: (dv) => (
        <ActionButtons>
          {permissions.canEditDefaultVulnerabilities && (
            <IconButton icon={Edit2} variant="edit" title="Edit" onClick={() => navigate(`/default-vulnerabilities/${dv.id}/edit`)} />
          )}
          {permissions.canEditDefaultVulnerabilities && (
            <IconButton
              icon={dv.archived ? ArchiveRestore : Archive}
              variant={dv.archived ? 'success' : 'warning'}
              title={dv.archived ? 'Unarchive' : 'Archive'}
              onClick={() => handleToggleArchive(dv)}
            />
          )}
          {permissions.canDeleteDefaultVulnerabilities && (
            <IconButton icon={Trash2} variant="delete" title="Delete" onClick={() => setDvToDelete(dv.id)} />
          )}
        </ActionButtons>
      ),
    },
  ];

  return (
    <Page>
      <div className="page-header">
        <div />
        <div className="section-actions">
          <Button
            variant="secondary"
            icon={showArchived ? EyeOff : Eye}
            onClick={() => { setShowArchived(v => !v); setPagination(prev => ({ ...prev, page: 0 })); }}
          >
            {showArchived ? 'Hide Archived' : 'Show Archived'}
          </Button>
          {permissions.canCreateDefaultVulnerabilities && (
            <Button
              variant="secondary"
              icon={Download}
              onClick={() => setShowImportConfirm(true)}
              disabled={importing}
            >
              {importing ? 'Importing...' : 'Import Default Vulnerabilities'}
            </Button>
          )}
          {permissions.canCreateDefaultVulnerabilities && (
            <Button variant="primary" icon={Plus} onClick={() => navigate('/default-vulnerabilities/new')}>
              Add Default Vulnerability
            </Button>
          )}
        </div>
      </div>

      {error && <ErrorMessage>{error}</ErrorMessage>}
      {importError && <ErrorMessage>{importError}</ErrorMessage>}

      <DataTable
        columns={columns}
        data={defaultVulns}
        loading={loading}
        pagination={pagination}
        onPageChange={handlePageChange}
        onPageSizeChange={handlePageSizeChange}
        onSearchChange={handleSearchChange}
        searchPlaceholder="Search default vulnerabilities"
        emptyMessage="No default vulnerabilities found."
        idAccessor="id"
        sort={sort}
        onSortChange={(next) => {
          // Re-sorting reshuffles the whole list, so the current page number is
          // meaningless afterwards — go back to the first page. Clearing the sort
          // restores the configured display order.
          setSort(next);
          setPagination((prev) => ({ ...prev, page: 0 }));
        }}
      />

      <ConfirmDialog
        isOpen={!!dvToDelete}
        onClose={() => setDvToDelete(null)}
        onConfirm={confirmDelete}
        title="Delete Default Vulnerability"
        message="Are you sure you want to delete this default vulnerability? This action cannot be undone."
        confirmText="Delete"
        variant="danger"
      />

      <ConfirmDialog
        isOpen={showImportConfirm}
        onClose={() => setShowImportConfirm(false)}
        onConfirm={handleImport}
        title="Import Default Vulnerabilities"
        message="This will pull the public default vulnerability template library and add any entries that don't already exist (matched by name). Existing entries are left untouched."
        confirmText="Import"
        variant="info"
      />

      <Modal
        isOpen={!!importResult}
        onClose={() => setImportResult(null)}
        title="Import Complete"
        size="sm"
        footer={<Button variant="primary" onClick={() => setImportResult(null)}>Close</Button>}
      >
        {importResult && (
          <div>
            <p>{importResult.importedCount} imported, {importResult.skippedCount} skipped (already present), {importResult.categoriesCreatedCount} categories created.</p>
            {importResult.errors.length > 0 && (
              <>
                <p>{importResult.errors.length} entr{importResult.errors.length === 1 ? 'y' : 'ies'} could not be imported:</p>
                <ul style={{ maxHeight: 200, overflowY: 'auto', fontSize: '0.85rem' }}>
                  {importResult.errors.map((e, i) => <li key={i}>{e}</li>)}
                </ul>
              </>
            )}
          </div>
        )}
      </Modal>
    </Page>
  );
}
