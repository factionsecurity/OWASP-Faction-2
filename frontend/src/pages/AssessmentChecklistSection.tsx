import { useEffect, useState, useMemo, useRef, useCallback } from 'react';
import { Plus, X, Search, Check, Minus } from 'lucide-react';
import { assessmentChecklistsApi, checklistTemplatesApi } from '../api';
import type {
  Assessment,
  AssessmentChecklist,
  ChecklistTemplate,
  ChecklistResponse,
  ChecklistResult,
} from '../types';
import { Button, Modal, ConfirmDialog, Select, FormGroup, FormLabel } from '../components';
import './AssessmentChecklistSection.css';

interface Props {
  assessment: Assessment;
  isFinalized: boolean;
}

export default function AssessmentChecklistSection({ assessment, isFinalized }: Props) {
  const [checklists, setChecklists] = useState<AssessmentChecklist[]>([]);
  const [availableTemplates, setAvailableTemplates] = useState<ChecklistTemplate[]>([]);
  const [activeTab, setActiveTab] = useState<string | null>(null);
  const [showAddModal, setShowAddModal] = useState(false);
  const [selectedTemplateId, setSelectedTemplateId] = useState('');
  const [checklistToRemove, setChecklistToRemove] = useState<string | null>(null);
  const [removingChecklist, setRemovingChecklist] = useState(false);
  const [localResponses, setLocalResponses] = useState<Record<string, ChecklistResponse[]>>({});
  const [searchQuery, setSearchQuery] = useState('');
  const debounceTimers = useRef<Record<string, ReturnType<typeof setTimeout>>>({});

  useEffect(() => {
    loadData();
  }, [assessment.id]);

  const loadData = async () => {
    const [checklistsRes, templatesRes] = await Promise.all([
      assessmentChecklistsApi.getByAssessment(assessment.id).catch(() => null),
      checklistTemplatesApi.getAll(assessment.assessmentTypeId).catch(() => null),
    ]);

    const loaded = checklistsRes?.data ?? [];
    setChecklists(loaded);
    setAvailableTemplates(templatesRes?.data ?? []);

    if (loaded.length > 0 && activeTab === null) {
      setActiveTab(loaded[0].id);
    }

    // Initialize local response state
    const responseMap: Record<string, ChecklistResponse[]> = {};
    loaded.forEach(cl => {
      responseMap[cl.id] = cl.responses ? cl.responses.map(r => ({ ...r })) : [];
    });
    setLocalResponses(responseMap);
  };

  const handleAddChecklist = async () => {
    if (!selectedTemplateId) return;
    try {
      const res = await assessmentChecklistsApi.add(assessment.id, { templateId: selectedTemplateId });
      if (res.data) {
        const added = res.data;
        setChecklists(prev => [...prev, added]);
        setLocalResponses(prev => ({
          ...prev,
          [added.id]: added.responses ? added.responses.map(r => ({ ...r })) : [],
        }));
        setActiveTab(added.id);
      }
      setShowAddModal(false);
      setSelectedTemplateId('');
    } catch {
      // silently fail — user sees no change
    }
  };

  const handleRemoveConfirmed = async () => {
    if (!checklistToRemove) return;
    setRemovingChecklist(true);
    try {
      await assessmentChecklistsApi.remove(assessment.id, checklistToRemove);
      setChecklists(prev => {
        const updated = prev.filter(cl => cl.id !== checklistToRemove);
        if (activeTab === checklistToRemove) {
          setActiveTab(updated.length > 0 ? updated[0].id : null);
        }
        return updated;
      });
      setLocalResponses(prev => {
        const next = { ...prev };
        delete next[checklistToRemove];
        return next;
      });
    } catch {
      // silently fail
    } finally {
      setRemovingChecklist(false);
      setChecklistToRemove(null);
    }
  };

  const saveNow = useCallback(async (checklistId: string, responses: ChecklistResponse[]) => {
    try {
      const res = await assessmentChecklistsApi.update(assessment.id, checklistId, { responses });
      if (res.data) {
        setChecklists(prev => prev.map(cl => cl.id === checklistId ? res.data! : cl));
      }
    } catch {
      // silently fail
    }
  }, [assessment.id]);

  const scheduleSave = useCallback((checklistId: string, updatedResponses: ChecklistResponse[], delay = 0) => {
    clearTimeout(debounceTimers.current[checklistId]);
    debounceTimers.current[checklistId] = setTimeout(() => {
      saveNow(checklistId, updatedResponses);
    }, delay);
  }, [saveNow]);

  const handleSetAll = (checklistId: string, result: ChecklistResult) => {
    setLocalResponses(prev => {
      const updated = (prev[checklistId] || []).map(r => ({ ...r, result }));
      scheduleSave(checklistId, updated);
      return { ...prev, [checklistId]: updated };
    });
  };

  const handleResultChange = (checklistId: string, questionId: string, result: ChecklistResult) => {
    setLocalResponses(prev => {
      const updated = (prev[checklistId] || []).map(r =>
        r.questionId === questionId ? { ...r, result } : r
      );
      scheduleSave(checklistId, updated);
      return { ...prev, [checklistId]: updated };
    });
  };

  const handleCommentChange = (checklistId: string, questionId: string, comment: string) => {
    setLocalResponses(prev => {
      const updated = (prev[checklistId] || []).map(r =>
        r.questionId === questionId ? { ...r, comment } : r
      );
      scheduleSave(checklistId, updated, 800);
      return { ...prev, [checklistId]: updated };
    });
  };

  const alreadyAddedTemplateIds = new Set(checklists.map(cl => cl.templateId));
  const templateOptions = availableTemplates.filter(t => !alreadyAddedTemplateIds.has(t.id));

  const activeChecklist = checklists.find(cl => cl.id === activeTab);
  const activeResponses = activeTab ? (localResponses[activeTab] || []) : [];

  const filteredResponses = useMemo(() => {
    const q = searchQuery.toLowerCase().trim();
    if (!q) return activeResponses.slice().sort((a, b) => a.order - b.order);
    return activeResponses
      .filter(r => r.questionText.toLowerCase().includes(q) || (r.comment || '').toLowerCase().includes(q))
      .sort((a, b) => a.order - b.order);
  }, [activeResponses, searchQuery]);

  return (
    <section className="content-section checklist-section-content">
      <div className="section-header">
        <h3>Checklists</h3>
        {!isFinalized && (
          <Button size="sm" variant="secondary" icon={Plus} onClick={() => setShowAddModal(true)}>
            Add Checklist
          </Button>
        )}
      </div>

      {checklists.length === 0 ? (
        <p className="checklist-empty">No checklists attached to this assessment.</p>
      ) : (
        <>
          {/* Tabs */}
          <div className="checklist-tabs">
            {checklists.map(cl => (
              <div
                key={cl.id}
                className={`checklist-tab${activeTab === cl.id ? ' checklist-tab--active' : ''}`}
                onClick={() => setActiveTab(cl.id)}
              >
                <span>{cl.templateName}</span>
                {!isFinalized && (
                  <button
                    className="checklist-tab-remove"
                    title="Remove checklist"
                    onClick={(e) => { e.stopPropagation(); setChecklistToRemove(cl.id); }}
                  >
                    <X size={12} />
                  </button>
                )}
              </div>
            ))}
          </div>

          {/* Tab content */}
          {activeChecklist && (
            <div className="checklist-tab-content">
              <div className="checklist-search-row">
                <div className="checklist-search-wrapper">
                  <Search size={15} className="checklist-search-icon" />
                  <input
                    type="text"
                    className="form-input checklist-search-input"
                    placeholder="Search questions…"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                  />
                  {searchQuery && (
                    <button className="checklist-search-clear" type="button" onClick={() => setSearchQuery('')}>
                      <X size={14} />
                    </button>
                  )}
                </div>
                {!isFinalized && (
                  <div className="checklist-set-all">
                    <button className="checklist-set-all-btn checklist-set-all-pass" type="button" onClick={() => handleSetAll(activeChecklist.id, 'PASS')}>Pass All</button>
                    <button className="checklist-set-all-btn checklist-set-all-fail" type="button" onClick={() => handleSetAll(activeChecklist.id, 'FAIL')}>Fail All</button>
                    <button className="checklist-set-all-btn checklist-set-all-na" type="button" onClick={() => handleSetAll(activeChecklist.id, 'NA')}>NA All</button>
                  </div>
                )}
              </div>

              <table className="checklist-table">
                <thead>
                  <tr>
                    <th className="checklist-th checklist-th-question">Question</th>
                    <th className="checklist-th checklist-th-comment">Comment</th>
                    <th className="checklist-th checklist-th-result">Result</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredResponses.map(response => (
                    <tr key={response.questionId} className="checklist-tr">
                      <td className="checklist-td checklist-td-question">{response.questionText}</td>
                      <td className="checklist-td checklist-td-comment">
                        <textarea
                          className="checklist-comment"
                          placeholder="Comment…"
                          value={response.comment || ''}
                          onChange={(e) => handleCommentChange(activeChecklist.id, response.questionId, e.target.value)}
                          disabled={isFinalized}
                          rows={1}
                        />
                      </td>
                      <td className="checklist-td checklist-td-result">
                        <div className="checklist-toggle-group">
                          <button
                            type="button"
                            className={`checklist-toggle checklist-toggle-pass${response.result === 'PASS' ? ' active' : ''}`}
                            onClick={() => !isFinalized && handleResultChange(activeChecklist.id, response.questionId, 'PASS')}
                            disabled={isFinalized}
                            title="Pass"
                          >
                            <Check size={13} strokeWidth={3} />
                            <span>Pass</span>
                          </button>
                          <button
                            type="button"
                            className={`checklist-toggle checklist-toggle-fail${response.result === 'FAIL' ? ' active' : ''}`}
                            onClick={() => !isFinalized && handleResultChange(activeChecklist.id, response.questionId, 'FAIL')}
                            disabled={isFinalized}
                            title="Fail"
                          >
                            <X size={13} strokeWidth={3} />
                            <span>Fail</span>
                          </button>
                          <button
                            type="button"
                            className={`checklist-toggle checklist-toggle-na${response.result === 'NA' ? ' active' : ''}`}
                            onClick={() => !isFinalized && handleResultChange(activeChecklist.id, response.questionId, 'NA')}
                            disabled={isFinalized}
                            title="N/A"
                          >
                            <Minus size={13} strokeWidth={3} />
                            <span>N/A</span>
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                  {filteredResponses.length === 0 && (
                    <tr>
                      <td colSpan={3} className="checklist-td checklist-empty-row">No questions match your search.</td>
                    </tr>
                  )}
                </tbody>
              </table>

            </div>
          )}
        </>
      )}

      {/* Add Checklist Modal */}
      <Modal
        isOpen={showAddModal}
        onClose={() => { setShowAddModal(false); setSelectedTemplateId(''); }}
        title="Add Checklist"
        size="sm"
        footer={
          <>
            <Button variant="secondary" onClick={() => { setShowAddModal(false); setSelectedTemplateId(''); }}>
              Cancel
            </Button>
            <Button variant="primary" onClick={handleAddChecklist} disabled={!selectedTemplateId}>
              Add
            </Button>
          </>
        }
      >
        {templateOptions.length === 0 ? (
          <p>No available checklist templates for this assessment type.</p>
        ) : (
          <FormGroup>
            <FormLabel>Select Template</FormLabel>
            <Select
              value={selectedTemplateId}
              onChange={(e) => setSelectedTemplateId(e.target.value)}
            >
              <option value="">Choose a template...</option>
              {templateOptions.map(t => (
                <option key={t.id} value={t.id}>{t.name}</option>
              ))}
            </Select>
          </FormGroup>
        )}
      </Modal>

      <ConfirmDialog
        isOpen={!!checklistToRemove}
        onClose={() => setChecklistToRemove(null)}
        onConfirm={handleRemoveConfirmed}
        title="Remove Checklist"
        message="Are you sure you want to remove this checklist from the assessment?"
        confirmText="Remove"
        variant="danger"
        isLoading={removingChecklist}
      />
    </section>
  );
}
