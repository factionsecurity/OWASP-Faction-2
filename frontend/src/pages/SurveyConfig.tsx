import { useEffect, useState } from 'react';
import { Plus, Edit2, Trash2, GripVertical, X, Power } from 'lucide-react';
import { surveyTemplatesApi } from '../api';
import type { SurveyTemplate, SurveyTemplateQuestion, SurveyFieldType } from '../types';
import {
  Button,
  IconButton,
  ActionButtons,
  Badge,
  Modal,
  ConfirmDialog,
  FormGroup,
  FormLabel,
  Input,
  Select,
} from '../components';
import Page from '../components/Page';
import './SurveyConfig.css';

interface SurveyConfigProps {
  /** Render without the Page wrapper, for embedding as a tab in Assessment Config */
  embedded?: boolean;
}

export default function SurveyConfig({ embedded = false }: SurveyConfigProps) {
  const [templates, setTemplates] = useState<SurveyTemplate[]>([]);
  const [loading, setLoading] = useState(true);

  const [showModal, setShowModal] = useState(false);
  const [modalMode, setModalMode] = useState<'create' | 'edit'>('create');
  const [selectedTemplate, setSelectedTemplate] = useState<SurveyTemplate | null>(null);
  const [saving, setSaving] = useState(false);
  const [modalError, setModalError] = useState('');

  const [templateName, setTemplateName] = useState('');
  const [questions, setQuestions] = useState<SurveyTemplateQuestion[]>([]);
  const [dragIndex, setDragIndex] = useState<number | null>(null);

  const [templateToDelete, setTemplateToDelete] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    loadTemplates();
  }, []);

  const loadTemplates = async () => {
    setLoading(true);
    try {
      const res = await surveyTemplatesApi.getAll();
      setTemplates(res.data ?? []);
    } catch {
      // silently fail
    } finally {
      setLoading(false);
    }
  };

  const openCreate = () => {
    setModalMode('create');
    setSelectedTemplate(null);
    setTemplateName('');
    setQuestions([]);
    setModalError('');
    setShowModal(true);
  };

  const openEdit = (template: SurveyTemplate) => {
    setModalMode('edit');
    setSelectedTemplate(template);
    setTemplateName(template.name);
    setQuestions(template.questions.map(q => ({ ...q, dropdownOptions: q.dropdownOptions ? [...q.dropdownOptions] : [] })));
    setModalError('');
    setShowModal(true);
  };

  const handleSave = async () => {
    if (!templateName.trim()) {
      setModalError('Name is required.');
      return;
    }
    setSaving(true);
    setModalError('');
    try {
      const payload = {
        name: templateName.trim(),
        questions: questions.map((q, i) => ({ ...q, order: i })),
      };
      if (modalMode === 'create') {
        const res = await surveyTemplatesApi.create(payload);
        if (res.data) setTemplates(prev => [res.data!, ...prev]);
      } else if (selectedTemplate) {
        const res = await surveyTemplatesApi.update(selectedTemplate.id, payload);
        if (res.data) setTemplates(prev => prev.map(t => t.id === selectedTemplate.id ? res.data! : t));
      }
      setShowModal(false);
    } catch {
      setModalError('Failed to save. Please try again.');
    } finally {
      setSaving(false);
    }
  };

  const handleToggleActive = async (template: SurveyTemplate) => {
    try {
      const res = await surveyTemplatesApi.update(template.id, { active: !template.active });
      if (res.data) setTemplates(prev => prev.map(t => t.id === template.id ? res.data! : t));
    } catch {
      // silently fail
    }
  };

  const handleDeleteConfirmed = async () => {
    if (!templateToDelete) return;
    setDeleting(true);
    try {
      await surveyTemplatesApi.delete(templateToDelete);
      setTemplates(prev => prev.filter(t => t.id !== templateToDelete));
    } catch {
      // silently fail
    } finally {
      setDeleting(false);
      setTemplateToDelete(null);
    }
  };

  // ── Question management ───────────────────────────────────────────────────

  const addQuestion = () => {
    const newQ: SurveyTemplateQuestion = {
      id: '',
      text: '',
      fieldType: 'TEXTAREA',
      dropdownOptions: [],
      order: questions.length,
    };
    setQuestions(prev => [...prev, newQ]);
  };

  const updateQuestion = (index: number, updates: Partial<SurveyTemplateQuestion>) => {
    setQuestions(prev => prev.map((q, i) => i === index ? { ...q, ...updates } : q));
  };

  const removeQuestion = (index: number) => {
    setQuestions(prev => prev.filter((_, i) => i !== index));
  };

  const addDropdownOption = (qIndex: number) => {
    setQuestions(prev => prev.map((q, i) => {
      if (i !== qIndex) return q;
      return { ...q, dropdownOptions: [...(q.dropdownOptions ?? []), ''] };
    }));
  };

  const updateDropdownOption = (qIndex: number, optIndex: number, value: string) => {
    setQuestions(prev => prev.map((q, i) => {
      if (i !== qIndex) return q;
      const opts = [...(q.dropdownOptions ?? [])];
      opts[optIndex] = value;
      return { ...q, dropdownOptions: opts };
    }));
  };

  const removeDropdownOption = (qIndex: number, optIndex: number) => {
    setQuestions(prev => prev.map((q, i) => {
      if (i !== qIndex) return q;
      return { ...q, dropdownOptions: (q.dropdownOptions ?? []).filter((_, oi) => oi !== optIndex) };
    }));
  };

  // ── Drag to reorder ───────────────────────────────────────────────────────

  const handleDragStart = (index: number) => setDragIndex(index);
  const handleDragOver = (e: React.DragEvent, index: number) => {
    e.preventDefault();
    if (dragIndex === null || dragIndex === index) return;
    const next = [...questions];
    const [moved] = next.splice(dragIndex, 1);
    next.splice(index, 0, moved);
    setDragIndex(index);
    setQuestions(next);
  };
  const handleDragEnd = () => setDragIndex(null);

  const Wrapper = embedded ? 'div' : Page;

  return (
    <Wrapper className="survey-config-page">
      <div className="page-header">
        {embedded ? <h2 className="survey-config-title">Survey Templates</h2> : <div />}
        <Button icon={Plus} onClick={openCreate}>New Survey Template</Button>
      </div>

      {loading ? (
        <p className="survey-config-loading">Loading…</p>
      ) : templates.length === 0 ? (
        <p className="survey-config-empty">No survey templates yet. Create one to get started.</p>
      ) : (
        <table className="survey-config-table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Questions</th>
              <th>Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {templates.map(t => (
              <tr key={t.id}>
                <td className="survey-config-name">{t.name}</td>
                <td>{t.questions?.length ?? 0}</td>
                <td>
                  <Badge variant={t.active ? 'success' : 'secondary'}>
                    {t.active ? 'Active' : 'Inactive'}
                  </Badge>
                </td>
                <td>
                  <ActionButtons>
                    <IconButton
                      icon={Power}
                      title={t.active ? 'Deactivate' : 'Activate'}
                      variant={t.active ? 'default' : 'success'}
                      onClick={() => handleToggleActive(t)}
                    />
                    <IconButton icon={Edit2} title="Edit" variant="edit" onClick={() => openEdit(t)} />
                    <IconButton icon={Trash2} title="Delete" variant="delete" onClick={() => setTemplateToDelete(t.id)} />
                  </ActionButtons>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {/* Create / Edit Modal */}
      <Modal
        isOpen={showModal}
        onClose={() => setShowModal(false)}
        title={modalMode === 'create' ? 'New Survey Template' : 'Edit Survey Template'}
        size="lg"
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowModal(false)}>Cancel</Button>
            <Button variant="primary" onClick={handleSave} disabled={saving}>
              {saving ? 'Saving…' : 'Save'}
            </Button>
          </>
        }
      >
        <div className="survey-modal-body">
          <FormGroup>
            <FormLabel required>Name</FormLabel>
            <Input
              value={templateName}
              onChange={(e) => setTemplateName(e.target.value)}
              placeholder="e.g. Application Security Questionnaire"
            />
          </FormGroup>

          {modalError && <p className="survey-modal-error">{modalError}</p>}

          <div className="survey-questions-header">
            <span className="survey-questions-title">Questions ({questions.length})</span>
            <Button size="sm" variant="secondary" icon={Plus} onClick={addQuestion}>
              Add Question
            </Button>
          </div>

          {questions.length === 0 ? (
            <p className="survey-no-questions">No questions yet. Click "Add Question" to begin.</p>
          ) : (
            <div className="survey-questions-list">
              {questions.map((q, qIndex) => (
                <div
                  key={qIndex}
                  className={`survey-question-row${dragIndex === qIndex ? ' dragging' : ''}`}
                  draggable
                  onDragStart={() => handleDragStart(qIndex)}
                  onDragOver={(e) => handleDragOver(e, qIndex)}
                  onDragEnd={handleDragEnd}
                >
                  <div className="survey-question-drag">
                    <GripVertical size={16} />
                  </div>
                  <div className="survey-question-fields">
                    <Input
                      value={q.text}
                      onChange={(e) => updateQuestion(qIndex, { text: e.target.value })}
                      placeholder="Question text"
                      className="survey-question-text-input"
                    />
                    <Select
                      value={q.fieldType}
                      onChange={(e) => updateQuestion(qIndex, { fieldType: e.target.value as SurveyFieldType, dropdownOptions: [] })}
                      className="survey-question-type-select"
                    >
                      <option value="TEXTAREA">Long text</option>
                      <option value="YES_NO">Yes / No</option>
                      <option value="DROPDOWN">Dropdown</option>
                    </Select>

                    {q.fieldType === 'DROPDOWN' && (
                      <div className="survey-dropdown-options">
                        <span className="survey-dropdown-label">Options</span>
                        {(q.dropdownOptions ?? []).map((opt, optIndex) => (
                          <div key={optIndex} className="survey-dropdown-option-row">
                            <Input
                              value={opt}
                              onChange={(e) => updateDropdownOption(qIndex, optIndex, e.target.value)}
                              placeholder={`Option ${optIndex + 1}`}
                              className="survey-dropdown-option-input"
                            />
                            <button
                              type="button"
                              className="survey-remove-option-btn"
                              onClick={() => removeDropdownOption(qIndex, optIndex)}
                            >
                              <X size={14} />
                            </button>
                          </div>
                        ))}
                        <button
                          type="button"
                          className="survey-add-option-btn"
                          onClick={() => addDropdownOption(qIndex)}
                        >
                          + Add option
                        </button>
                      </div>
                    )}
                  </div>
                  <button
                    type="button"
                    className="survey-question-remove"
                    onClick={() => removeQuestion(qIndex)}
                    title="Remove question"
                  >
                    <X size={14} />
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>
      </Modal>

      <ConfirmDialog
        isOpen={!!templateToDelete}
        onClose={() => setTemplateToDelete(null)}
        onConfirm={handleDeleteConfirmed}
        title="Delete Survey Template"
        message="Are you sure you want to delete this survey template? This cannot be undone."
        confirmText="Delete"
        variant="danger"
        isLoading={deleting}
      />
    </Wrapper>
  );
}
