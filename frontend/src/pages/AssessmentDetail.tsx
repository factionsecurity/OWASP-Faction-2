import { useEffect, useState, useRef, createRef, useCallback } from 'react';
import DOMPurify from 'dompurify';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { usePageTitle } from '../context/PageTitleContext';
import {
  Users,
  Braces,
  FileText,
  ShieldAlert,
  Flag,
  Copy,
  Check,
  FileOutput,
  Download,
  Lock,
  Paperclip,
  Trash2,
  UploadCloud,
  Plus,
  ClipboardCheck,
  Loader2,
  CheckSquare,
  History,
  BookOpen,
  Eye,
  Pencil,
  RotateCcw,
  Unlock,
} from 'lucide-react';
import { assessmentsApi, applicationsApi, organizationsApi, inlineImagesApi, peerReviewsApi, reportsApi, workflowConfigApi, uploadFileContent } from '../api';
import type { Assessment, Application, Organization, UserDefinedField, FieldLockInfo, AssessmentFile, DefaultVulnerability, PeerReview, AssessmentWorkflowConfig } from '../types';
import DefaultVulnerabilitySearchDialog from '../components/DefaultVulnerabilitySearchDialog';
import AssessmentVulnerabilitySection from './AssessmentVulnerabilitySection';
import AssessmentFinalizeSection from './AssessmentFinalizeSection';
import AssessmentChecklistSection from './AssessmentChecklistSection';
import AssessmentHistorySection from './AssessmentHistorySection';
import AssessmentNotebookSection from './AssessmentNotebookSection';
import ReportPreviewDrawer from '../components/ReportPreviewDrawer';
import PeerReviewDiff from './PeerReviewDiff';
import type { RefObject } from 'react';
import { Button, Badge, Toast } from '../components';
import RichTextEditor from '../components/RichTextEditor';
import type { RichTextEditorRef } from '../components/RichTextEditor';
import Page from '../components/Page';
import AssessmentInfoEditDialog from '../components/AssessmentInfoEditDialog';
import { usePermissions } from '../utils/permissions';
import { createSseParser } from '../utils/sse';
import ConfirmDialog from '../components/ConfirmDialog';
import './AssessmentDetail.css';

/**
 * How long after completion an assessment can still be reopened. Mirrors
 * AssessmentService.REOPEN_WINDOW_DAYS, which enforces it and keeps the assessment in the
 * assessment queue for the same period.
 */
const REOPEN_WINDOW_DAYS = 30;

/** Inner sidebar: hover-to-expand, or pinned open / closed by the lock toggle. */
type SidebarLock = 'auto' | 'open' | 'closed';
const SIDEBAR_LOCK_KEY = 'assessment-sidebar-lock';

/** Cycles auto → locked open → locked closed → auto. */
const NEXT_SIDEBAR_LOCK: Record<SidebarLock, SidebarLock> = {
  auto: 'open',
  open: 'closed',
  closed: 'auto',
};

const SIDEBAR_LOCK_LABELS: Record<SidebarLock, string> = {
  auto: 'Expands on hover — click to lock open',
  open: 'Locked open — click to lock closed',
  closed: 'Locked closed — click to unlock',
};

const STATUS_COLORS: Record<string, 'success' | 'warning' | 'info' | 'danger' | 'secondary'> = {
  DRAFT: 'secondary',
  IN_PROGRESS: 'info',
  ON_HOLD: 'warning',
  PENDING_REVIEW: 'info',
  COMPLETED: 'success',
  APPROVED: 'success',
  ARCHIVED: 'secondary',
};

function getStatusColor(status: string, completedStatus?: string): 'success' | 'warning' | 'info' | 'danger' | 'secondary' {
  if (completedStatus && status === completedStatus) return 'success';
  return STATUS_COLORS[status] ?? 'info';
}

/**
 * Section ids a notification link may point at. Validated rather than trusted, because
 * `activeSection` drives which block renders and an unknown value renders none of them —
 * a blank page is a worse failure than the wrong tab. The two prefixed forms are dynamic:
 * `field-<variableName>` for a user-defined field, `vuln-section-<name>` per report section.
 */
const STATIC_LINKABLE_SECTIONS = [
  'assessment-info', 'variables', 'checklists', 'notebook',
  'history', 'finalize', 'peer-review', 'vulnerabilities',
];

function isLinkableSection(section: string | null): boolean {
  if (!section) return false;
  return STATIC_LINKABLE_SECTIONS.includes(section)
    || section.startsWith('field-')
    || section.startsWith('vuln-section-');
}

export default function AssessmentDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { permissions } = usePermissions();
  const [searchParams, setSearchParams] = useSearchParams();
  const { setBreadcrumbs } = usePageTitle();

  // Capture ?vuln= once at render time so it survives any URL changes
  const initialVulnIdRef = useRef(searchParams.get('vuln'));

  // Inner sidebar behaviour: 'auto' expands on hover (the default), the locked states pin it
  // open or closed so the section list stops moving while working in the content area.
  // Remembered per browser — it is a workspace preference, not assessment data.
  const [sidebarLock, setSidebarLock] = useState<SidebarLock>(() => {
    try {
      const stored = localStorage.getItem(SIDEBAR_LOCK_KEY);
      return stored === 'open' || stored === 'closed' ? stored : 'auto';
    } catch {
      return 'auto';
    }
  });

  // Collapsed rail shows icons only, so hovering one surfaces its label in a tooltip. It is
  // positioned from the hovered element's rect and rendered fixed: the rail and its scrolling
  // list both clip their overflow, so a pseudo-element tooltip would be cut off.
  const [navTooltip, setNavTooltip] = useState<{ label: string; top: number; left: number } | null>(null);

  const [assessment, setAssessment] = useState<Assessment | null>(null);
  const [application, setApplication] = useState<Application | null>(null);
  const [organization, setOrganization] = useState<Organization | null>(null);
  const [workflowConfig, setWorkflowConfig] = useState<AssessmentWorkflowConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [fieldDefinitions, setFieldDefinitions] = useState<UserDefinedField[]>([]);
  const [fieldValues, setFieldValues] = useState<Record<string, string>>({});
  const [saveStatus, setSaveStatus] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');
  const [toastKey, setToastKey] = useState(0);
  const [showToast, setShowToast] = useState(false);
  const [richSaveStatuses, setRichSaveStatuses] = useState<
    Record<string, 'idle' | 'saving' | 'saved' | 'error'>
  >({});
  // Deep links from notifications carry ?section= (e.g. a notebook mention links to
  // ?section=notebook&node=<id>). Without honouring it the page opened on its default tab
  // and the linked note was never shown, however correct the rest of the link was.
  // Read once at mount: AssessmentNotebookSection strips these params after it opens the
  // node, and re-reading would then bounce the user back to the default tab.
  const initialSectionRef = useRef(searchParams.get('section'));
  const [activeSection, setActiveSection] = useState<string>(
    isLinkableSection(initialSectionRef.current)
      ? initialSectionRef.current as string
      : initialVulnIdRef.current ? 'vulnerabilities' : 'assessment-info'
  );
  const [copiedVar, setCopiedVar] = useState<string | null>(null);

  const [sseUnavailable, setSseUnavailable] = useState(false);
  // Bumped on every vulnerabilities_changed SSE event; the vulnerability
  // sections refetch their list when it changes.
  const [vulnRefreshToken, setVulnRefreshToken] = useState(0);

  const [showDefaultVulnSearch, setShowDefaultVulnSearch] = useState(false);
  const [showEditInfo, setShowEditInfo] = useState(false);
  const [showReopenConfirm, setShowReopenConfirm] = useState(false);
  const [reopening, setReopening] = useState(false);
  const [pendingDefaultVuln, setPendingDefaultVuln] = useState<DefaultVulnerability | null | undefined>(undefined);
  const [pendingVulnName, setPendingVulnName] = useState<string | undefined>(undefined);

  const [activePeerReview, setActivePeerReview] = useState<PeerReview | null>(null);

  const [attachments, setAttachments] = useState<AssessmentFile[]>([]);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [generating, setGenerating] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [toastMessage, setToastMessage] = useState('Saved');
  const [toastVariant, setToastVariant] = useState<'success' | 'warning' | 'danger'>('success');
  const pollTimer = useRef<ReturnType<typeof setInterval> | null>(null);
  const loadDataStartedForRef = useRef<string | null>(null);

  const saveTimer = useRef<ReturnType<typeof setTimeout>>();
  const richSaveTimers = useRef<Record<string, ReturnType<typeof setTimeout>>>({});
  const editorRefsMap = useRef<Map<string, RefObject<RichTextEditorRef>>>(new Map());

  const fieldValuesRef = useRef<Record<string, string>>({});
  const [fieldLocks, setFieldLocks] = useState<Record<string, FieldLockInfo | undefined>>({});
  const fieldLocksRef = useRef<Record<string, FieldLockInfo | undefined>>({});
  const currentUsername = useRef(
    (() => { try { return JSON.parse(localStorage.getItem('user') || '{}').username ?? ''; } catch { return ''; } })()
  ).current;

  useEffect(() => { fieldLocksRef.current = fieldLocks; }, [fieldLocks]);
  useEffect(() => { fieldValuesRef.current = fieldValues; }, [fieldValues]);

  useEffect(() => {
    if (!id || loadDataStartedForRef.current === id) return;
    loadDataStartedForRef.current = id;
    loadData(id);
    return () => {
      setBreadcrumbs(null);
      if (pollTimer.current) clearInterval(pollTimer.current);
    };
  }, [id]);

  const refreshVulnerabilitySummary = async () => {
    if (!id) return;
    const res = await assessmentsApi.getById(id).catch(() => null);
    if (res?.success && res.data) {
      setAssessment(prev => prev ? { ...prev, vulnerabilitySummary: res.data!.vulnerabilitySummary } : prev);
    }
  };

  const showToastMessage = (message: string, variant: 'success' | 'warning' | 'danger' = 'success') => {
    setToastMessage(message);
    setToastVariant(variant);
    setToastKey(k => k + 1);
    setShowToast(true);
  };

  /**
   * Watch a generation run to completion. Every artifact (DOCX, PDF, encrypted PDF)
   * reports its own status, so the run is only done once none of them is GENERATING —
   * the presence of generatedReportFileId can't be the signal, since a regeneration
   * starts with last run's id still on the assessment and would look finished
   * immediately. Mirrors ReportDocumentsPanel's poll loop.
   */
  const pollReportGeneration = useCallback(() => {
    if (!id) return;
    setGenerating(true);
    let attempts = 0;
    const MAX_ATTEMPTS = 60; // 3 minutes
    if (pollTimer.current) clearInterval(pollTimer.current);
    pollTimer.current = setInterval(async () => {
      attempts++;
      try {
        const docs = await reportsApi.getDocuments(id);
        const stillGenerating = (docs.data?.documents ?? []).some(d => d.status === 'GENERATING');
        if (!stillGenerating) {
          clearInterval(pollTimer.current!);
          pollTimer.current = null;
          setGenerating(false);
          const res = await assessmentsApi.getById(id).catch(() => null);
          if (res?.success && res.data) {
            setAssessment(prev => prev ? {
              ...prev,
              generatedReportFileId: res.data!.generatedReportFileId,
              reportGeneratedAt: res.data!.reportGeneratedAt,
            } : prev);
          }
          const failed = (docs.data?.documents ?? []).some(d => d.status === 'FAILED');
          showToastMessage(
            failed ? 'Report generation failed — see the Finalize tab for details' : 'Report ready for download',
            failed ? 'danger' : 'success'
          );
          return;
        }
      } catch { /* ignore poll errors */ }
      if (attempts >= MAX_ATTEMPTS) {
        clearInterval(pollTimer.current!);
        pollTimer.current = null;
        setGenerating(false);
        showToastMessage('Report generation timed out', 'warning');
      }
    }, 3000);
  }, [id]);

  // A run started elsewhere (the Finalize panel) or before a reload is still in flight;
  // pick the spinner back up so the header button matches reality.
  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    reportsApi.getDocuments(id)
      .then(res => {
        if (cancelled) return;
        if ((res.data?.documents ?? []).some(d => d.status === 'GENERATING')) pollReportGeneration();
      })
      .catch(() => { /* the Finalize tab surfaces document errors */ });
    return () => { cancelled = true; };
  }, [id, pollReportGeneration]);

  const handleGenerateReport = async () => {
    if (!id) return;
    setGenerating(true);
    try {
      await reportsApi.generate(id);
      showToastMessage('Report generation started');
      pollReportGeneration();
    } catch {
      setGenerating(false);
      showToastMessage('Failed to start report generation', 'danger');
    }
  };

  const handleDownloadReport = async () => {
    if (!id) return;
    try {
      window.open(reportsApi.getDownloadUrl(id), '_blank', 'noopener,noreferrer');
    } catch {
      showToastMessage('Failed to get download URL', 'danger');
    }
  };

  const loadData = async (assessmentId: string) => {
    setLoading(true);
    setError('');
    try {
      const assessmentRes = await assessmentsApi.getById(assessmentId);
      if (!assessmentRes.success || !assessmentRes.data) {
        setError('Assessment not found');
        return;
      }
      const a = assessmentRes.data;
      setAssessment(a);
      setFieldValues(a.fieldValues || {});
      setAttachments(a.attachments || []);
      // Use the assessment's own snapshotted field definitions — these IDs are
      // guaranteed to match the keys in fieldValues and what the backend accepts.
      setFieldDefinitions(a.fieldDefinitions || []);

      const appRes = await applicationsApi.getById(a.applicationId);
      const app = appRes.success ? appRes.data ?? null : null;
      if (app) setApplication(app);

      // Prefer the assessment's own orgId; fall back to the application's orgId
      const orgId = a.organizationId || app?.organizationId;
      if (orgId) {
        const orgRes = await organizationsApi.getById(orgId);
        if (orgRes.success && orgRes.data) setOrganization(orgRes.data);
      }

      const appName = app?.name || a.applicationName || 'Unknown App';
      setBreadcrumbs([
        { label: 'Assessments', to: '/assessments' },
        { label: `${appName} — ${a.name}` },
      ]);

      // Fire-and-forget — must NOT be awaited; the vuln section's calls are
      // sequential (one at a time) so the combined peak never exceeds 6 connections.
      workflowConfigApi.getConfig()
        .then(r => { if (r.success && r.data) setWorkflowConfig(r.data); })
        .catch(() => {});

      if (a.activePeerReviewId) {
        peerReviewsApi.getById(a.activePeerReviewId)
          .then(r => { if (r.success && r.data) setActivePeerReview(r.data); })
          .catch(() => {});
      } else {
        setActivePeerReview(null);
      }
    } catch (err: unknown) {
      const axiosErr = err as { response?: { data?: { message?: string } } };
      setError(axiosErr.response?.data?.message || 'Failed to load assessment');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (!id || loading) return;
    const controller = new AbortController();

    const clientId = sessionStorage.getItem('sseClientId') ?? (() => {
      const cid = Math.random().toString(36).slice(2);
      sessionStorage.setItem('sseClientId', cid);
      return cid;
    })();

    const handleEvent = (type: string, data: string) => {
      if (type === 'current_locks') {
        const { locks } = JSON.parse(data) as { locks: FieldLockInfo[] };
        const map: Record<string, FieldLockInfo> = {};
        locks.forEach(l => { map[l.fieldId] = l; });
        setFieldLocks(map);
      } else if (type === 'field_locked') {
        const lockData = JSON.parse(data) as FieldLockInfo;
        setFieldLocks(prev => ({ ...prev, [lockData.fieldId]: lockData }));
      } else if (type === 'field_unlocked') {
        const { fieldId } = JSON.parse(data) as { fieldId: string };
        setFieldLocks(prev => { const n = { ...prev }; delete n[fieldId]; return n; });
      } else if (type === 'field_updated') {
        const { fieldId, value } = JSON.parse(data) as { fieldId: string; value: string };
        const lock = fieldLocksRef.current[fieldId];
        if (!lock || lock.username !== currentUsername) {
          setFieldValues(prev => ({ ...prev, [fieldId]: value }));
        }
      } else if (type === 'vulnerabilities_changed') {
        // Another client (or an API/MCP upload) changed this assessment's
        // vulnerabilities — refresh the list section and the summary counts.
        setVulnRefreshToken(t => t + 1);
        refreshVulnerabilitySummary();
      }
    };

    const connect = async () => {
      let errorCount = 0;
      while (!controller.signal.aborted) {
        try {
          const token = localStorage.getItem('token') ?? '';
          const response = await fetch(
            `/api/v1/assessments/${id}/events?clientId=${clientId}`,
            {
              headers: {
                Authorization: `Bearer ${token}`,
                Accept: 'text/event-stream',
                'Cache-Control': 'no-cache',
              },
              signal: controller.signal,
            }
          );

          if (!response.ok || !response.body) {
            throw new Error(`SSE ${response.status}`);
          }

          errorCount = 0; // successful connection — reset counter
          const reader = response.body.getReader();
          const decoder = new TextDecoder();
          const feed = createSseParser(handleEvent);

          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            feed(decoder.decode(value, { stream: true }));
          }
        } catch {
          if (controller.signal.aborted) break;
          errorCount++;
          if (errorCount >= 3) {
            setSseUnavailable(true);
            break;
          }
          await new Promise(r => setTimeout(r, 3000));
        }
      }
    };

    connect();
    return () => controller.abort();
  }, [id, loading]);

  // Filter fieldValues to only include IDs present in the assessment's own
  // snapshotted fieldDefinitions. The live template may have new fields whose
  // IDs are not in the snapshot; the backend would reject them with a 400.
  const filterToSnapshotIds = useCallback(
    (values: Record<string, string>): Record<string, string> => {
      const snapshotIds = new Set(
        (assessment?.fieldDefinitions ?? []).map((f) => f.id)
      );
      const filtered: Record<string, string> = {};
      for (const [k, v] of Object.entries(values)) {
        if (snapshotIds.has(k)) filtered[k] = v;
      }
      return filtered;
    },
    [assessment]
  );

  // Auto-save for string/dropdown fields (800ms debounce)
  const saveFieldValues = useCallback(
    async (values: Record<string, string>) => {
      if (!id) return;
      setSaveStatus('saving');
      try {
        await assessmentsApi.update(id, { fieldValues: filterToSnapshotIds(values) });
        setSaveStatus('saved');
        setTimeout(() => setSaveStatus('idle'), 2000);
        setToastKey(k => k + 1);
        setShowToast(true);
      } catch {
        setSaveStatus('error');
      }
    },
    [id, filterToSnapshotIds]
  );

  // When a deep link (?vuln=) is present and the assessment uses sections,
  // switch from the generic 'vulnerabilities' initial value to the first section's nav ID.
  useEffect(() => {
    if (!initialVulnIdRef.current || loading || !assessment) return;
    if (assessment.sections && assessment.sections.length > 0 && activeSection === 'vulnerabilities') {
      setActiveSection(`vuln-section-${assessment.sections[0]}`);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loading]);

  const showNavTooltip = useCallback((label: string, e: React.MouseEvent<HTMLElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    setNavTooltip({ label, top: rect.top + rect.height / 2, left: rect.right + 8 });
  }, []);

  const hideNavTooltip = useCallback(() => setNavTooltip(null), []);

  const cycleSidebarLock = useCallback(() => {
    hideNavTooltip();
    setSidebarLock(prev => {
      const next = NEXT_SIDEBAR_LOCK[prev];
      try {
        localStorage.setItem(SIDEBAR_LOCK_KEY, next);
      } catch {
        // Private browsing or blocked storage — the preference just does not persist.
      }
      return next;
    });
  }, [hideNavTooltip]);

  // Locks lapse 10s after the last edit and nothing else drops them, so moving to
  // another section deliberately keeps whatever the user was just editing locked.
  const handleSectionChange = useCallback((nextSection: string) => {
    setActiveSection(nextSection);
  }, []);

  const handleVulnSelected = useCallback((vulnId: string | null) => {
    setSearchParams(p => {
      if (vulnId) p.set('vuln', vulnId); else p.delete('vuln');
      return p;
    }, { replace: true });
  }, [setSearchParams]);

  const getLockState = (fieldId: string) => {
    const lock = fieldLocks[fieldId];
    if (!lock) return { locked: false, isMine: false, holder: '' };
    return { locked: true, isMine: lock.username === currentUsername, holder: lock.displayName || lock.username };
  };

  const handleFieldChange = (fieldId: string, value: string) => {
    const next = { ...fieldValues, [fieldId]: value };
    if (id) assessmentsApi.acquireLock(id, fieldId).catch(() => {});
    setFieldValues(next);
    clearTimeout(saveTimer.current);
    saveTimer.current = setTimeout(() => saveFieldValues(next), 800);
  };

  // Rich text field change handler (1.5s debounce)
  const handleRichFieldChange = (fieldId: string, value: string) => {
    const next = { ...fieldValues, [fieldId]: value };
    if (id) assessmentsApi.acquireLock(id, fieldId).catch(() => {});
    setFieldValues(next);
    clearTimeout(richSaveTimers.current[fieldId]);
    richSaveTimers.current[fieldId] = setTimeout(
      () => saveRichField(fieldId, next),
      1500
    );
  };

  const saveRichField = async (fieldId: string, values?: Record<string, string>) => {
    if (!id) return;
    // Use the ref when no snapshot is provided (e.g. manual Save button click)
    // so we always get the latest values regardless of closure staleness.
    const valuesToSave = values ?? fieldValuesRef.current;
    setRichSaveStatuses((prev) => ({ ...prev, [fieldId]: 'saving' }));
    try {
      await assessmentsApi.update(id, { fieldValues: filterToSnapshotIds(valuesToSave) });
      setRichSaveStatuses((prev) => ({ ...prev, [fieldId]: 'saved' }));
      setTimeout(
        () => setRichSaveStatuses((prev) => ({ ...prev, [fieldId]: 'idle' })),
        2000
      );
      setToastKey(k => k + 1);
      setShowToast(true);
    } catch {
      setRichSaveStatuses((prev) => ({ ...prev, [fieldId]: 'error' }));
    }
  };

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || !id) return;
    setUploading(true);
    setUploadError('');
    try {
      // Step 1: allocate a file id and backend upload target
      const prepareRes = await assessmentsApi.prepareUpload(id, file.name, file.type, file.size);
      if (!prepareRes.success || !prepareRes.data) throw new Error('Failed to get upload URL');
      const { fileId, uploadUrl } = prepareRes.data;

      // Step 2: stream the body to the backend, which writes it to storage
      await uploadFileContent(uploadUrl, file);

      // Step 3: confirm metadata
      const confirmRes = await assessmentsApi.confirmUpload(id, fileId, file.name, file.type, file.size);
      if (!confirmRes.success || !confirmRes.data) throw new Error('Failed to confirm upload');
      setAttachments(prev => [...prev, confirmRes.data!]);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Upload failed';
      setUploadError(msg);
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const handleDownload = async (fileId: string, fileName: string) => {
    if (!id) return;
    try {
      const a = document.createElement('a');
      a.href = assessmentsApi.getDownloadUrl(id, fileId);
      a.download = fileName;
      a.target = '_blank';
      a.rel = 'noopener noreferrer';
      a.click();
    } catch {
      // silently fail
    }
  };

  const handleDeleteFile = async (fileId: string) => {
    if (!id) return;
    try {
      await assessmentsApi.deleteFile(id, fileId);
      setAttachments(prev => prev.filter(f => f.id !== fileId));
    } catch {
      // silently fail
    }
  };

  const formatBytes = (bytes: number): string => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

  const handleInlineImageUpload = async (file: File): Promise<string> => {
    if (!id) throw new Error('No assessment ID');
    const res = await inlineImagesApi.upload(id, file);
    if (!res.success || !res.data) throw new Error('Image upload failed');
    return res.data.url;
  };

  const copyToClipboard = async (text: string, key: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedVar(key);
      setTimeout(() => setCopiedVar(null), 2000);
    } catch {
      // silently fail
    }
  };

  const getEditorRef = (varName: string): RefObject<RichTextEditorRef> => {
    if (!editorRefsMap.current.has(varName)) {
      editorRefsMap.current.set(varName, createRef<RichTextEditorRef>());
    }
    return editorRefsMap.current.get(varName)!;
  };

  if (loading) {
    return (
      <div
        className="d-flex justify-content-center align-items-center"
        style={{ minHeight: '400px' }}
      >
        <div className="spinner-border text-primary" role="status">
          <span className="visually-hidden">Loading...</span>
        </div>
      </div>
    );
  }

  if (error || !assessment) {
    return (
      <div style={{ padding: '2rem' }}>
        <div className="alert alert-danger">{error || 'Assessment not found'}</div>
        <Button variant="secondary" onClick={() => navigate('/assessments')}>
          Go to Assessments
        </Button>
      </div>
    );
  }

  const isFinalized = workflowConfig
    ? assessment.status === workflowConfig.completedStatus
        || ['COMPLETED', 'APPROVED', 'ARCHIVED'].includes(assessment.status)
    : ['COMPLETED', 'APPROVED', 'ARCHIVED'].includes(assessment.status);

  // Whole days left in the reopen window; 0 once it has lapsed or the assessment isn't completed.
  // Mirrors AssessmentService.REOPEN_WINDOW_DAYS, which enforces it — the server rejects a late
  // reopen whatever the UI offers.
  const reopenDaysLeft = (() => {
    if (!isFinalized || !assessment.completedDate) return 0;
    const deadline = new Date(assessment.completedDate).getTime()
      + REOPEN_WINDOW_DAYS * 24 * 60 * 60 * 1000;
    return Math.max(0, Math.ceil((deadline - Date.now()) / (24 * 60 * 60 * 1000)));
  })();

  const handleReopen = async () => {
    setReopening(true);
    try {
      const res = await assessmentsApi.updateStatus(assessment.id, workflowConfig?.inProgressStatus ?? 'IN_PROGRESS');
      if (res.success && res.data) {
        setAssessment(res.data);
        showToastMessage('Assessment reopened');
      }
    } catch (err: any) {
      showToastMessage(err.response?.data?.message || 'Failed to reopen assessment', 'danger');
    } finally {
      setReopening(false);
      setShowReopenConfirm(false);
    }
  };

  // The Edit button only appears for callers who may actually edit this assessment. The backend
  // enforces the same thing per-assessment (AccessScopeService#checkAssessmentEditAccess) — an
  // assessor scoped to their own assessments can't save someone else's — so this only decides
  // whether to offer the affordance.
  const canEditThisAssessment = permissions.canEditAssessments;
  const isPeerReviewLocked = assessment.peerReviewStatus === 'IN_PEER_REVIEW'
    || assessment.peerReviewStatus === 'NEEDS_ACCEPTANCE';

  const sortByDisplayOrder = (a: UserDefinedField, b: UserDefinedField) =>
    (a.displayOrder ?? 0) - (b.displayOrder ?? 0);

  const stringDropdownFields = fieldDefinitions
    .filter((f) => f.fieldType === 'STRING' || f.fieldType === 'DROPDOWN')
    .sort(sortByDisplayOrder);
  const richTextFields = fieldDefinitions
    .filter((f) => f.fieldType === 'RICH_TEXT')
    .sort(sortByDisplayOrder);

  const sidebarItems = [
    { id: 'assessment-info', label: 'Assessment Info', Icon: Users },
    ...(stringDropdownFields.length > 0
      ? [{ id: 'variables', label: 'Variables', Icon: Braces }]
      : []),
    ...richTextFields.map((f) => ({
      id: `field-${f.variableName}`,
      label: f.displayName,
      Icon: FileText,
    })),
    ...(assessment.sections && assessment.sections.length > 0
      ? assessment.sections.map((s) => ({
          id: `vuln-section-${s}`,
          label: `${s} Vulnerabilities`,
          Icon: ShieldAlert,
        }))
      : [{ id: 'vulnerabilities', label: 'Vulnerabilities', Icon: ShieldAlert }]),
    { id: 'checklists', label: 'Checklists', Icon: CheckSquare },
    { id: 'notebook', label: 'Notebook', Icon: BookOpen },
    { id: 'history', label: 'History', Icon: History },
    { id: 'finalize', label: 'Finalize', Icon: Flag },
    ...(assessment.peerReviewStatus === 'NEEDS_ACCEPTANCE'
      ? [{ id: 'peer-review', label: 'Peer Review', Icon: ClipboardCheck }]
      : []),
  ];

  const vuln = assessment.vulnerabilitySummary;

  return (
    <>
    <Page variant="flush" fill className="assessment-page">
      {/* Quick Stats Bar */}
      <div className="vuln-stats-bar">
        <span className="vuln-stat critical">
          <span className="vuln-dot" />
          Critical {vuln?.critical ?? 0}
        </span>
        <span className="vuln-stat high">
          <span className="vuln-dot" />
          High {vuln?.high ?? 0}
        </span>
        <span className="vuln-stat medium">
          <span className="vuln-dot" />
          Medium {vuln?.medium ?? 0}
        </span>
        <span className="vuln-stat low">
          <span className="vuln-dot" />
          Low {vuln?.low ?? 0}
        </span>
        <span className="vuln-stat info">
          <span className="vuln-dot" />
          Info {vuln?.informational ?? 0}
        </span>

        <div className="stats-bar-actions">
          {!isFinalized && (
            <Button size="sm" variant="secondary" onClick={() => setShowDefaultVulnSearch(true)}>
              <Plus size={14} />
              Add Vulnerability
            </Button>
          )}
          {/* A completed assessment's report is the issued deliverable — the server rejects a
              regenerate, so don't offer one. Downloads and previews stay available. */}
          <Button size="sm" variant="secondary" onClick={handleGenerateReport} disabled={generating || isFinalized}>
            {generating ? <Loader2 size={14} className="spin" /> : <FileOutput size={14} />}
            {generating ? 'Generating...' : 'Generate Report'}
          </Button>
          <Button size="sm" variant="secondary" disabled={!assessment.generatedReportFileId || generating || previewLoading} onClick={() => setPreviewOpen(true)}>
            {previewLoading ? <Loader2 size={14} className="spin" /> : <Eye size={14} />}
            {previewLoading ? 'Loading Preview...' : 'Preview Report'}
          </Button>
          <Button size="sm" variant="secondary" disabled={!assessment.generatedReportFileId || generating} onClick={handleDownloadReport}>
            <Download size={14} />
            Download Report
          </Button>
        </div>
      </div>

      {/* Two-column workspace */}
      <div className="assessment-workspace">
        {/* Inner Sidebar */}
        <nav
          className={`inner-sidebar${sidebarLock === 'auto' ? '' : ` inner-sidebar--locked-${sidebarLock}`}`}
          aria-label="Section navigation"
        >
          <div className="inner-nav-list">
            {sidebarItems.map(({ id: sId, label, Icon }) => (
              <div
                key={sId}
                className={`inner-nav-item${activeSection === sId ? ' active' : ''}`}
                onClick={() => handleSectionChange(sId)}
                role="button"
                tabIndex={0}
                onKeyDown={(e) => e.key === 'Enter' && handleSectionChange(sId)}
                onMouseEnter={sidebarLock === 'closed' ? (e) => showNavTooltip(label, e) : undefined}
                onMouseLeave={sidebarLock === 'closed' ? hideNavTooltip : undefined}
                title={sidebarLock === 'closed' ? undefined : label}
              >
                <Icon size={18} className="inner-nav-icon" />
                <span className="inner-nav-label">{label}</span>
              </div>
            ))}
          </div>
          <div className="inner-nav-footer">
            <button
              type="button"
              className="inner-nav-lock"
              onClick={cycleSidebarLock}
              onMouseEnter={sidebarLock === 'closed'
                ? (e) => showNavTooltip(SIDEBAR_LOCK_LABELS[sidebarLock], e)
                : undefined}
              onMouseLeave={sidebarLock === 'closed' ? hideNavTooltip : undefined}
              title={sidebarLock === 'closed' ? undefined : SIDEBAR_LOCK_LABELS[sidebarLock]}
              aria-label={SIDEBAR_LOCK_LABELS[sidebarLock]}
              aria-pressed={sidebarLock !== 'auto'}
            >
              {sidebarLock === 'auto'
                ? <Unlock size={18} className="inner-nav-icon" />
                : <Lock size={18} className="inner-nav-icon" />}
            </button>
          </div>
        </nav>
        {navTooltip && (
          <div
            className="inner-nav-tooltip"
            style={{ top: navTooltip.top, left: navTooltip.left }}
            role="tooltip"
          >
            {navTooltip.label}
          </div>
        )}

        {/* Content — only the active section is rendered */}
        <main className="assessment-content">
          {isFinalized && (
            <div className="finalized-banner">
              <Lock size={15} />
              <span className="finalized-banner-text">
                This assessment is <strong>{assessment.status.replace(/_/g, ' ')}</strong> — fields are read-only.
                {reopenDaysLeft > 0
                  ? ` It can be reopened for ${reopenDaysLeft} more ${reopenDaysLeft === 1 ? 'day' : 'days'}.`
                  : ` It was completed more than ${REOPEN_WINDOW_DAYS} days ago and can no longer be reopened.`}
              </span>
              {reopenDaysLeft > 0 && permissions.canEditAssessments && (
                <Button size="sm" variant="secondary" onClick={() => setShowReopenConfirm(true)} disabled={reopening}>
                  <RotateCcw size={14} />
                  {reopening ? 'Reopening…' : 'Reopen'}
                </Button>
              )}
            </div>
          )}
          {isPeerReviewLocked && !isFinalized && (
            <div className="finalized-banner">
              <Lock size={15} />
              {assessment.peerReviewStatus === 'NEEDS_ACCEPTANCE'
                ? 'Peer review is complete — review the changes to unlock this assessment.'
                : 'This assessment is locked while peer review is in progress.'}
              {assessment.peerReviewStatus === 'NEEDS_ACCEPTANCE' && activePeerReview && (
                <Button
                  size="sm"
                  variant="secondary"
                  onClick={() => handleSectionChange('peer-review')}
                >
                  <ClipboardCheck size={14} />
                  Review Changes
                </Button>
              )}
            </div>
          )}
          {sseUnavailable && (
            <div className="sse-unavailable-notice">
              Live collaboration unavailable — changes you make are still saved.
            </div>
          )}

          {/* ── Section 1: Assessment Info ── */}
          {activeSection === 'assessment-info' && <section id="assessment-info" className="content-section">
            <div className="section-header">
              <h3>Assessment Info</h3>
              <div className="section-header-actions">
                {canEditThisAssessment && !isFinalized && (
                  <Button size="sm" variant="secondary" onClick={() => setShowEditInfo(true)}>
                    <Pencil size={14} />
                    Edit
                  </Button>
                )}
              </div>
            </div>

            <table className="info-table">
              <tbody>
                <tr>
                  <td className="info-label">Assessment Name</td>
                  <td>
                    <span className="inline-flex-row">
                      {assessment.name}
                      <Badge
                        variant={workflowConfig?.statusColors?.[assessment.status] ? undefined : getStatusColor(assessment.status, workflowConfig?.completedStatus)}
                        customColor={workflowConfig?.statusColors?.[assessment.status]}
                      >
                        {assessment.status.replace(/_/g, ' ')}
                      </Badge>
                      {assessment.isPastDue && (
                        <Badge variant="danger">Past Due</Badge>
                      )}
                    </span>
                  </td>
                </tr>
                <tr>
                  <td className="info-label">Application</td>
                  <td>{assessment.applicationName || '-'}</td>
                </tr>
                <tr>
                  <td className="info-label">Organization</td>
                  <td>{organization?.name || '-'}</td>
                </tr>
                <tr>
                  <td className="info-label">Assessment Type</td>
                  <td>{assessment.assessmentTypeName || '-'}</td>
                </tr>
                <tr>
                  <td className="info-label">Campaign</td>
                  <td>{assessment.campaignName || '-'}</td>
                </tr>
                <tr>
                  <td className="info-label">Team</td>
                  <td>{assessment.teamName || '-'}</td>
                </tr>
                <tr>
                  <td className="info-label">Start Date</td>
                  <td>
                    {assessment.startDate
                      ? new Date(assessment.startDate).toLocaleDateString()
                      : '-'}
                  </td>
                </tr>
                <tr>
                  <td className="info-label">Planned End Date</td>
                  <td>
                    {assessment.plannedEndDate
                      ? new Date(assessment.plannedEndDate).toLocaleDateString()
                      : '-'}
                  </td>
                </tr>
                <tr>
                  <td className="info-label">Assessors</td>
                  <td>
                    {assessment.assessorNames && assessment.assessorNames.length > 0 ? (
                      <div className="assessors-cell">
                        <div className="assessor-list">
                          {assessment.assessorNames.map((name, i) => {
                            const email = assessment.assessorEmails?.[i];
                            return (
                              <span key={i} className="assessor-chip">
                                {name}
                                {email && (
                                  <button
                                    className="assessor-copy-btn"
                                    onClick={() => copyToClipboard(email, `assessor-${i}`)}
                                    title={`Copy ${email}`}
                                  >
                                    {copiedVar === `assessor-${i}` ? <Check size={12} /> : <Copy size={12} />}
                                  </button>
                                )}
                              </span>
                            );
                          })}
                        </div>
                        {(assessment.assessorEmails?.length ?? 0) > 0 && (
                          <button
                            className="copy-btn"
                            onClick={() => copyToClipboard(assessment.assessorEmails!.join('; '), 'assessors-all')}
                            title="Copy all emails"
                          >
                            {copiedVar === 'assessors-all' ? <Check size={11} /> : <Copy size={11} />}
                            Copy all emails
                          </button>
                        )}
                      </div>
                    ) : '-'}
                  </td>
                </tr>
                <tr>
                  <td className="info-label">Engagement Manager</td>
                  <td>
                    {assessment.engagementManagerName || assessment.engagementManagerId ? (
                      <span className="assessor-chip">
                        {assessment.engagementManagerName || assessment.engagementManagerId}
                        {assessment.engagementManagerEmail && (
                          <button
                            className="assessor-copy-btn"
                            onClick={() => copyToClipboard(assessment.engagementManagerEmail!, 'em-email')}
                            title={`Copy ${assessment.engagementManagerEmail}`}
                          >
                            {copiedVar === 'em-email' ? <Check size={12} /> : <Copy size={12} />}
                          </button>
                        )}
                      </span>
                    ) : '-'}
                  </td>
                </tr>
                <tr>
                  <td className="info-label">Description</td>
                  <td>
                    {application?.description ? (
                      <div
                        className="description-html"
                        dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(application.description) }}
                      />
                    ) : (
                      '-'
                    )}
                  </td>
                </tr>
                <tr>
                  <td className="info-label">Tech Stack</td>
                  <td>
                    {application?.technologies && application.technologies.length > 0 ? (
                      <div className="tech-pills">
                        {application.technologies.map((t, i) => (
                          <span key={i} className="tech-pill">
                            {t}
                          </span>
                        ))}
                      </div>
                    ) : (
                      '-'
                    )}
                  </td>
                </tr>
                <tr>
                  <td className="info-label">URLs in Scope</td>
                  <td>
                    <div className="url-list">
                      {assessment.engagementUrls?.map((eu, i) => (
                        <div key={`eu-${i}`}>
                          <a href={eu.url} target="_blank" rel="noopener noreferrer">
                            {eu.url}
                          </a>
                          {eu.description && (
                            <span className="url-desc"> — {eu.description}</span>
                          )}
                        </div>
                      ))}
                      {application?.urls?.map((u, i) => (
                        <div key={`au-${i}`}>
                          <a href={u.url} target="_blank" rel="noopener noreferrer">
                            {u.url}
                          </a>
                          {u.title && <span className="url-desc"> — {u.title}</span>}
                        </div>
                      ))}
                      {!assessment.engagementUrls?.length && !application?.urls?.length && '-'}
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>

            {assessment.scope && (
              <div className="scope-block">
                <h4>Scope</h4>
                <div className="scope-viewer">
                  <RichTextEditor value={assessment.scope} disabled />
                </div>
              </div>
            )}

            {assessment.stakeholders && assessment.stakeholders.length > 0 && (
              <div className="stakeholders-section">
                <h4>Stakeholders</h4>
                <table className="info-table">
                  <thead>
                    <tr>
                      <th>Name</th>
                      <th>Email</th>
                      <th>Role</th>
                    </tr>
                  </thead>
                  <tbody>
                    {assessment.stakeholders.map((s, i) => (
                      <tr key={i}>
                        <td>{s.name}</td>
                        <td>{s.email}</td>
                        <td>{s.role}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {/* ── Attachments ── */}
            <div className="attachments-section">
              <div className="attachments-header">
                <h4>
                  <Paperclip size={15} />
                  Attachments
                  {attachments.length > 0 && (
                    <span className="attachments-count">{attachments.length}</span>
                  )}
                </h4>
                {!isFinalized && (
                  <>
                    <input
                      ref={fileInputRef}
                      type="file"
                      style={{ display: 'none' }}
                      onChange={handleFileUpload}
                    />
                    <Button
                      size="sm"
                      variant="secondary"
                      onClick={() => fileInputRef.current?.click()}
                      disabled={uploading}
                    >
                      <UploadCloud size={14} />
                      {uploading ? 'Uploading...' : 'Upload File'}
                    </Button>
                  </>
                )}
              </div>

              {uploadError && (
                <p className="attachment-error">{uploadError}</p>
              )}

              {attachments.length === 0 ? (
                <p className="attachments-empty">No files attached.</p>
              ) : (
                <ul className="attachment-list">
                  {attachments.map((file) => (
                    <li key={file.id} className="attachment-item">
                      <FileText size={15} className="attachment-icon" />
                      <div className="attachment-info">
                        <span className="attachment-name">{file.fileName}</span>
                        <span className="attachment-meta">
                          {formatBytes(file.fileSize)} · {file.uploadedByName} ·{' '}
                          {new Date(file.uploadedAt).toLocaleDateString()}
                        </span>
                      </div>
                      <div className="attachment-actions">
                        <button
                          className="attachment-action-btn"
                          onClick={() => handleDownload(file.id, file.fileName)}
                          title="Download"
                        >
                          <Download size={14} />
                        </button>
                        {!isFinalized && (
                          <button
                            className="attachment-action-btn attachment-action-btn--danger"
                            onClick={() => handleDeleteFile(file.id)}
                            title="Delete"
                          >
                            <Trash2 size={14} />
                          </button>
                        )}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </section>}

          {/* ── Section 3: Variables ── */}
          {activeSection === 'variables' && stringDropdownFields.length > 0 && (
            <section id="variables" className="content-section">
              <div className="section-header">
                <div className="section-header-left">
                  <h3>Variables</h3>
                  <span className={`save-status save-status--${saveStatus}`}>
                    {saveStatus === 'saving' && 'Saving...'}
                    {saveStatus === 'saved' && 'Saved ✓'}
                    {saveStatus === 'error' && 'Error saving'}
                  </span>
                </div>
              </div>

              <div className="variables-list">
                {stringDropdownFields.map((field) => {
                  const { locked, isMine, holder } = getLockState(field.id);
                  const isDisabled = isFinalized || isPeerReviewLocked || (locked && !isMine);
                  return (
                    <div key={field.variableName} className="variable-item">
                      <div className="variable-header">
                        <label className="variable-label">
                          {field.displayName}
                          {field.required && <span className="required-mark"> *</span>}
                        </label>
                        <div className="variable-header-right">
                          {locked && (
                            <span className={`field-lock-badge field-lock-badge--${isMine ? 'mine' : 'other'}`}>
                              <Lock size={11} />
                              {isMine ? 'You are editing' : `${holder} is editing`}
                            </span>
                          )}
                          <button
                            className="copy-btn"
                            onClick={() =>
                              copyToClipboard(`\${${field.variableName}}`, field.variableName)
                            }
                            title="Copy variable name"
                          >
                            <code className="var-chip">{`\${${field.variableName}}`}</code>
                            {copiedVar === field.variableName ? (
                              <Check size={12} />
                            ) : (
                              <Copy size={12} />
                            )}
                          </button>
                        </div>
                      </div>

                      {field.fieldType === 'DROPDOWN' ? (
                        <select
                          className={`field-input${isDisabled ? ' field-input--locked' : ''}`}
                          value={fieldValues[field.id] || ''}
                          onChange={(e) => handleFieldChange(field.id, e.target.value)}
                          disabled={isDisabled}
                        >
                          <option value="">Select...</option>
                          {field.dropdownOptions?.map((opt) => (
                            <option key={opt} value={opt}>
                              {opt}
                            </option>
                          ))}
                        </select>
                      ) : (
                        <input
                          type="text"
                          className={`field-input${isDisabled ? ' field-input--locked' : ''}`}
                          value={fieldValues[field.id] || ''}
                          onChange={(e) => handleFieldChange(field.id, e.target.value)}
                          placeholder={field.helpText || `Enter ${field.displayName}`}
                          disabled={isDisabled}
                        />
                      )}

                      {field.helpText && <p className="field-help">{field.helpText}</p>}
                    </div>
                  );
                })}
              </div>
            </section>
          )}

          {/* ── Sections 4+: Rich Text Fields ── */}
          {richTextFields.map((field) => {
            const sectionId = `field-${field.variableName}`;
            if (activeSection !== sectionId) return null;

            const editorRef = getEditorRef(field.id);
            const rStatus = richSaveStatuses[field.id] || 'idle';
            const copyKey = `rich-${field.variableName}`;
            const { locked, isMine, holder } = getLockState(field.id);
            // Finalised / in-peer-review really are read-only, so they keep `disabled`.
            // Another user's lock is transient and the reader should still see their
            // edits arrive, so it goes to the editor as `lockedBy` instead — `disabled`
            // would strip the toolbar and flatten the field to a read-only view.
            const lockedByOther = locked && !isMine;
            const isDisabled = isFinalized || isPeerReviewLocked;

            return (
              <section
                key={field.variableName}
                id={sectionId}
                className="content-section"
              >
                <div className="section-header">
                  <div className="section-header-left">
                    <h3>{field.displayName}</h3>
                    {locked && (
                      <span className={`field-lock-badge field-lock-badge--${isMine ? 'mine' : 'other'}`}>
                        <Lock size={11} />
                        {isMine ? 'You are editing' : `${holder} is editing`}
                      </span>
                    )}
                    {rStatus !== 'idle' && (
                      <span className={`field-lock-badge save-status-badge--${rStatus}`}>
                        {rStatus === 'saving' && 'Saving...'}
                        {rStatus === 'saved' && 'Saved ✓'}
                        {rStatus === 'error' && 'Error saving'}
                      </span>
                    )}
                  </div>
                  <div className="section-header-actions">
                    <button
                      className="copy-btn"
                      onClick={() =>
                        copyToClipboard(`\${${field.variableName}}`, copyKey)
                      }
                      title="Copy variable name"
                    >
                      <code className="var-chip">{`\${${field.variableName}}`}</code>
                      {copiedVar === copyKey ? <Check size={12} /> : <Copy size={12} />}
                    </button>
                  </div>
                </div>

                <div className={isDisabled ? 'editor-locked-overlay' : ''}>
                  <RichTextEditor
                    ref={editorRef}
                    value={fieldValues[field.id] || ''}
                    onChange={(val) => handleRichFieldChange(field.id, val)}
                    onImageUpload={!isFinalized ? handleInlineImageUpload : undefined}
                    disabled={isDisabled}
                    lockedBy={lockedByOther ? holder : undefined}
                    aiContext={{ assessmentId: id!, scope: 'ASSESSMENT' }}
                    templateScope="ASSESSMENT"
                  />
                </div>
              </section>
            );
          })}

          {/* ── Section: Vulnerabilities (no sections) ── */}
          {activeSection === 'vulnerabilities' && !loading && (
            <AssessmentVulnerabilitySection
              assessmentId={id!}
              assessment={assessment}
              fieldLocks={fieldLocks}
              currentUsername={currentUsername}
              isFinalized={isFinalized || isPeerReviewLocked}
              pendingDefaultVuln={pendingDefaultVuln}
              pendingVulnName={pendingVulnName}
              onPendingConsumed={() => { setPendingDefaultVuln(undefined); setPendingVulnName(undefined); }}
              onAddVulnerability={() => setShowDefaultVulnSearch(true)}
              onVulnerabilitiesChanged={refreshVulnerabilitySummary}
              initialVulnId={initialVulnIdRef.current ?? undefined}
              onVulnSelected={handleVulnSelected}
              refreshToken={vulnRefreshToken}
            />
          )}

          {/* ── Section: Per-section Vulnerabilities ── */}
          {!loading && assessment.sections && assessment.sections.map((sectionName) => {
            const sectionId = `vuln-section-${sectionName}`;
            if (activeSection !== sectionId) return null;
            return (
              <AssessmentVulnerabilitySection
                key={sectionId}
                assessmentId={id!}
                assessment={assessment}
                fieldLocks={fieldLocks}
                currentUsername={currentUsername}
                isFinalized={isFinalized || isPeerReviewLocked}
                pendingDefaultVuln={pendingDefaultVuln}
                pendingVulnName={pendingVulnName}
                onPendingConsumed={() => { setPendingDefaultVuln(undefined); setPendingVulnName(undefined); }}
                onAddVulnerability={() => setShowDefaultVulnSearch(true)}
                onVulnerabilitiesChanged={refreshVulnerabilitySummary}
                section={sectionName}
                initialVulnId={initialVulnIdRef.current ?? undefined}
                onVulnSelected={handleVulnSelected}
                refreshToken={vulnRefreshToken}
              />
            );
          })}

          {/* ── Section: Checklists ── */}
          {activeSection === 'checklists' && (
            <AssessmentChecklistSection
              assessment={assessment}
              isFinalized={isFinalized}
            />
          )}

          {/* ── Section: Notebook ── */}
          {activeSection === 'notebook' && (
            <AssessmentNotebookSection
              applicationId={assessment.applicationId}
              assessmentId={id!}
            />
          )}

          {/* ── Section: History ── */}
          {activeSection === 'history' && (
            <AssessmentHistorySection assessment={assessment} />
          )}

          {/* ── Section: Finalize ── */}
          {activeSection === 'finalize' && (
            <AssessmentFinalizeSection
              assessmentId={id!}
              assessment={assessment}
              isFinalized={isFinalized}
              completedStatus={workflowConfig?.completedStatus}
              inProgressStatus={workflowConfig?.inProgressStatus}
              onAssessmentUpdated={(updated) => setAssessment(updated)}
            />
          )}

          {/* ── Section: Peer Review Diff ── */}
          {activeSection === 'peer-review' && activePeerReview && (
            <section id="peer-review" className="content-section">
              <div className="section-header">
                <h3>Peer Review</h3>
              </div>
              <PeerReviewDiff
                review={activePeerReview}
                assessment={assessment}
                onAccepted={(updated) => {
                  setAssessment(updated);
                  // Field state is held separately from the assessment object —
                  // without this, accepted peer-review changes don't show
                  // until a full page refresh.
                  setFieldDefinitions(updated.fieldDefinitions || []);
                  setFieldValues(updated.fieldValues || {});
                  setActivePeerReview(null);
                  handleSectionChange('assessment-info');
                }}
              />
            </section>
          )}
        </main>
      </div>
    </Page>

    {assessment && (
      <AssessmentInfoEditDialog
        isOpen={showEditInfo}
        onClose={() => setShowEditInfo(false)}
        assessment={assessment}
        application={application}
        canEditApplication={permissions.canEditApplications}
        onSaved={() => loadData(assessment.id)}
      />
    )}

    <ConfirmDialog
      isOpen={showReopenConfirm}
      onClose={() => setShowReopenConfirm(false)}
      onConfirm={handleReopen}
      title="Reopen Assessment"
      message={'Reopen this assessment for editing? Its completion date will be cleared, so '
        + `finalizing it again starts a new ${REOPEN_WINDOW_DAYS}-day window.`}
      confirmText="Reopen"
      variant="warning"
      isLoading={reopening}
    />

    {showToast && (
      <Toast key={toastKey} message={toastMessage} variant={toastVariant} onDone={() => setShowToast(false)} />
    )}

    <ReportPreviewDrawer
      assessment={previewOpen ? assessment : null}
      onClose={() => setPreviewOpen(false)}
      onLoadingChange={setPreviewLoading}
    />

    <DefaultVulnerabilitySearchDialog
      isOpen={showDefaultVulnSearch}
      onClose={() => setShowDefaultVulnSearch(false)}
      onSelect={(dv, blankName) => {
        setPendingDefaultVuln(dv ?? null);
        setPendingVulnName(blankName);
        // Stay on the current section if already on a vuln section; otherwise navigate to the first one
        if (!activeSection.startsWith('vuln-section-') && activeSection !== 'vulnerabilities') {
          handleSectionChange(
            assessment && assessment.sections && assessment.sections.length > 0
              ? `vuln-section-${assessment.sections[0]}`
              : 'vulnerabilities'
          );
        }
        setShowDefaultVulnSearch(false);
      }}
    />
    </>
  );
}
