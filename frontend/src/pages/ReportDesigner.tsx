import { useState, useRef, useEffect, useCallback } from 'react';
import { Plus, Upload, Download, ChevronDown, ChevronUp, X, Trash2, Copy, CopyPlus, GripVertical, AlertCircle } from 'lucide-react';
import { Button, IconButton, Input, Select, Toast } from '../components';
import RichTextEditor from '../components/RichTextEditor';
import Page from '../components/Page';
import Modal from '../components/Modal';
import CssEditor from '../components/CssEditor';
import './ReportDesigner.css';
import { reportTemplatesApi, assessmentTypesApi } from '../api';
import type { ReportTemplate, ReportTemplateSummary, UserDefinedField, FieldType, FieldScope, AssessmentType, ScoringType } from '../types';

/** Returns an error message if the CSS has obvious syntax issues, null if it looks valid. */
function validateCSS(css: string): string | null {
  if (!css.trim()) return null;

  // Strip block comments so comment content doesn't confuse the brace counter.
  const noComments = css.replace(/\/\*[\s\S]*?\*\//g, '').trim();
  if (!noComments) return null;

  // 1. Brace balance check.
  let depth = 0;
  for (const ch of noComments) {
    if (ch === '{') depth++;
    else if (ch === '}') {
      if (--depth < 0) return 'Unexpected closing brace';
    }
  }
  if (depth > 0) return `${depth} unclosed rule block${depth > 1 ? 's' : ''}`;

  // 2. Orphaned-content check.
  //    Walk the string tracking nesting depth. Anything at depth-0 is
  //    either a selector (it will be followed by '{') or orphaned text.
  //    Collect segments at depth-0: each segment is pushed when a '{'
  //    opens a new rule. The *last* segment (after the final '}') must be
  //    whitespace-only — if not, the user has content outside any rule block.
  //    If there are no rules at all, the entire string becomes that last segment.
  let current = '';
  const segments: string[] = [];
  depth = 0;
  for (const ch of noComments) {
    if (ch === '{') {
      if (depth === 0) { segments.push(current); current = ''; }
      depth++;
    } else if (ch === '}') {
      depth--;
      if (depth === 0) current = ''; // reset after each rule closes
    } else if (depth === 0) {
      current += ch;
    }
  }
  segments.push(current);

  const trailing = segments[segments.length - 1].trim();
  if (trailing) return 'Content found outside of any rule block';

  return null;
}

export default function ReportDesigner() {
  const [templates, setTemplates] = useState<ReportTemplateSummary[]>([]);
  const [selectedTemplate, setSelectedTemplate] = useState<ReportTemplate | null>(null);
  const [assessmentTypes, setAssessmentTypes] = useState<AssessmentType[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [isDirty, setIsDirty] = useState(false);
  const [toastKey, setToastKey] = useState(0);
  const [showToast, setShowToast] = useState(false);
  const [toastMessage, setToastMessage] = useState('Template saved');
  const [toastVariant, setToastVariant] = useState<'success' | 'warning'>('success');
  const [error, setError] = useState<string | null>(null);
  const [cssExpanded, setCssExpanded] = useState(false);
  // Clone dialog: the template being duplicated, plus the name to give the copy.
  const [cloneSource, setCloneSource] = useState<ReportTemplateSummary | null>(null);
  const [cloneName, setCloneName] = useState('');
  const [cloning, setCloning] = useState(false);
  const [cloneError, setCloneError] = useState<string | null>(null);
  const [localCss, setLocalCss] = useState('');
  const [isDragging, setIsDragging] = useState(false);
  const [assessmentTypeSearch, setAssessmentTypeSearch] = useState('');
  const [assessmentTypeDropdownOpen, setAssessmentTypeDropdownOpen] = useState(false);
  const [pointerDrag, setPointerDrag] = useState<{
    fieldIndex: number;
    cursorX: number;
    cursorY: number;
    fieldName: string;
    fieldType: FieldType;
  } | null>(null);
  const pointerDragRef = useRef<typeof pointerDrag>(null);
  const fieldRefs = useRef<Map<string, HTMLDivElement>>(new Map());
  const reorderLockRef = useRef(false);
  const [sectionDrag, setSectionDrag] = useState<{ index: number } | null>(null);
  const sectionDragRef = useRef<{ index: number } | null>(null);
  const sectionRefs = useRef<Map<number, HTMLDivElement>>(new Map());
  const sectionReorderLockRef = useRef(false);
  const [newSectionInput, setNewSectionInput] = useState('');
  const updateTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const selectedTemplateRef = useRef<ReportTemplate | null>(null);

  // Keep ref in sync with state
  useEffect(() => {
    selectedTemplateRef.current = selectedTemplate;
  }, [selectedTemplate]);

  // Load templates and assessment types on mount
  useEffect(() => {
    loadData();
  }, []);

  // Cleanup timeout on unmount
  useEffect(() => {
    return () => {
      if (updateTimeoutRef.current) {
        clearTimeout(updateTimeoutRef.current);
      }
    };
  }, []);

  const loadData = async () => {
    try {
      setLoading(true);
      setError(null);

      // Load templates and assessment types in parallel
      const [templatesRes, assessmentTypesRes] = await Promise.all([
        reportTemplatesApi.getAll(0, 100, undefined, undefined, true),
        assessmentTypesApi.getAll(0, 100)
      ]);

      if (templatesRes.success && templatesRes.data) {
        setTemplates(templatesRes.data);
        // Load first template details if available
        if (templatesRes.data.length > 0) {
          await loadTemplateDetails(templatesRes.data[0].id);
        }
      }

      if (assessmentTypesRes.success && assessmentTypesRes.data) {
        setAssessmentTypes(assessmentTypesRes.data);
      }
    } catch (err: any) {
      const errorMessage = err.response?.data?.message || err.response?.data?.error || 'Failed to load data';
      setError(errorMessage);
      console.error('Error loading data:', err);
    } finally {
      setLoading(false);
    }
  };

  const loadTemplateDetails = async (id: string) => {
    setIsDirty(false);
    try {
      const response = await reportTemplatesApi.getById(id);
      if (response.success && response.data) {
        setSelectedTemplate(response.data);
        setLocalCss(response.data.css || '');
      }
    } catch (err: any) {
      const errorMessage = err.response?.data?.message || err.response?.data?.error || 'Failed to load template details';
      setError(errorMessage);
      console.error('Error loading template:', err);
    }
  };

  const handleCreateTemplate = async () => {
    try {
      setSaving(true);
      setError(null);

      // Check if assessment types are loaded
      if (assessmentTypes.length === 0) {
        setError('No assessment types available. Please create an assessment type first.');
        setSaving(false);
        return;
      }

      // Create with at least one default field (required by backend validation).
      // css is deliberately omitted — the backend fills in the default report
      // stylesheet (ReportTemplateService.DEFAULT_TEMPLATE_CSS) when none is sent.
      const newTemplate = {
        name: 'New Report Template',
        description: '',
        assessmentTypeId: assessmentTypes[0].id,
        userDefinedFields: []
      };

      const response = await reportTemplatesApi.create(newTemplate);

      if (response.success && response.data) {
        // Reload templates list
        await loadData();
        // Select the new template
        await loadTemplateDetails(response.data.id);
      }
    } catch (err: any) {
      const errorMessage = err.response?.data?.message || err.response?.data?.error || 'Failed to create template';
      setError(errorMessage);
      console.error('Error creating template:', err);
      console.error('Error response:', err.response?.data);
    } finally {
      setSaving(false);
    }
  };

  // ── Clone ────────────────────────────────────────────────────────────────
  const openCloneDialog = (template: ReportTemplateSummary) => {
    setCloneSource(template);
    // Suggest a free name up front: "(Copy)", then "(Copy 2)", "(Copy 3)"… so repeated
    // duplication of the same template doesn't bounce off the unique-name check every time.
    const base = `${template.name} (Copy`;
    const taken = new Set(templates.map((t) => t.name));
    let suggestion = `${base})`;
    for (let n = 2; taken.has(suggestion); n++) suggestion = `${base} ${n})`;
    setCloneName(suggestion);
    setCloneError(null);
  };

  const handleCloneTemplate = async () => {
    if (!cloneSource) return;
    const name = cloneName.trim();
    if (!name) {
      setCloneError('A name is required.');
      return;
    }
    try {
      setCloning(true);
      setCloneError(null);

      const response = await reportTemplatesApi.clone(cloneSource.id, name);

      setCloneSource(null);
      await loadData();
      // Open the copy so it's obvious what happened and it can be edited straight away.
      if (response.success && response.data) {
        await loadTemplateDetails(response.data.id);
      }
    } catch (err: any) {
      setCloneError(err.response?.data?.message || err.response?.data?.error || 'Failed to duplicate template');
    } finally {
      setCloning(false);
    }
  };

  const handleDeleteTemplate = async (id: string) => {
    if (!confirm('Are you sure you want to delete this template?')) return;

    try {
      setSaving(true);
      setError(null);

      await reportTemplatesApi.delete(id);

      // Reload templates
      await loadData();
    } catch (err: any) {
      const errorMessage = err.response?.data?.message || err.response?.data?.error || 'Failed to delete template';
      setError(errorMessage);
      console.error('Error deleting template:', err);
    } finally {
      setSaving(false);
    }
  };

  const updateTemplate = useCallback((updates: Partial<ReportTemplate>, immediate: boolean = false) => {
    const currentTemplate = selectedTemplateRef.current;
    if (!currentTemplate) return;

    const templateId = currentTemplate.id;

    // Mark unsaved changes immediately
    setIsDirty(true);

    // Clear existing timeout
    if (updateTimeoutRef.current) {
      clearTimeout(updateTimeoutRef.current);
    }

    const saveChanges = async () => {
      try {
        setSaving(true);
        setError(null);

        await reportTemplatesApi.update(templateId, updates);

        // Only reload sidebar list - DON'T update selectedTemplate to prevent focus loss
        const templatesRes = await reportTemplatesApi.getAll(0, 100, undefined, undefined, true);
        if (templatesRes.success && templatesRes.data) {
          setTemplates(templatesRes.data);
        }

        setSaving(false);
        setIsDirty(false);
        const cssError = 'css' in updates && updates.css != null
          ? validateCSS(updates.css)
          : null;
        setToastMessage(cssError ? `Saved — CSS warning: ${cssError}` : 'Template saved');
        setToastVariant(cssError ? 'warning' : 'success');
        setToastKey(k => k + 1);
        setShowToast(true);
      } catch (err: any) {
        const errorMessage = err.response?.data?.message || err.response?.data?.error || 'Failed to update template';
        setError(errorMessage);
        console.error('Error updating template:', err);
        setSaving(false);

        // Only reload on error to restore correct state
        await loadTemplateDetails(templateId);
      }
    };

    if (immediate) {
      saveChanges();
    } else {
      // Debounce the API call
      updateTimeoutRef.current = setTimeout(saveChanges, 1000);
    }
  }, []); // No dependencies - use ref instead

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file && selectedTemplate) {
      try {
        setSaving(true);
        setError(null);

        const response = await reportTemplatesApi.uploadFile(selectedTemplate.id, file);

        if (response.success && response.data) {
          setSelectedTemplate(response.data);
        }
      } catch (err: any) {
        const errorMessage = err.response?.data?.message || err.response?.data?.error || 'Failed to upload file';
        setError(errorMessage);
        console.error('Error uploading file:', err);
      } finally {
        setSaving(false);
      }
    }
  };

  const handleDownloadTemplate = async () => {
    if (!selectedTemplate?.templateFileId) return;

    try {
      const blob = await reportTemplatesApi.downloadFile(selectedTemplate.id);
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = selectedTemplate.templateFileName || 'template.docx';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      window.URL.revokeObjectURL(url);
    } catch (err: any) {
      const errorMessage = err.response?.data?.message || err.response?.data?.error || 'Failed to download file';
      setError(errorMessage);
      console.error('Error downloading file:', err);
    }
  };

  const addUserDefinedField = (scope: FieldScope) => {
    if (!selectedTemplate) return;
    const newField: UserDefinedField = {
      id: Date.now().toString(), // Temporary ID
      variableName: 'new_field',
      displayName: 'New Field',
      fieldType: 'STRING',
      fieldScope: scope,
    };
    const updatedFields = [...selectedTemplate.userDefinedFields, newField];

    // Update state immediately for structural changes (so new field appears)
    setSelectedTemplate({ ...selectedTemplate, userDefinedFields: updatedFields });

    // Update ref
    if (selectedTemplateRef.current) {
      selectedTemplateRef.current.userDefinedFields = updatedFields;
    }

    // Save to backend
    updateTemplate({ userDefinedFields: updatedFields }, true);
  };

  const updateUserDefinedField = (id: string, updates: Partial<UserDefinedField>) => {
    const currentTemplate = selectedTemplateRef.current;
    if (!currentTemplate) return;

    const updatedFields = currentTemplate.userDefinedFields.map((f) =>
      f.id === id ? { ...f, ...updates } : f
    );

    // Always update ref so the next debounced save sees the latest values
    selectedTemplateRef.current = { ...currentTemplate, userDefinedFields: updatedFields };

    // Mirror into state on every edit, text included. Read-only UI derived from the field —
    // the card's title and the "Variable" row's ${...} snippet and copy button — renders from
    // state, so without this it keeps showing the values from before the edit (a renamed field
    // still advertised ${new_field} until the template was reloaded). Safe for typing: the
    // inputs are uncontrolled (defaultValue with a stable key) inside a row keyed by field.id,
    // so re-rendering never remounts them and leaves the typed text and caret alone.
    setSelectedTemplate({ ...currentTemplate, userDefinedFields: updatedFields });

    // Structural changes (fieldType) toggle conditional UI, so persist them right away;
    // text edits debounce.
    updateTemplate({ userDefinedFields: updatedFields }, 'fieldType' in updates);
  };

  const deleteUserDefinedField = (id: string) => {
    if (!selectedTemplate) return;
    const updatedFields = selectedTemplate.userDefinedFields.filter((f) => f.id !== id);

    // Update state immediately for structural changes (so field disappears)
    setSelectedTemplate({ ...selectedTemplate, userDefinedFields: updatedFields });

    // Update ref
    if (selectedTemplateRef.current) {
      selectedTemplateRef.current.userDefinedFields = updatedFields;
    }

    // Save to backend
    updateTemplate({ userDefinedFields: updatedFields }, true);
  };

  const addDropdownOption = (fieldId: string, option: string) => {
    if (!selectedTemplate) return;
    const updatedFields = selectedTemplate.userDefinedFields.map((f) =>
      f.id === fieldId
        ? { ...f, dropdownOptions: [...(f.dropdownOptions || []), option] }
        : f
    );

    // Update state for structural change
    setSelectedTemplate({ ...selectedTemplate, userDefinedFields: updatedFields });

    // Update ref
    if (selectedTemplateRef.current) {
      selectedTemplateRef.current.userDefinedFields = updatedFields;
    }

    // Save to backend
    updateTemplate({ userDefinedFields: updatedFields }, true);
  };

  const removeDropdownOption = (fieldId: string, optionIndex: number) => {
    if (!selectedTemplate) return;
    const updatedFields = selectedTemplate.userDefinedFields.map((f) =>
      f.id === fieldId
        ? { ...f, dropdownOptions: f.dropdownOptions?.filter((_, i) => i !== optionIndex) }
        : f
    );

    // Update state for structural change
    setSelectedTemplate({ ...selectedTemplate, userDefinedFields: updatedFields });

    // Update ref
    if (selectedTemplateRef.current) {
      selectedTemplateRef.current.userDefinedFields = updatedFields;
    }

    // Save to backend
    updateTemplate({ userDefinedFields: updatedFields }, true);
  };

  const addSection = () => {
    const name = newSectionInput.trim();
    if (!name || !selectedTemplate) return;
    const updatedSections = [...(selectedTemplate.sections ?? []), name];
    setSelectedTemplate({ ...selectedTemplate, sections: updatedSections });
    if (selectedTemplateRef.current) selectedTemplateRef.current.sections = updatedSections;
    setNewSectionInput('');
    updateTemplate({ sections: updatedSections }, true);
  };

  const deleteSection = (index: number) => {
    if (!selectedTemplate) return;
    const updatedSections = (selectedTemplate.sections ?? []).filter((_, i) => i !== index);
    setSelectedTemplate({ ...selectedTemplate, sections: updatedSections });
    if (selectedTemplateRef.current) selectedTemplateRef.current.sections = updatedSections;
    updateTemplate({ sections: updatedSections }, true);
  };

  const handleSectionPointerDown = (e: React.PointerEvent<HTMLDivElement>, index: number) => {
    e.preventDefault();
    const drag = { index };
    sectionDragRef.current = drag;
    sectionReorderLockRef.current = false;
    setSectionDrag(drag);
  };

  const copyVariableToClipboard = (variableName: string) => {
    const variableText = `\${${variableName}}`;
    navigator.clipboard.writeText(variableText).then(() => {
      alert(`Copied: ${variableText}`);
    }).catch((err) => {
      console.error('Failed to copy:', err);
    });
  };

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(true);
  };

  const handleDragLeave = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
  };

  const handleDrop = async (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);

    const files = e.dataTransfer.files;
    if (files && files[0] && selectedTemplate) {
      const file = files[0];
      if (file.name.endsWith('.docx')) {
        try {
          setSaving(true);
          const response = await reportTemplatesApi.uploadFile(selectedTemplate.id, file);
          if (response.success && response.data) {
            setSelectedTemplate(response.data);
          }
        } catch (err: any) {
          const errorMessage = err.response?.data?.message || err.response?.data?.error || 'Failed to upload file';
          setError(errorMessage);
        } finally {
          setSaving(false);
        }
      } else {
        alert('Please upload a .docx file');
      }
    }
  };

  const handlePointerDown = (e: React.PointerEvent<HTMLDivElement>, index: number, field: UserDefinedField) => {
    e.preventDefault();
    const drag = {
      fieldIndex: index,
      cursorX: e.clientX,
      cursorY: e.clientY,
      fieldName: field.displayName || 'New Field',
      fieldType: field.fieldType,
    };
    pointerDragRef.current = drag;
    reorderLockRef.current = false;
    setPointerDrag(drag);
  };

  // Document-level pointer listeners — mounted while a field drag is active.
  // Using document listeners instead of React synthetic events means pointerup
  // always fires even if the component re-renders mid-drag or the cursor leaves
  // the drag handle element.
  const isDraggingField = !!pointerDrag;
  useEffect(() => {
    if (!isDraggingField) return;

    const onMove = (e: PointerEvent) => {
      const drag = pointerDragRef.current;
      if (!drag) return;
      const template = selectedTemplateRef.current;
      if (!template) return;

      const updatedDrag = { ...drag, cursorX: e.clientX, cursorY: e.clientY };
      pointerDragRef.current = updatedDrag;
      setPointerDrag(updatedDrag);

      // Lock after each swap until React re-renders and DOM positions are
      // fresh — prevents oscillation when dragging downward.
      if (reorderLockRef.current) return;

      for (const [fieldId, el] of fieldRefs.current.entries()) {
        const rect = el.getBoundingClientRect();
        if (e.clientY >= rect.top && e.clientY <= rect.bottom) {
          const targetIndex = template.userDefinedFields.findIndex(f => f.id === fieldId);
          if (targetIndex === -1 || targetIndex === drag.fieldIndex) break;

          const draggedScope = template.userDefinedFields[drag.fieldIndex]?.fieldScope ?? 'ASSESSMENT';
          const targetScope = template.userDefinedFields[targetIndex]?.fieldScope ?? 'ASSESSMENT';
          if (draggedScope !== targetScope) break;

          const fields = [...template.userDefinedFields];
          const [draggedField] = fields.splice(drag.fieldIndex, 1);
          fields.splice(targetIndex, 0, draggedField);

          const newTemplate = { ...template, userDefinedFields: fields };
          selectedTemplateRef.current = newTemplate;
          setSelectedTemplate(newTemplate);

          const newDrag = { ...updatedDrag, fieldIndex: targetIndex };
          pointerDragRef.current = newDrag;
          setPointerDrag(newDrag);

          reorderLockRef.current = true;
          requestAnimationFrame(() => requestAnimationFrame(() => {
            reorderLockRef.current = false;
          }));
          break;
        }
      }
    };

    const onUp = () => {
      const template = selectedTemplateRef.current;
      if (template) {
        updateTemplate({ userDefinedFields: template.userDefinedFields });
      }
      pointerDragRef.current = null;
      setPointerDrag(null);
    };

    document.addEventListener('pointermove', onMove);
    document.addEventListener('pointerup', onUp);
    document.addEventListener('pointercancel', onUp);
    return () => {
      document.removeEventListener('pointermove', onMove);
      document.removeEventListener('pointerup', onUp);
      document.removeEventListener('pointercancel', onUp);
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isDraggingField]);

  const isSectionDragging = !!sectionDrag;
  useEffect(() => {
    if (!isSectionDragging) return;

    const onMove = (e: PointerEvent) => {
      const drag = sectionDragRef.current;
      if (!drag) return;
      const template = selectedTemplateRef.current;
      if (!template) return;

      if (sectionReorderLockRef.current) return;

      for (const [idx, el] of sectionRefs.current.entries()) {
        const rect = el.getBoundingClientRect();
        if (e.clientY >= rect.top && e.clientY <= rect.bottom) {
          if (idx === drag.index) break;

          const sections = [...(template.sections ?? [])];
          const [dragged] = sections.splice(drag.index, 1);
          sections.splice(idx, 0, dragged);

          const newTemplate = { ...template, sections };
          selectedTemplateRef.current = newTemplate;
          setSelectedTemplate(newTemplate);

          const newDrag = { index: idx };
          sectionDragRef.current = newDrag;
          setSectionDrag(newDrag);

          sectionReorderLockRef.current = true;
          requestAnimationFrame(() => requestAnimationFrame(() => {
            sectionReorderLockRef.current = false;
          }));
          break;
        }
      }
    };

    const onUp = () => {
      const template = selectedTemplateRef.current;
      if (template) updateTemplate({ sections: template.sections ?? [] });
      sectionDragRef.current = null;
      setSectionDrag(null);
    };

    document.addEventListener('pointermove', onMove);
    document.addEventListener('pointerup', onUp);
    document.addEventListener('pointercancel', onUp);
    return () => {
      document.removeEventListener('pointermove', onMove);
      document.removeEventListener('pointerup', onUp);
      document.removeEventListener('pointercancel', onUp);
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isSectionDragging]);

  const getAssessmentTypeLabel = (id: string) => {
    return assessmentTypes.find(at => at.id === id)?.name || '';
  };

  // Strip corrupted placeholder text that old editor versions saved as content
  const sanitizeRichDefault = (val: string | undefined): string => {
    if (!val) return '';
    const trimmed = val.trim();
    if (trimmed === 'Write\nPreview' || trimmed === 'Write\n\nPreview') return '';
    return val;
  };

  if (loading) {
    return <div className="report-designer"><div className="loading">Loading templates...</div></div>;
  }

  return (
    <>
    <Page variant="flush" fill className="report-designer">
      {/* Error Display */}
      {error && (
        <div className="error-banner">
          <AlertCircle size={20} />
          <span>{error}</span>
          <button onClick={() => setError(null)}><X size={16} /></button>
        </div>
      )}

      {/* Left Sidebar - Template List */}
      <div className="template-sidebar">
        <div className="sidebar-header">
          <Button onClick={handleCreateTemplate} icon={Plus} size="sm" disabled={saving}>
            New Template
          </Button>
        </div>
        <div className="template-list">
          {templates.map((template) => (
            <div
              key={template.id}
              className={`template-item ${selectedTemplate?.id === template.id ? 'active' : ''}`}
              onClick={() => loadTemplateDetails(template.id)}
            >
              <div className="template-item-content">
                <div className="template-name">{template.name}</div>
                {template.templateFileName && (
                  <div className="template-file">{template.templateFileName}</div>
                )}
                <div className="template-meta">
                  v{template.version} • {template.fieldCount} fields
                </div>
              </div>
              <IconButton
                icon={CopyPlus}
                onClick={(e) => {
                  e.stopPropagation();
                  openCloneDialog(template);
                }}
                variant="default"
                title="Duplicate template"
                disabled={saving}
              />
              <IconButton
                icon={Trash2}
                onClick={(e) => {
                  e.stopPropagation();
                  handleDeleteTemplate(template.id);
                }}
                variant="delete"
                title="Delete template"
                disabled={saving}
              />
            </div>
          ))}
          {templates.length === 0 && (
            <div className="empty-state">
              No templates yet. Click "New Template" to create one.
            </div>
          )}
        </div>
      </div>

      {/* Main Content - Template Editor */}
      <div className="template-editor">
        {selectedTemplate ? (
          <div className="editor-content">
            {saving && <div className="save-status">Saving...</div>}

            {/* ── Template Information ─────────────────────────────────── */}
            <div className="rd-section">
              <div className="rd-section-header">
                <span>Template Information{isDirty && <span className="unsaved-indicator"> *</span>}</span>
              </div>
              <div className="rd-body">
                <div className="rd-row">
                  <div className="rd-label">Template Name <span className="rd-required">*</span></div>
                  <div className="rd-value">
                    <Input
                      key={`name-${selectedTemplate.id}`}
                      defaultValue={selectedTemplate.name}
                      onChange={(e) => updateTemplate({ name: e.target.value })}
                      placeholder="Enter template name"
                    />
                  </div>
                </div>
                <div className="rd-row">
                  <div className="rd-label">Description</div>
                  <div className="rd-value">
                    <Input
                      key={`desc-${selectedTemplate.id}`}
                      defaultValue={selectedTemplate.description || ''}
                      onChange={(e) => updateTemplate({ description: e.target.value })}
                      placeholder="Enter template description"
                    />
                  </div>
                </div>
                <div className="rd-row">
                  <div className="rd-label">Assessment Type <span className="rd-required">*</span></div>
                  <div className="rd-value">
                    <div className="searchable-select">
                      <Input
                        value={
                          assessmentTypeDropdownOpen
                            ? assessmentTypeSearch
                            : getAssessmentTypeLabel(selectedTemplate.assessmentTypeId)
                        }
                        onChange={(e) => setAssessmentTypeSearch(e.target.value)}
                        onFocus={() => { setAssessmentTypeDropdownOpen(true); setAssessmentTypeSearch(''); }}
                        onBlur={() => { setTimeout(() => setAssessmentTypeDropdownOpen(false), 200); }}
                        placeholder="Search or select assessment type"
                      />
                      {assessmentTypeDropdownOpen && (
                        <div className="searchable-select-dropdown">
                          {assessmentTypes
                            .filter(at => at.name.toLowerCase().includes(assessmentTypeSearch.toLowerCase()))
                            .map(at => (
                              <div
                                key={at.id}
                                className="searchable-select-option"
                                onClick={() => {
                                  updateTemplate({ assessmentTypeId: at.id });
                                  setAssessmentTypeDropdownOpen(false);
                                  setAssessmentTypeSearch('');
                                }}
                              >
                                {at.name}
                              </div>
                            ))}
                          {assessmentTypes.filter(at =>
                            at.name.toLowerCase().includes(assessmentTypeSearch.toLowerCase())
                          ).length === 0 && (
                            <div className="searchable-select-option searchable-select-empty">No results found</div>
                          )}
                        </div>
                      )}
                    </div>
                  </div>
                </div>
                <div className="rd-row">
                  <div className="rd-label">Scoring Type</div>
                  <div className="rd-value">
                    <Select
                      value={selectedTemplate.scoringType ?? 'NATIVE'}
                      onChange={(e) => {
                        const st = e.target.value as ScoringType;
                        setSelectedTemplate({ ...selectedTemplate, scoringType: st });
                        if (selectedTemplateRef.current) {
                          selectedTemplateRef.current = { ...selectedTemplateRef.current, scoringType: st };
                        }
                        updateTemplate({ scoringType: st }, true);
                      }}
                    >
                      <option value="NATIVE">Native</option>
                      <option value="CVSS_31">CVSS 3.1</option>
                      <option value="CVSS_40">CVSS 4.0</option>
                    </Select>
                  </div>
                </div>
                <div className="rd-row rd-row--top">
                  <div className="rd-label">Document Template</div>
                  <div className="rd-value">
                    <div
                      className={`drop-zone ${isDragging ? 'dragging' : ''}`}
                      onDragOver={handleDragOver}
                      onDragLeave={handleDragLeave}
                      onDrop={handleDrop}
                    >
                      <p>Drag and drop a .docx file here (up to 1 GB)</p>
                    </div>
                    <div className="template-upload-section">
                      <label className="upload-button">
                        <Upload size={18} />
                        Upload Template
                        <input
                          type="file"
                          accept=".docx"
                          onChange={handleFileUpload}
                          style={{ display: 'none' }}
                          disabled={saving}
                        />
                      </label>
                      {selectedTemplate.templateFileName && (
                        <>
                          <Button onClick={handleDownloadTemplate} icon={Download} variant="secondary" disabled={saving}>
                            Download
                          </Button>
                          <span className="template-filename">
                            {selectedTemplate.templateFileName}
                            {selectedTemplate.templateFileSize &&
                              ` (${(selectedTemplate.templateFileSize / 1024 / 1024).toFixed(2)} MB)`
                            }
                          </span>
                        </>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            </div>

            {/* ── Custom CSS ────────────────────────────────────────────── */}
            <div className="rd-section">
              <div className="rd-section-header">
                <span>Custom CSS Formatting{isDirty && <span className="unsaved-indicator"> *</span>}</span>
                <Button
                  onClick={() => setCssExpanded(!cssExpanded)}
                  icon={cssExpanded ? ChevronUp : ChevronDown}
                  variant="secondary"
                  size="sm"
                >
                  {cssExpanded ? 'Collapse' : 'Expand'}
                </Button>
              </div>
              <div className="rd-body">
                <div className="rd-row">
                  <div className="rd-label">Report Font</div>
                  <div className="rd-value">
                    <input
                      type="text"
                      key={`font-${selectedTemplate.id}`}
                      className="rd-input"
                      defaultValue={selectedTemplate.font ?? ''}
                      onChange={(e) => updateTemplate({ font: e.target.value })}
                      placeholder="e.g. Arial"
                    />
                  </div>
                </div>
                <div className="rd-row rd-row--top">
                  <div className="rd-label">CSS</div>
                  <div className="rd-value">
                    <div className="css-editor">
                      {cssExpanded ? (
                        <CssEditor
                          key={`css-${selectedTemplate.id}`}
                          value={localCss}
                          onChange={(next) => { setLocalCss(next); updateTemplate({ css: next }); }}
                          placeholder="/* Add your custom CSS here */"
                        />
                      ) : (
                        <pre className="css-preview"><code>{localCss || '/* No CSS defined */'}</code></pre>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            </div>

            {/* ── Sections ─────────────────────────────────────────────── */}
            <div className="rd-section">
              <div className="rd-section-header">
                <span>Sections</span>
              </div>
              <div className="rd-body">
                <p className="rd-help-text">
                  Sections group vulnerabilities in the assessment. Vulnerabilities are assigned to a section and will appear under separate sub-menus in the assessment view.
                </p>
                <div className="rd-fields">
                  {(selectedTemplate.sections ?? []).map((section, idx) => (
                    <div
                      key={idx}
                      ref={(el) => {
                        if (el) sectionRefs.current.set(idx, el);
                        else sectionRefs.current.delete(idx);
                      }}
                      className={`rd-field ${sectionDrag?.index === idx ? 'rd-field--dragging' : ''}`}
                    >
                      <div className="rd-field-header">
                        <div
                          className="rd-drag-handle"
                          onPointerDown={(e) => handleSectionPointerDown(e, idx)}
                        >
                          <GripVertical size={16} />
                        </div>
                        <span className="rd-field-name">{section}</span>
                        <IconButton
                          icon={Trash2}
                          onClick={() => deleteSection(idx)}
                          variant="delete"
                          title="Remove section"
                          disabled={saving}
                        />
                      </div>
                    </div>
                  ))}
                </div>
                <div className="rd-add-section-row">
                  <Input
                    value={newSectionInput}
                    onChange={(e) => setNewSectionInput(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') addSection(); }}
                    placeholder="New section name..."
                  />
                  <Button
                    onClick={addSection}
                    icon={Plus}
                    size="sm"
                    disabled={saving || !newSectionInput.trim()}
                  >
                    Add Section
                  </Button>
                </div>
              </div>
            </div>

            {/* ── User Defined Fields ───────────────────────────────────── */}
            {(() => {
              const renderFieldItem = (field: UserDefinedField, fullIndex: number) => (
                <div
                  key={field.id || fullIndex}
                  ref={(el) => {
                    if (el) fieldRefs.current.set(field.id, el);
                    else fieldRefs.current.delete(field.id);
                  }}
                  className={`rd-field ${pointerDrag?.fieldIndex === fullIndex ? 'rd-field--dragging' : ''}`}
                >
                  <div className="rd-field-header">
                    <div
                      className="rd-drag-handle"
                      onPointerDown={(e) => handlePointerDown(e, fullIndex, field)}
                    >
                      <GripVertical size={16} />
                    </div>
                    <span className="rd-field-name">{field.displayName || 'New Field'}</span>
                    <IconButton
                      icon={Trash2}
                      onClick={() => deleteUserDefinedField(field.id)}
                      variant="delete"
                      title="Remove field"
                      disabled={saving}
                    />
                  </div>
                  <div className="rd-field-body">
                    <div className="rd-row">
                      <div className="rd-label">Display Name</div>
                      <div className="rd-value">
                        <Input
                          key={`display-${field.id}`}
                          defaultValue={field.displayName}
                          onChange={(e) => {
                            const displayName = e.target.value;
                            const variableName = displayName
                              .toLowerCase()
                              .replace(/\s+/g, '-')
                              .replace(/[^a-z0-9-]/g, '');
                            updateUserDefinedField(field.id, { displayName, variableName });
                            const varInput = document.querySelector(`[data-field-var-id="${field.id}"]`) as HTMLInputElement | null;
                            if (varInput) varInput.value = variableName;
                          }}
                          placeholder="Display Name"
                        />
                      </div>
                    </div>
                    <div className="rd-row">
                      <div className="rd-label">Variable Name</div>
                      <div className="rd-value">
                        <Input
                          key={`variable-${field.id}`}
                          defaultValue={field.variableName}
                          data-field-var-id={field.id}
                          onChange={(e) => updateUserDefinedField(field.id, { variableName: e.target.value })}
                          placeholder="variable_name"
                        />
                      </div>
                    </div>
                    <div className="rd-row">
                      <div className="rd-label">Field Type</div>
                      <div className="rd-value">
                        <Select
                          value={field.fieldType}
                          onChange={(e) => updateUserDefinedField(field.id, { fieldType: e.target.value as FieldType })}
                          disabled={saving}
                        >
                          <option value="STRING">String</option>
                          <option value="DROPDOWN">Dropdown</option>
                          <option value="RICH_TEXT">Rich Text</option>
                        </Select>
                      </div>
                    </div>
                    <div className="rd-row rd-row--top">
                      <div className="rd-label">Default Value</div>
                      <div className="rd-value">
                        {field.fieldType === 'STRING' && (
                          <Input
                            key={`default-${field.id}`}
                            defaultValue={field.defaultValue || ''}
                            onChange={(e) => updateUserDefinedField(field.id, { defaultValue: e.target.value })}
                            placeholder="Enter default value"
                          />
                        )}
                        {field.fieldType === 'RICH_TEXT' && (
                          <RichTextEditor
                            key={`rich-${field.id}`}
                            value={sanitizeRichDefault(field.defaultValue)}
                            onChange={(val) => updateUserDefinedField(field.id, { defaultValue: val })}
                          />
                        )}
                        {field.fieldType === 'DROPDOWN' && field.dropdownOptions && field.dropdownOptions.length > 0 && (
                          <Select
                            value={field.defaultValue || ''}
                            onChange={(e) => updateUserDefinedField(field.id, { defaultValue: e.target.value })}
                            disabled={saving}
                          >
                            <option value="">Select default option</option>
                            {field.dropdownOptions.map((option) => (
                              <option key={option} value={option}>{option}</option>
                            ))}
                          </Select>
                        )}
                        {field.fieldType === 'DROPDOWN' && (!field.dropdownOptions || field.dropdownOptions.length === 0) && (
                          <span className="rd-hint">Add options below first</span>
                        )}
                      </div>
                    </div>
                    {field.fieldType === 'DROPDOWN' && (
                      <div className="rd-row rd-row--top">
                        <div className="rd-label">Options</div>
                        <div className="rd-value">
                          <div className="options-list">
                            {field.dropdownOptions?.map((option, idx) => (
                              <div key={idx} className="option-item">
                                <span>{option}</span>
                                <IconButton
                                  icon={X}
                                  onClick={() => removeDropdownOption(field.id, idx)}
                                  variant="delete"
                                  title="Remove option"
                                  disabled={saving}
                                />
                              </div>
                            ))}
                          </div>
                          <div className="add-option">
                            <Input
                              placeholder="New option (press Enter to add)"
                              onKeyPress={(e) => {
                                if (e.key === 'Enter') {
                                  const input = e.target as HTMLInputElement;
                                  if (input.value.trim()) {
                                    addDropdownOption(field.id, input.value.trim());
                                    input.value = '';
                                  }
                                }
                              }}
                            />
                          </div>
                        </div>
                      </div>
                    )}
                    <div className="rd-row">
                      <div className="rd-label">Variable</div>
                      <div className="rd-value rd-usage">
                        <code className="rd-usage-code">{`\${${field.variableName}}`}</code>
                        <IconButton
                          icon={Copy}
                          onClick={() => copyVariableToClipboard(field.variableName)}
                          variant="default"
                          title="Copy variable to clipboard"
                        />
                      </div>
                    </div>
                  </div>
                </div>
              );

              const assessmentFields = selectedTemplate.userDefinedFields.filter(
                (f) => (f.fieldScope ?? 'ASSESSMENT') === 'ASSESSMENT'
              );
              const vulnerabilityFields = selectedTemplate.userDefinedFields.filter(
                (f) => f.fieldScope === 'VULNERABILITY'
              );

              return (
                <>
                  <div className="rd-section">
                    <div className="rd-section-header">
                      <span>User Defined Fields — Assessments{isDirty && <span className="unsaved-indicator"> *</span>}</span>
                    </div>
                    <div className="rd-fields">
                      {assessmentFields.map((field) => {
                        const fullIndex = selectedTemplate.userDefinedFields.findIndex((f) => f.id === field.id);
                        return renderFieldItem(field, fullIndex);
                      })}
                      {assessmentFields.length === 0 && (
                        <div className="empty-state">No assessment fields yet. Click "Add Field" to create one.</div>
                      )}
                    </div>
                    <div className="rd-section-footer">
                      <Button onClick={() => addUserDefinedField('ASSESSMENT')} icon={Plus} size="sm" disabled={saving}>
                        Add Field
                      </Button>
                    </div>
                  </div>

                  <div className="rd-section">
                    <div className="rd-section-header">
                      <span>User Defined Fields — Vulnerabilities{isDirty && <span className="unsaved-indicator"> *</span>}</span>
                    </div>
                    <div className="rd-fields">
                      {vulnerabilityFields.map((field) => {
                        const fullIndex = selectedTemplate.userDefinedFields.findIndex((f) => f.id === field.id);
                        return renderFieldItem(field, fullIndex);
                      })}
                      {vulnerabilityFields.length === 0 && (
                        <div className="empty-state">No vulnerability fields yet. Click "Add Field" to create one.</div>
                      )}
                    </div>
                    <div className="rd-section-footer">
                      <Button onClick={() => addUserDefinedField('VULNERABILITY')} icon={Plus} size="sm" disabled={saving}>
                        Add Field
                      </Button>
                    </div>
                  </div>
                </>
              );
            })()}
          </div>
        ) : (
          <div className="empty-state-main">
            <p>Select a template or create a new one to get started</p>
          </div>
        )}
      </div>
    </Page>

    {showToast && (
      <Toast key={toastKey} message={toastMessage} variant={toastVariant} onDone={() => setShowToast(false)} />
    )}

    {/* Duplicate template — the name is the only thing that differs from the source. */}
    <Modal
      isOpen={!!cloneSource}
      onClose={() => { if (!cloning) setCloneSource(null); }}
      title="Duplicate Template"
      size="md"
      closeOnOverlayClick={!cloning}
      footer={
        <div style={{ display: 'flex', gap: '0.75rem', justifyContent: 'flex-end', width: '100%' }}>
          <Button variant="secondary" onClick={() => setCloneSource(null)} disabled={cloning}>
            Cancel
          </Button>
          <Button onClick={handleCloneTemplate} disabled={cloning}>
            {cloning ? 'Duplicating…' : 'Duplicate'}
          </Button>
        </div>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
        <p style={{ margin: 0, color: 'var(--text-secondary)' }}>
          Creates an exact copy of <strong>{cloneSource?.name}</strong> — every field and variable,
          the CSS, sections, and the uploaded document. Only the name differs.
        </p>
        <div>
          <label className="form-label">New Name <span style={{ color: '#ef4444' }}>*</span></label>
          <Input
            value={cloneName}
            onChange={(e) => { setCloneName(e.target.value); setCloneError(null); }}
            onKeyDown={(e) => { if (e.key === 'Enter' && !cloning) handleCloneTemplate(); }}
            placeholder="Template name"
            autoFocus
          />
          {cloneError && (
            <div style={{ color: '#ef4444', fontSize: '0.85rem', marginTop: '0.25rem' }}>{cloneError}</div>
          )}
        </div>
      </div>
    </Modal>

    {pointerDrag && (
      <div
        className="rd-drag-overlay"
        style={{ left: pointerDrag.cursorX + 16, top: pointerDrag.cursorY - 16 }}
      >
        <GripVertical size={14} />
        <span>{pointerDrag.fieldName}</span>
        <span className="rd-drag-overlay-type">{pointerDrag.fieldType}</span>
      </div>
    )}
    </>
  );
}
