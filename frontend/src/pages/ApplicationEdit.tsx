import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import UserAvatar from '../components/UserAvatar';
import {
  X, Pencil, MessageSquare, Trash2, Send, Eye,
  CalendarClock, CheckCircle2, ClipboardList, ClipboardCheck, Info,
  Copy, Check, ChevronDown, ChevronUp,
  type LucideIcon,
} from 'lucide-react';
import { marked } from 'marked';
import { applicationsApi, organizationsApi, subOrganizationsApi, entityFieldsApi, regionConfigApi, assessmentsApi, vulnerabilitiesApi, workflowConfigApi, assessmentSurveysApi } from '../api';
import type {
  UpdateApplicationRequest,
  ApplicationStatus,
  ApplicationUrl,
  Stakeholder,
  AppOwner,
  Organization,
  SubOrganization,
  UserDefinedField,
  ApplicationComment,
  Assessment,
  AssessmentSurvey,
  Vulnerability,
} from '../types';
import RichTextEditor, { type RichTextEditorRef } from '../components/RichTextEditor';
import ConfirmDialog from '../components/ConfirmDialog';
import Page from '../components/Page';
import { useCommentPolling } from '../hooks/useCommentPolling';
import ThreadSubscribers from '../components/ThreadSubscribers';
import DataTable, { type Column, type PaginationInfo, type SortState } from '../components/DataTable';
import { applyClientSort, type SortAccessors } from '../utils/tableSort';
import VulnerabilityDetailDrawer from '../components/VulnerabilityDetailDrawer';
import VulnSummaryPanel from '../components/VulnSummaryPanel';
import ReportPreviewDrawer from '../components/ReportPreviewDrawer';
import SurveyDrawer from '../components/SurveyDrawer';
import ResourceUserManager from './ResourceUserManager';
import './ResourceUserManager.css';
import {
  Button,
  IconButton,
  ActionButtons,
  Badge,
  SeverityBadge,
  FormGroup,
  FormLabel,
  FormHint,
  FormRow,
  Input,
  Select,
  ErrorMessage,
} from '../components';
import { usePageTitle } from '../context/PageTitleContext';
import { vulnStatusBadgeVariant } from '../utils/vulnStatus';
import './Applications.css';
import './ApplicationEdit.css';

// Same palette as the main Vulnerabilities tab
// Same fallback mapping as the main Assessments tab
const ASSESSMENT_STATUS_COLORS: Record<string, 'success' | 'warning' | 'info' | 'danger' | 'secondary'> = {
  DRAFT: 'secondary',
  IN_PROGRESS: 'info',
  ON_HOLD: 'warning',
  PENDING_REVIEW: 'info',
  COMPLETED: 'success',
  APPROVED: 'success',
  ARCHIVED: 'secondary',
};

const VULN_PAGE_SIZE = 10;

const COMMON_TECHNOLOGIES = [
  'Java', 'JavaScript', 'TypeScript', 'Python', 'C#', 'Go', 'Rust', 'PHP',
  'React', 'Angular', 'Vue', 'Node.js', 'Spring Boot', '.NET', 'Django', 'Flask',
  'PostgreSQL', 'MySQL', 'MongoDB', 'Redis', 'Oracle', 'SQL Server',
  'AWS', 'Azure', 'GCP', 'Docker', 'Kubernetes', 'Jenkins',
  'REST API', 'GraphQL', 'gRPC', 'Apache', 'Nginx', 'Tomcat',
];

const STATUS_LABELS: Record<ApplicationStatus, string> = {
  PRODUCTION: 'Production',
  DEVELOPMENT: 'Development',
  STAGING: 'Staging',
  TESTING: 'Testing',
  DECOMMISSIONED: 'Decommissioned',
  PLANNED: 'Planned',
};

const STATUS_BADGE_VARIANTS: Record<ApplicationStatus, 'primary' | 'secondary' | 'success' | 'danger' | 'warning' | 'info'> = {
  PRODUCTION: 'success',
  DEVELOPMENT: 'info',
  STAGING: 'warning',
  TESTING: 'secondary',
  DECOMMISSIONED: 'danger',
  PLANNED: 'primary',
};

// ── Avatar helpers (mirrors VulnerabilityDetailDrawer's local pattern) ─────────
interface CommentAvatarProps { name: string; authorId: string; size?: number }
function CommentAvatar({ name, authorId, size = 34 }: CommentAvatarProps) {
  return (
    <UserAvatar userId={authorId} name={name} size={size} className="app-chat-avatar" />
  );
}

// System lifecycle messages get an icon chip in the avatar slot, keyed off the
// bold prefix the backend writes (see ApplicationService.addSystemComment callers).
const SYSTEM_EVENT_ICONS: Array<{ match: string; icon: LucideIcon; variant: string }> = [
  { match: '**Assessment scheduled**', icon: CalendarClock, variant: 'scheduled' },
  { match: '**Assessment completed**', icon: CheckCircle2, variant: 'completed' },
  { match: '**Survey assigned**', icon: ClipboardList, variant: 'survey' },
  { match: '**Survey completed**', icon: ClipboardCheck, variant: 'completed' },
];

function SystemEventIcon({ content }: { content: string }) {
  const event = SYSTEM_EVENT_ICONS.find(e => content.startsWith(e.match));
  const Icon = event?.icon ?? Info;
  return (
    <span className={`app-chat-event-icon app-chat-event-icon--${event?.variant ?? 'info'}`}>
      <Icon size={18} />
    </span>
  );
}

// Small inline copy-to-clipboard button rendered next to email addresses.
function CopyEmailButton({ email, title = 'Copy email' }: { email: string; title?: string }) {
  const [copied, setCopied] = useState(false);
  const handleCopy = () => {
    navigator.clipboard.writeText(email).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  };
  return (
    <button
      type="button"
      className={`app-detail-copy-email${copied ? ' copied' : ''}`}
      onClick={handleCopy}
      title={copied ? 'Copied!' : title}
    >
      {copied ? <Check size={13} /> : <Copy size={13} />}
    </button>
  );
}

function formatCommentDate(iso: string): string {
  const d = new Date(iso);
  const now = new Date();
  const diffMs = now.getTime() - d.getTime();
  const diffMin = Math.floor(diffMs / 60000);
  if (diffMin < 1) return 'just now';
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffH = Math.floor(diffMin / 60);
  if (diffH < 24) return `${diffH}h ago`;
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: d.getFullYear() !== now.getFullYear() ? 'numeric' : undefined });
}

// ── Static/edit section wrapper (adapted from VulnerabilityDetailDrawer's
//    EditableSection — per-section rather than per-field, since this page
//    persists via a single whole-record PUT, not a per-field PATCH) ───────────
interface EditableSectionProps {
  title: string;
  sectionKey: string;
  editing: boolean;
  canWrite: boolean;
  saving: boolean;
  onToggle: (key: string) => void;
  onSave: (key: string) => void;
  viewContent: React.ReactNode;
  editContent: React.ReactNode;
}
function EditableSection({ title, sectionKey, editing, canWrite, saving, onToggle, onSave, viewContent, editContent }: EditableSectionProps) {
  return (
    <div className="form-panel app-detail-section">
      <div className="app-detail-section-header">
        <h3 className="form-section-title">{title}</h3>
        {canWrite && (
          <div className="app-detail-section-actions">
            {editing && (
              <Button type="button" size="sm" variant="primary" onClick={() => onSave(sectionKey)} disabled={saving}>
                {saving ? 'Saving…' : 'Save'}
              </Button>
            )}
            <button
              type="button"
              className={`app-detail-edit-btn${editing ? ' active' : ''}`}
              onClick={() => onToggle(sectionKey)}
              title={editing ? 'Cancel' : 'Edit'}
            >
              {editing ? <X size={14} /> : <Pencil size={14} />}
            </button>
          </div>
        )}
      </div>
      {editing ? editContent : viewContent}
    </div>
  );
}

interface AppFormSnapshot {
  appId: string;
  formData: {
    name: string;
    description: string;
    status: ApplicationStatus;
    organizationId: string;
    subOrganizationId: string;
    region: string;
    applicationType: string;
    assessmentFrequency: string;
    customFrequencyMonths?: number;
  };
  urls: ApplicationUrl[];
  stakeholders: Stakeholder[];
  technologies: string[];
  appOwner: AppOwner;
  fieldValues: Record<string, string>;
}

export default function ApplicationEdit() {
  const { id } = useParams<{ id: string }>();
  const { setBreadcrumbs } = usePageTitle();

  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const [appId, setAppId] = useState('');
  const [formData, setFormData] = useState<AppFormSnapshot['formData']>({
    name: '',
    description: '',
    status: 'DEVELOPMENT',
    organizationId: '',
    subOrganizationId: '',
    region: 'Global',
    applicationType: '',
    assessmentFrequency: '',
    customFrequencyMonths: undefined,
  });
  const [urls, setUrls] = useState<ApplicationUrl[]>([]);
  const [newUrl, setNewUrl] = useState({ url: '', title: '' });
  const [stakeholders, setStakeholders] = useState<Stakeholder[]>([]);
  const [newStakeholder, setNewStakeholder] = useState({ name: '', email: '', role: '' });
  const [technologies, setTechnologies] = useState<string[]>([]);
  const [newTechnology, setNewTechnology] = useState('');
  const [appOwner, setAppOwner] = useState<AppOwner>({ fullName: '', email: '' });
  const [organizations, setOrganizations] = useState<Organization[]>([]);
  // Divisions of the currently selected organization; reloaded whenever it changes.
  const [subOrganizations, setSubOrganizations] = useState<SubOrganization[]>([]);
  const [regions, setRegions] = useState<string[]>([]);
  const [fieldDefinitions, setFieldDefinitions] = useState<UserDefinedField[]>([]);
  const [fieldValues, setFieldValues] = useState<Record<string, string>>({});
  const [assignedUsers, setAssignedUsers] = useState<import('../types').AssignedUser[]>([]);
  const [savedSnapshot, setSavedSnapshot] = useState<AppFormSnapshot | null>(null);
  const [editingSections, setEditingSections] = useState<Set<string>>(new Set());
  const [showMoreDetails, setShowMoreDetails] = useState(false);

  // Vulnerabilities for this application (mirrors the main Vulnerabilities tab)
  const [appVulnerabilities, setAppVulnerabilities] = useState<Vulnerability[]>([]);
  const [vulnAssessmentMap, setVulnAssessmentMap] = useState<Record<string, Assessment>>({});
  const [vulnsLoading, setVulnsLoading] = useState(false);
  const [vulnSearch, setVulnSearch] = useState('');
  const [vulnShowClosed, setVulnShowClosed] = useState(false);
  const [vulnPage, setVulnPage] = useState(0);
  const [vulnSort, setVulnSort] = useState<SortState | null>(null);
  const [selectedVuln, setSelectedVuln] = useState<Vulnerability | null>(null);
  const [selectedVulnAssessment, setSelectedVulnAssessment] = useState<Assessment | null>(null);

  // Assessments tab in the same panel
  const [detailTab, setDetailTab] = useState<'vulnerabilities' | 'assessments'>('vulnerabilities');
  const [assessmentSearch, setAssessmentSearch] = useState('');
  const [assessmentPage, setAssessmentPage] = useState(0);
  const [assessmentSort, setAssessmentSort] = useState<SortState | null>(null);
  const [assessmentStatusColors, setAssessmentStatusColors] = useState<Record<string, string>>({});
  const [assessmentSurveyMap, setAssessmentSurveyMap] = useState<Record<string, AssessmentSurvey[]>>({});
  const [surveyAssessment, setSurveyAssessment] = useState<Assessment | null>(null);
  const [previewAssessment, setPreviewAssessment] = useState<Assessment | null>(null);

  // Chat
  const [comments, setComments] = useState<ApplicationComment[]>([]);
  const [subscribers, setSubscribers] = useState<string[]>([]);
  const [commentDraft, setCommentDraft] = useState('');
  const [composeExpanded, setComposeExpanded] = useState(false);
  const composeEditorRef = useRef<RichTextEditorRef>(null);
  const [submittingComment, setSubmittingComment] = useState(false);
  const [deletingCommentId, setDeletingCommentId] = useState<string | null>(null);

  // The application "Discussion" is the stakeholder-facing chat, so it is the thread most
  // likely to have someone on the other end — and since reply-by-email, comments can
  // arrive from the inbound poller with nothing happening in this tab.
  useCommentPolling({
    enabled: !!id,
    paused: submittingComment || !!deletingCommentId,
    refresh: async () => {
      if (!id) return;
      try {
        const res = await applicationsApi.getById(id);
        if (res.data?.comments) setComments(res.data.comments);
        if (res.data?.subscribers) setSubscribers(res.data.subscribers);
      } catch {
        // Transient failures are not worth surfacing — the next tick retries.
      }
    },
  });
  const [confirmDeleteCommentId, setConfirmDeleteCommentId] = useState<string | null>(null);

  const currentUser = JSON.parse(localStorage.getItem('user') || '{}');
  const currentUsername: string = currentUser.username || '';
  const authorities: string[] = currentUser.authorities || [];
  const isSuperAdmin = authorities.includes('super_admin');
  const hasEditAll = authorities.includes('applications:edit:all');
  const hasReadOwned = authorities.includes('applications:read:owned');
  const hasEditOrg = authorities.includes('applications:edit:org');
  // Owned scope: an assignment on this app gates by its access level; a user
  // who can see the app WITHOUT an assignment is org-level (full access).
  const myAssignment = assignedUsers.find(u => u.userId === currentUser.id);
  const canWrite = isSuperAdmin || hasEditAll || hasEditOrg ||
    (hasReadOwned && (myAssignment ? myAssignment.accessLevel === 'WRITE' : true));
  const canAssignUsers = isSuperAdmin || hasEditAll;

  useEffect(() => {
    if (!id) return;

    const loadApp = applicationsApi.getById(id).then((appRes) => {
      if (appRes.data) {
        const app = appRes.data;
        setAssignedUsers(app.assignedUsers || []);
        setAppId(app.appId || '');
        const loadedFormData: AppFormSnapshot['formData'] = {
          name: app.name,
          description: app.description || '',
          status: app.status || 'DEVELOPMENT',
          organizationId: app.organizationId || '',
          subOrganizationId: app.subOrganizationId || '',
          region: app.region || 'Global',
          applicationType: app.applicationType || '',
          assessmentFrequency: app.assessmentFrequency || '',
          customFrequencyMonths: app.customFrequencyMonths,
        };
        const loadedUrls = app.urls || [];
        const loadedStakeholders = app.stakeHolders || [];
        const loadedTechnologies = app.technologies || [];
        const loadedAppOwner = app.appOwner || { fullName: '', email: '' };
        const loadedFieldValues = app.fieldValues || {};

        setFormData(loadedFormData);
        setUrls(loadedUrls);
        setStakeholders(loadedStakeholders);
        setTechnologies(loadedTechnologies);
        setAppOwner(loadedAppOwner);
        setFieldValues(loadedFieldValues);
        setComments(app.comments || []);
        setSubscribers(app.subscribers || []);
        setSavedSnapshot({
          appId: app.appId || '',
          formData: loadedFormData,
          urls: loadedUrls,
          stakeholders: loadedStakeholders,
          technologies: loadedTechnologies,
          appOwner: loadedAppOwner,
          fieldValues: loadedFieldValues,
        });
      }
    });

    const loadOrgs = organizationsApi.getAll(0, 1000)
      .then((orgsRes) => { if (orgsRes.data) setOrganizations(orgsRes.data); })
      .catch(() => { /* user may lack org read permission — org dropdown stays empty */ });

    const loadRegions = regionConfigApi.getRegions()
      .then((r) => setRegions(r))
      .catch(() => { /* fall back to empty; select will still show current value */ });

    const loadFields = entityFieldsApi.getConfig('APPLICATION')
      .then((fieldsRes) => {
        if (fieldsRes.data) {
          const sorted = [...(fieldsRes.data.fieldDefinitions || [])].sort(
            (a, b) => (a.displayOrder ?? 0) - (b.displayOrder ?? 0)
          );
          setFieldDefinitions(sorted);
        }
      })
      .catch(() => { /* custom fields unavailable */ });

    Promise.all([loadApp, loadOrgs, loadFields, loadRegions])
      .catch((err: any) => {
        setError(err.response?.data?.message || 'Failed to load application');
      })
      .finally(() => {
        setLoading(false);
      });
  }, [id]);

  // Divisions belong to one organization, so the picker's options follow the selected org.
  useEffect(() => {
    if (!formData.organizationId) { setSubOrganizations([]); return; }
    let cancelled = false;
    subOrganizationsApi.list(formData.organizationId)
      .then((res) => { if (!cancelled) setSubOrganizations(res.data ?? []); })
      .catch(() => { if (!cancelled) setSubOrganizations([]); });
    return () => { cancelled = true; };
  }, [formData.organizationId]);

  useEffect(() => {
    setBreadcrumbs([
      { label: 'Applications', to: '/applications' },
      { label: appId ? `${appId} ${formData.name}` : (formData.name || 'Application') },
    ]);
    return () => setBreadcrumbs(null);
  }, [appId, formData.name]);

  // Load this application's vulnerabilities: its assessments, then vulns per assessment
  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    workflowConfigApi.getConfig().then((res) => {
      if (!cancelled && res.success && res.data?.statusColors) setAssessmentStatusColors(res.data.statusColors);
    }).catch(() => {});
    (async () => {
      setVulnsLoading(true);
      try {
        // Fetch only this application's assessments via the indexed by-application
        // endpoint. (Previously loaded the first 1000 global assessments and filtered
        // client-side — slow, and silently missed assessments outside that window.)
        const assessmentsRes = await assessmentsApi.getByApplication(id, 0, 1000);
        const appAssessments = assessmentsRes.data || [];
        const map: Record<string, Assessment> = {};
        for (const a of appAssessments) map[a.id] = a;
        const [vulnResults, surveyResults] = await Promise.all([
          Promise.all(appAssessments.map((a) =>
            vulnerabilitiesApi.getAll(a.id, 0, 1000)
              .then((r) => r.data || [])
              .catch(() => [] as Vulnerability[])
          )),
          Promise.all(appAssessments.map((a) =>
            assessmentSurveysApi.getByAssessment(a.id)
              .then((r) => ({ id: a.id, surveys: r.data || [] }))
              .catch(() => ({ id: a.id, surveys: [] as AssessmentSurvey[] }))
          )),
        ]);
        if (!cancelled) {
          setVulnAssessmentMap(map);
          setAppVulnerabilities(vulnResults.flat());
          const surveyMap: Record<string, AssessmentSurvey[]> = {};
          surveyResults.forEach(({ id: aid, surveys }) => { surveyMap[aid] = surveys; });
          setAssessmentSurveyMap(surveyMap);
        }
      } catch {
        /* vulnerabilities are supplementary — the page still works without them */
      } finally {
        if (!cancelled) setVulnsLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [id]);

  const toggleSection = (key: string) => {
    setEditingSections((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        revertSection(key);
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  };

  const revertSection = (key: string) => {
    if (!savedSnapshot) return;
    switch (key) {
      case 'basicInfo':
        setAppId(savedSnapshot.appId);
        setFormData((prev) => ({
          ...prev,
          name: savedSnapshot.formData.name,
          description: savedSnapshot.formData.description,
          status: savedSnapshot.formData.status,
          organizationId: savedSnapshot.formData.organizationId,
          subOrganizationId: savedSnapshot.formData.subOrganizationId,
          region: savedSnapshot.formData.region,
          applicationType: savedSnapshot.formData.applicationType,
          assessmentFrequency: savedSnapshot.formData.assessmentFrequency,
          customFrequencyMonths: savedSnapshot.formData.customFrequencyMonths,
        }));
        setAppOwner(savedSnapshot.appOwner);
        setTechnologies(savedSnapshot.technologies);
        setNewTechnology('');
        setUrls(savedSnapshot.urls);
        setNewUrl({ url: '', title: '' });
        setStakeholders(savedSnapshot.stakeholders);
        setNewStakeholder({ name: '', email: '', role: '' });
        break;
      case 'customFields':
        setFieldValues(savedSnapshot.fieldValues);
        break;
    }
  };

  const handleAddUrl = () => {
    if (!newUrl.url || !newUrl.title) return;
    try {
      new URL(newUrl.url);
      setUrls([...urls, { ...newUrl }]);
      setNewUrl({ url: '', title: '' });
    } catch {
      setError('Please enter a valid URL (e.g., https://example.com)');
    }
  };

  const handleAddStakeholder = () => {
    if (!newStakeholder.name || !newStakeholder.email || !newStakeholder.role) return;
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!emailRegex.test(newStakeholder.email)) {
      setError('Please enter a valid email address');
      return;
    }
    setStakeholders([...stakeholders, { ...newStakeholder }]);
    setNewStakeholder({ name: '', email: '', role: '' });
  };

  const handleToggleTechnology = (tech: string) => {
    setTechnologies((prev) =>
      prev.includes(tech) ? prev.filter((t) => t !== tech) : [...prev, tech]
    );
  };

  const handleAddTechnology = () => {
    if (newTechnology && !technologies.includes(newTechnology)) {
      setTechnologies([...technologies, newTechnology]);
      setNewTechnology('');
    }
  };

  const handleSaveSection = async (key: string) => {
    if (!id) return;
    setSaving(true);
    setError('');
    try {
      const requestData: UpdateApplicationRequest = {
        ...formData,
        appId: appId || undefined,
        organizationId: formData.organizationId || undefined,
        subOrganizationId: formData.subOrganizationId || undefined,
        region: formData.region || 'Global',
        urls: urls.length > 0 ? urls : undefined,
        stakeHolders: stakeholders.length > 0 ? stakeholders : undefined,
        technologies: technologies.length > 0 ? technologies : undefined,
        appOwner: appOwner.fullName && appOwner.email ? appOwner : undefined,
        fieldValues: Object.keys(fieldValues).length > 0 ? fieldValues : undefined,
      };
      await applicationsApi.update(id, requestData);
      setSavedSnapshot({ appId, formData, urls, stakeholders, technologies, appOwner, fieldValues });
      setEditingSections((prev) => {
        const next = new Set(prev);
        next.delete(key);
        return next;
      });
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to save application');
    } finally {
      setSaving(false);
    }
  };

  // Focus the editor as soon as the compose box expands
  useEffect(() => {
    if (composeExpanded) composeEditorRef.current?.focus();
  }, [composeExpanded]);

  const handleAddComment = async () => {
    if (!id || !commentDraft.trim()) return;
    setSubmittingComment(true);
    try {
      const res = await applicationsApi.addComment(id, commentDraft.trim());
      if (res.data) setComments(res.data);
      setCommentDraft('');
      setComposeExpanded(false);
    } catch {
      // keep draft so the user doesn't lose what they typed
    } finally {
      setSubmittingComment(false);
    }
  };

  const handleDeleteComment = async (commentId: string) => {
    if (!id) return;
    setDeletingCommentId(commentId);
    try {
      const res = await applicationsApi.deleteComment(id, commentId);
      if (res.data) setComments(res.data);
    } catch {
      // ignore
    } finally {
      setDeletingCommentId(null);
      setConfirmDeleteCommentId(null);
    }
  };

  if (loading) {
    return <div style={{ padding: '2rem', color: 'var(--text-muted)' }}>Loading...</div>;
  }

  const regularFields = fieldDefinitions.filter((f) => f.fieldType !== 'RICH_TEXT');
  const richTextFields = fieldDefinitions.filter((f) => f.fieldType === 'RICH_TEXT');
  const orgName = organizations.find((o) => o.id === formData.organizationId)?.name;

  // Vulnerability table (same behavior as the main Vulnerabilities tab: tracked
  // vulns only, closed hidden unless toggled, Eye opens the detail drawer)
  const filteredVulns = appVulnerabilities.filter((v) => {
    if (!v.openedAt) return false;
    if (!vulnShowClosed && v.closedAt) return false;
    if (vulnSearch) {
      const q = vulnSearch.toLowerCase();
      if (
        !v.name.toLowerCase().includes(q) &&
        !(v.assetLocation || '').toLowerCase().includes(q) &&
        !(vulnAssessmentMap[v.assessmentId]?.name || '').toLowerCase().includes(q)
      ) return false;
    }
    return true;
  });

  const sortedVulns = applyClientSort(filteredVulns, vulnSort, {
    name: (v) => v.name,
    severity: (v) => v.severity,
    status: (v) => v.status,
    openedAt: (v) => v.openedAt,
    closedAt: (v) => v.closedAt,
  } as SortAccessors<Vulnerability>);

  const vulnPagination: PaginationInfo = {
    page: vulnPage,
    pageSize: VULN_PAGE_SIZE,
    total: filteredVulns.length,
    totalPages: Math.ceil(filteredVulns.length / VULN_PAGE_SIZE),
  };

  const tableVulns = sortedVulns.slice(vulnPage * VULN_PAGE_SIZE, (vulnPage + 1) * VULN_PAGE_SIZE);

  const vulnColumns: Column<Vulnerability>[] = [
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
      render: (v) => {
        const s = v.status || 'None';
        return <Badge variant={vulnStatusBadgeVariant(s)}>{s}</Badge>;
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
            onClick={() => {
              setSelectedVuln(v);
              setSelectedVulnAssessment(vulnAssessmentMap[v.assessmentId] || null);
            }}
            title="View Details"
            variant="edit"
          />
        </ActionButtons>
      ),
    },
  ];

  // Assessments table (all of this application's assessments)
  const appAssessments = Object.values(vulnAssessmentMap).sort((a, b) => {
    const aDate = a.startDate || a.createdAt || '';
    const bDate = b.startDate || b.createdAt || '';
    return bDate.localeCompare(aDate);
  });

  const filteredAssessments = appAssessments.filter((a) => {
    if (assessmentSearch) {
      const q = assessmentSearch.toLowerCase();
      if (!a.name.toLowerCase().includes(q) && !a.status.toLowerCase().includes(q)) return false;
    }
    return true;
  });

  const sortedAssessments = applyClientSort(filteredAssessments, assessmentSort, {
    name: (a) => a.name,
    status: (a) => a.status,
    startDate: (a) => a.startDate,
    plannedEndDate: (a) => a.plannedEndDate,
  } as SortAccessors<Assessment>);

  const assessmentPagination: PaginationInfo = {
    page: assessmentPage,
    pageSize: VULN_PAGE_SIZE,
    total: filteredAssessments.length,
    totalPages: Math.ceil(filteredAssessments.length / VULN_PAGE_SIZE),
  };

  const tableAssessments = sortedAssessments.slice(
    assessmentPage * VULN_PAGE_SIZE,
    (assessmentPage + 1) * VULN_PAGE_SIZE,
  );

  const assessmentColumns: Column<Assessment>[] = [
    {
      header: 'Name',
      sortKey: 'name',
      render: (a) => (
        <div>
          <div>{a.name}</div>
          {a.isPastDue && <Badge variant="danger" size="sm">Past Due</Badge>}
        </div>
      ),
    },
    {
      header: 'Status',
      sortKey: 'status',
      render: (a) => {
        const custom = assessmentStatusColors[a.status];
        return (
          <Badge
            variant={custom ? undefined : (ASSESSMENT_STATUS_COLORS[a.status] || 'secondary')}
            customColor={custom}
          >
            {a.status}
          </Badge>
        );
      },
    },
    {
      header: 'Start Date',
      sortKey: 'startDate',
      render: (a) => a.startDate ? new Date(a.startDate).toLocaleDateString() : '-',
    },
    {
      header: 'End Date',
      sortKey: 'plannedEndDate',
      render: (a) => a.plannedEndDate ? new Date(a.plannedEndDate).toLocaleDateString() : '-',
    },
    {
      header: 'Vulnerabilities',
      render: (a) => {
        const aVulns = appVulnerabilities.filter((v) => v.assessmentId === a.id);
        const critical = aVulns.filter((v) => v.severity === 'CRITICAL').length;
        const high = aVulns.filter((v) => v.severity === 'HIGH').length;
        const medium = aVulns.filter((v) => v.severity === 'MEDIUM').length;
        const low = aVulns.filter((v) => v.severity === 'LOW').length;
        if (critical + high + medium + low === 0) return <span className="text-muted">None</span>;
        return (
          <div className="vuln-severity-counts">
            {critical > 0 && <span className="vuln-count vuln-critical">{critical}C</span>}
            {high > 0 && <span className="vuln-count vuln-high">{high}H</span>}
            {medium > 0 && <span className="vuln-count vuln-medium">{medium}M</span>}
            {low > 0 && <span className="vuln-count vuln-low">{low}L</span>}
          </div>
        );
      },
    },
    {
      header: 'Surveys',
      render: (a) => {
        const surveys = assessmentSurveyMap[a.id] ?? [];
        if (surveys.length === 0) return null;
        const incomplete = surveys.filter((s) => s.status !== 'COMPLETE').length;
        const allDone = incomplete === 0;
        return (
          <ActionButtons>
            <IconButton
              icon={ClipboardList}
              onClick={() => setSurveyAssessment(a)}
              title="Open Surveys"
              variant={allDone ? 'success' : 'warning'}
            />
            <Badge variant={allDone ? 'success' : 'warning'} size="sm">
              {allDone ? 'Complete' : `${incomplete} pending`}
            </Badge>
          </ActionButtons>
        );
      },
    },
    {
      header: 'Report',
      render: (a) =>
        a.generatedReportFileId ? (
          <ActionButtons>
            <IconButton
              icon={Eye}
              onClick={() => setPreviewAssessment(a)}
              title="Preview PenTest Report"
              variant="edit"
            />
          </ActionButtons>
        ) : null,
    },
  ];

  return (
    <Page className="applications-page app-detail-page">
      {error && <ErrorMessage>{error}</ErrorMessage>}

      {/* Same summary donuts as the Applications/Vulnerabilities panes, scoped to this application */}
      <div className="app-detail-trend">
        <VulnSummaryPanel applicationId={id} />
      </div>

      <div className="app-detail-layout">
        <div className="app-detail-main">
          {/* Basic Information */}
          <EditableSection
            title="Basic Information"
            sectionKey="basicInfo"
            editing={editingSections.has('basicInfo')}
            canWrite={canWrite}
            saving={saving}
            onToggle={toggleSection}
            onSave={handleSaveSection}
            viewContent={
              <div className="app-detail-view-grid app-detail-view-grid--3">
                <div className="app-detail-field">
                  <span className="app-detail-field-label">Name</span>
                  <span className="app-detail-field-value app-detail-field-value--strong">
                    {appId && <span className="app-detail-appid-badge">{appId}</span>}
                    {formData.name}
                  </span>
                </div>
                <div className="app-detail-field">
                  <span className="app-detail-field-label">Status</span>
                  <span className="app-detail-field-value">
                    <Badge variant={STATUS_BADGE_VARIANTS[formData.status]}>{STATUS_LABELS[formData.status]}</Badge>
                  </span>
                </div>
                <div className="app-detail-field">
                  <span className="app-detail-field-label">Application Type</span>
                  <span className="app-detail-field-value">{formData.applicationType || '—'}</span>
                </div>
                <div className="app-detail-field">
                  <span className="app-detail-field-label">Application Owner</span>
                  <span className="app-detail-field-value">
                    {appOwner.fullName ? (
                      <>
                        {appOwner.fullName}
                        {appOwner.email && (
                          <>
                            {' · '}
                            <a href={`mailto:${appOwner.email}`} className="link">{appOwner.email}</a>
                            <CopyEmailButton email={appOwner.email} />
                          </>
                        )}
                      </>
                    ) : '—'}
                  </span>
                </div>
                {showMoreDetails && (
                <>
                <div className="app-detail-field app-detail-field--span2">
                  <span className="app-detail-field-label">Technologies</span>
                  {technologies.length === 0 ? (
                    <span className="app-detail-field-value">—</span>
                  ) : (
                    <div className="technology-tags">
                      {technologies.map((tech) => (
                        <div key={tech} className="technology-tag">{tech}</div>
                      ))}
                    </div>
                  )}
                </div>
                <div className="app-detail-field">
                  <span className="app-detail-field-label">Organization</span>
                  <span className="app-detail-field-value">{orgName || '—'}</span>
                </div>
                <div className="app-detail-field">
                  <span className="app-detail-field-label">Region</span>
                  <span className="app-detail-field-value">{formData.region || '—'}</span>
                </div>
                <div className="app-detail-field">
                  <span className="app-detail-field-label">Assessment Frequency</span>
                  <span className="app-detail-field-value">
                    {formData.assessmentFrequency || '—'}
                    {formData.assessmentFrequency === 'Custom' && formData.customFrequencyMonths
                      ? ` (${formData.customFrequencyMonths} ${formData.customFrequencyMonths === 1 ? 'month' : 'months'})`
                      : ''}
                  </span>
                </div>
                <div className="app-detail-field">
                  <span className="app-detail-field-label">
                    Stakeholders
                    {stakeholders.length > 0 && (
                      <CopyEmailButton
                        email={stakeholders.map((s) => s.email).join(', ')}
                        title="Copy all emails"
                      />
                    )}
                  </span>
                  {stakeholders.length === 0 ? (
                    <span className="app-detail-field-value">—</span>
                  ) : (
                    <div className="app-detail-simple-list">
                      {stakeholders.map((stakeholder, index) => (
                        <div key={index} className="app-detail-simple-list-item">
                          {stakeholder.name}
                          {' - '}
                          <a href={`mailto:${stakeholder.email}`} className="link">{stakeholder.email}</a>
                          <CopyEmailButton email={stakeholder.email} />
                          {' - '}
                          {stakeholder.role}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
                <div className="app-detail-field app-detail-field--span2">
                  <span className="app-detail-field-label">URLs</span>
                  {urls.length === 0 ? (
                    <span className="app-detail-field-value">—</span>
                  ) : (
                    <div className="app-detail-simple-list">
                      {urls.map((url, index) => (
                        <div key={index} className="app-detail-simple-list-item">
                          {url.title}
                          {' - '}
                          <a href={url.url} target="_blank" rel="noopener noreferrer" className="link">{url.url}</a>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
                <div className="app-detail-field app-detail-field--full">
                  <span className="app-detail-field-label">Description</span>
                  {formData.description ? (
                    <div className="app-detail-field-value app-detail-field-value--rich">
                      <RichTextEditor value={formData.description} disabled />
                    </div>
                  ) : (
                    <span className="app-detail-field-value">—</span>
                  )}
                </div>
                </>
                )}
                <div className="app-detail-field app-detail-field--full">
                  <button
                    type="button"
                    className="app-detail-show-more"
                    onClick={() => setShowMoreDetails((v) => !v)}
                  >
                    {showMoreDetails ? (
                      <>Show less <ChevronUp size={14} /></>
                    ) : (
                      <>Show more details <ChevronDown size={14} /></>
                    )}
                  </button>
                </div>
              </div>
            }
            editContent={
              <>
                <FormRow columns={3}>
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
                    <FormLabel>Application ID</FormLabel>
                    <Input
                      placeholder="e.g. APP-42"
                      value={appId}
                      onChange={(e) => setAppId(e.target.value)}
                    />
                  </FormGroup>
                  <FormGroup>
                    <FormLabel>Status</FormLabel>
                    <Select
                      value={formData.status}
                      onChange={(e) => setFormData({ ...formData, status: e.target.value as ApplicationStatus })}
                    >
                      {Object.entries(STATUS_LABELS).map(([value, label]) => (
                        <option key={value} value={value}>{label}</option>
                      ))}
                    </Select>
                  </FormGroup>
                </FormRow>
                <FormRow columns={3}>
                  <FormGroup>
                    <FormLabel>Organization</FormLabel>
                    <Select
                      value={formData.organizationId}
                      onChange={(e) => setFormData({
                        ...formData,
                        organizationId: e.target.value,
                        // The division belongs to the old organization, so it can't survive the move.
                        subOrganizationId: '',
                      })}
                    >
                      <option value="">None</option>
                      {organizations.map((org) => (
                        <option key={org.id} value={org.id}>{org.name}</option>
                      ))}
                    </Select>
                  </FormGroup>
                  <FormGroup>
                    <FormLabel>Sub-Organization</FormLabel>
                    <Select
                      value={formData.subOrganizationId}
                      onChange={(e) => setFormData({ ...formData, subOrganizationId: e.target.value })}
                      disabled={!formData.organizationId || subOrganizations.length === 0}
                    >
                      <option value="">None</option>
                      {subOrganizations.map((sub) => (
                        <option key={sub.id} value={sub.id}>{sub.name}</option>
                      ))}
                    </Select>
                    <FormHint>
                      {!formData.organizationId
                        ? 'Choose an organization first.'
                        : subOrganizations.length === 0
                          ? 'This organization has no sub-organizations yet.'
                          : 'Optional division within the organization.'}
                    </FormHint>
                  </FormGroup>
                  <FormGroup>
                    <FormLabel>Region</FormLabel>
                    <Select
                      value={formData.region}
                      onChange={(e) => setFormData({ ...formData, region: e.target.value })}
                    >
                      {regions.length === 0 && (
                        <option value={formData.region}>{formData.region}</option>
                      )}
                      {regions.map((r) => (
                        <option key={r} value={r}>{r}</option>
                      ))}
                    </Select>
                  </FormGroup>
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
                </FormRow>
                <FormRow columns={3}>
                  <FormGroup>
                    <FormLabel>Assessment Frequency</FormLabel>
                    <Select
                      value={formData.assessmentFrequency}
                      onChange={(e) => setFormData({ ...formData, assessmentFrequency: e.target.value, customFrequencyMonths: undefined })}
                    >
                      <option value="">Select frequency</option>
                      <option value="Ad Hoc">Ad Hoc</option>
                      <option value="Yearly">Yearly</option>
                      <option value="Custom">Custom</option>
                    </Select>
                  </FormGroup>
                  {formData.assessmentFrequency === 'Custom' && (
                    <FormGroup>
                      <FormLabel>Frequency Interval</FormLabel>
                      <Select
                        value={formData.customFrequencyMonths ?? ''}
                        onChange={(e) => setFormData({ ...formData, customFrequencyMonths: e.target.value ? Number(e.target.value) : undefined })}
                      >
                        <option value="">Select interval...</option>
                        {[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 18, 24].map((m) => (
                          <option key={m} value={m}>{m} {m === 1 ? 'month' : 'months'}</option>
                        ))}
                      </Select>
                    </FormGroup>
                  )}
                </FormRow>
                <FormRow columns={3}>
                  <FormGroup>
                    <FormLabel>Application Owner</FormLabel>
                    <Input
                      placeholder="Full Name"
                      value={appOwner.fullName}
                      onChange={(e) => setAppOwner({ ...appOwner, fullName: e.target.value })}
                    />
                    <Input
                      type="email"
                      placeholder="Email"
                      style={{ marginTop: '0.5rem' }}
                      value={appOwner.email}
                      onChange={(e) => setAppOwner({ ...appOwner, email: e.target.value })}
                    />
                  </FormGroup>
                  <div className="form-group" style={{ gridColumn: 'span 2' }}>
                    <FormLabel>Technologies</FormLabel>
                    {technologies.length > 0 && (
                      <div className="technology-tags" style={{ marginBottom: '0.5rem' }}>
                        {technologies.map((tech) => (
                          <div key={tech} className="technology-tag">
                            {tech}
                            <button type="button" onClick={() => handleToggleTechnology(tech)}>
                              <X size={12} />
                            </button>
                          </div>
                        ))}
                      </div>
                    )}
                    <div className="technology-tags" style={{ marginBottom: '0.5rem' }}>
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
                    <div className="technology-input-group">
                      <Input
                        placeholder="Add custom technology"
                        value={newTechnology}
                        onChange={(e) => setNewTechnology(e.target.value)}
                        onKeyDown={(e) => {
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
                </FormRow>
                <FormRow columns={3}>
                  <FormGroup>
                    <FormLabel>Stakeholders</FormLabel>
                    {stakeholders.length > 0 && (
                      <div className="app-detail-simple-list" style={{ marginBottom: '0.5rem' }}>
                        {stakeholders.map((stakeholder, index) => (
                          <div key={index} className="app-detail-simple-list-item">
                            {stakeholder.name}
                            {' - '}
                            {stakeholder.email}
                            {' - '}
                            {stakeholder.role}
                            <button
                              type="button"
                              className="app-detail-list-remove"
                              title="Remove"
                              onClick={() => setStakeholders(stakeholders.filter((_, i) => i !== index))}
                            >
                              <X size={12} />
                            </button>
                          </div>
                        ))}
                      </div>
                    )}
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
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
                      <div>
                        <Button type="button" onClick={handleAddStakeholder} size="sm">
                          Add
                        </Button>
                      </div>
                    </div>
                  </FormGroup>
                  <div className="form-group" style={{ gridColumn: 'span 2' }}>
                    <FormLabel>URLs</FormLabel>
                    {urls.length > 0 && (
                      <div className="app-detail-simple-list" style={{ marginBottom: '0.5rem' }}>
                        {urls.map((url, index) => (
                          <div key={index} className="app-detail-simple-list-item">
                            {url.title}
                            {' - '}
                            <a href={url.url} target="_blank" rel="noopener noreferrer" className="link">{url.url}</a>
                            <button
                              type="button"
                              className="app-detail-list-remove"
                              title="Remove"
                              onClick={() => setUrls(urls.filter((_, i) => i !== index))}
                            >
                              <X size={12} />
                            </button>
                          </div>
                        ))}
                      </div>
                    )}
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
                </FormRow>
                <FormGroup>
                  <FormLabel>Description</FormLabel>
                  <RichTextEditor
                    value={formData.description}
                    onChange={(html) => setFormData({ ...formData, description: html })}
                  />
                </FormGroup>
              </>
            }
          />


          {/* Vulnerabilities / Assessments for this application */}
          <div className="form-panel app-detail-section">
            <div className="app-tab-nav app-detail-tab-nav">
              <button
                className={`app-tab-btn${detailTab === 'vulnerabilities' ? ' active' : ''}`}
                onClick={() => setDetailTab('vulnerabilities')}
              >
                Vulnerabilities
              </button>
              <button
                className={`app-tab-btn${detailTab === 'assessments' ? ' active' : ''}`}
                onClick={() => setDetailTab('assessments')}
              >
                Assessments
              </button>
            </div>
            {detailTab === 'vulnerabilities' ? (
              <DataTable
                columns={vulnColumns}
                data={tableVulns}
                loading={vulnsLoading}
                pagination={vulnPagination}
                onPageChange={(p) => setVulnPage(p)}
                onPageSizeChange={() => {}}
                onSearchChange={(q) => { setVulnSearch(q); setVulnPage(0); }}
                searchPlaceholder="Search vulnerabilities"
                emptyMessage={vulnShowClosed ? 'No vulnerabilities found' : 'No open vulnerabilities found'}
                idAccessor="id"
                headerChildren={
                  <label className="app-vulns-show-closed">
                    <input
                      type="checkbox"
                      checked={vulnShowClosed}
                      onChange={(e) => { setVulnShowClosed(e.target.checked); setVulnPage(0); }}
                    />
                    Show Closed
                  </label>
                }
                sort={vulnSort}
                onSortChange={(next) => { setVulnSort(next); setVulnPage(0); }}
              />
            ) : (
              <DataTable
                columns={assessmentColumns}
                data={tableAssessments}
                loading={vulnsLoading}
                pagination={assessmentPagination}
                onPageChange={(p) => setAssessmentPage(p)}
                onPageSizeChange={() => {}}
                onSearchChange={(q) => { setAssessmentSearch(q); setAssessmentPage(0); }}
                searchPlaceholder="Search assessments"
                emptyMessage="No assessments found"
                idAccessor="id"
                sort={assessmentSort}
                onSortChange={(next) => { setAssessmentSort(next); setAssessmentPage(0); }}
              />
            )}
          </div>

          {/* Application owners (portal user assignment) */}
          {canAssignUsers && id && (
            <div className="form-panel app-detail-section">
              <ResourceUserManager resourceType="application" resourceId={id} />
            </div>
          )}

          {/* Additional Information */}
          {fieldDefinitions.length > 0 && (
            <EditableSection
              title="Additional Information"
              sectionKey="customFields"
              editing={editingSections.has('customFields')}
              canWrite={canWrite}
              saving={saving}
              onToggle={toggleSection}
              onSave={handleSaveSection}
              viewContent={
                <>
                  <div className="app-detail-view-grid">
                    {regularFields.map((field) => (
                      <div className="app-detail-field" key={field.id}>
                        <span className="app-detail-field-label">{field.displayName}</span>
                        <span className="app-detail-field-value">{fieldValues[field.id] || '—'}</span>
                      </div>
                    ))}
                  </div>
                  {richTextFields.map((field) => (
                    <div className="app-detail-field app-detail-field--full" key={field.id}>
                      <span className="app-detail-field-label">{field.displayName}</span>
                      {fieldValues[field.id] ? (
                        <RichTextEditor value={fieldValues[field.id]} disabled />
                      ) : (
                        <span className="app-detail-field-value">—</span>
                      )}
                    </div>
                  ))}
                </>
              }
              editContent={
                <>
                  {regularFields.length > 0 && (
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '1rem' }}>
                      {regularFields.map((field) => (
                        <FormGroup key={field.id}>
                          <FormLabel>
                            {field.displayName}
                            {field.required && (
                              <span style={{ color: 'var(--color-danger)', marginLeft: 2 }}>*</span>
                            )}
                          </FormLabel>
                          {field.fieldType === 'DROPDOWN' ? (
                            <Select
                              value={fieldValues[field.id] || ''}
                              onChange={(e) =>
                                setFieldValues({ ...fieldValues, [field.id]: e.target.value })
                              }
                            >
                              <option value="">Select...</option>
                              {(field.dropdownOptions || []).map((opt) => (
                                <option key={opt} value={opt}>{opt}</option>
                              ))}
                            </Select>
                          ) : (
                            <Input
                              value={fieldValues[field.id] || ''}
                              onChange={(e) =>
                                setFieldValues({ ...fieldValues, [field.id]: e.target.value })
                              }
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
                        {field.required && (
                          <span style={{ color: 'var(--color-danger)', marginLeft: 2 }}>*</span>
                        )}
                      </FormLabel>
                      <RichTextEditor
                        value={fieldValues[field.id] || ''}
                        onChange={(val) => setFieldValues({ ...fieldValues, [field.id]: val })}
                      />
                    </FormGroup>
                  ))}
                </>
              }
            />
          )}
        </div>

        {/* Chat — assessment team and application stakeholders/owners */}
        <aside className="app-detail-chat">
          <div className="app-detail-chat-header">
            <h3 className="form-section-title app-detail-chat-title">
              <MessageSquare size={16} />
              Discussion {comments.length > 0 && `(${comments.length})`}
            </h3>
          </div>
          {id && (
            <ThreadSubscribers
              api={{
                add: u => applicationsApi.addSubscriber(id, u).then(r => r.data),
                remove: u => applicationsApi.removeSubscriber(id, u).then(r => r.data),
              }}
              initial={subscribers}
              currentUsername={currentUsername}
              onChange={setSubscribers}
            />
          )}
          <div className="app-detail-chat-compose">
            <CommentAvatar name={currentUsername} authorId={currentUsername} size={32} />
            <div className="app-detail-chat-compose-input">
              {composeExpanded ? (
                <>
                  <RichTextEditor
                    ref={composeEditorRef}
                    value={commentDraft}
                    onChange={setCommentDraft}
                    placeholder="Add a comment…"
                    mentions
                    mentionContext={{ applicationId: id }}
                  />
                  <div className="app-chat-compose-actions">
                    <button
                      className="app-chat-compose-cancel"
                      onClick={() => { setComposeExpanded(false); setCommentDraft(''); }}
                      disabled={submittingComment}
                    >
                      Cancel
                    </button>
                    <button
                      className="app-chat-submit"
                      onClick={handleAddComment}
                      disabled={submittingComment || !commentDraft.trim()}
                    >
                      <Send size={14} />
                      {submittingComment ? 'Sending…' : 'Send'}
                    </button>
                  </div>
                </>
              ) : (
                <button
                  type="button"
                  className="app-chat-compose-collapsed"
                  onClick={() => setComposeExpanded(true)}
                >
                  Add a comment…
                </button>
              )}
            </div>
          </div>
          <div className="app-detail-chat-list">
            {comments.length === 0 && (
              <p className="app-chat-empty">No messages yet — start the conversation with the app team.</p>
            )}
            {[...comments]
              .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
              .map((c) => {
                const displayName = c.authorName || c.authorId;
                const isOwn = c.authorId === currentUsername;
                return (
                  <div key={c.id} className={`app-chat-comment${isOwn ? ' app-chat-comment--own' : ''}${c.systemGenerated ? ' app-chat-comment--system' : ''}`}>
                    {c.systemGenerated
                      ? <SystemEventIcon content={c.content} />
                      : <CommentAvatar name={displayName} authorId={c.authorId} />}
                    <div className="app-chat-bubble">
                      <div className="app-chat-meta">
                        {c.systemGenerated
                          ? <span className="app-chat-system-label">System</span>
                          : <span className="app-chat-author">{displayName}</span>
                        }
                        <span className="app-chat-dot">·</span>
                        <span className="app-chat-date" title={new Date(c.createdAt).toLocaleString()}>
                          {formatCommentDate(c.createdAt)}
                        </span>
                        {isOwn && !c.systemGenerated && (
                          <button
                            className="app-chat-delete"
                            onClick={() => setConfirmDeleteCommentId(c.id)}
                            title="Delete message"
                            disabled={deletingCommentId === c.id}
                          >
                            <Trash2 size={12} />
                          </button>
                        )}
                      </div>
                      <div className="app-chat-body">
                        <RichTextEditor value={c.systemGenerated ? marked.parse(c.content) as string : c.content} disabled />
                      </div>
                    </div>
                  </div>
                );
              })}
          </div>
        </aside>
      </div>

      <ConfirmDialog
        isOpen={!!confirmDeleteCommentId}
        onClose={() => setConfirmDeleteCommentId(null)}
        onConfirm={() => confirmDeleteCommentId && handleDeleteComment(confirmDeleteCommentId)}
        title="Delete Message"
        message="Are you sure you want to delete this message? This cannot be undone."
        confirmText="Delete"
        variant="danger"
        isLoading={!!deletingCommentId}
      />

      <VulnerabilityDetailDrawer
        vulnerability={selectedVuln}
        assessment={selectedVulnAssessment}
        onClose={() => { setSelectedVuln(null); setSelectedVulnAssessment(null); }}
      />

      <SurveyDrawer
        assessment={surveyAssessment}
        onClose={() => setSurveyAssessment(null)}
      />

      <ReportPreviewDrawer
        assessment={previewAssessment}
        onClose={() => setPreviewAssessment(null)}
      />
    </Page>
  );
}
