import { useEffect, useState, useRef } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { X, Calendar, Plus, Trash2, Paperclip, UploadCloud, FileText, Download, Copy, Eye, Eraser } from 'lucide-react';
import {
  assessmentsApi,
  applicationsApi,
  assessmentTypesApi,
  reportTemplatesApi,
  usersApi,
  teamsApi,
  campaignsApi,
  inlineImagesApi,
  workflowConfigApi,
  surveyTemplatesApi,
  assessmentSurveysApi,
  uploadFileContent,
} from '../api';
import type {
  Assessment,
  AssessmentType,
  AssessmentFile,
  AssessmentWorkflowConfig,
  Campaign,
  Team,
  User,
  SurveyTemplate,
  AssessmentSurvey,
} from '../types';
import { Button, FormLabel, Input, Select, Badge, RichTextEditor, DualListBox, ConfirmDialog } from '../components';
import SearchableApplicationSelect from '../components/SearchableApplicationSelect';
import type { RichTextEditorRef } from '../components';
import AssessmentCalendar from '../components/AssessmentCalendar';
import SurveyDrawer from '../components/SurveyDrawer';
import Page from '../components/Page';
import { usePageTitle } from '../context/PageTitleContext';
import './CreateAssessment.css';

// Planned end date is picked as a duration from the start date; "custom" falls back to a
// plain date input. Values are counts of WORKING days, with the start day counting as the
// first — so a Monday start over "1 Week" (5 working days) ends that Friday, and "2 Weeks"
// (10 working days) ends the Friday after, a 12-day span on the calendar.
const DURATION_OPTIONS = [
  { value: '2', label: '2 Days' },
  { value: '3', label: '3 Days' },
  { value: '5', label: '1 Week' },
  { value: '10', label: '2 Weeks' },
] as const;

const CUSTOM_DURATION = 'custom';

/** A new assessment is scheduled for a working week unless it is changed. */
const DEFAULT_DURATION = '5';

const MS_PER_DAY = 86_400_000;

/** Parse a yyyy-mm-dd string as a local date, so no calculation shifts across a timezone. */
function parseDate(isoDate: string): Date {
  const [y, m, d] = isoDate.split('-').map(Number);
  return new Date(y, m - 1, d);
}

function formatDateInput(dt: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())}`;
}

const isWeekend = (dt: Date) => dt.getDay() === 0 || dt.getDay() === 6;

/**
 * A yyyy-mm-dd input value as the zone-less ISO datetime the API uses for these fields
 * (they are `LocalDateTime` server-side, and Jackson emits exactly this shape).
 *
 * Deliberately not `new Date(value).toISOString()`: that parses the bare date as UTC midnight
 * and stamps a `Z`, so anything rendering it in a negative-offset zone — the calendar — lands
 * on the previous day. A start date has no time of day and no timezone; keep it that way.
 */
function toApiDate(isoDate: string): string {
  return `${isoDate}T00:00:00`;
}

/**
 * The date `businessDays` working days from the start, counting the start itself as the first,
 * so the result always lands on a weekday. A start that falls on a weekend counts from the
 * following Monday rather than scheduling work nobody will do.
 */
function addBusinessDays(isoDate: string, businessDays: number): string {
  const dt = parseDate(isoDate);
  while (isWeekend(dt)) dt.setDate(dt.getDate() + 1);
  let remaining = Math.max(businessDays, 1) - 1;
  while (remaining > 0) {
    dt.setDate(dt.getDate() + 1);
    if (!isWeekend(dt)) remaining -= 1;
  }
  return formatDateInput(dt);
}

/** Working days from start to end inclusive; 0 when the range is empty or inverted. */
function businessDaysBetween(start: string, end: string): number {
  const from = parseDate(start);
  const to = parseDate(end);
  if (to < from) return 0;
  // Guard a nonsense range (a mistyped year) from spinning the loop.
  if ((to.getTime() - from.getTime()) / MS_PER_DAY > 3650) return 0;
  let count = 0;
  for (const dt = from; dt <= to; dt.setDate(dt.getDate() + 1)) {
    if (!isWeekend(dt)) count += 1;
  }
  return count;
}

/** Which dropdown entry a start/end pair corresponds to — a preset if it lands on one exactly. */
function inferDuration(start: string, end: string): string {
  if (!start || !end) return CUSTOM_DURATION;
  const days = String(businessDaysBetween(start, end));
  // Round-trip check: a preset only applies if re-deriving the end date reproduces it, so a
  // span with the right working-day count but a weekend end date still reads as Custom.
  return DURATION_OPTIONS.some(o => o.value === days) && addBusinessDays(start, Number(days)) === end
    ? days
    : CUSTOM_DURATION;
}

function getEmptyFormData() {
  return {
    name: '',
    applicationId: '',
    assessmentTypeId: '',
    campaignId: '',
    reportTemplateId: '',
    teamId: '',
    status: 'DRAFT',
    startDate: '',
    plannedEndDate: '',
    engagementManagerId: '',
    remediationManagerId: '',
    assessorIds: [] as string[],
    scope: '',
  };
}

export default function CreateAssessment() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const mode = id ? 'edit' : 'create';
  const { setBreadcrumbs } = usePageTitle();

  useEffect(() => {
    setBreadcrumbs([
      { label: 'Scheduling', to: '/scheduling' },
      { label: mode === 'edit' ? 'Edit Assessment' : 'Create Assessment' },
    ]);
    return () => setBreadcrumbs(null);
  }, [mode]);
  const editorRef = useRef<RichTextEditorRef>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [loading, setLoading] = useState(false);
  const [initialLoading, setInitialLoading] = useState(!!id);
  const [workflowConfig, setWorkflowConfig] = useState<AssessmentWorkflowConfig | null>(null);
  const [error, setError] = useState('');
  const [isDirty, setIsDirty] = useState(false);
  const [showCancelConfirm, setShowCancelConfirm] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [showClearConfirm, setShowClearConfirm] = useState(false);
  const [initialFormData, setInitialFormData] = useState<string>('');
  const [initialUrls, setInitialUrls] = useState<string>('');
  const [initialStakeholders, setInitialStakeholders] = useState<string>('');
  const [users, setUsers] = useState<User[]>([]);
  const [teams, setTeams] = useState<Team[]>([]);
  const [applicationAppId, setApplicationAppId] = useState('');
  const [applicationName, setApplicationName] = useState('');
  const [assessmentTypes, setAssessmentTypes] = useState<AssessmentType[]>([]);
  const [campaigns, setCampaigns] = useState<Campaign[]>([]);
  const [reportTemplates, setReportTemplates] = useState<any[]>([]);
  const [conflicts, setConflicts] = useState<Assessment[]>([]);
  const [teamAssessments, setTeamAssessments] = useState<Assessment[]>([]);
  const [attachments, setAttachments] = useState<AssessmentFile[]>([]);
  const [pendingFiles, setPendingFiles] = useState<File[]>([]);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState('');

  // Name autocomplete (create mode only)
  const [allPreviousAssessments, setAllPreviousAssessments] = useState<Assessment[]>([]);
  const [nameSuggestions, setNameSuggestions] = useState<Assessment[]>([]);
  const [showSuggestions, setShowSuggestions] = useState(false);
  const [sourceAssessmentId, setSourceAssessmentId] = useState<string | null>(null);
  const [sourceFiles, setSourceFiles] = useState<AssessmentFile[]>([]);
  const [editorKey, setEditorKey] = useState(0);

  // Surveys
  const [availableSurveyTemplates, setAvailableSurveyTemplates] = useState<SurveyTemplate[]>([]);
  const [assessmentSurveys, setAssessmentSurveys] = useState<AssessmentSurvey[]>([]);
  const [surveyToAdd, setSurveyToAdd] = useState('');
  const [addingSurvey, setAddingSurvey] = useState(false);
  const [pendingSurveyTemplateIds, setPendingSurveyTemplateIds] = useState<string[]>([]);
  const [removingSurveyId, setRemovingSurveyId] = useState<string | null>(null);
  const [viewSurveyId, setViewSurveyId] = useState<string | null>(null);
  const nameWrapRef = useRef<HTMLDivElement>(null);

  const [formData, setFormData] = useState<{
    name: string;
    applicationId: string;
    assessmentTypeId: string;
    campaignId: string;
    reportTemplateId: string;
    teamId: string;
    status: string;
    startDate: string;
    plannedEndDate: string;
    engagementManagerId: string;
    remediationManagerId: string;
    assessorIds: string[];
    scope: string;
  }>(getEmptyFormData());

  // Which planned-end preset is selected. A new assessment starts on the default duration;
  // edit mode overwrites this from the saved dates once the assessment loads.
  const [duration, setDuration] = useState<string>(DEFAULT_DURATION);

  const [engagementUrls, setEngagementUrls] = useState<Array<{ url: string; description: string }>>([]);
  const [newUrl, setNewUrl] = useState({ url: '', description: '' });

  const [stakeholders, setStakeholders] = useState<Array<{ name: string; email: string; role?: string }>>([]);
  const [newStakeholder, setNewStakeholder] = useState({ name: '', email: '', role: '' });

  // Calendar preview assessment
  const calendarPreview: Assessment | null = formData.name && formData.startDate && formData.plannedEndDate
    ? {
        id: id || 'preview',
        name: formData.name,
        applicationId: formData.applicationId,
        assessmentTypeId: formData.assessmentTypeId,
        organizationId: '',
        reportTemplateId: formData.reportTemplateId,
        reportTemplateVersion: 1,
        templateName: '',
        fieldDefinitions: [],
        fieldValues: {},
        status: formData.status,
        startDate: toApiDate(formData.startDate),
        plannedEndDate: toApiDate(formData.plannedEndDate),
        assessorIds: formData.assessorIds,
        engagementManagerId: formData.engagementManagerId,
        remediationManagerId: formData.remediationManagerId,
        scope: formData.scope,
        engagementUrls,
        stakeholders,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      } as Assessment
    : null;

  // Filter users by selected team
  const filteredUsers = formData.teamId
    ? users.filter((user) => user.teamIds?.includes(formData.teamId))
    : users;

  // Assessors: internal users only. When a team is selected, also include users
  // who aren't assigned to any team (they're available to any team).
  const availableAssessorUsers = users.filter((user) => {
    if (!user.isInternal) return false;
    if (!formData.teamId) return true;
    const hasTeam = (user.teamIds?.length ?? 0) > 0;
    return !hasTeam || user.teamIds!.includes(formData.teamId);
  });

  useEffect(() => {
    loadReferenceData();
    loadTeamAssessments();
    surveyTemplatesApi.getAll(true)
      .then(r => { if (r.success && r.data) setAvailableSurveyTemplates(r.data); })
      .catch(() => {});
    if (id) {
      loadAssessment(id);
      assessmentSurveysApi.getByAssessment(id)
        .then(r => { if (r.success && r.data) setAssessmentSurveys(r.data); })
        .catch(() => {});
    }
    workflowConfigApi.getConfig()
      .then(r => { if (r.success && r.data) setWorkflowConfig(r.data); })
      .catch(() => {});
    if (!id) {
      assessmentsApi.getAll(0, 1000)
        .then(r => { if (r.success && r.data) setAllPreviousAssessments(r.data); })
        .catch(() => {});
    }
  }, [id]);

  // Dismiss name suggestions on outside click
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (nameWrapRef.current && !nameWrapRef.current.contains(e.target as Node)) {
        setShowSuggestions(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  useEffect(() => {
    if (formData.assessmentTypeId) {
      loadReportTemplates(formData.assessmentTypeId);
    }
  }, [formData.assessmentTypeId]);

  useEffect(() => {
    if (formData.applicationId && !id) {
      loadApplicationStakeholders(formData.applicationId);
    }
  }, [formData.applicationId]);

  const fillApplicationNameFromAssessmentName = () => {
    if (!formData.applicationId && !applicationName && formData.name) {
      setApplicationName(formData.name);
    }
  };

  const fillAssessmentNameFromApplicationName = () => {
    if (!formData.name && applicationName) {
      setFormData((prev) => (prev.name ? prev : { ...prev, name: applicationName }));
    }
  };

  useEffect(() => {
    if (formData.assessorIds.length > 0 && formData.startDate && formData.plannedEndDate) {
      checkConflicts();
    } else {
      setConflicts([]);
    }
  }, [formData.assessorIds, formData.startDate, formData.plannedEndDate]);

  // Track form changes for unsaved warning
  useEffect(() => {
    if (!initialLoading) {
      const currentFormData = JSON.stringify(formData);
      const currentUrls = JSON.stringify(engagementUrls);
      const currentStakeholders = JSON.stringify(stakeholders);

      // For create mode, check if any fields have values
      if (mode === 'create') {
        const hasChanges = !!(
          formData.name ||
          formData.applicationId ||
          formData.assessmentTypeId ||
          formData.teamId ||
          formData.startDate ||
          formData.plannedEndDate ||
          formData.engagementManagerId ||
          formData.remediationManagerId ||
          formData.assessorIds.length > 0 ||
          formData.scope ||
          engagementUrls.length > 0 ||
          stakeholders.length > 0
        );
        setIsDirty(hasChanges);
      } else {
        // For edit mode, compare to initial state
        const hasChanges =
          currentFormData !== initialFormData ||
          currentUrls !== initialUrls ||
          currentStakeholders !== initialStakeholders;
        setIsDirty(hasChanges);
      }
    }
  }, [formData, engagementUrls, stakeholders, initialLoading, mode, initialFormData, initialUrls, initialStakeholders]);

 const loadReferenceData = async () => {
    try {
      const [usersRes, typesRes, teamsRes, campaignsRes] = await Promise.all([
        usersApi.getAll(0, 1000),
        assessmentTypesApi.getAll(0, 1000),
        teamsApi.getAll(0, 1000),
        campaignsApi.getAllUnpaged().catch(() => null),
      ]);

      if (usersRes.success && usersRes.data) setUsers(usersRes.data);
      if (typesRes.success && typesRes.data) setAssessmentTypes(typesRes.data);
      if (teamsRes.success && teamsRes.data) setTeams(teamsRes.data);
      if (campaignsRes?.success && campaignsRes.data) {
        setCampaigns(campaignsRes.data);
        // New assessments start on the default campaign (still changeable)
        const defaultCampaign = campaignsRes.data.find((c) => c.isDefault);
        if (mode === 'create' && defaultCampaign) {
          setFormData((prev) => (prev.campaignId ? prev : { ...prev, campaignId: defaultCampaign.id }));
        }
      }
    } catch (err) {
      console.error('Failed to load reference data:', err);
    }
  };

  const loadAssessment = async (assessmentId: string) => {
    setInitialLoading(true);
    try {
      const response = await assessmentsApi.getById(assessmentId);
      if (response.success && response.data) {
        const assessment = response.data;
        const loadedFormData = {
          name: assessment.name,
          applicationId: assessment.applicationId,
          assessmentTypeId: assessment.assessmentTypeId,
          campaignId: assessment.campaignId || '',
          reportTemplateId: assessment.reportTemplateId || '',
          teamId: assessment.teamId || '',
          status: assessment.status,
          startDate: assessment.startDate ? assessment.startDate.split('T')[0] : '',
          plannedEndDate: assessment.plannedEndDate ? assessment.plannedEndDate.split('T')[0] : '',
          engagementManagerId: assessment.engagementManagerId || '',
          remediationManagerId: assessment.remediationManagerId || '',
          assessorIds: assessment.assessorIds || [],
          scope: assessment.scope || '',
        };
        // Show the preset the saved dates land on, so editing an assessment scheduled as
        // "1 Week" reopens as 1 Week rather than Custom.
        setDuration(inferDuration(loadedFormData.startDate, loadedFormData.plannedEndDate));
        const loadedUrls = assessment.engagementUrls || [];
        const loadedStakeholders = assessment.stakeholders || [];

 setFormData(loadedFormData);
        setEngagementUrls(loadedUrls);
        setStakeholders(loadedStakeholders);
        setAttachments(assessment.attachments || []);

        if (assessment.applicationId) {
          // Use the appId/name already enriched on the assessment DTO rather than a
          // separate applications:read-gated lookup — assessors editing an assessment
          // may not hold application-read permissions.
          setApplicationAppId(assessment.appId || '');
          setApplicationName(assessment.applicationName || '');
        }

        // Store initial state for comparison
        setInitialFormData(JSON.stringify(loadedFormData));
        setInitialUrls(JSON.stringify(loadedUrls));
        setInitialStakeholders(JSON.stringify(loadedStakeholders));
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load assessment');
    } finally {
      setInitialLoading(false);
    }
  };

  const loadReportTemplates = async (assessmentTypeId: string) => {
    try {
      const response = await reportTemplatesApi.getByAssessmentType(assessmentTypeId);
      if (response.success && response.data) {
        const activeTemplates = response.data.filter((t: any) => t.active);
        setReportTemplates(activeTemplates);

        // Auto-select if only one template and creating new
        if (activeTemplates.length === 1 && !id) {
          setFormData((prev) => ({ ...prev, reportTemplateId: activeTemplates[0].id }));
        }
      }
    } catch (err) {
      console.error('Failed to load report templates:', err);
    }
  };

  const loadApplicationStakeholders = async (applicationId: string) => {
    try {
      const response = await applicationsApi.getById(applicationId);
      if (response.success && response.data?.stakeHolders) {
        setStakeholders(
          response.data.stakeHolders.map((sh: any) => ({
            name: sh.name,
            email: sh.email,
            role: sh.role || '',
          }))
        );
      }
    } catch (err) {
      console.error('Failed to load application stakeholders:', err);
    }
  };

  const loadTeamAssessments = async () => {
    try {
      // Load assessments for the current month and adjacent months
      const now = new Date();
      const startOfRange = new Date(now.getFullYear(), now.getMonth() - 1, 1);
      const endOfRange = new Date(now.getFullYear(), now.getMonth() + 2, 0);

      const response = await assessmentsApi.getCalendarView(
        startOfRange.toISOString(),
        endOfRange.toISOString(),
        0,
        1000
      );

      if (response.success && response.data) {
        // Filter out the current assessment being edited
        const otherAssessments = id
          ? response.data.filter((a) => a.id !== id)
          : response.data;
        setTeamAssessments(otherAssessments);
      }
    } catch (err) {
      console.error('Failed to load team assessments:', err);
    }
  };

  const handleNameChange = (value: string) => {
    setFormData(prev => ({ ...prev, name: value }));
    if (value.trim().length >= 2) {
      const q = value.toLowerCase();
      const seen = new Set<string>();
      const matches = allPreviousAssessments
        .filter(a => a.name.toLowerCase().includes(q))
        .filter(a => {
          const key = a.name.toLowerCase();
          if (seen.has(key)) return false;
          seen.add(key);
          return true;
        })
        .slice(0, 8);
      setNameSuggestions(matches);
      setShowSuggestions(matches.length > 0);
    } else {
      setNameSuggestions([]);
      setShowSuggestions(false);
    }
  };

  const handleSelectSuggestion = async (suggestionName: string) => {
    setFormData(prev => ({ ...prev, name: suggestionName }));
    setShowSuggestions(false);

    // Find the most recent assessment with this name
    const source = allPreviousAssessments
      .filter(a => a.name === suggestionName)
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())[0];
    if (!source) return;

    try {
      const res = await assessmentsApi.getById(source.id);
      if (!res.success || !res.data) return;
      const full = res.data;

      // Reload templates explicitly — the assessmentTypeId effect won't refire
      // when the source assessment has the same type as the current selection
      if (full.assessmentTypeId) {
        loadReportTemplates(full.assessmentTypeId);
      } else {
        setReportTemplates([]);
      }
      setFormData(prev => ({
        ...prev,
        name: suggestionName,
        applicationId: full.applicationId || '',
        assessmentTypeId: full.assessmentTypeId || '',
        campaignId: full.campaignId || '',
        reportTemplateId: full.reportTemplateId || '',
        engagementManagerId: full.engagementManagerId || '',
        remediationManagerId: full.remediationManagerId || '',
        assessorIds: full.assessorIds || [],
        scope: full.scope || '',
      }));
      setApplicationAppId(full.appId || '');
      setApplicationName(full.applicationName || '');
      setEngagementUrls(full.engagementUrls || []);
      setStakeholders(full.stakeholders || []);
      setEditorKey(k => k + 1);

      if (full.attachments?.length) {
        setSourceAssessmentId(source.id);
        setSourceFiles(full.attachments);
      } else {
        setSourceAssessmentId(null);
        setSourceFiles([]);
      }
    } catch { /* ignore */ }
  };

  const checkConflicts = async () => {
    try {
      const response = await assessmentsApi.checkConflicts(
        id || null,
        formData.assessorIds,
        toApiDate(formData.startDate),
        toApiDate(formData.plannedEndDate)
      );
      if (response.success && response.data) {
        setConflicts(response.data);
      }
    } catch (err) {
      console.error('Failed to check conflicts:', err);
    }
  };

  const handleCalendarDrop = (_assessmentId: string, newStart: Date, newEnd: Date, _revert?: () => void) => {
    // formatDateInput reads the local calendar date; toISOString would convert to UTC first
    // and shift the day in any zone east of Greenwich.
    const startDate = formatDateInput(newStart);
    const plannedEndDate = formatDateInput(newEnd);
    // A dragged span may happen to land on a preset — re-read it rather than forcing Custom.
    setDuration(inferDuration(startDate, plannedEndDate));
    setFormData({ ...formData, startDate, plannedEndDate });
  };

  /** Moving the start date keeps a chosen duration, sliding the end date with it. */
  const handleStartDateChange = (startDate: string) => {
    setFormData(prev => ({
      ...prev,
      startDate,
      plannedEndDate: duration !== CUSTOM_DURATION && startDate
        ? addBusinessDays(startDate, Number(duration))
        : prev.plannedEndDate,
    }));
  };

  const handleDurationChange = (next: string) => {
    setDuration(next);
    if (next === CUSTOM_DURATION) return; // keep whatever end date is already there to edit
    setFormData(prev => ({
      ...prev,
      plannedEndDate: prev.startDate ? addBusinessDays(prev.startDate, Number(next)) : prev.plannedEndDate,
    }));
  };

  const handleAddUrl = () => {
    if (newUrl.url && newUrl.description) {
      setEngagementUrls([...engagementUrls, newUrl]);
      setNewUrl({ url: '', description: '' });
    }
  };

  const handleRemoveUrl = (index: number) => {
    setEngagementUrls(engagementUrls.filter((_, i) => i !== index));
  };

  const handleAddStakeholder = () => {
    if (newStakeholder.name && newStakeholder.email) {
      setStakeholders([...stakeholders, newStakeholder]);
      setNewStakeholder({ name: '', email: '', role: '' });
    }
  };

  const handleRemoveStakeholder = (index: number) => {
    setStakeholders(stakeholders.filter((_, i) => i !== index));
  };

  const formatBytes = (bytes: number): string => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

  const uploadSingleFile = async (assessmentId: string, file: File): Promise<AssessmentFile | null> => {
    const prepareRes = await assessmentsApi.prepareUpload(assessmentId, file.name, file.type, file.size);
    if (!prepareRes.success || !prepareRes.data) throw new Error('Failed to get upload URL');
    const { fileId, uploadUrl } = prepareRes.data;
    await uploadFileContent(uploadUrl, file);
    const confirmRes = await assessmentsApi.confirmUpload(assessmentId, fileId, file.name, file.type, file.size);
    if (!confirmRes.success || !confirmRes.data) throw new Error('Failed to confirm upload');
    return confirmRes.data;
  };

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (fileInputRef.current) fileInputRef.current.value = '';
    if (mode === 'create') {
      setPendingFiles(prev => [...prev, file]);
    } else {
      setUploading(true);
      setUploadError('');
      try {
        const uploaded = await uploadSingleFile(id!, file);
        if (uploaded) setAttachments(prev => [...prev, uploaded]);
      } catch (err: unknown) {
        setUploadError(err instanceof Error ? err.message : 'Upload failed');
      } finally {
        setUploading(false);
      }
    }
  };

  const handleRemovePending = (index: number) => {
    setPendingFiles(prev => prev.filter((_, i) => i !== index));
  };

  // Only available in edit mode (assessment ID exists)
  const handleInlineImageUpload = id
    ? async (file: File): Promise<string> => {
        const res = await inlineImagesApi.upload(id, file);
        if (!res.success || !res.data) throw new Error('Image upload failed');
        return res.data.url;
      }
    : undefined;

  const handleDownload = async (fileId: string, fileName: string) => {
    if (!id) return;
    try {
      const a = document.createElement('a');
      a.href = assessmentsApi.getDownloadUrl(id, fileId);
      a.download = fileName;
      a.target = '_blank';
      a.rel = 'noopener noreferrer';
      a.click();
    } catch { /* silently fail */ }
  };

  const handleDeleteFile = async (fileId: string) => {
    if (!id) return;
    try {
      await assessmentsApi.deleteFile(id, fileId);
      setAttachments(prev => prev.filter(f => f.id !== fileId));
    } catch { /* silently fail */ }
  };

  const handleAddSurvey = async () => {
    if (!surveyToAdd) return;
    if (!id) {
      // Create mode — the assessment doesn't exist yet, so queue the survey
      // locally and attach it right after the assessment is created.
      setPendingSurveyTemplateIds(prev => [...prev, surveyToAdd]);
      setSurveyToAdd('');
      return;
    }
    setAddingSurvey(true);
    try {
      const res = await assessmentSurveysApi.add(id, { templateId: surveyToAdd });
      if (res.data) setAssessmentSurveys(prev => [...prev, res.data!]);
      setSurveyToAdd('');
    } catch { /* silently fail */ } finally {
      setAddingSurvey(false);
    }
  };

  const handleRemovePendingSurvey = (templateId: string) => {
    setPendingSurveyTemplateIds(prev => prev.filter(t => t !== templateId));
  };

  const handleRemoveSurvey = async (surveyId: string) => {
    if (!id) return;
    setRemovingSurveyId(surveyId);
    try {
      await assessmentSurveysApi.remove(id, surveyId);
      setAssessmentSurveys(prev => prev.filter(s => s.id !== surveyId));
    } catch { /* silently fail */ } finally {
      setRemovingSurveyId(null);
    }
  };

  const handleSubmit = async (e: React.FormEvent, shouldClose: boolean = true) => {
    e.preventDefault();
    setLoading(true);
    setError('');

    try {
      // Get markdown content from editor
      const scopeContent = editorRef.current?.getHTML() || '';

  const payload: any = {
        name: formData.name,
        ...(formData.applicationId ? { applicationId: formData.applicationId } : {}),
        ...(applicationAppId && !formData.applicationId ? { appId: applicationAppId } : {}),
        ...(!formData.applicationId && applicationName ? { applicationName } : {}),
        assessmentTypeId: formData.assessmentTypeId,
        // On update an empty string clears the assignment; on create omit it entirely
        campaignId: formData.campaignId || (mode === 'edit' ? '' : undefined),
        reportTemplateId: formData.reportTemplateId,
        teamId: formData.teamId || undefined,
        status: formData.status,
        startDate: formData.startDate ? toApiDate(formData.startDate) : undefined,
        plannedEndDate: formData.plannedEndDate ? toApiDate(formData.plannedEndDate) : undefined,
        engagementManagerId: formData.engagementManagerId || undefined,
        remediationManagerId: formData.remediationManagerId || undefined,
        assessorIds: formData.assessorIds,
        scope: scopeContent || undefined,
        engagementUrls,
        stakeholders,
      };

      if (mode === 'create') {
        const res = await assessmentsApi.create(payload);
        if (res.success && res.data) {
          const newId = res.data.id;
          for (const file of pendingFiles) {
            try {
              const uploaded = await uploadSingleFile(newId, file);
              if (uploaded) setAttachments(prev => [...prev, uploaded]);
            } catch { /* continue uploading remaining files */ }
          }
          setPendingFiles([]);

          for (const templateId of pendingSurveyTemplateIds) {
            try {
              const surveyRes = await assessmentSurveysApi.add(newId, { templateId });
              if (surveyRes.data) setAssessmentSurveys(prev => [...prev, surveyRes.data!]);
            } catch { /* continue attaching remaining surveys */ }
          }
          setPendingSurveyTemplateIds([]);

          // Copy files from source assessment
          if (sourceAssessmentId && sourceFiles.length > 0) {
            for (const srcFile of sourceFiles) {
              try {
                // Same-origin, so the media cookie rides along automatically.
                const resp = await fetch(
                    assessmentsApi.getDownloadUrl(sourceAssessmentId, srcFile.id));
                if (!resp.ok) continue;
                const blob = await resp.blob();
                const copied = new File([blob], srcFile.fileName, { type: srcFile.contentType || 'application/octet-stream' });
                const uploaded = await uploadSingleFile(newId, copied);
                if (uploaded) setAttachments(prev => [...prev, uploaded]);
              } catch { /* continue */ }
            }
            setSourceFiles([]);
            setSourceAssessmentId(null);
          }
        }
      } else {
        await assessmentsApi.update(id!, payload);
      }

      setIsDirty(false);

      if (shouldClose) {
        navigate('/scheduling');
      } else {
        // Show success message or toast
        window.scrollTo({ top: 0, behavior: 'smooth' });
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to save assessment');
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } finally {
      setLoading(false);
    }
  };

  const handleSave = (e: React.FormEvent) => {
    handleSubmit(e, false);
  };

  const handleSaveAndClose = (e: React.FormEvent) => {
    handleSubmit(e, true);
  };

  const handleCancel = () => {
    if (isDirty) {
      setShowCancelConfirm(true);
    } else {
      navigate('/scheduling');
    }
  };

  const handleConfirmCancel = () => {
    setShowCancelConfirm(false);
    navigate('/scheduling');
  };

  const handleClear = () => {
    if (isDirty) {
      setShowClearConfirm(true);
    } else {
      handleConfirmClear();
    }
  };

  const handleConfirmClear = () => {
    setShowClearConfirm(false);
    setError('');
    setFormData(getEmptyFormData());
    setDuration(DEFAULT_DURATION);
    setApplicationAppId('');
    setApplicationName('');
    setEngagementUrls([]);
    setNewUrl({ url: '', description: '' });
    setStakeholders([]);
    setNewStakeholder({ name: '', email: '', role: '' });
    setAttachments([]);
    setPendingFiles([]);
    setUploadError('');
    setSourceAssessmentId(null);
    setSourceFiles([]);
    setAssessmentSurveys([]);
    setPendingSurveyTemplateIds([]);
    setNameSuggestions([]);
    setShowSuggestions(false);
    // Force the rich text editor to remount so its internal state clears too
    setEditorKey((k) => k + 1);
  };

  const handleDelete = () => {
    setShowDeleteConfirm(true);
  };

  const handleConfirmDelete = async () => {
    if (!id) return;

    setLoading(true);
    setError('');

    try {
      await assessmentsApi.delete(id);
      setShowDeleteConfirm(false);
      navigate('/engagement');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to delete assessment');
      setShowDeleteConfirm(false);
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } finally {
      setLoading(false);
    }
  };

  if (initialLoading) {
    return (
      <div className="d-flex justify-content-center align-items-center" style={{ minHeight: '400px' }}>
        <div className="spinner-border" role="status">
          <span className="visually-hidden">Loading...</span>
        </div>
      </div>
    );
  }

  return (
    <Page variant="flush" fill className="create-assessment-page">
      {/* Workflow Timeline */}
      <div className="workflow-timeline-bar">
        <span className="wt-page-title">{mode === 'edit' ? 'Assessment View' : 'Create Assessment'}</span>
        {workflowConfig && workflowConfig.statuses.length > 0 && (
          <>
            <div className="wt-bar-divider" />
            {workflowConfig.statuses.map((status, index) => {
              const statuses = workflowConfig.statuses;
              const currentIndex = statuses.indexOf(formData.status);
              const isPast = currentIndex > index;
              const isActive = formData.status === status;
              const isLast = index === statuses.length - 1;
              return (
                <div key={status} className={`wt-step${isPast ? ' past' : ''}${isActive ? ' active' : ''}`}>
                  <div className="wt-node"><div className="wt-dot" /></div>
                  <span className="wt-label">{status}</span>
                  {!isLast && <div className={`wt-connector${isPast ? ' filled' : ''}`} />}
                </div>
              );
            })}
          </>
        )}
      </div>

      <div className="create-assessment-content">
      {error && (
        <div className="alert alert-danger alert-dismissible fade show mb-4" role="alert">
          {error}
          <button type="button" className="btn-close" onClick={() => setError('')}></button>
        </div>
      )}

      <div className="split-view">
        {/* Left Side - Form */}
        <div className="form-panel">
          <form onSubmit={handleSubmit}>
            {/* Basic Information */}
            <div className="form-section">
              <div className="attachments-header">
                <h5 className="section-title mb-0">Basic Information</h5>
                {mode === 'create' && (
                  <button type="button" className="wt-back-btn" onClick={handleClear}>
                    <Eraser size={14} />
                    Clear Form
                  </button>
                )}
              </div>
              <div className="row g-3">
                <div className="col-md-4">
                  <FormLabel>Application Id</FormLabel>
                  <Input
                    type="text"
                    value={applicationAppId}
                    onChange={(e) => {
                      setApplicationAppId(e.target.value);
                      // Editing this field directly overrides any previously selected
                      // existing application — fall back to appId-based lookup/create.
                      setFormData((prev) => (prev.applicationId ? { ...prev, applicationId: '' } : prev));
                    }}
                    placeholder="Optional — custom application ID"
                  />
                </div>
                <div className="col-md-4">
                  <FormLabel required>Application Name</FormLabel>
                  <SearchableApplicationSelect
                    value={formData.applicationId}
                    appId={applicationAppId}
                    applicationName={applicationName}
                    primaryField="name"
                    onChange={(id, appId, name) => {
                      // Prefill assessment type + report template from this application's
                      // most recent assessment (apps with no history leave them untouched)
                      const source = id
                        ? allPreviousAssessments
                            .filter(a => a.applicationId === id && a.assessmentTypeId)
                            .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())[0]
                        : undefined;
                      setFormData((prev) => ({
                        ...prev,
                        applicationId: id,
                        // On selection, fill the assessment name if it's blank or still
                        // holds the auto-filled search text (a deliberate name is kept)
                        name: id && (!prev.name || prev.name === applicationName) ? name : prev.name,
                        ...(source ? {
                          assessmentTypeId: source.assessmentTypeId,
                          reportTemplateId: source.reportTemplateId || '',
                        } : {}),
                      }));
                      setApplicationName(name);
                      if (source) loadReportTemplates(source.assessmentTypeId);
                      // Only sync the Application Id field when a concrete existing
                      // application was actually selected — free typing here shouldn't
                      // clobber a custom Application Id the user already entered.
                      if (id) {
                        setApplicationAppId(appId);
                        loadApplicationStakeholders(id);
                      }
                    }}
                    onClear={() => {
                      setFormData((prev) => ({ ...prev, applicationId: '' }));
                      setApplicationName('');
                    }}
                    onBlur={fillAssessmentNameFromApplicationName}
                    allowCreate={true}
                    placeholder="Search or enter application name..."
                  />
                </div>
                <div className="col-md-4">
                  <FormLabel required>Assessment Name</FormLabel>
                  {mode === 'create' ? (
                    <div className="name-autocomplete-wrap" ref={nameWrapRef}>
                      <Input
                        type="text"
                        placeholder="Assessment name"
                        value={formData.name}
                        onChange={(e) => handleNameChange(e.target.value)}
                        onFocus={() => nameSuggestions.length > 0 && setShowSuggestions(true)}
                        onBlur={fillApplicationNameFromAssessmentName}
                        autoComplete="off"
                        required
                      />
                      {showSuggestions && (
                        <ul className="name-suggestions-dropdown">
                          {nameSuggestions.map(a => (
                            <li key={a.id} onMouseDown={() => handleSelectSuggestion(a.name)}>
                              <span className="name-suggestion-name">{a.name}</span>
                              <span className="name-suggestion-meta">
                                {a.applicationId === formData.applicationId ? applicationName : ''}
                                {a.startDate ? ` · ${new Date(a.startDate).toLocaleDateString()}` : ''}
                              </span>
                            </li>
                          ))}
                        </ul>
                      )}
                    </div>
                  ) : (
                    <Input
                      type="text"
                      placeholder="Assessment name"
                      value={formData.name}
                      onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                      onBlur={fillApplicationNameFromAssessmentName}
                      required
                    />
                  )}
                </div>
                <div className="col-md-4">
                  <FormLabel required>Assessment Type</FormLabel>
                  <Select
                    value={formData.assessmentTypeId}
                    onChange={(e) => {
                      setFormData({ ...formData, assessmentTypeId: e.target.value, reportTemplateId: '' });
                      setReportTemplates([]);
                    }}
                    required
                  >
                    <option value="">Select type...</option>
                    {assessmentTypes.map((type) => (
                      <option key={type.id} value={type.id}>
                        {type.name}
                      </option>
                    ))}
                  </Select>
                </div>
                <div className="col-md-4">
                  <FormLabel>Start Date</FormLabel>
                  <Input
                    type="date"
                    value={formData.startDate}
                    onChange={(e) => handleStartDateChange(e.target.value)}
                  />
                </div>
                <div className="col-md-4">
                  <FormLabel>Planned End Date</FormLabel>
                  <Select value={duration} onChange={(e) => handleDurationChange(e.target.value)}>
                    {DURATION_OPTIONS.map((o) => (
                      <option key={o.value} value={o.value}>{o.label}</option>
                    ))}
                    <option value={CUSTOM_DURATION}>Custom</option>
                  </Select>
                  {duration === CUSTOM_DURATION && (
                    <Input
                      type="date"
                      className="mt-1"
                      value={formData.plannedEndDate}
                      onChange={(e) => setFormData({ ...formData, plannedEndDate: e.target.value })}
                    />
                  )}
                  {formData.startDate && formData.plannedEndDate && (
                    <small className="text-muted mt-1 d-block">
                      Ends {new Date(formData.plannedEndDate + 'T00:00:00').toLocaleDateString()}
                      {' · '}
                      {businessDaysBetween(formData.startDate, formData.plannedEndDate)} working days
                    </small>
                  )}
                  {!formData.startDate && duration !== CUSTOM_DURATION && (
                    <small className="text-muted mt-1 d-block">
                      Pick a start date to set the end date.
                    </small>
                  )}
                </div>
                <div className="col-md-4">
                  <FormLabel>Status</FormLabel>
                  <Select
                    value={formData.status}
                    onChange={(e) => setFormData({ ...formData, status: e.target.value })}
                  >
                    {workflowConfig
                      ? workflowConfig.statuses.map(s => <option key={s} value={s}>{s}</option>)
                      : <option value={formData.status}>{formData.status}</option>
                    }
                  </Select>
                </div>
                {campaigns.length > 0 && (
                  <div className="col-md-4">
                    <FormLabel>Campaign</FormLabel>
                    <Select
                      value={formData.campaignId}
                      onChange={(e) => setFormData({ ...formData, campaignId: e.target.value })}
                    >
                      <option value="">No campaign</option>
                      {campaigns.map((campaign) => (
                        <option key={campaign.id} value={campaign.id}>
                          {campaign.name}
                        </option>
                      ))}
                    </Select>
                  </div>
                )}
              </div>
            </div>

            {/* Report Template — always on the page; grayed out until a type is chosen */}
            <div className="form-section">
              <h5 className="section-title">Report Template</h5>
              <div className="form-group">
                <FormLabel required>Template</FormLabel>
                <Select
                  value={formData.reportTemplateId}
                  onChange={(e) => setFormData({ ...formData, reportTemplateId: e.target.value })}
                  required
                  // Locked when no assessment type is chosen yet, or when the lone
                  // template really is the current selection — an empty/stale
                  // selection (e.g. the previous template was deleted) must stay
                  // pickable.
                  disabled={!formData.assessmentTypeId
                    || (reportTemplates.length === 1
                      && formData.reportTemplateId === reportTemplates[0].id)}
                >
                  <option value="">Select template...</option>
                  {reportTemplates.map((template) => (
                    <option key={template.id} value={template.id}>
                      {template.name} (v{template.version})
                    </option>
                  ))}
                </Select>
                {!formData.assessmentTypeId ? (
                  <small className="text-muted d-block mt-1">Select an assessment type to choose a template</small>
                ) : reportTemplates.length === 0 ? (
                  <small className="text-muted d-block mt-1">No active templates for this assessment type</small>
                ) : reportTemplates.length === 1 && formData.reportTemplateId === reportTemplates[0].id ? (
                  <small className="text-muted d-block mt-1">Auto-selected (only one active template)</small>
                ) : null}
              </div>
            </div>

            {/* Contacts */}
            <div className="form-section">
              <h5 className="section-title">Contacts</h5>

              {/* Team and Managers */}
              <div className="row g-3">
                <div className="col-md-4">
                  <div className="form-group">
                    <FormLabel>Team</FormLabel>
                    <Select
                      value={formData.teamId}
                      onChange={(e) => setFormData({ ...formData, teamId: e.target.value })}
                    >
                      <option value="">Unassigned</option>
                      {teams.map((team) => (
                        <option key={team.id} value={team.id}>
                          {team.name}
                        </option>
                      ))}
                    </Select>
                  </div>
                </div>
                <div className="col-md-4">
                  <div className="form-group">
                    <FormLabel>Engagement Manager</FormLabel>
                    <Select
                      value={formData.engagementManagerId}
                      onChange={(e) => setFormData({ ...formData, engagementManagerId: e.target.value })}
                    >
                      <option value="">Select manager...</option>
                      {filteredUsers.map((user) => (
                        <option key={user.id} value={user.id}>
                          {user.firstName} {user.lastName} ({user.username})
                        </option>
                      ))}
                    </Select>
                  </div>
                </div>
                <div className="col-md-4">
                  <div className="form-group">
                    <FormLabel>Remediation Manager</FormLabel>
                    <Select
                      value={formData.remediationManagerId}
                      onChange={(e) => setFormData({ ...formData, remediationManagerId: e.target.value })}
                    >
                      <option value="">Select manager...</option>
                      {filteredUsers.map((user) => (
                        <option key={user.id} value={user.id}>
                          {user.firstName} {user.lastName} ({user.username})
                        </option>
                      ))}
                    </Select>
                  </div>
                </div>
              </div>

              {/* Assessors - Dual List Box */}
              <div className="form-group">
                <FormLabel>Assessors</FormLabel>
                <DualListBox
                  availableItems={availableAssessorUsers.map((user) => ({
                    id: user.id,
                    name: `${user.firstName} ${user.lastName}`,
                    email: user.email,
                  }))}
                  selectedIds={formData.assessorIds}
                  onChange={(ids) => setFormData({ ...formData, assessorIds: ids })}
                  availableLabel="Available Assessors"
                  selectedLabel="Selected Assessors"
                />
              </div>

              {/* Conflict Warnings */}
              {conflicts.length > 0 && (
                <div className="alert alert-warning mb-3">
                  <strong>⚠️ Scheduling Conflicts Detected:</strong>
                  <ul className="mb-0 mt-2 small">
                    {conflicts.map((conflict) => (
                      <li key={conflict.id}>
                        {conflict.name} ({conflict.status}) -{' '}
                        {conflict.startDate && new Date(conflict.startDate).toLocaleDateString()} to{' '}
                        {conflict.plannedEndDate && new Date(conflict.plannedEndDate).toLocaleDateString()}
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              {/* Stakeholders */}
              <div>
                <FormLabel>Stakeholders</FormLabel>
                <div className="row g-3">
                  <div className="col-md-12">
                    <div className="stakeholder-list">
                      {stakeholders.length === 0 ? (
                        <div className="text-center text-muted py-3">
                          <small>No Stakeholders</small>
                        </div>
                      ) : (
                        stakeholders.map((stakeholder, index) => (
                          <div key={index} className="row g-2 mb-2 align-items-center">
                            <div className="col-md-4">
                              <div className="small">{stakeholder.name}</div>
                            </div>
                            <div className="col-md-4">
                              <a href={`mailto:${stakeholder.email}`} className="small text-muted">
                                {stakeholder.email}
                              </a>
                            </div>
                            <div className="col-md-2">
                              <div className="small text-muted">{stakeholder.role || '-'}</div>
                            </div>
                            <div className="col-md-2">
                              <button
                                type="button"
                                className="stakeholder-remove-btn"
                                onClick={() => handleRemoveStakeholder(index)}
                                title="Remove"
                              >
                                <Trash2 size={14} />
                              </button>
                            </div>
                          </div>
                        ))
                      )}
                    </div>
                  </div>
                </div>
                <div className="row g-2">
                  <div className="col-md-4">
                    <Input
                      placeholder="Name"
                      value={newStakeholder.name}
                      onChange={(e) => setNewStakeholder({ ...newStakeholder, name: e.target.value })}
                    />
                  </div>
                  <div className="col-md-4">
                    <Input
                      type="email"
                      placeholder="Email"
                      value={newStakeholder.email}
                      onChange={(e) => setNewStakeholder({ ...newStakeholder, email: e.target.value })}
                    />
                  </div>
                  <div className="col-md-2">
                    <Input
                      placeholder="Role"
                      value={newStakeholder.role}
                      onChange={(e) => setNewStakeholder({ ...newStakeholder, role: e.target.value })}
                    />
                  </div>
                  <div className="col-md-2">
                    <Button type="button" onClick={handleAddStakeholder} variant="primary" size="sm">
                      <Plus size={16} style={{ marginRight: '0.25rem' }} />
                      Add
                    </Button>
                  </div>
                </div>
              </div>
            </div>

            {/* Scope */}
            <div className="form-section">
              <h5 className="section-title">Scope</h5>
              <div className="form-group">
                <FormLabel>Scope</FormLabel>
                <div style={{ minHeight: '300px' }}>
                  <RichTextEditor
                    key={editorKey}
                    ref={editorRef}
                    value={formData.scope || ''}
                    onChange={(value) => setFormData({ ...formData, scope: value })}
                    onImageUpload={handleInlineImageUpload}
                  />
                </div>
              </div>
            </div>

            {/* Engagement URLs */}
<div className="form-section">
               <h5 className="section-title">Engagement URLs</h5>

               {engagementUrls.map((url, index) => (
                <div key={index} className="d-flex align-items-center gap-2 mb-2">
                  <div className="flex-grow-1">
                    <div className="fw-medium small">{url.description}</div>
                    <a href={url.url} target="_blank" rel="noopener noreferrer" className="text-muted small">
                      {url.url}
                    </a>
                  </div>
                  <button
                    type="button"
                    className="btn btn-sm btn-danger"
                    onClick={() => handleRemoveUrl(index)}
                  >
                    <X size={14} />
                  </button>
                </div>
              ))}
              <div className="row g-2">
                <div className="col-md-6">
                  <Input
                    type="url"
                    placeholder="URL"
                    value={newUrl.url}
                    onChange={(e) => setNewUrl({ ...newUrl, url: e.target.value })}
                  />
                </div>
                <div className="col-md-4">
                  <Input
                    placeholder="Description"
                    value={newUrl.description}
                    onChange={(e) => setNewUrl({ ...newUrl, description: e.target.value })}
                  />
                </div>
                <div className="col-md-2">
                  <Button type="button" onClick={handleAddUrl} variant="primary" size="sm">
                    <Plus size={16} style={{ marginRight: '0.25rem' }} />
                    Add
                  </Button>
                </div>
              </div>
            </div>

            {/* Surveys — in create mode, selections are queued locally and
                attached right after the assessment is created */}
            <div className="form-section">
              <div className="attachments-header">
                <h5 className="section-title mb-0" style={{ display: 'flex', alignItems: 'center', gap: '0.375rem' }}>
                  Surveys
                  {(assessmentSurveys.length + pendingSurveyTemplateIds.length) > 0 && (
                    <span className="attachments-count">{assessmentSurveys.length + pendingSurveyTemplateIds.length}</span>
                  )}
                </h5>
                {availableSurveyTemplates.length > 0 && (
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                    <Select
                      value={surveyToAdd}
                      onChange={(e) => setSurveyToAdd(e.target.value)}
                    >
                      <option value="">Select survey…</option>
                      {availableSurveyTemplates
                        .filter(t => !assessmentSurveys.some(s => s.templateId === t.id) && !pendingSurveyTemplateIds.includes(t.id))
                        .map(t => (
                          <option key={t.id} value={t.id}>{t.name}</option>
                        ))}
                    </Select>
                    <div style={{ whiteSpace: 'nowrap' }}>
                      <Button
                        type="button"
                        size="md"
                        variant="primary"
                        onClick={handleAddSurvey}
                        disabled={!surveyToAdd || addingSurvey}
                      >
                        <Plus size={16} />
                        Add Survey
                      </Button>
                    </div>
                  </div>
                )}
              </div>

              {mode === 'create' && (
                pendingSurveyTemplateIds.length === 0 ? (
                  <p className="attachments-empty">No surveys attached.</p>
                ) : (
                  <ul className="attachment-list">
                    {pendingSurveyTemplateIds.map(templateId => {
                      const template = availableSurveyTemplates.find(t => t.id === templateId);
                      return (
                        <li key={templateId} className="attachment-item">
                          <div className="attachment-info">
                            <span className="attachment-name">{template?.name || 'Survey'}</span>
                          </div>
                          <div className="attachment-actions">
                            <button
                              type="button"
                              className="attachment-action-btn attachment-action-btn--danger"
                              onClick={() => handleRemovePendingSurvey(templateId)}
                              title="Remove"
                            >
                              <X size={14} />
                            </button>
                          </div>
                        </li>
                      );
                    })}
                  </ul>
                )
              )}

              {mode === 'edit' && (
                assessmentSurveys.length === 0 ? (
                  <p className="attachments-empty">No surveys attached.</p>
                ) : (
                  <ul className="attachment-list">
                    {assessmentSurveys.map(s => (
                      <li key={s.id} className="attachment-item">
                        <div className="attachment-info">
                          <span className="attachment-name">{s.templateName}</span>
                          <span className="attachment-meta" style={{ display: 'flex', alignItems: 'center', gap: '0.4rem', flexWrap: 'wrap' }}>
                            <Badge variant={s.status === 'COMPLETE' ? 'success' : 'warning'} size="sm">
                              {s.status === 'COMPLETE' ? 'Complete' : 'Incomplete'}
                            </Badge>
                            {s.completedBy && <span>{s.completedBy}</span>}
                          </span>
                        </div>
                        <div className="attachment-actions">
                          <button
                            type="button"
                            className="attachment-action-btn"
                            onClick={() => setViewSurveyId(s.id)}
                            title="View answers"
                          >
                            <Eye size={14} />
                          </button>
                          <button
                            type="button"
                            className="attachment-action-btn attachment-action-btn--danger"
                            onClick={() => handleRemoveSurvey(s.id)}
                            disabled={removingSurveyId === s.id}
                            title="Remove"
                          >
                            <X size={14} />
                          </button>
                        </div>
                      </li>
                    ))}
                  </ul>
                )
              )}
            </div>

            {/* Files */}
            <div className="form-section">
              <div className="attachments-header">
                <h5 className="section-title mb-0" style={{ display: 'flex', alignItems: 'center', gap: '0.375rem' }}>
                  <Paperclip size={16} />
                  Files
                  {(attachments.length + pendingFiles.length + sourceFiles.length) > 0 && (
                    <span className="attachments-count">{attachments.length + pendingFiles.length + sourceFiles.length}</span>
                  )}
                </h5>
                <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                  <input ref={fileInputRef} type="file" style={{ display: 'none' }} onChange={handleFileChange} />
                  <Button
                    type="button"
                    size="sm"
                    variant="secondary"
                    onClick={() => fileInputRef.current?.click()}
                    disabled={uploading}
                  >
                    <UploadCloud size={14} />
                    {uploading ? 'Uploading...' : 'Add File'}
                  </Button>
                </div>
              </div>

              {uploadError && <p className="attachment-error">{uploadError}</p>}

              {mode === 'create' && (pendingFiles.length > 0 || sourceFiles.length > 0) && (
                <p className="text-muted" style={{ fontSize: '0.8rem', margin: '0.25rem 0 0.5rem' }}>
                  Files will be uploaded when the assessment is saved.
                </p>
              )}

              {/* Pending files (create mode) */}
              {pendingFiles.length > 0 && (
                <ul className="attachment-list" style={{ marginBottom: attachments.length > 0 ? '0.5rem' : 0 }}>
                  {pendingFiles.map((file, i) => (
                    <li key={i} className="attachment-item">
                      <FileText size={15} className="attachment-icon" />
                      <div className="attachment-info">
                        <span className="attachment-name">{file.name}</span>
                        <span className="attachment-meta">{formatBytes(file.size)} · pending</span>
                      </div>
                      <div className="attachment-actions">
                        <button
                          type="button"
                          className="attachment-action-btn attachment-action-btn--danger"
                          onClick={() => handleRemovePending(i)}
                          title="Remove"
                        >
                          <X size={14} />
                        </button>
                      </div>
                    </li>
                  ))}
                </ul>
              )}

              {/* Saved attachments (edit mode) */}
              {attachments.length > 0 && (
                <ul className="attachment-list">
                  {attachments.map((file) => (
                    <li key={file.id} className="attachment-item">
                      <FileText size={15} className="attachment-icon" />
                      <div className="attachment-info">
                        <span className="attachment-name">{file.fileName}</span>
                        <span className="attachment-meta">
                          {formatBytes(file.fileSize)} · {file.uploadedByName} · {new Date(file.uploadedAt).toLocaleDateString()}
                        </span>
                      </div>
                      <div className="attachment-actions">
                        <button
                          type="button"
                          className="attachment-action-btn"
                          onClick={() => handleDownload(file.id, file.fileName)}
                          title="Download"
                        >
                          <Download size={14} />
                        </button>
                        <button
                          type="button"
                          className="attachment-action-btn attachment-action-btn--danger"
                          onClick={() => handleDeleteFile(file.id)}
                          title="Delete"
                        >
                          <Trash2 size={14} />
                        </button>
                      </div>
                    </li>
                  ))}
                </ul>
              )}

              {/* Source files to be copied (create mode) */}
              {sourceFiles.length > 0 && (
                <ul className="attachment-list" style={{ marginBottom: pendingFiles.length > 0 || attachments.length > 0 ? '0.5rem' : 0 }}>
                  {sourceFiles.map(f => (
                    <li key={f.id} className="attachment-item">
                      <Copy size={15} className="attachment-icon" />
                      <div className="attachment-info">
                        <span className="attachment-name">{f.fileName}</span>
                        <span className="attachment-meta">{formatBytes(f.fileSize)} · copied from previous assessment</span>
                      </div>
                      <div className="attachment-actions">
                        <button
                          type="button"
                          className="attachment-action-btn attachment-action-btn--danger"
                          onClick={() => setSourceFiles(prev => prev.filter(x => x.id !== f.id))}
                          title="Remove"
                        >
                          <X size={14} />
                        </button>
                      </div>
                    </li>
                  ))}
                </ul>
              )}

              {attachments.length === 0 && pendingFiles.length === 0 && sourceFiles.length === 0 && (
                <p className="attachments-empty">No files attached.</p>
              )}
            </div>

            {/* Submit Buttons */}
            <div className="form-actions">
              <div>
                {mode === 'edit' && (
                  <Button type="button" variant="danger" onClick={handleDelete} disabled={loading}>
                    <Trash2 size={18} />
                    Delete
                  </Button>
                )}
              </div>
              <div className="button-group">
                <Button type="button" variant="secondary" onClick={handleCancel} disabled={loading}>
                  Cancel
                </Button>
                {mode === 'edit' && (
                  <Button type="button" variant="primary" onClick={handleSave} disabled={loading}>
                    {loading ? 'Saving...' : 'Save'}
                  </Button>
                )}
                <Button type="button" variant="primary" onClick={handleSaveAndClose} disabled={loading}>
                  {loading ? 'Saving...' : 'Save & Close'}
                </Button>
              </div>
            </div>
          </form>
        </div>

        {/* Right Side - Calendar Preview */}
        <div className="calendar-panel">
          <div className="calendar-sticky">
            <h5 className="section-title mb-3">Calendar Preview</h5>
            {calendarPreview ? (
              <>
                <div className="preview-info mb-3 p-3 bg-light rounded">
                  <div className="fw-semibold mb-1">{calendarPreview.name}</div>
                  <div className="small text-muted">
                    {calendarPreview.startDate && new Date(calendarPreview.startDate).toLocaleDateString()} →{' '}
                    {calendarPreview.plannedEndDate && new Date(calendarPreview.plannedEndDate).toLocaleDateString()}
                  </div>
                </div>
                <div className="calendar-frame">
                  <AssessmentCalendar
                    assessments={[calendarPreview, ...teamAssessments]}
                    loading={false}
                    onEventClick={() => {}}
                    onEventDrop={handleCalendarDrop}
                    onEventResize={handleCalendarDrop}
                    currentAssessmentId={id || 'preview'}
                  />
                </div>
                <div className="mt-3 p-3 bg-info bg-opacity-10 rounded">
                  <small className="text-muted">
                    <strong>💡 Tip:</strong> Drag the calendar event to move it, or drag the edges to resize and adjust start/end dates. Changes update the form automatically.
                  </small>
                </div>
              </>
            ) : (
              <div className="text-center p-5 text-muted">
                <Calendar size={48} className="mb-3 opacity-25" />
                <p className="mb-0">Fill in the name and dates to see the calendar preview</p>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Cancel Confirmation Dialog */}
      <ConfirmDialog
        isOpen={showCancelConfirm}
        onClose={() => setShowCancelConfirm(false)}
        onConfirm={handleConfirmCancel}
        title="Unsaved Changes"
        message="You have unsaved changes. Are you sure you want to leave? All changes will be lost."
        confirmText="Yes, Leave"
        cancelText="Stay"
        variant="warning"
      />

      {/* Clear Form Confirmation Dialog */}
      <ConfirmDialog
        isOpen={showClearConfirm}
        onClose={() => setShowClearConfirm(false)}
        onConfirm={handleConfirmClear}
        title="Clear Form"
        message="This will clear everything you've entered on this form. Are you sure?"
        confirmText="Clear"
        cancelText="Cancel"
        variant="warning"
      />

      {/* Delete Confirmation Dialog */}
      <ConfirmDialog
        isOpen={showDeleteConfirm}
        onClose={() => setShowDeleteConfirm(false)}
        onConfirm={handleConfirmDelete}
        title="Delete Assessment"
        message={`Are you sure you want to delete "${formData.name}"? This will permanently delete the assessment and all associated vulnerabilities. This action cannot be undone.`}
        confirmText="Delete"
        cancelText="Cancel"
        variant="danger"
        isLoading={loading}
      />
      </div>{/* end create-assessment-content */}

      {viewSurveyId && id && (
        <SurveyDrawer
          assessment={{ id, name: formData.name } as any}
          onClose={() => setViewSurveyId(null)}
          readOnly={true}
          initialSurveyId={viewSurveyId}
        />
      )}
    </Page>
  );
}
