import { useEffect, useState, useCallback, useMemo, useRef } from 'react';
import { Edit2, Trash2, Plus, Eye, EyeOff, Power, GripVertical, X, Upload, Download, Copy } from 'lucide-react';
import { assessmentTypesApi, assessmentsApi, vulnerabilityCategoriesApi, checklistTemplatesApi } from '../api';
import type { AssessmentType, CreateAssessmentTypeRequest, UpdateAssessmentTypeRequest, VulnerabilityCategory, ChecklistTemplate, ChecklistTemplateQuestion } from '../types';

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
} from '../components';
import { usePermissions } from '../utils/permissions';
import Page from '../components/Page';
import ApplicationIdConfig from './ApplicationIdConfig';
import SeverityNamesConfig from './SeverityNamesConfig';
import SurveyConfig from './SurveyConfig';
import Campaigns from './Campaigns';
import './AssessmentConfig.css';
import { usePersistedState } from '../hooks/usePersistedState';
import { useEdition } from '../context/EditionContext';
import { PaidBadge } from '../components/PaidFeature';
import { DEFAULT_WORKFLOW_ID, useWorkflows } from '../hooks/useWorkflow';
import { workflowErrorMessages } from '../utils/workflowErrors';
import { useWorkflowsContext } from '../context/WorkflowsContext';
import WorkflowsTab from './workflows/WorkflowsTab';

const TYPES_TABLE_KEY = 'assessmentConfig.assessmentTypes';
const CATEGORIES_TABLE_KEY = 'assessmentConfig.vulnerabilityCategories';
const CHECKLISTS_TABLE_KEY = 'assessmentConfig.checklistTemplates';

export default function AssessmentConfig() {
  const { permissions } = usePermissions();
  const { hasFeature } = useEdition();
  // Every workflow, archived ones too, so the types table can name whichever one a type is on.
  const { workflows, reload: reloadWorkflows } = useWorkflows(true);
  const workflowById = (id?: string) => workflows.find((w) => w.id === (id || DEFAULT_WORKFLOW_ID));
  // The nine screens elsewhere that render rows from more than one workflow read the shared
  // WorkflowsContext, not this page's own local copy — refresh both after a save/create/archive/delete
  // so an edit made here shows up on them without a full reload.
  const { reload: reloadWorkflowsContext } = useWorkflowsContext();
  const handleWorkflowsChanged = useCallback(() => {
    reloadWorkflows();
    reloadWorkflowsContext();
  }, [reloadWorkflows, reloadWorkflowsContext]);

  // ── Assessment Types ────────────────────────────────────────────────────────
  const [assessmentTypes, setAssessmentTypes] = useState<AssessmentType[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showModal, setShowModal] = useState(false);
  const [modalMode, setModalMode] = useState<'create' | 'edit'>('create');
  const [selectedType, setSelectedType] = useState<AssessmentType | null>(null);
  const [showInactive, setShowInactive] = usePersistedState(TYPES_TABLE_KEY, 'showInactive', false);

  const [pagination, setPagination] = usePersistedState<PaginationInfo>(TYPES_TABLE_KEY, 'pagination', {
    page: 0,
    pageSize: 10,
    total: 0,
    totalPages: 0,
  });

  const [searchQuery, setSearchQuery] = usePersistedState(TYPES_TABLE_KEY, 'search', '');
  const [typeSort, setTypeSort] = usePersistedState<SortState | null>(TYPES_TABLE_KEY, 'sort', null);
  const [catSort, setCatSort] = usePersistedState<SortState | null>(CATEGORIES_TABLE_KEY, 'sort', null);
  const [checklistSort, setChecklistSort] = usePersistedState<SortState | null>(CHECKLISTS_TABLE_KEY, 'sort', null);

  const [formData, setFormData] = useState({
    name: '',
    description: '',
    active: true,
    workflowId: DEFAULT_WORKFLOW_ID,
  });
  // Editing a type onto another workflow: confirmed first, with how many assessments stay where they are.
  const [workflowChange, setWorkflowChange] = useState<{ count: number; from: string; to: string } | null>(null);
  const [savingType, setSavingType] = useState(false);

  // ── Vulnerability Categories ────────────────────────────────────────────────
  const [vulnCategories, setVulnCategories] = useState<VulnerabilityCategory[]>([]);
  const [catLoading, setCatLoading] = useState(true);
  const [catError, setCatError] = useState('');
  const [showCatModal, setShowCatModal] = useState(false);
  const [catModalMode, setCatModalMode] = useState<'create' | 'edit'>('create');
  const [selectedCategory, setSelectedCategory] = useState<VulnerabilityCategory | null>(null);
  const [catSearchQuery, setCatSearchQuery] = usePersistedState(CATEGORIES_TABLE_KEY, 'search', '');
  const [catPagination, setCatPagination] = usePersistedState<PaginationInfo>(CATEGORIES_TABLE_KEY, 'pagination', {
    page: 0,
    pageSize: 10,
    total: 0,
    totalPages: 0,
  });
  const [catFormData, setCatFormData] = useState({ name: '', description: '' });
  const [catToDelete, setCatToDelete] = useState<string | null>(null);

  // ── Assessment Type delete confirm ─────────────────────────────────────────
  const [typeToDelete, setTypeToDelete] = useState<string | null>(null);

  // ── Tabs ────────────────────────────────────────────────────────────────────
  const [activeTab, setActiveTab] = useState<'types' | 'workflows' | 'checklists' | 'surveys' | 'campaigns' | 'applicationIds' | 'severityNames'>('types');
  // The Workflows tab mounts on its first visit and is only hidden afterwards, so unsaved workflow
  // edits survive a look at another tab.
  const [workflowsVisited, setWorkflowsVisited] = useState(false);
  useEffect(() => {
    if (activeTab === 'workflows') setWorkflowsVisited(true);
  }, [activeTab]);
  // ── Checklist Templates ─────────────────────────────────────────────────────
  const [checklistTemplates, setChecklistTemplates] = useState<ChecklistTemplate[]>([]);
  const [checklistLoading, setChecklistLoading] = useState(true);
  const [checklistError, setChecklistError] = useState('');
  const [showChecklistModal, setShowChecklistModal] = useState(false);
  const [checklistModalMode, setChecklistModalMode] = useState<'create' | 'edit'>('create');
  const [selectedChecklist, setSelectedChecklist] = useState<ChecklistTemplate | null>(null);
  const [checklistToDelete, setChecklistToDelete] = useState<string | null>(null);
  const [deletingChecklist, setDeletingChecklist] = useState(false);
  const [checklistTypeFilter, setChecklistTypeFilter] = usePersistedState(CHECKLISTS_TABLE_KEY, 'typeFilter', '');
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
      workflowId: DEFAULT_WORKFLOW_ID,
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
      workflowId: type.workflowId || DEFAULT_WORKFLOW_ID,
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

    // Existing assessments keep their workflow when a type changes workflow, so say how many that is first.
    const previousWorkflowId = selectedType?.workflowId || DEFAULT_WORKFLOW_ID;
    if (modalMode === 'edit' && selectedType && formData.workflowId !== previousWorkflowId) {
      try {
        const res = await assessmentsApi.getAll(0, 1, undefined, undefined, selectedType.id);
        setWorkflowChange({
          count: res.pagination?.totalElements ?? 0,
          from: workflowById(previousWorkflowId)?.name ?? previousWorkflowId,
          to: workflowById(formData.workflowId)?.name ?? formData.workflowId,
        });
      } catch (err: any) {
        setError(err.response?.data?.message || 'Failed to count the assessments of this type');
      }
      return;
    }
    await saveType();
  };

  const saveType = async () => {
    setSavingType(true);
    try {
      if (modalMode === 'create') {
        const createData: CreateAssessmentTypeRequest = {
          name: formData.name,
          description: formData.description,
          active: formData.active,
          workflowId: formData.workflowId,
        };
        await assessmentTypesApi.create(createData);
      } else if (selectedType) {
        const updateData: UpdateAssessmentTypeRequest = {
          name: formData.name,
          description: formData.description,
          active: formData.active,
          workflowId: formData.workflowId,
        };
        await assessmentTypesApi.update(selectedType.id, updateData);
      }

      setWorkflowChange(null);
      setShowModal(false);
      await loadAssessmentTypes();
    } catch (err) {
      setWorkflowChange(null);
      setError(workflowErrorMessages(err, 'Failed to save assessment type').join(' '));
    } finally {
      setSavingType(false);
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
      header: 'Workflow',
      render: (type) => {
        const workflow = workflowById(type.workflowId);
        if (!workflow) return '—';
        return (
          <>
            {workflow.name}
            {workflow.archived && <>{' '}<Badge variant="secondary" size="sm">Archived</Badge></>}
          </>
        );
      },
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
            className={`config-tab-btn${activeTab === 'workflows' ? ' active' : ''}`}
            onClick={() => setActiveTab('workflows')}
          >
            Workflows
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
        {/* Same reasoning: the terminology endpoint writes as super_admin only. */}
        {permissions.canManageSeverityNames && (
          <button
            className={`config-tab-btn${activeTab === 'severityNames' ? ' active' : ''}`}
            onClick={() => setActiveTab('severityNames')}
          >
            Severity Names
          </button>
        )}
      </div>

      {activeTab === 'applicationIds' && <ApplicationIdConfig embedded />}
      {activeTab === 'severityNames' && <SeverityNamesConfig embedded />}

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
          initialSearch={searchQuery}
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
            <Button type="submit" variant="primary" disabled={savingType}>
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

        <FormGroup>
          <FormLabel>Workflow</FormLabel>
          <Select
            value={formData.workflowId}
            onChange={(e) => setFormData({ ...formData, workflowId: e.target.value })}
          >
            {workflows
              .filter((w) => !w.archived || w.id === selectedType?.workflowId)
              .map((w) => (
                <option
                  key={w.id}
                  value={w.id}
                  disabled={!hasFeature('custom_workflows') && w.id !== DEFAULT_WORKFLOW_ID && w.id !== selectedType?.workflowId}
                >
                  {w.name}{w.archived ? ' (archived)' : ''}
                </option>
              ))}
          </Select>
          <FormHint>
            New assessments of this type use this workflow. Existing assessments keep the workflow they have.
            {!hasFeature('custom_workflows') && <>{' '}<PaidBadge label="Other workflows: not in this edition" /></>}
          </FormHint>
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

      <ConfirmDialog
        isOpen={!!workflowChange}
        onClose={() => setWorkflowChange(null)}
        onConfirm={saveType}
        title="Change Workflow"
        message={workflowChange
          ? `New assessments of this type will use "${workflowChange.to}". The ${workflowChange.count} `
            + `existing ${workflowChange.count === 1 ? 'assessment' : 'assessments'} of this type stay on `
            + `"${workflowChange.from}". An existing assessment can be moved from its Finalize section.`
          : ''}
        confirmText="Change Workflow"
        variant="warning"
        isLoading={savingType}
      />

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
          initialSearch={catSearchQuery}
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

      {/* ── Workflows ──────────────────────────────────────────────────────── */}
      {permissions.canManageAssessmentWorkflow && workflowsVisited && (
        <div hidden={activeTab !== 'workflows'}>
          <WorkflowsTab onWorkflowsChanged={handleWorkflowsChanged} active={activeTab === 'workflows'} />
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

    </>
  );
}
