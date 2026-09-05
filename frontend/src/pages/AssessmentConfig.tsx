import { useEffect, useState, useCallback, useMemo, useRef } from 'react';
import { Edit2, Trash2, Plus, Eye, EyeOff, Power, GripVertical, X, Upload, Download, Copy, Lock, ChevronUp, ChevronDown } from 'lucide-react';
import { assessmentTypesApi, vulnerabilityCategoriesApi, checklistTemplatesApi, workflowConfigApi } from '../api';
import type { AssessmentType, CreateAssessmentTypeRequest, UpdateAssessmentTypeRequest, VulnerabilityCategory, ChecklistTemplate, ChecklistTemplateQuestion, AssessmentWorkflowConfig, VulnerabilitySla, RemediationStage } from '../types';

import DataTable, { Column, PaginationInfo, SortState, sortParam } from '../components/DataTable';
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
  Textarea,
  Checkbox,
  FormHint,
  ErrorMessage,
  Select,
  Toast,
} from '../components';
import { usePermissions } from '../utils/permissions';
import Page from '../components/Page';
import { DEFAULT_VULN_STATUSES } from '../utils/vulnStatus';
import { VULNERABILITY_SEVERITIES } from '../utils/vulnSeverity';
import ApplicationIdConfig from './ApplicationIdConfig';
import SurveyConfig from './SurveyConfig';
import Campaigns from './Campaigns';
import './AssessmentConfig.css';

export default function AssessmentConfig() {
  const { permissions } = usePermissions();

  // ── Assessment Types ────────────────────────────────────────────────────────
  const [assessmentTypes, setAssessmentTypes] = useState<AssessmentType[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showModal, setShowModal] = useState(false);
  const [modalMode, setModalMode] = useState<'create' | 'edit'>('create');
  const [selectedType, setSelectedType] = useState<AssessmentType | null>(null);
  const [showInactive, setShowInactive] = useState(false);

  const [pagination, setPagination] = useState<PaginationInfo>({
    page: 0,
    pageSize: 10,
    total: 0,
    totalPages: 0,
  });

  const [searchQuery, setSearchQuery] = useState('');
  const [typeSort, setTypeSort] = useState<SortState | null>(null);
  const [catSort, setCatSort] = useState<SortState | null>(null);
  const [checklistSort, setChecklistSort] = useState<SortState | null>(null);

  const [formData, setFormData] = useState({
    name: '',
    description: '',
    active: true,
  });

  // ── Vulnerability Categories ────────────────────────────────────────────────
  const [vulnCategories, setVulnCategories] = useState<VulnerabilityCategory[]>([]);
  const [catLoading, setCatLoading] = useState(true);
  const [catError, setCatError] = useState('');
  const [showCatModal, setShowCatModal] = useState(false);
  const [catModalMode, setCatModalMode] = useState<'create' | 'edit'>('create');
  const [selectedCategory, setSelectedCategory] = useState<VulnerabilityCategory | null>(null);
  const [catSearchQuery, setCatSearchQuery] = useState('');
  const [catPagination, setCatPagination] = useState<PaginationInfo>({
    page: 0,
    pageSize: 10,
    total: 0,
    totalPages: 0,
  });
  const [catFormData, setCatFormData] = useState({ name: '', description: '' });
  const [catToDelete, setCatToDelete] = useState<string | null>(null);

  // ── Assessment Type delete confirm ─────────────────────────────────────────
  const [typeToDelete, setTypeToDelete] = useState<string | null>(null);

  // ── Workflow Config ─────────────────────────────────────────────────────────
  const [workflowConfig, setWorkflowConfig] = useState<AssessmentWorkflowConfig | null>(null);
  const [wfAllowSelfPeerReview, setWfAllowSelfPeerReview] = useState(false);
  const [wfLoading, setWfLoading] = useState(true);
  const [wfError, setWfError] = useState('');
  const [wfStatuses, setWfStatuses] = useState<string[]>([]);
  const [wfNewStatus, setWfNewStatus] = useState('');
  const [wfNewAssessmentStatus, setWfNewAssessmentStatus] = useState('');
  const [wfInProgressStatus, setWfInProgressStatus] = useState('');
  const [wfCompletedStatus, setWfCompletedStatus] = useState('');
  const [wfDragIndex, setWfDragIndex] = useState<number | null>(null);
  const [wfStatusColors, setWfStatusColors] = useState<Record<string, string>>({});
  const [colorPickerMenu, setColorPickerMenu] = useState<{ status: string; x: number; y: number } | null>(null);
  const [wfVulnSlas, setWfVulnSlas] = useState<VulnerabilitySla[]>([]);
  const [wfSlaForm, setWfSlaForm] = useState({ severity: '', pastDueDays: '', warningDays: '' });
  const [wfSlaError, setWfSlaError] = useState('');
  const [wfVulnStatuses, setWfVulnStatuses] = useState<string[]>([]);
  const [wfNewVulnStatus, setWfNewVulnStatus] = useState('');
  const [wfStages, setWfStages] = useState<RemediationStage[]>([]);
  const [wfNewStageName, setWfNewStageName] = useState('');
  // Autosave bookkeeping — last payload persisted to the server, as JSON
  const wfLastSavedRef = useRef<string>('');
  const wfSavingRef = useRef(false);

  // ── Saved toast ─────────────────────────────────────────────────────────────
  const [showToast, setShowToast] = useState(false);
  const [toastKey, setToastKey] = useState(0);
  const [toastMessage, setToastMessage] = useState('Saved');
  const [toastVariant, setToastVariant] = useState<'success' | 'danger'>('success');

  const showToastMessage = (message: string, variant: 'success' | 'danger' = 'success') => {
    setToastMessage(message);
    setToastVariant(variant);
    setToastKey(k => k + 1);
    setShowToast(true);
  };

  // ── Tabs ────────────────────────────────────────────────────────────────────
  const [activeTab, setActiveTab] = useState<'types' | 'workflow' | 'checklists' | 'surveys' | 'campaigns' | 'applicationIds'>('types');
  // ── Checklist Templates ─────────────────────────────────────────────────────
  const [checklistTemplates, setChecklistTemplates] = useState<ChecklistTemplate[]>([]);
  const [checklistLoading, setChecklistLoading] = useState(true);
  const [checklistError, setChecklistError] = useState('');
  const [showChecklistModal, setShowChecklistModal] = useState(false);
  const [checklistModalMode, setChecklistModalMode] = useState<'create' | 'edit'>('create');
  const [selectedChecklist, setSelectedChecklist] = useState<ChecklistTemplate | null>(null);
  const [checklistToDelete, setChecklistToDelete] = useState<string | null>(null);
  const [deletingChecklist, setDeletingChecklist] = useState(false);
  const [checklistTypeFilter, setChecklistTypeFilter] = useState('');
  const [checklistFormData, setChecklistFormData] = useState({
    name: '',
    assessmentTypeId: '',
    questions: [] as ChecklistTemplateQuestion[],
    preventClosure: false,
  });
  const dragChecklistIdx = useRef<number | null>(null);
  const csvInputRef = useRef<HTMLInputElement>(null);


  useEffect(() => {
    loadAssessmentTypes();
  }, [pagination.page, pagination.pageSize, searchQuery, typeSort]);

  useEffect(() => {
    loadWorkflowConfig();
  }, []);

  const loadAssessmentTypes = async () => {
    try {
      setLoading(true);
      const response = await assessmentTypesApi.getAll(
        pagination.page, pagination.pageSize, sortParam(typeSort) ?? 'name,asc', searchQuery);
      if (response.data) {
        setAssessmentTypes(response.data);
        setPagination({
          page: response.pagination?.page || 0,
          pageSize: response.pagination?.size || 10,
          total: response.pagination?.totalElements || 0,
          totalPages: response.pagination?.totalPages || 0,
        });
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load assessment types');
    } finally {
      setLoading(false);
    }
  };

  const handleCreate = () => {
    setModalMode('create');
    setSelectedType(null);
    setFormData({
      name: '',
      description: '',
      active: true,
    });
    setError('');
    setShowModal(true);
  };

  const handleEdit = (type: AssessmentType) => {
    setModalMode('edit');
    setSelectedType(type);
    setFormData({
      name: type.name,
      description: type.description,
      active: type.active,
    });
    setError('');
    setShowModal(true);
  };

  const handleDelete = (typeId: string) => {
    setTypeToDelete(typeId);
  };

  const confirmDeleteType = async () => {
    if (!typeToDelete) return;
    try {
      await assessmentTypesApi.delete(typeToDelete);
      await loadAssessmentTypes();
    } catch (err: any) {
      alert(err.response?.data?.message || 'Failed to delete assessment type');
    } finally {
      setTypeToDelete(null);
    }
  };

  const handleToggleActive = async (type: AssessmentType) => {
    const action = type.active ? 'deactivate' : 'reactivate';
    if (!confirm(`Are you sure you want to ${action} this assessment type?`)) return;

    try {
      const updateData: UpdateAssessmentTypeRequest = {
        name: type.name,
        description: type.description,
        active: !type.active,
      };
      await assessmentTypesApi.update(type.id, updateData);
      await loadAssessmentTypes();
    } catch (err: any) {
      alert(err.response?.data?.message || `Failed to ${action} assessment type`);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    try {
      if (modalMode === 'create') {
        const createData: CreateAssessmentTypeRequest = {
          name: formData.name,
          description: formData.description,
          active: formData.active,
        };
        await assessmentTypesApi.create(createData);
      } else if (selectedType) {
        const updateData: UpdateAssessmentTypeRequest = {
          name: formData.name,
          description: formData.description,
          active: formData.active,
        };
        await assessmentTypesApi.update(selectedType.id, updateData);
      }

      setShowModal(false);
      await loadAssessmentTypes();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to save assessment type');
    }
  };

  const handlePageChange = useCallback((page: number) => {
    setPagination(prev => ({ ...prev, page }));
  }, []);

  const handlePageSizeChange = useCallback((pageSize: number) => {
    setPagination(prev => ({ ...prev, pageSize, page: 0 }));
  }, []);

  const handleSearchChange = useCallback((search: string) => {
    setSearchQuery(search);
    setPagination((prev) => ({ ...prev, page: 0 }));
  }, []);

  const filteredTypes = showInactive
    ? assessmentTypes
    : assessmentTypes.filter(type => type.active);

  // ── Workflow Config handlers ─────────────────────────────────────────────────

  const loadWorkflowConfig = async () => {
    try {
      setWfLoading(true);
      const res = await workflowConfigApi.getConfig();
      if (res.success && res.data) {
        applyWorkflowConfig(res.data);
      }
    } catch {
      setWfError('Failed to load workflow config');
    } finally {
      setWfLoading(false);
    }
  };

  const applyWorkflowConfig = (config: AssessmentWorkflowConfig) => {
    setWorkflowConfig(config);
    setWfStatuses(config.statuses || []);
    setWfNewAssessmentStatus(config.newAssessmentStatus || '');
    setWfInProgressStatus(config.inProgressStatus || '');
    setWfCompletedStatus(config.completedStatus || '');
    setWfStatusColors(config.statusColors || {});
    setWfVulnSlas(config.vulnerabilitySlas || []);
    setWfVulnStatuses(config.vulnerabilityStatuses || []);
    setWfStages(config.remediationStages || []);
    setWfAllowSelfPeerReview(!!config.allowSelfPeerReview);
    wfLastSavedRef.current = JSON.stringify(wfPayloadOf(config));
  };

  // The subset of the workflow config this page edits — used for change detection
  const wfPayloadOf = (c: AssessmentWorkflowConfig) => ({
    statuses: c.statuses || [],
    newAssessmentStatus: c.newAssessmentStatus || '',
    inProgressStatus: c.inProgressStatus || '',
    completedStatus: c.completedStatus || '',
    statusColors: c.statusColors || {},
    vulnerabilitySlas: c.vulnerabilitySlas || [],
    vulnerabilityStatuses: c.vulnerabilityStatuses || [],
    remediationStages: c.remediationStages || [],
    allowSelfPeerReview: !!c.allowSelfPeerReview,
  });

  // Autosave: persist workflow edits ~1s after the last change
  useEffect(() => {
    if (!workflowConfig || wfLoading) return;
    const payload = {
      statuses: wfStatuses,
      newAssessmentStatus: wfNewAssessmentStatus,
      inProgressStatus: wfInProgressStatus,
      completedStatus: wfCompletedStatus,
      statusColors: wfStatusColors,
      vulnerabilitySlas: wfVulnSlas,
      vulnerabilityStatuses: wfVulnStatuses,
      remediationStages: wfStages,
      allowSelfPeerReview: wfAllowSelfPeerReview,
    };
    const serialized = JSON.stringify(payload);
    if (serialized === wfLastSavedRef.current) return;

    const timer = setTimeout(async () => {
      if (wfSavingRef.current) return; // a save is in flight; the next effect run picks up remaining changes
      wfSavingRef.current = true;
      setWfError('');
      try {
        const res = await workflowConfigApi.updateConfig({ ...workflowConfig, ...payload });
        if (res.success && res.data) {
          // Don't re-apply server state to the form — the user may have kept
          // typing while the request was in flight; just mark this payload saved.
          setWorkflowConfig(res.data);
          wfLastSavedRef.current = serialized;
          showToastMessage('Saved');
        } else {
          setWfError(res.message || 'Failed to save workflow config');
          showToastMessage('Failed to save', 'danger');
        }
      } catch (err: any) {
        setWfError(err.response?.data?.message || 'Failed to save workflow config');
        showToastMessage('Failed to save', 'danger');
      } finally {
        wfSavingRef.current = false;
      }
    }, 1000);
    return () => clearTimeout(timer);
  }, [wfStatuses, wfNewAssessmentStatus, wfInProgressStatus, wfCompletedStatus,
      wfStatusColors, wfVulnSlas, wfVulnStatuses, wfStages, wfAllowSelfPeerReview, workflowConfig, wfLoading]);

  const handleWfAddStatus = () => {
    const trimmed = wfNewStatus.trim();
    if (!trimmed || wfStatuses.includes(trimmed)) return;
    setWfStatuses(prev => [...prev, trimmed]);
    setWfNewStatus('');
  };

  const handleWfRemoveStatus = (status: string) => {
    setWfStatuses(prev => prev.filter(s => s !== status));
    if (wfNewAssessmentStatus === status) setWfNewAssessmentStatus('');
    if (wfInProgressStatus === status) setWfInProgressStatus('');
    if (wfCompletedStatus === status) setWfCompletedStatus('');
  };

  const handleWfDragStart = (index: number) => setWfDragIndex(index);

  const handleWfDragOver = (e: React.DragEvent, index: number) => {
    e.preventDefault();
    if (wfDragIndex === null || wfDragIndex === index) return;
    const updated = [...wfStatuses];
    const [moved] = updated.splice(wfDragIndex, 1);
    updated.splice(index, 0, moved);
    setWfStatuses(updated);
    setWfDragIndex(index);
  };

  const handleWfDragEnd = () => setWfDragIndex(null);

  const handleWfColorContextMenu = (e: React.MouseEvent, status: string) => {
    e.preventDefault();
    setColorPickerMenu({ status, x: e.clientX, y: e.clientY });
  };

  const handleWfColorSelect = (status: string, color: string | null) => {
    if (color === null) {
      setWfStatusColors(prev => { const next = { ...prev }; delete next[status]; return next; });
    } else {
      setWfStatusColors(prev => ({ ...prev, [status]: color }));
    }
    setColorPickerMenu(null);
  };

  useEffect(() => {
    if (!colorPickerMenu) return;
    const close = () => setColorPickerMenu(null);
    document.addEventListener('click', close);
    return () => document.removeEventListener('click', close);
  }, [colorPickerMenu]);

  // ── Vulnerability Category handlers ─────────────────────────────────────────

  useEffect(() => {
    loadVulnCategories();
  }, []);

  const loadVulnCategories = async () => {
    try {
      setCatLoading(true);
      const response = await vulnerabilityCategoriesApi.getAll();
      if (response.data) {
        setVulnCategories(response.data);
      }
    } catch (err: any) {
      setCatError(err.response?.data?.message || 'Failed to load vulnerability categories');
    } finally {
      setCatLoading(false);
    }
  };

  const handleCatCreate = () => {
    setCatModalMode('create');
    setSelectedCategory(null);
    setCatFormData({ name: '', description: '' });
    setCatError('');
    setShowCatModal(true);
  };

  const handleCatEdit = (category: VulnerabilityCategory) => {
    setCatModalMode('edit');
    setSelectedCategory(category);
    setCatFormData({ name: category.name, description: category.description || '' });
    setCatError('');
    setShowCatModal(true);
  };

  const handleCatDelete = (categoryId: string) => {
    setCatToDelete(categoryId);
  };

  const confirmDeleteCategory = async () => {
    if (!catToDelete) return;
    try {
      await vulnerabilityCategoriesApi.delete(catToDelete);
      await loadVulnCategories();
    } catch (err: any) {
      alert(err.response?.data?.message || 'Failed to delete vulnerability category');
    } finally {
      setCatToDelete(null);
    }
  };

  const handleCatSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setCatError('');
    try {
      if (catModalMode === 'create') {
        await vulnerabilityCategoriesApi.create({ name: catFormData.name, description: catFormData.description || undefined });
      } else if (selectedCategory) {
        await vulnerabilityCategoriesApi.update(selectedCategory.id, { name: catFormData.name, description: catFormData.description || undefined });
      }
      setShowCatModal(false);
      await loadVulnCategories();
    } catch (err: any) {
      setCatError(err.response?.data?.message || 'Failed to save vulnerability category');
    }
  };

  const handleCatPageChange = useCallback((page: number) => {
    setCatPagination(prev => ({ ...prev, page }));
  }, []);

  const handleCatPageSizeChange = useCallback((pageSize: number) => {
    setCatPagination(prev => ({ ...prev, pageSize, page: 0 }));
  }, []);

  const handleCatSearchChange = useCallback((search: string) => {
    setCatSearchQuery(search);
    setCatPagination(prev => ({ ...prev, page: 0 }));
  }, []);

  // ── Checklist Template handlers ──────────────────────────────────────────────

  useEffect(() => {
    loadChecklistTemplates();
  }, []);

  const loadChecklistTemplates = async () => {
    try {
      setChecklistLoading(true);
      const response = await checklistTemplatesApi.getAll();
      if (response.data) setChecklistTemplates(response.data);
    } catch (err: any) {
      setChecklistError(err.response?.data?.message || 'Failed to load checklist templates');
    } finally {
      setChecklistLoading(false);
    }
  };

  const handleChecklistCreate = () => {
    setChecklistModalMode('create');
    setSelectedChecklist(null);
    setChecklistFormData({ name: '', assessmentTypeId: '', questions: [], preventClosure: false });
    setChecklistError('');
    setShowChecklistModal(true);
  };

  const handleChecklistEdit = (template: ChecklistTemplate) => {
    setChecklistModalMode('edit');
    setSelectedChecklist(template);
    setChecklistFormData({
      name: template.name,
      assessmentTypeId: template.assessmentTypeId,
      questions: template.questions.map(q => ({ ...q })),
      preventClosure: template.preventClosure,
    });
    setChecklistError('');
    setShowChecklistModal(true);
  };

  const handleChecklistSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setChecklistError('');
    const questionsWithOrder = checklistFormData.questions.map((q, i) => ({ ...q, order: i }));
    try {
      if (checklistModalMode === 'create') {
        await checklistTemplatesApi.create({
          name: checklistFormData.name,
          assessmentTypeId: checklistFormData.assessmentTypeId,
          questions: questionsWithOrder,
          preventClosure: checklistFormData.preventClosure,
        });
      } else if (selectedChecklist) {
        await checklistTemplatesApi.update(selectedChecklist.id, {
          name: checklistFormData.name,
          assessmentTypeId: checklistFormData.assessmentTypeId,
          questions: questionsWithOrder,
          preventClosure: checklistFormData.preventClosure,
        });
      }
      setShowChecklistModal(false);
      await loadChecklistTemplates();
    } catch (err: any) {
      setChecklistError(err.response?.data?.message || 'Failed to save checklist template');
    }
  };

  const confirmDeleteChecklist = async () => {
    if (!checklistToDelete) return;
    setDeletingChecklist(true);
    try {
      await checklistTemplatesApi.delete(checklistToDelete);
      await loadChecklistTemplates();
    } catch (err: any) {
      setChecklistError(err.response?.data?.message || 'Failed to delete checklist template');
    } finally {
      setDeletingChecklist(false);
      setChecklistToDelete(null);
    }
  };

  const addChecklistQuestion = () => {
    setChecklistFormData(prev => ({
      ...prev,
      questions: [...prev.questions, { id: '', text: '', order: prev.questions.length }],
    }));
  };

  const updateChecklistQuestion = (idx: number, text: string) => {
    setChecklistFormData(prev => {
      const questions = [...prev.questions];
      questions[idx] = { ...questions[idx], text };
      return { ...prev, questions };
    });
  };

  const removeChecklistQuestion = (idx: number) => {
    setChecklistFormData(prev => ({
      ...prev,
      questions: prev.questions.filter((_, i) => i !== idx),
    }));
  };

  const handleQuestionDragStart = (idx: number) => {
    dragChecklistIdx.current = idx;
  };

  const handleQuestionDragOver = (e: React.DragEvent, idx: number) => {
    e.preventDefault();
    const from = dragChecklistIdx.current;
    if (from === null || from === idx) return;
    setChecklistFormData(prev => {
      const questions = [...prev.questions];
      const [moved] = questions.splice(from, 1);
      questions.splice(idx, 0, moved);
      dragChecklistIdx.current = idx;
      return { ...prev, questions };
    });
  };

  const handleCsvUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (ev) => {
      const text = ev.target?.result as string;
      const newQuestions = text
        .split('\n')
        .map(line => line.trim())
        .filter(line => line.length > 0)
        .map((text, i) => ({ id: '', text, order: i }));
      setChecklistFormData(prev => ({
        ...prev,
        questions: [...prev.questions, ...newQuestions],
      }));
    };
    reader.readAsText(file);
    // reset so the same file can be re-uploaded if needed
    e.target.value = '';
  };

  const handleDownloadCsv = (template: ChecklistTemplate) => {
    const lines = (template.questions || [])
      .slice()
      .sort((a, b) => a.order - b.order)
      .map(q => q.text);
    const csv = lines.join('\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${template.name.replace(/[^a-z0-9]/gi, '_')}_questions.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleCloneChecklist = async (template: ChecklistTemplate) => {
    try {
      await checklistTemplatesApi.create({
        name: `${template.name} (Copy)`,
        assessmentTypeId: template.assessmentTypeId,
        questions: (template.questions || []).map(q => ({ ...q, id: '' })),
        preventClosure: template.preventClosure,
      });
      await loadChecklistTemplates();
    } catch (err: any) {
      setChecklistError(err.response?.data?.message || 'Failed to clone checklist template');
    }
  };

  const filteredChecklistTemplates = useMemo(() => {
    if (!checklistTypeFilter) return checklistTemplates;
    return checklistTemplates.filter(t => t.assessmentTypeId === checklistTypeFilter);
  }, [checklistTemplates, checklistTypeFilter]);

  const sortedChecklistTemplates = useMemo(
    () => applyClientSort(filteredChecklistTemplates, checklistSort, {
      name: (t) => t.name,
      // The Assessment Type cell shows the type's name, so sort by that rather than its id.
      assessmentTypeName: (t) =>
        assessmentTypes.find(at => at.id === t.assessmentTypeId)?.name || t.assessmentTypeId,
      questionCount: (t) => t.questions?.length ?? 0,
      active: (t) => t.active,
    } as SortAccessors<ChecklistTemplate>),
    [filteredChecklistTemplates, checklistSort, assessmentTypes],
  );

  const filteredCategories = useMemo(() => {
    const q = catSearchQuery.toLowerCase();
    return q
      ? vulnCategories.filter(c => c.name.toLowerCase().includes(q) || (c.description || '').toLowerCase().includes(q))
      : vulnCategories;
  }, [vulnCategories, catSearchQuery]);

  const sortedCategories = useMemo(
    () => applyClientSort(filteredCategories, catSort, {
      name: (cat) => cat.name,
      description: (cat) => cat.description,
      createdAt: (cat) => cat.createdAt,
    } as SortAccessors<VulnerabilityCategory>),
    [filteredCategories, catSort],
  );

  const pagedCategories = useMemo(() => {
    const start = catPagination.page * catPagination.pageSize;
    return sortedCategories.slice(start, start + catPagination.pageSize);
  }, [sortedCategories, catPagination.page, catPagination.pageSize]);

  const catPaginationInfo: PaginationInfo = {
    page: catPagination.page,
    pageSize: catPagination.pageSize,
    total: filteredCategories.length,
    totalPages: Math.ceil(filteredCategories.length / catPagination.pageSize) || 1,
  };


  const columns: Column<AssessmentType>[] = [
    {
      header: 'Name',
      sortKey: 'name',
      accessor: 'name',
    },
    {
      header: 'Description',
      sortKey: 'description',
      accessor: 'description',
    },
    {
      header: 'Status',
      sortKey: 'active',
      render: (type) => (
        <Badge variant={type.active ? 'success' : 'secondary'}>
          {type.active ? 'Active' : 'Inactive'}
        </Badge>
      ),
    },
    {
      header: 'Created',
      sortKey: 'createdAt',
      render: (type) => new Date(type.createdAt).toLocaleDateString(),
    },
    {
      header: 'Actions',
      render: (type) => (
        <ActionButtons>
          <IconButton
            icon={Edit2}
            variant="edit"
            title="Edit"
            onClick={() => handleEdit(type)}
          />
          <IconButton
            icon={Power}
            variant={type.active ? 'warning' : 'success'}
            title={type.active ? 'Deactivate' : 'Reactivate'}
            onClick={() => handleToggleActive(type)}
          />
          <IconButton
            icon={Trash2}
            variant="delete"
            title="Delete"
            onClick={() => handleDelete(type.id)}
          />
        </ActionButtons>
      ),
    },
  ];

  const catColumns: Column<VulnerabilityCategory>[] = [
    { header: 'Name', accessor: 'name', sortKey: 'name' },
    { header: 'Description', sortKey: 'description', render: (cat) => cat.description || '-' },
    {
      header: 'Created',
      sortKey: 'createdAt',
      render: (cat) => new Date(cat.createdAt).toLocaleDateString(),
    },
    {
      header: 'Actions',
      render: (cat) => (
        <ActionButtons>
          {permissions.canEditVulnerabilityCategories && (
            <IconButton icon={Edit2} variant="edit" title="Edit" onClick={() => handleCatEdit(cat)} />
          )}
          {permissions.canDeleteVulnerabilityCategories && (
            <IconButton icon={Trash2} variant="delete" title="Delete" onClick={() => handleCatDelete(cat.id)} />
          )}
        </ActionButtons>
      ),
    },
  ];


  const checklistColumns: Column<ChecklistTemplate>[] = [
    { header: 'Name', accessor: 'name', sortKey: 'name' },
    {
      header: 'Assessment Type',
      sortKey: 'assessmentTypeName',
      render: (t) => assessmentTypes.find(at => at.id === t.assessmentTypeId)?.name || t.assessmentTypeId,
    },
    { header: 'Questions', sortKey: 'questionCount', render: (t) => t.questions?.length ?? 0 },
    {
      header: 'Active',
      sortKey: 'active',
      render: (t) => (
        <Badge variant={t.active ? 'success' : 'secondary'}>{t.active ? 'Active' : 'Inactive'}</Badge>
      ),
    },
    {
      header: 'Actions',
      render: (t) => (
        <ActionButtons>
          {permissions.canManageChecklistTemplates && (
            <IconButton icon={Edit2} variant="edit" title="Edit" onClick={() => handleChecklistEdit(t)} />
          )}
          <IconButton icon={Download} variant="default" title="Download questions as CSV" onClick={() => handleDownloadCsv(t)} />
          {permissions.canManageChecklistTemplates && (
            <IconButton icon={Copy} variant="default" title="Clone checklist" onClick={() => handleCloneChecklist(t)} />
          )}
          {permissions.canManageChecklistTemplates && (
            <IconButton icon={Trash2} variant="delete" title="Delete" onClick={() => setChecklistToDelete(t.id)} />
          )}
        </ActionButtons>
      ),
    },
  ];

  return (
    <>
    <Page className="assessment-config-page">
      <div className="config-tab-nav">
        <button
          className={`config-tab-btn${activeTab === 'types' ? ' active' : ''}`}
          onClick={() => setActiveTab('types')}
        >
          Types &amp; Categories
        </button>
        {permissions.canManageAssessmentWorkflow && (
          <button
            className={`config-tab-btn${activeTab === 'workflow' ? ' active' : ''}`}
            onClick={() => setActiveTab('workflow')}
          >
            Assessment Workflow
          </button>
        )}
        <button
          className={`config-tab-btn${activeTab === 'checklists' ? ' active' : ''}`}
          onClick={() => setActiveTab('checklists')}
        >
          Checklist Templates
        </button>
        {permissions.canManageSurveys && (
          <button
            className={`config-tab-btn${activeTab === 'surveys' ? ' active' : ''}`}
            onClick={() => setActiveTab('surveys')}
          >
            Survey Templates
          </button>
        )}
        {permissions.canManageCampaigns && (
          <button
            className={`config-tab-btn${activeTab === 'campaigns' ? ' active' : ''}`}
            onClick={() => setActiveTab('campaigns')}
          >
            Campaigns
          </button>
        )}
        {/* Its own endpoints are super-admin only, so the tab follows — offering one that
            answers 403 is worse than not offering it. */}
        {permissions.canManageApplicationIdConfig && (
          <button
            className={`config-tab-btn${activeTab === 'applicationIds' ? ' active' : ''}`}
            onClick={() => setActiveTab('applicationIds')}
          >
            Application IDs
          </button>
        )}
      </div>

      {activeTab === 'applicationIds' && <ApplicationIdConfig embedded />}

      {activeTab === 'types' && (
      <div className="config-grid">
      <div className="config-section">
        <div className="section-header">
          <h2>Assessment Types</h2>
          <div className="section-actions">
            <Button
              variant="secondary"
              icon={showInactive ? EyeOff : Eye}
              onClick={() => setShowInactive(!showInactive)}
            >
              {showInactive ? 'Hide Inactive' : 'Show Inactive'}
            </Button>
            <Button
              variant="primary"
              icon={Plus}
              onClick={handleCreate}
            >
              Add Assessment Type
            </Button>
          </div>
        </div>

        {error && <ErrorMessage>{error}</ErrorMessage>}

        <DataTable
          columns={columns}
          data={filteredTypes}
          loading={loading}
          pagination={pagination}
          onPageChange={handlePageChange}
          onPageSizeChange={handlePageSizeChange}
          onSearchChange={handleSearchChange}
          searchPlaceholder="Search assessment types"
          idAccessor="id"
          sort={typeSort}
          onSortChange={(next) => {
            // Re-sorting reshuffles the whole result set, so the current page number
            // is meaningless afterwards — go back to the first page.
            setTypeSort(next);
            setPagination((prev) => ({ ...prev, page: 0 }));
          }}
        />
      </div>

      <Modal
        isOpen={showModal}
        onClose={() => setShowModal(false)}
        title={modalMode === 'create' ? 'Add Assessment Type' : 'Edit Assessment Type'}
        size="md"
        closeOnOverlayClick={false}
        onSubmit={handleSubmit}
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowModal(false)}>
              Cancel
            </Button>
            <Button type="submit" variant="primary">
              {modalMode === 'create' ? 'Create' : 'Update'}
            </Button>
          </>
        }
      >
        {error && <ErrorMessage>{error}</ErrorMessage>}

        <FormGroup>
          <FormLabel required>Name</FormLabel>
          <Input
            type="text"
            value={formData.name}
            onChange={(e) => setFormData({ ...formData, name: e.target.value })}
            required
            placeholder="e.g., Web Application Pentest"
          />
        </FormGroup>

        <FormGroup>
          <FormLabel required>Description</FormLabel>
          <Textarea
            value={formData.description}
            onChange={(e) => setFormData({ ...formData, description: e.target.value })}
            required
            rows={3}
            placeholder="Describe the assessment type..."
          />
        </FormGroup>

        <Checkbox
          label="Active"
          checked={formData.active}
          onChange={(e) => setFormData({ ...formData, active: e.target.checked })}
        />
        <FormHint>
          Inactive assessment types will not be available for new assessments
        </FormHint>
      </Modal>

      {/* ── Vulnerability Categories ─────────────────────────────────────── */}
      <div className="config-section">
        <div className="section-header">
          <h2>Vulnerability Categories</h2>
          {permissions.canCreateVulnerabilityCategories && (
            <div className="section-actions">
              <Button variant="primary" icon={Plus} onClick={handleCatCreate}>
                Add Category
              </Button>
            </div>
          )}
        </div>

        {catError && <ErrorMessage>{catError}</ErrorMessage>}

        <DataTable
          columns={catColumns}
          data={pagedCategories}
          loading={catLoading}
          pagination={catPaginationInfo}
          onPageChange={handleCatPageChange}
          onPageSizeChange={handleCatPageSizeChange}
          onSearchChange={handleCatSearchChange}
          searchPlaceholder="Search vulnerability categories"
          emptyMessage="No vulnerability categories found."
          idAccessor="id"
          sort={catSort}
          onSortChange={(next) => {
            setCatSort(next);
            setCatPagination((prev) => ({ ...prev, page: 0 }));
          }}
        />
      </div>
      </div>
      )}

      {/* ── Assessment Workflow Config ──────────────────────────────────── */}
      {activeTab === 'workflow' && permissions.canManageAssessmentWorkflow && (
      <div className="config-section workflow-config-section">
        <div className="section-header">
          <h2>Assessment Workflow</h2>
        </div>

        {wfError && <ErrorMessage>{wfError}</ErrorMessage>}

        {wfLoading ? (
          <div style={{ padding: '1rem', color: 'var(--text-secondary)' }}>Loading…</div>
        ) : (
          <>
            <FormGroup>
              <FormLabel>Status List</FormLabel>
              <FormHint>Drag to reorder. These statuses are available when managing assessments.</FormHint>
              <div className="wf-status-list">
                {wfStatuses.map((status, index) => (
                  <div
                    key={status}
                    className={`wf-status-item${wfDragIndex === index ? ' dragging' : ''}`}
                    draggable
                    onDragStart={() => handleWfDragStart(index)}
                    onDragOver={(e) => handleWfDragOver(e, index)}
                    onDragEnd={handleWfDragEnd}
                    onContextMenu={(e) => handleWfColorContextMenu(e, status)}
                    title="Right-click to set color"
                  >
                    <GripVertical size={14} className="wf-drag-handle" />
                    <span
                      className="wf-status-color-dot"
                      style={wfStatusColors[status] ? { backgroundColor: wfStatusColors[status] } : undefined}
                    />
                    <span className="wf-status-label">{status}</span>
                    <button
                      type="button"
                      className="wf-status-remove"
                      onClick={() => handleWfRemoveStatus(status)}
                      title="Remove"
                    >
                      <X size={12} />
                    </button>
                  </div>
                ))}
              </div>
              <div className="wf-add-status">
                <Input
                  type="text"
                  value={wfNewStatus}
                  onChange={(e) => setWfNewStatus(e.target.value)}
                  placeholder="New status name…"
                  onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); handleWfAddStatus(); } }}
                />
                <Button variant="secondary" icon={Plus} onClick={handleWfAddStatus} disabled={!wfNewStatus.trim()}>
                  Add
                </Button>
              </div>
            </FormGroup>

            <FormGroup>
              <FormLabel>New Assessment Status</FormLabel>
              <FormHint>Status assigned when a new assessment is created.</FormHint>
              <Select
                value={wfNewAssessmentStatus}
                onChange={(e) => setWfNewAssessmentStatus(e.target.value)}
              >
                <option value="">— Select —</option>
                {wfStatuses.map(s => <option key={s} value={s}>{s}</option>)}
              </Select>
            </FormGroup>

            <FormGroup>
              <FormLabel>In Progress Status</FormLabel>
              <FormHint>Status automatically applied when an assessment is within its scheduled date range.</FormHint>
              <Select
                value={wfInProgressStatus}
                onChange={(e) => setWfInProgressStatus(e.target.value)}
              >
                <option value="">— Select —</option>
                {wfStatuses.map(s => <option key={s} value={s}>{s}</option>)}
              </Select>
            </FormGroup>

            <FormGroup>
              <FormLabel>Completed Status</FormLabel>
              <FormHint>Status applied when an assessment is finalized. Assessments in this status cannot be modified.</FormHint>
              <Select
                value={wfCompletedStatus}
                onChange={(e) => setWfCompletedStatus(e.target.value)}
              >
                <option value="">— Select —</option>
                {wfStatuses.map(s => <option key={s} value={s}>{s}</option>)}
              </Select>
            </FormGroup>

            {/* ── Vulnerability SLAs ──────────────────────────────────────── */}
            <FormGroup>
              <FormLabel>Vulnerability SLAs</FormLabel>
              <FormHint>
                Set deadlines for opened vulnerabilities by severity. Warning days must be less than past due days.
                Severities without an SLA will not be tracked.
              </FormHint>

              {wfVulnSlas.length > 0 && (
                <table className="sla-table">
                  <thead>
                    <tr>
                      <th>Severity</th>
                      <th>Warning (days)</th>
                      <th>Past Due (days)</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    {wfVulnSlas.map(sla => (
                      <tr key={sla.severity}>
                        <td><span className={`sla-severity sla-severity--${sla.severity.toLowerCase()}`}>{sla.severity}</span></td>
                        <td>{sla.warningDays}</td>
                        <td>{sla.pastDueDays}</td>
                        <td>
                          <button
                            type="button"
                            className="sla-remove"
                            onClick={() => setWfVulnSlas(prev => prev.filter(s => s.severity !== sla.severity))}
                            title="Remove"
                          >
                            <X size={13} />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}

              {wfSlaError && <div className="sla-error">{wfSlaError}</div>}

              {(() => {
                const usedSeverities = new Set(wfVulnSlas.map(s => s.severity));
                const availableSeverities = VULNERABILITY_SEVERITIES.filter(s => !usedSeverities.has(s));
                if (availableSeverities.length === 0) return null;
                return (
                  <div className="sla-add-row">
                    <Select
                      value={wfSlaForm.severity}
                      onChange={(e) => setWfSlaForm(f => ({ ...f, severity: e.target.value }))}
                    >
                      <option value="">Severity…</option>
                      {availableSeverities.map(s => <option key={s} value={s}>{s}</option>)}
                    </Select>
                    <Input
                      type="number"
                      min={1}
                      value={wfSlaForm.warningDays}
                      onChange={(e) => setWfSlaForm(f => ({ ...f, warningDays: e.target.value }))}
                      placeholder="Warning days"
                    />
                    <Input
                      type="number"
                      min={1}
                      value={wfSlaForm.pastDueDays}
                      onChange={(e) => setWfSlaForm(f => ({ ...f, pastDueDays: e.target.value }))}
                      placeholder="Past due days"
                    />
                    <Button
                      variant="secondary"
                      icon={Plus}
                      onClick={() => {
                        setWfSlaError('');
                        const { severity, warningDays, pastDueDays } = wfSlaForm;
                        const warn = parseInt(warningDays);
                        const due = parseInt(pastDueDays);
                        if (!severity) return setWfSlaError('Select a severity.');
                        if (!warningDays || !pastDueDays || isNaN(warn) || isNaN(due)) return setWfSlaError('Enter valid day values.');
                        if (warn <= 0 || due <= 0) return setWfSlaError('Days must be greater than 0.');
                        if (warn >= due) return setWfSlaError('Warning days must be less than past due days.');
                        setWfVulnSlas(prev => [...prev, { severity, warningDays: warn, pastDueDays: due }]);
                        setWfSlaForm({ severity: '', warningDays: '', pastDueDays: '' });
                      }}
                    >
                      Add
                    </Button>
                  </div>
                );
              })()}
            </FormGroup>

            {/* ── Remediation Stages ──────────────────────────────────────── */}
            <FormGroup>
              <FormLabel>Remediation Stages</FormLabel>
              <FormHint>
                Ordered environments a fix moves through (e.g. Development, Staging, Production).
                Closing a vulnerability in the last stage closes the finding outright; earlier stages
                record the date but leave it open. Stages can be completed in any order and never
                affect the SLA clock.
              </FormHint>

              <div className="rs-list">
                {wfStages.map((stage, i) => (
                  <div key={stage.id} className="rs-item">
                    <span className="rs-order">{i + 1}</span>
                    <input
                      className="rs-name-input"
                      value={stage.name}
                      onChange={e => setWfStages(prev =>
                        prev.map((s, idx) => idx === i ? { ...s, name: e.target.value } : s))}
                    />
                    {i === wfStages.length - 1 && (
                      <span className="rs-terminal-badge">closes the finding</span>
                    )}
                    <button
                      type="button"
                      className="rs-move"
                      disabled={i === 0}
                      title="Move up"
                      onClick={() => setWfStages(prev => {
                        const next = [...prev];
                        [next[i - 1], next[i]] = [next[i], next[i - 1]];
                        return next;
                      })}
                    >
                      <ChevronUp size={14} />
                    </button>
                    <button
                      type="button"
                      className="rs-move"
                      disabled={i === wfStages.length - 1}
                      title="Move down"
                      onClick={() => setWfStages(prev => {
                        const next = [...prev];
                        [next[i], next[i + 1]] = [next[i + 1], next[i]];
                        return next;
                      })}
                    >
                      <ChevronDown size={14} />
                    </button>
                    <button
                      type="button"
                      className="rs-remove"
                      disabled={wfStages.length === 1}
                      title={wfStages.length === 1 ? 'At least one stage is required' : 'Remove stage'}
                      onClick={() => setWfStages(prev => prev.filter((_, idx) => idx !== i))}
                    >
                      <X size={13} />
                    </button>
                  </div>
                ))}
              </div>

              <div className="rs-add-row">
                <Input
                  value={wfNewStageName}
                  onChange={e => setWfNewStageName(e.target.value)}
                  placeholder="New stage name (e.g. QA)"
                  onKeyDown={e => {
                    if (e.key !== 'Enter') return;
                    e.preventDefault();
                    const name = wfNewStageName.trim();
                    if (!name) return;
                    setWfStages(prev => [...prev, { id: crypto.randomUUID(), name }]);
                    setWfNewStageName('');
                  }}
                />
                <Button
                  variant="secondary"
                  icon={Plus}
                  onClick={() => {
                    const name = wfNewStageName.trim();
                    if (!name) return;
                    setWfStages(prev => [...prev, { id: crypto.randomUUID(), name }]);
                    setWfNewStageName('');
                  }}
                >
                  Add
                </Button>
              </div>
            </FormGroup>

            {/* ── Vulnerability Statuses ──────────────────────────────────── */}
            <FormGroup>
              <FormLabel>Vulnerability Statuses</FormLabel>
              <FormHint>
                Default statuses are built-in and cannot be removed — this includes the retest statuses
                (In Retest, Passed Retest, Failed Retest) set automatically by the retest workflow.
                Add custom statuses for your workflow below.
              </FormHint>

              <div className="vs-list">
                {DEFAULT_VULN_STATUSES.map(s => (
                  <div key={s} className="vs-item vs-item--locked">
                    <span className="vs-lock-icon"><Lock size={12} /></span>
                    <span className="vs-label">{s}</span>
                    <span className="vs-badge">default</span>
                  </div>
                ))}
                {wfVulnStatuses.map(s => (
                  <div key={s} className="vs-item">
                    <span className="vs-label">{s}</span>
                    <button
                      type="button"
                      className="vs-remove"
                      onClick={() => setWfVulnStatuses(prev => prev.filter(v => v !== s))}
                      title="Remove"
                    >
                      <X size={13} />
                    </button>
                  </div>
                ))}
              </div>

              <div className="vs-add-row">
                <Input
                  type="text"
                  value={wfNewVulnStatus}
                  onChange={(e) => setWfNewVulnStatus(e.target.value)}
                  placeholder="New status name…"
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      const trimmed = wfNewVulnStatus.trim();
                      const locked = DEFAULT_VULN_STATUSES;
                      if (!trimmed || wfVulnStatuses.includes(trimmed) || locked.includes(trimmed)) return;
                      setWfVulnStatuses(prev => [...prev, trimmed]);
                      setWfNewVulnStatus('');
                    }
                  }}
                />
                <Button
                  variant="secondary"
                  icon={Plus}
                  disabled={!wfNewVulnStatus.trim()}
                  onClick={() => {
                    const trimmed = wfNewVulnStatus.trim();
                    const locked = DEFAULT_VULN_STATUSES;
                    if (!trimmed || wfVulnStatuses.includes(trimmed) || locked.includes(trimmed)) return;
                    setWfVulnStatuses(prev => [...prev, trimmed]);
                    setWfNewVulnStatus('');
                  }}
                >
                  Add
                </Button>
              </div>
            </FormGroup>

            {/* ── Peer Review ─────────────────────────────────────────────── */}
            <FormGroup>
              <FormLabel>Peer Review</FormLabel>
              <Checkbox
                label="Allow reviewing your own submissions"
                checked={wfAllowSelfPeerReview}
                onChange={(e) => setWfAllowSelfPeerReview(e.target.checked)}
              />
              <FormHint>
                Off by default: whoever submits an assessment for peer review cannot also review it.
                Turn this on for small teams where a second reviewer isn&apos;t always available.
                Accepting or rejecting a completed review is always done by the submitter.
              </FormHint>
            </FormGroup>
          </>
        )}
      </div>
      )}

      {/* ── Checklist Templates ──────────────────────────────────────────── */}
      {activeTab === 'checklists' && (
      <div className="config-section checklist-section">
        <div className="section-header">
          <h2>Checklist Templates</h2>
          {permissions.canManageChecklistTemplates && (
            <div className="section-actions">
              <Button variant="primary" icon={Plus} onClick={handleChecklistCreate}>
                Add Template
              </Button>
            </div>
          )}
        </div>

        <div className="checklist-filter-row">
          <Select
            className="checklist-type-filter"
            value={checklistTypeFilter}
            onChange={(e) => setChecklistTypeFilter(e.target.value)}
          >
            <option value="">All Assessment Types</option>
            {assessmentTypes.filter(t => t.active).map(t => (
              <option key={t.id} value={t.id}>{t.name}</option>
            ))}
          </Select>
        </div>

        {checklistError && <ErrorMessage>{checklistError}</ErrorMessage>}

        <DataTable
          columns={checklistColumns}
          data={sortedChecklistTemplates}
          loading={checklistLoading}
          pagination={{ page: 0, pageSize: sortedChecklistTemplates.length || 10, total: sortedChecklistTemplates.length, totalPages: 1 }}
          onPageChange={() => {}}
          onPageSizeChange={() => {}}
          onSearchChange={() => {}}
          searchable={false}
          searchPlaceholder="Search checklist templates"
          emptyMessage="No checklist templates found."
          idAccessor="id"
          sort={checklistSort}
          onSortChange={setChecklistSort}
        />
      </div>
      )}

      {/* ── Survey Templates ─────────────────────────────────────────────── */}
      {activeTab === 'surveys' && permissions.canManageSurveys && (
      <div className="config-section">
        <SurveyConfig embedded />
      </div>
      )}

      {activeTab === 'campaigns' && permissions.canManageCampaigns && (
      <div className="config-section">
        <Campaigns embedded />
      </div>
      )}

      <Modal
        isOpen={showChecklistModal}
        onClose={() => setShowChecklistModal(false)}
        title={checklistModalMode === 'create' ? 'Add Checklist Template' : 'Edit Checklist Template'}
        size="md"
        closeOnOverlayClick={false}
        onSubmit={handleChecklistSubmit}
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowChecklistModal(false)}>Cancel</Button>
            <Button type="submit" variant="primary">
              {checklistModalMode === 'create' ? 'Create' : 'Update'}
            </Button>
          </>
        }
      >
        {checklistError && <ErrorMessage>{checklistError}</ErrorMessage>}

        <FormGroup>
          <FormLabel required>Name</FormLabel>
          <Input
            type="text"
            value={checklistFormData.name}
            onChange={(e) => setChecklistFormData({ ...checklistFormData, name: e.target.value })}
            required
            placeholder="e.g., OWASP Top 10 Checklist"
          />
        </FormGroup>

        <FormGroup>
          <FormLabel required>Assessment Type</FormLabel>
          <Select
            value={checklistFormData.assessmentTypeId}
            onChange={(e) => setChecklistFormData({ ...checklistFormData, assessmentTypeId: e.target.value })}
            required
          >
            <option value="">Select assessment type...</option>
            {assessmentTypes.filter(t => t.active).map(t => (
              <option key={t.id} value={t.id}>{t.name}</option>
            ))}
          </Select>
        </FormGroup>

        <FormGroup>
          <FormLabel>Questions</FormLabel>
          <div className="checklist-questions-list">
            {checklistFormData.questions.map((q, idx) => (
              <div
                key={idx}
                className="checklist-question-row"
                draggable
                onDragStart={() => handleQuestionDragStart(idx)}
                onDragOver={(e) => handleQuestionDragOver(e, idx)}
              >
                <span className="checklist-drag-handle">
                  <GripVertical size={14} />
                </span>
                <Input
                  type="text"
                  value={q.text}
                  onChange={(e) => updateChecklistQuestion(idx, e.target.value)}
                  placeholder={`Question ${idx + 1}`}
                />
                <IconButton
                  icon={X}
                  variant="delete"
                  title="Remove"
                  onClick={() => removeChecklistQuestion(idx)}
                />
              </div>
            ))}
          </div>
          <div className="checklist-add-question-row">
            <Button variant="secondary" icon={Plus} onClick={addChecklistQuestion}>
              Add Question
            </Button>
            <Button variant="secondary" icon={Upload} onClick={() => csvInputRef.current?.click()}>
              Upload CSV
            </Button>
            <input
              ref={csvInputRef}
              type="file"
              accept=".csv,text/csv,text/plain"
              style={{ display: 'none' }}
              onChange={handleCsvUpload}
            />
          </div>
        </FormGroup>

        <Checkbox
          label="Prevent Assessment Closure Unless Completed"
          checked={checklistFormData.preventClosure}
          onChange={(e) => setChecklistFormData({ ...checklistFormData, preventClosure: e.target.checked })}
        />
        <FormHint>
          When enabled, the assessment cannot be finalized until every question in this checklist is answered (Pass, Fail, or N/A).
        </FormHint>
      </Modal>

      <ConfirmDialog
        isOpen={!!checklistToDelete}
        onClose={() => setChecklistToDelete(null)}
        onConfirm={confirmDeleteChecklist}
        title="Delete Checklist Template"
        message="Are you sure you want to delete this checklist template? This cannot be undone."
        confirmText="Delete"
        variant="danger"
        isLoading={deletingChecklist}
      />

      <Modal
        isOpen={showCatModal}
        onClose={() => setShowCatModal(false)}
        title={catModalMode === 'create' ? 'Add Vulnerability Category' : 'Edit Vulnerability Category'}
        size="md"
        closeOnOverlayClick={false}
        onSubmit={handleCatSubmit}
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowCatModal(false)}>
              Cancel
            </Button>
            <Button type="submit" variant="primary">
              {catModalMode === 'create' ? 'Create' : 'Update'}
            </Button>
          </>
        }
      >
        {catError && <ErrorMessage>{catError}</ErrorMessage>}

        <FormGroup>
          <FormLabel required>Name</FormLabel>
          <Input
            type="text"
            value={catFormData.name}
            onChange={(e) => setCatFormData({ ...catFormData, name: e.target.value })}
            required
            placeholder="e.g., Injection"
          />
        </FormGroup>

        <FormGroup>
          <FormLabel>Description</FormLabel>
          <Textarea
            value={catFormData.description}
            onChange={(e) => setCatFormData({ ...catFormData, description: e.target.value })}
            rows={3}
            placeholder="Describe the vulnerability category..."
          />
        </FormGroup>
      </Modal>

      <ConfirmDialog
        isOpen={!!typeToDelete}
        onClose={() => setTypeToDelete(null)}
        onConfirm={confirmDeleteType}
        title="Delete Assessment Type"
        message="Are you sure you want to delete this assessment type? If it is in use, it will be deactivated instead."
        confirmText="Delete"
        variant="danger"
      />

      <ConfirmDialog
        isOpen={!!catToDelete}
        onClose={() => setCatToDelete(null)}
        onConfirm={confirmDeleteCategory}
        title="Delete Vulnerability Category"
        message="Are you sure you want to delete this vulnerability category?"
        confirmText="Delete"
        variant="danger"
      />

    </Page>

    {colorPickerMenu && (
      <div
        className="wf-color-picker"
        style={{ top: colorPickerMenu.y, left: colorPickerMenu.x }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="wf-color-swatches">
          {['#ef4444', '#f97316', '#eab308', '#22c55e', '#14b8a6', '#3b82f6', '#8b5cf6', '#ec4899', '#6b7280'].map((color) => (
            <button
              key={color}
              type="button"
              className="wf-color-swatch"
              style={{ backgroundColor: color }}
              onClick={() => handleWfColorSelect(colorPickerMenu.status, color)}
              title={color}
            />
          ))}
        </div>
        <div className="wf-color-custom-row">
          <label className="wf-color-custom-label">Custom</label>
          <input
            type="color"
            className="wf-color-input"
            value={wfStatusColors[colorPickerMenu.status] || '#3b82f6'}
            onChange={(e) => handleWfColorSelect(colorPickerMenu.status, e.target.value)}
          />
        </div>
        <button
          type="button"
          className="wf-color-reset"
          onClick={() => handleWfColorSelect(colorPickerMenu.status, null)}
        >
          Remove color
        </button>
      </div>
    )}

    {showToast && (
      <Toast key={toastKey} message={toastMessage} variant={toastVariant} onDone={() => setShowToast(false)} />
    )}
    </>
  );
}
