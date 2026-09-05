import { useEffect, useState, useCallback, useMemo } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Eye, Trash2, Plus, Network, X, ShieldCheck, ShieldAlert, Upload, Download } from 'lucide-react';
import ApplicationsAssessmentsTab from './ApplicationsAssessmentsTab';
import VulnerabilitiesView from './VulnerabilitiesView';
import VulnSummaryPanel from '../components/VulnSummaryPanel';
import { applicationsApi, organizationsApi, entityFieldsApi, regionConfigApi, subOrganizationsApi } from '../api';
import type { Application, CreateApplicationRequest, UpdateApplicationRequest, ApplicationStatus, ApplicationUrl, Stakeholder, AppOwner, Organization, SubOrganization, ApplicationImportResult, UserDefinedField } from '../types';
import RichTextEditor from '../components/RichTextEditor';
import DataTable, { Column, PaginationInfo, SortState, sortParam } from '../components/DataTable';
import SearchableSelect from '../components/SearchableSelect';
import Page from '../components/Page';
import {
  Modal,
  Button,
  IconButton,
  ActionButtons,
  Badge,
  FormGroup,
  FormLabel,
  FormRow,
  Input,
  Select,
} from '../components';
import '../components/SearchableSelect.css';
import './Applications.css';
import { useTerminology } from '../context/TerminologyContext';

const APPLICATION_STATUS_COLORS: Record<ApplicationStatus, 'success' | 'warning' | 'info' | 'danger'> = {
  PRODUCTION: 'success',
  DEVELOPMENT: 'info',
  STAGING: 'warning',
  TESTING: 'info',
  DECOMMISSIONED: 'danger',
  PLANNED: 'warning',
};

/** Filter options for the status pill; the same set the create/edit form offers. */
const APPLICATION_STATUSES = Object.keys(APPLICATION_STATUS_COLORS) as ApplicationStatus[];

const COMMON_TECHNOLOGIES = [
  'Java', 'JavaScript', 'TypeScript', 'Python', 'C#', 'Go', 'Rust', 'PHP',
  'React', 'Angular', 'Vue', 'Node.js', 'Spring Boot', '.NET', 'Django', 'Flask',
  'PostgreSQL', 'MySQL', 'MongoDB', 'Redis', 'Oracle', 'SQL Server',
  'AWS', 'Azure', 'GCP', 'Docker', 'Kubernetes', 'Jenkins',
  'REST API', 'GraphQL', 'gRPC', 'Apache', 'Nginx', 'Tomcat',
];

type ApplicationsTab = 'applications' | 'assessments' | 'vulnerabilities';

export default function Applications() {
  const { organizationSingular } = useTerminology();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const tabParam = searchParams.get('tab');
  const [activeTab, setActiveTab] = useState<ApplicationsTab>(
    tabParam === 'applications' || tabParam === 'assessments' || tabParam === 'vulnerabilities'
      ? tabParam
      : 'applications'
  );
  const [applications, setApplications] = useState<Application[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showModal, setShowModal] = useState(false);
  const [organizations, setOrganizations] = useState<Organization[]>([]);

  const user = JSON.parse(localStorage.getItem('user') || '{}');
  const authorities: string[] = user.authorities || [];

  const [pagination, setPagination] = useState<PaginationInfo>({
    page: 0,
    pageSize: 10,
    total: 0,
    totalPages: 0,
  });

  const [searchQuery, setSearchQuery] = useState('');
  const [sort, setSort] = useState<SortState | null>(null);
  const [filterOrganization, setFilterOrganization] = useState('');
  const [filterSubOrganization, setFilterSubOrganization] = useState('');
  const [filterStatus, setFilterStatus] = useState<ApplicationStatus | ''>('');
  // Every division the user can see, so the picker works with or without an organization chosen.
  const [subOrganizations, setSubOrganizations] = useState<SubOrganization[]>([]);

  // CSV sync (admins only)
  const [showImport, setShowImport] = useState(false);
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importing, setImporting] = useState(false);
  const [importResult, setImportResult] = useState<ApplicationImportResult | null>(null);
  const [importError, setImportError] = useState('');

  const [regions, setRegions] = useState<string[]>([]);

  // Form state
  const [formData, setFormData] = useState({
    name: '',
    appId: '',
    description: '',
    status: 'DEVELOPMENT' as ApplicationStatus,
    organizationId: '',
    region: 'Global',
    applicationType: '',
    assessmentFrequency: 'Ad Hoc',
  });

  const [urls, setUrls] = useState<ApplicationUrl[]>([]);
  const [newUrl, setNewUrl] = useState({ url: '', title: '' });

  const [stakeholders, setStakeholders] = useState<Stakeholder[]>([]);
  const [newStakeholder, setNewStakeholder] = useState({ name: '', email: '', role: '' });

  const [technologies, setTechnologies] = useState<string[]>([]);
  const [newTechnology, setNewTechnology] = useState('');

  const [appOwner, setAppOwner] = useState<AppOwner>({ fullName: '', email: '' });

  const [fieldDefinitions, setFieldDefinitions] = useState<UserDefinedField[]>([]);
  const [fieldValues, setFieldValues] = useState<Record<string, string>>({});

  useEffect(() => {
    regionConfigApi.getRegions().then(setRegions).catch(() => {});
  }, []);

  useEffect(() => {
    loadApplications();
    loadOrganizations();
    loadFieldDefinitions();
  }, [pagination.page, pagination.pageSize, searchQuery, sort,
      filterOrganization, filterSubOrganization, filterStatus]);

  useEffect(() => {
    subOrganizationsApi.listAll()
      .then((res) => setSubOrganizations(res.data || []))
      .catch(() => setSubOrganizations([]));
  }, []);

  const loadApplications = async () => {
    try {
      setLoading(true);
      setError('');
      const response = await applicationsApi.getAll(
        pagination.page, pagination.pageSize, searchQuery, sortParam(sort),
        {
          organizationId: filterOrganization,
          subOrganizationId: filterSubOrganization,
          status: filterStatus,
        });
      if (response.data) {
        setApplications(response.data);
        setPagination({
          page: response.pagination?.page || 0,
          pageSize: response.pagination?.size || 10,
          total: response.pagination?.totalElements || 0,
          totalPages: response.pagination?.totalPages || 0,
        });
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load applications');
    } finally {
      setLoading(false);
    }
  };

  const loadOrganizations = async () => {
    const canReadOrgs = authorities.includes('super_admin') ||
      authorities.some((a) => a.match(/^organizations:read/));
    if (!canReadOrgs) return;
    try {
      const response = await organizationsApi.getAll(0, 1000);
      if (response.data) {
        setOrganizations(response.data);
      }
    } catch (err: any) {
      console.error('Failed to load organizations:', err);
    }
  };

  const loadFieldDefinitions = async () => {
    try {
      const response = await entityFieldsApi.getConfig('APPLICATION');
      if (response.data) {
        const sorted = [...(response.data.fieldDefinitions || [])].sort(
          (a, b) => (a.displayOrder ?? 0) - (b.displayOrder ?? 0)
        );
        setFieldDefinitions(sorted);
      }
    } catch (err: any) {
      console.error('Failed to load application field definitions:', err);
    }
  };


  const resetForm = () => {
    setFormData({
      name: '',
      appId: '',
      description: '',
      status: 'DEVELOPMENT',
      organizationId: '',
      region: 'Global',
      applicationType: '',
      assessmentFrequency: 'Ad Hoc',
    });
    setUrls([]);
    setNewUrl({ url: '', title: '' });
    setStakeholders([]);
    setNewStakeholder({ name: '', email: '', role: '' });
    setTechnologies([]);
    setNewTechnology('');
    setAppOwner({ fullName: '', email: '' });
    setFieldValues({});
  };

  const handleCreate = () => {
    resetForm();
    setShowModal(true);
  };

  const handleEdit = (application: Application) => {
    navigate(`/applications/${application.id}/edit`);
  };

  const handleDelete = async (id: string) => {
    if (!confirm('Are you sure you want to delete this application?')) return;

    try {
      await applicationsApi.delete(id);
      await loadApplications();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to delete application');
    }
  };

  const handleAddUrl = () => {
    if (!newUrl.url || !newUrl.title) return;

    // Validate URL format
    try {
      new URL(newUrl.url);
      setUrls([...urls, { ...newUrl }]);
      setNewUrl({ url: '', title: '' });
    } catch (err) {
      setError('Please enter a valid URL (e.g., https://example.com)');
    }
  };

  const handleRemoveUrl = (index: number) => {
    setUrls(urls.filter((_, i) => i !== index));
  };

  const handleAddStakeholder = () => {
    if (!newStakeholder.name || !newStakeholder.email || !newStakeholder.role) return;

    // Validate email format
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!emailRegex.test(newStakeholder.email)) {
      setError('Please enter a valid email address');
      return;
    }

    setStakeholders([...stakeholders, { ...newStakeholder }]);
    setNewStakeholder({ name: '', email: '', role: '' });
  };

  const handleRemoveStakeholder = (index: number) => {
    setStakeholders(stakeholders.filter((_, i) => i !== index));
  };

  const handleAddTechnology = () => {
    if (newTechnology && !technologies.includes(newTechnology)) {
      setTechnologies([...technologies, newTechnology]);
      setNewTechnology('');
    }
  };

  const handleToggleTechnology = (tech: string) => {
    if (technologies.includes(tech)) {
      setTechnologies(technologies.filter((t) => t !== tech));
    } else {
      setTechnologies([...technologies, tech]);
    }
  };

  const handleRemoveTechnology = (tech: string) => {
    setTechnologies(technologies.filter((t) => t !== tech));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    try {
      const requestData: CreateApplicationRequest | UpdateApplicationRequest = {
        ...formData,
        organizationId: formData.organizationId || undefined, // Convert empty string to undefined
        urls: urls.length > 0 ? urls : undefined,
        stakeHolders: stakeholders.length > 0 ? stakeholders : undefined,
        technologies: technologies.length > 0 ? technologies : undefined,
        appOwner: appOwner.fullName && appOwner.email ? appOwner : undefined,
        fieldValues: Object.keys(fieldValues).length > 0 ? fieldValues : undefined,
      };

      await applicationsApi.create(requestData as CreateApplicationRequest);

      setShowModal(false);
      await loadApplications();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to create application');
    }
  };

  const handlePageChange = useCallback((page: number) => {
    setPagination((prev) => ({ ...prev, page }));
  }, []);

  const handlePageSizeChange = useCallback((pageSize: number) => {
    setPagination((prev) => ({ ...prev, page: 0, pageSize }));
  }, []);

  const handleSearchChange = useCallback((search: string) => {
    setSearchQuery(search);
    setPagination((prev) => ({ ...prev, page: 0 }));
  }, []);

  // Re-sorting reshuffles the whole result set, so the current page number is
  // meaningless afterwards — go back to the first page.
  const handleSortChange = useCallback((next: SortState | null) => {
    setSort(next);
    setPagination((prev) => ({ ...prev, page: 0 }));
  }, []);

  const formatTechnologies = (technologies?: string[]) => {
    if (!technologies || technologies.length === 0) return 'None';
    return technologies.slice(0, 3).join(', ') + (technologies.length > 3 ? '...' : '');
  };

  const formatStatus = (status?: ApplicationStatus) => {
    if (!status) return <Badge variant="info">Unknown</Badge>;
    return <Badge variant={APPLICATION_STATUS_COLORS[status]}>{status}</Badge>;
  };

  const truncateDescription = (html: string) => {
    const text = html.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
    return text.length > 100 ? text.slice(0, 100).trimEnd() + '…' : text;
  };

  const openImport = () => {
    setImportFile(null);
    setImportResult(null);
    setImportError('');
    setShowImport(true);
  };

  const handleDownloadTemplate = async () => {
    try {
      const blob = await applicationsApi.importTemplate();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'application-import-template.csv';
      document.body.appendChild(a);
      a.click();
      window.URL.revokeObjectURL(url);
      document.body.removeChild(a);
    } catch (err: any) {
      setImportError(err.response?.data?.message || 'Failed to download the template');
    }
  };

  const handleImport = async () => {
    if (!importFile) return;
    try {
      setImporting(true);
      setImportError('');
      const response = await applicationsApi.importCsv(importFile);
      setImportResult(response.data ?? null);
      // Rows already applied, so show the new state behind the dialog.
      loadApplications();
      loadOrganizations();
      subOrganizationsApi.listAll()
        .then((res) => setSubOrganizations(res.data || []))
        .catch(() => {});
    } catch (err: any) {
      setImportError(err.response?.data?.message || 'Failed to sync applications');
    } finally {
      setImporting(false);
    }
  };

  // Check permissions
  const canCreate = authorities.includes('super_admin') || authorities.includes('applications:create:all');
  const canEdit = authorities.includes('super_admin') || authorities.includes('applications:edit:all') || authorities.includes('applications:read:owned') || authorities.includes('applications:edit:org');
  const canDelete = authorities.includes('super_admin') || authorities.includes('applications:delete:all');
  const canView = authorities.includes('super_admin') || authorities.some((a) => a.startsWith('applications:read'));
  // The CSV sync writes across every organization, so it is administrators only — the same gate
  // the backend enforces.
  const isAdmin = authorities.includes('super_admin');

  // Divisions belong to an organization, so once one is picked only its own are offered. With no
  // organization chosen the names are ambiguous across organizations, so they carry the owner.
  const subOrgOptions = useMemo(() => {
    const scoped = filterOrganization
      ? subOrganizations.filter((sub) => sub.organizationId === filterOrganization)
      : subOrganizations;
    return scoped.map((sub) => ({
      value: sub.id,
      label: filterOrganization || !sub.organizationName
        ? sub.name
        : `${sub.name} — ${sub.organizationName}`,
    }));
  }, [subOrganizations, filterOrganization]);

  const orgNameById = useMemo(() => {
    const map: Record<string, string> = {};
    for (const org of organizations) map[org.id] = org.name;
    return map;
  }, [organizations]);

 const columns: Column<Application>[] = [
    {
      header: 'App ID',
      sortKey: 'appId',
      render: (app) => (
        <span className="font-mono" style={{ fontSize: '0.875rem', color: '#2563eb' }}>
          {app.appId || '—'}
        </span>
      ),
    },
    {
      header: 'Name',
      sortKey: 'name',
      render: (app) => (
        <div>
          <div className="font-medium">{app.name}</div>
          {app.description && (
            <div className="text-sm text-muted">
              {/* Descriptions are rich HTML now — show a plain-text summary in the table */}
              {truncateDescription(app.description)}
            </div>
          )}
        </div>
      ),
    },
    {
      header: organizationSingular,
      render: (app) => (app.organizationId && orgNameById[app.organizationId]) || '—',
    },
    {
      header: 'Status',
      sortKey: 'status',
      render: (app) => formatStatus(app.status),
    },
    {
      header: 'Technologies',
      render: (app) => formatTechnologies(app.technologies),
    },
    {
      // Not sortable: the cell shows appOwner.fullName and falls back to ownerName, so
      // ordering by either column alone would disagree with what's on screen.
      header: 'Owner',
      render: (app) => {
        if (app.appOwner) {
          return (
            <div>
              <div className="font-medium">{app.appOwner.fullName}</div>
              <div className="text-sm text-muted">
                <a href={`mailto:${app.appOwner.email}`} className="link">
                  {app.appOwner.email}
                </a>
              </div>
            </div>
          );
        }
        if (app.ownerName) {
          return (
            <div>
              <div className="font-medium">{app.ownerName}</div>
              {app.ownerEmail && (
                <div className="text-sm text-muted">
                  <a href={`mailto:${app.ownerEmail}`} className="link">
                    {app.ownerEmail}
                  </a>
                </div>
              )}
            </div>
          );
        }
        return 'N/A';
      },
    },
    {
      header: 'Open Issues',
      render: (app) => {
        const count = app.openIssueCount || 0;
        if (count === 0) {
          return (
          <span title="No open tracked issues">
            <ShieldCheck size={20} color="#22c55e" strokeWidth={1.75} />
          </span>
        );
        }
        return (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: '0.35rem', color: '#f97316', fontWeight: 600, fontSize: '0.875rem' }}>
            <ShieldAlert size={20} strokeWidth={1.75} />
            {count}
          </span>
        );
      },
    },
    {
      header: 'Actions',
      render: (app) => (
        <ActionButtons>
          {(canEdit || canView) && (
            <IconButton
              icon={Eye}
              onClick={() => handleEdit(app)}
              title="View"
            />
          )}
          {canDelete && <IconButton icon={Trash2} onClick={() => handleDelete(app.id)} variant="delete" title="Delete" />}
          <IconButton icon={Network} onClick={() => {/* TODO: View connections */}} title="View Connections" />
        </ActionButtons>
      ),
    },
  ];

  return (
    <Page className="applications-page">
      {/* Pane-level global vulnerability summary — shown above all tabs, fetched once, does not
          reload on tab switch, and deliberately not wired to any tab's filters. */}
      <VulnSummaryPanel />

      {/* Tab Navigation */}
      <div className="app-tab-nav">
        <button
          className={`app-tab-btn${activeTab === 'applications' ? ' active' : ''}`}
          onClick={() => setActiveTab('applications')}
        >
          All Applications
        </button>
        <button
          className={`app-tab-btn${activeTab === 'assessments' ? ' active' : ''}`}
          onClick={() => setActiveTab('assessments')}
        >
          All Assessments
        </button>
        <button
          className={`app-tab-btn${activeTab === 'vulnerabilities' ? ' active' : ''}`}
          onClick={() => setActiveTab('vulnerabilities')}
        >
          All Vulnerabilities
        </button>
        <div className={`app-tab-actions${activeTab === 'applications' ? '' : ' app-tab-action--hidden'}`}>
          {isAdmin && (
            <Button variant="secondary" onClick={openImport} icon={Upload}>
              Sync from CSV
            </Button>
          )}
          {canCreate && (
            <Button onClick={handleCreate} icon={Plus}>
              Create Application
            </Button>
          )}
        </div>
      </div>

      {activeTab === 'applications' ? (
        <DataTable
          columns={columns}
          data={applications}
          loading={loading}
          pagination={pagination}
          onPageChange={handlePageChange}
          onPageSizeChange={handlePageSizeChange}
          onSearchChange={handleSearchChange}
          searchPlaceholder="Search applications"
          emptyMessage="No applications found"
          idAccessor="id"
          sort={sort}
          onSortChange={handleSortChange}
          onRowClick={(canEdit || canView) ? handleEdit : undefined}
          headerChildren={
            <div className="ss-filter-bar">
              <SearchableSelect
                value={filterOrganization}
                onChange={(value) => {
                  setFilterOrganization(value);
                  // The chosen division belongs to the old organization — drop it rather than
                  // filter by something outside the new one.
                  setFilterSubOrganization('');
                  setPagination((prev) => ({ ...prev, page: 0 }));
                }}
                options={organizations.map((org) => ({ value: org.id, label: org.name }))}
                placeholder="All Organizations"
              />
              <SearchableSelect
                value={filterSubOrganization}
                onChange={(value) => {
                  setFilterSubOrganization(value);
                  setPagination((prev) => ({ ...prev, page: 0 }));
                }}
                options={subOrgOptions}
                placeholder="All Sub-Organizations"
                searchable={false}
              />
              <SearchableSelect
                value={filterStatus}
                onChange={(value) => {
                  setFilterStatus(value as ApplicationStatus | '');
                  setPagination((prev) => ({ ...prev, page: 0 }));
                }}
                options={APPLICATION_STATUSES.map((s) => ({ value: s, label: s }))}
                placeholder="All Statuses"
                searchable={false}
              />
            </div>
          }
        />
      ) : activeTab === 'vulnerabilities' ? (
        <VulnerabilitiesView />
      ) : (
        <ApplicationsAssessmentsTab />
      )}

      <Modal
        isOpen={showImport}
        onClose={() => setShowImport(false)}
        title="Sync Applications from CSV"
        size="lg"
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowImport(false)}>
              {importResult ? 'Close' : 'Cancel'}
            </Button>
            <Button
              variant="primary"
              onClick={handleImport}
              disabled={!importFile || importing}
            >
              {importing ? 'Syncing…' : 'Sync'}
            </Button>
          </>
        }
      >
        <div className="app-import">
          <p className="app-import-lede">
            Each row is upserted: matched by <strong>appId</strong> first, then by{' '}
            <strong>name</strong>, and inserted when neither matches. Organizations and
            sub-organizations named in a row are created if they don't exist yet. Columns left out
            of the file are left untouched on the applications it updates.
          </p>
          <p className="app-import-lede">
            List columns hold several entries separated by <code>;</code>, with the parts of one
            entry separated by <code>|</code> — technologies are plain names
            (<code>Java;React</code>), URLs are <code>address|title</code>, and stakeholders are{' '}
            <code>name|email|role</code>. A filled-in list cell replaces the whole list.
          </p>

          <Button variant="secondary" icon={Download} onClick={handleDownloadTemplate}>
            Download Template
          </Button>

          <FormGroup>
            <FormLabel>CSV file</FormLabel>
            <input
              type="file"
              accept=".csv,text/csv"
              className="app-import-file"
              onChange={(e) => {
                setImportFile(e.target.files?.[0] ?? null);
                setImportResult(null);
                setImportError('');
              }}
            />
          </FormGroup>

          {importError && <div className="error-message">{importError}</div>}

          {importResult && (
            <div className="app-import-result">
              <div className="app-import-counts">
                <Badge variant="success">{importResult.created} created</Badge>
                <Badge variant="info">{importResult.updated} updated</Badge>
                <Badge variant={importResult.failed ? 'danger' : 'info'}>
                  {importResult.failed} failed
                </Badge>
                <span className="text-muted">of {importResult.processed} rows</span>
              </div>

              {importResult.createdOrganizations.length > 0 && (
                <p className="text-sm">
                  New organizations: {importResult.createdOrganizations.join(', ')}
                </p>
              )}
              {importResult.createdSubOrganizations.length > 0 && (
                <p className="text-sm">
                  New sub-organizations: {importResult.createdSubOrganizations.join(', ')}
                </p>
              )}

              {importResult.errors.length > 0 && (
                <table className="app-import-errors">
                  <thead>
                    <tr><th>Line</th><th>Row</th><th>Problem</th></tr>
                  </thead>
                  <tbody>
                    {importResult.errors.map((e) => (
                      <tr key={`${e.line}-${e.message}`}>
                        <td>{e.line}</td>
                        <td>{e.identifier || '—'}</td>
                        <td>{e.message}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          )}
        </div>
      </Modal>

      <Modal
        isOpen={showModal}
        onClose={() => setShowModal(false)}
        title="Create Application"
        size="lg"
        closeOnOverlayClick={false}
      >
        <form onSubmit={handleSubmit} className="application-form">
          {error && <div className="error-message">{error}</div>}

          {/* Basic Information */}
          <div className="form-section">
            <h3 className="form-section-title">Basic Information</h3>
            <FormRow columns={2}>
              <FormGroup>
                <FormLabel required>Name</FormLabel>
                <Input
                  placeholder="Application Name"
                  value={formData.name}
                  onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  required
                />
              </FormGroup>
              <FormGroup>
                <FormLabel>
                  App ID <small className="text-muted">(optional)</small>
                </FormLabel>
                <Input
                  placeholder="e.g., ASMT-5"
                  value={formData.appId || ''}
                  onChange={(e) => setFormData({ ...formData, appId: e.target.value })}
                />
              </FormGroup>
              <FormGroup>
                <FormLabel>Status</FormLabel>
                <Select
                  value={formData.status}
                  onChange={(e) => setFormData({ ...formData, status: e.target.value as ApplicationStatus })}
                >
                  <option value="PRODUCTION">Production</option>
                  <option value="DEVELOPMENT">Development</option>
                  <option value="STAGING">Staging</option>
                  <option value="TESTING">Testing</option>
                  <option value="DECOMMISSIONED">Decommissioned</option>
                  <option value="PLANNED">Planned</option>
                </Select>
              </FormGroup>
            </FormRow>
            <FormRow columns={2}>
              <FormGroup>
                <FormLabel>{organizationSingular}</FormLabel>
                <Select
                  value={formData.organizationId}
                  onChange={(e) => setFormData({ ...formData, organizationId: e.target.value })}
                >
                  <option value="">None</option>
                  {organizations.map((org) => (
                    <option key={org.id} value={org.id}>
                      {org.name}
                    </option>
                  ))}
                </Select>
              </FormGroup>
              <FormGroup>
                <FormLabel>Region</FormLabel>
                <Select
                  value={formData.region}
                  onChange={(e) => setFormData({ ...formData, region: e.target.value })}
                >
                  {regions.length === 0 && (
                    <option value="Global">Global</option>
                  )}
                  {regions.map((r) => (
                    <option key={r} value={r}>{r}</option>
                  ))}
                </Select>
              </FormGroup>
            </FormRow>
            <FormGroup>
              <FormLabel>Application Owner</FormLabel>
              <FormRow columns={2}>
                <Input
                  placeholder="Full Name"
                  value={appOwner.fullName}
                  onChange={(e) => setAppOwner({ ...appOwner, fullName: e.target.value })}
                />
                <Input
                  type="email"
                  placeholder="Email"
                  value={appOwner.email}
                  onChange={(e) => setAppOwner({ ...appOwner, email: e.target.value })}
                />
              </FormRow>
            </FormGroup>
            <FormGroup>
              <FormLabel>Description</FormLabel>
              <RichTextEditor
                value={formData.description}
                onChange={(html) => setFormData({ ...formData, description: html })}
              />
            </FormGroup>
          </div>

          {/* URLs */}
          <div className="form-section">
            <h3 className="form-section-title">URLs</h3>
            {urls.map((url, index) => (
              <div key={index} className="list-item">
                <div className="list-item-content">
                  <div className="font-medium">{url.title}</div>
                  <div className="text-sm text-muted">
                    <a href={url.url} target="_blank" rel="noopener noreferrer" className="link">
                      {url.url}
                    </a>
                  </div>
                </div>
                <IconButton
                  icon={X}
                  onClick={() => handleRemoveUrl(index)}
                  variant="delete"
                  title="Remove"
                />
              </div>
            ))}
            <div className="technology-input-group">
              <Input
                type="url"
                placeholder="URL"
                value={newUrl.url}
                onChange={(e) => setNewUrl({ ...newUrl, url: e.target.value })}
              />
              <Input
                placeholder="Title"
                value={newUrl.title}
                onChange={(e) => setNewUrl({ ...newUrl, title: e.target.value })}
              />
              <Button type="button" onClick={handleAddUrl} size="sm">
                Add
              </Button>
            </div>
          </div>

          {/* Stakeholders */}
          <div className="form-section">
            <h3 className="form-section-title">Stakeholders</h3>
            {stakeholders.map((stakeholder, index) => (
              <div key={index} className="list-item">
                <div className="list-item-content">
                  <div className="font-medium">{stakeholder.name}</div>
                  <div className="text-sm text-muted">
                    <a href={`mailto:${stakeholder.email}`} className="link">
                      {stakeholder.email}
                    </a>
                    {' - '}{stakeholder.role}
                  </div>
                </div>
                <IconButton
                  icon={X}
                  onClick={() => handleRemoveStakeholder(index)}
                  variant="delete"
                  title="Remove"
                />
              </div>
            ))}
            <div className="technology-input-group">
              <Input
                placeholder="Full Name"
                value={newStakeholder.name}
                onChange={(e) => setNewStakeholder({ ...newStakeholder, name: e.target.value })}
              />
              <Input
                type="email"
                placeholder="Email"
                value={newStakeholder.email}
                onChange={(e) => setNewStakeholder({ ...newStakeholder, email: e.target.value })}
              />
              <Input
                placeholder="Role"
                value={newStakeholder.role}
                onChange={(e) => setNewStakeholder({ ...newStakeholder, role: e.target.value })}
              />
              <Button type="button" onClick={handleAddStakeholder} size="sm">
                Add
              </Button>
            </div>
          </div>

          {/* Technologies */}
          <div className="form-section">
            <h3 className="form-section-title">Technologies</h3>

            {/* Selected Technologies */}
            {technologies.length > 0 && (
              <div style={{ marginBottom: '1rem' }}>
                <div style={{ fontSize: '0.875rem', fontWeight: 500, marginBottom: '0.5rem' }}>Selected:</div>
                <div className="technology-tags">
                  {technologies.map((tech) => (
                    <div key={tech} className="technology-tag">
                      {tech}
                      <button type="button" onClick={() => handleRemoveTechnology(tech)}>
                        <X size={12} />
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Common Technologies */}
            <div style={{ marginBottom: '1rem' }}>
              <div style={{ fontSize: '0.875rem', fontWeight: 500, marginBottom: '0.5rem' }}>Common Technologies:</div>
              <div className="technology-tags">
                {COMMON_TECHNOLOGIES.map((tech) => (
                  <button
                    key={tech}
                    type="button"
                    onClick={() => handleToggleTechnology(tech)}
                    className={`technology-tag ${technologies.includes(tech) ? 'selected' : 'selectable'}`}
                    style={{ cursor: 'pointer' }}
                  >
                    {tech}
                  </button>
                ))}
              </div>
            </div>

            {/* Custom Technology Input */}
            <div>
              <div style={{ fontSize: '0.875rem', fontWeight: 500, marginBottom: '0.5rem' }}>Add Custom:</div>
              <div className="technology-input-group">
                <Input
                  placeholder="Add custom technology"
                  value={newTechnology}
                  onChange={(e) => setNewTechnology(e.target.value)}
                  onKeyPress={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      handleAddTechnology();
                    }
                  }}
                />
                <Button type="button" onClick={handleAddTechnology} size="sm">
                  Add
                </Button>
              </div>
            </div>
          </div>

          {/* Assessment Information */}
          <div className="form-section">
            <h3 className="form-section-title">Assessment Information</h3>
            <FormRow columns={2}>
              <FormGroup>
                <FormLabel>Application Type</FormLabel>
                <Select
                  value={formData.applicationType}
                  onChange={(e) => setFormData({ ...formData, applicationType: e.target.value })}
                >
                  <option value="">Select type</option>
                  <option value="Web Application">Web Application</option>
                  <option value="Mobile Application">Mobile Application</option>
                  <option value="API">API</option>
                  <option value="Thick Client">Thick Client</option>
                  <option value="Other">Other</option>
                </Select>
              </FormGroup>
              <FormGroup>
                <FormLabel>Assessment Frequency</FormLabel>
                <Select
                  value={formData.assessmentFrequency}
                  onChange={(e) => setFormData({ ...formData, assessmentFrequency: e.target.value })}
                >
                  <option value="">Select frequency</option>
                  <option value="Ad Hoc">Ad Hoc</option>
                  <option value="Yearly">Yearly</option>
                  <option value="Custom">Custom</option>
                </Select>
              </FormGroup>
            </FormRow>
          </div>

          {/* Additional Information */}
          {fieldDefinitions.length > 0 && (
            <div className="form-section">
              <h3 className="form-section-title">Additional Information</h3>
              {(() => {
                const regularFields = fieldDefinitions.filter((f) => f.fieldType !== 'RICH_TEXT');
                const richTextFields = fieldDefinitions.filter((f) => f.fieldType === 'RICH_TEXT');
                return (
                  <>
                    {regularFields.length > 0 && (
                      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '1rem' }}>
                        {regularFields.map((field) => (
                          <FormGroup key={field.id}>
                            <FormLabel>
                              {field.displayName}
                              {field.required && <span style={{ color: 'var(--color-danger)', marginLeft: 2 }}>*</span>}
                            </FormLabel>
                            {field.fieldType === 'DROPDOWN' ? (
                              <Select
                                value={fieldValues[field.id] || ''}
                                onChange={(e) => setFieldValues({ ...fieldValues, [field.id]: e.target.value })}
                              >
                                <option value="">Select...</option>
                                {(field.dropdownOptions || []).map((opt) => (
                                  <option key={opt} value={opt}>{opt}</option>
                                ))}
                              </Select>
                            ) : (
                              <Input
                                value={fieldValues[field.id] || ''}
                                onChange={(e) => setFieldValues({ ...fieldValues, [field.id]: e.target.value })}
                                placeholder={field.helpText || `Enter ${field.displayName}`}
                              />
                            )}
                            {field.helpText && (
                              <p style={{ fontSize: '0.8125rem', color: 'var(--color-text-muted)', marginTop: '0.25rem' }}>
                                {field.helpText}
                              </p>
                            )}
                          </FormGroup>
                        ))}
                      </div>
                    )}
                    {richTextFields.map((field) => (
                      <FormGroup key={field.id}>
                        <FormLabel>
                          {field.displayName}
                          {field.required && <span style={{ color: 'var(--color-danger)', marginLeft: 2 }}>*</span>}
                        </FormLabel>
                        <RichTextEditor
                          value={fieldValues[field.id] || ''}
                          onChange={(val) => setFieldValues({ ...fieldValues, [field.id]: val })}
                        />
                      </FormGroup>
                    ))}
                  </>
                );
              })()}
            </div>
          )}

          <div className="modal-actions">
            <Button type="button" variant="secondary" onClick={() => setShowModal(false)}>
              Cancel
            </Button>
            <Button type="submit">Create</Button>
          </div>
        </form>
      </Modal>
    </Page>
  );
}
