import { useEffect, useState, useCallback } from 'react';
import { Plus, X, CheckCircle2, Circle } from 'lucide-react';
import { assessmentSurveysApi, surveyTemplatesApi } from '../api';
import type {
  Assessment,
  AssessmentSurvey,
  SurveyTemplate,
  SurveyResponse,
} from '../types';
import { Button, Modal, ConfirmDialog, Select, FormGroup, FormLabel } from '../components';
import { usePermissions } from '../utils/permissions';
import './AssessmentSurveySection.css';

interface Props {
  assessment: Assessment;
  isFinalized: boolean;
}

export default function AssessmentSurveySection({ assessment, isFinalized }: Props) {
  const { permissions: perms } = usePermissions();
  const canEdit = perms.canEditAssessments;
  const canComplete = perms.canCompleteSurveys;

  const [surveys, setSurveys] = useState<AssessmentSurvey[]>([]);
  const [availableTemplates, setAvailableTemplates] = useState<SurveyTemplate[]>([]);
  const [activeTab, setActiveTab] = useState<string | null>(null);
  const [showAddModal, setShowAddModal] = useState(false);
  const [selectedTemplateId, setSelectedTemplateId] = useState('');
  const [surveyToRemove, setSurveyToRemove] = useState<string | null>(null);
  const [removingSurvey, setRemovingSurvey] = useState(false);
  const [localAnswers, setLocalAnswers] = useState<Record<string, Record<string, string>>>({});
  const [saving, setSaving] = useState<Record<string, boolean>>({});
  const debounceTimers: Record<string, ReturnType<typeof setTimeout>> = {};

  useEffect(() => {
    loadData();
  }, [assessment.id]);

  const loadData = async () => {
    const [surveysRes, templatesRes] = await Promise.all([
      assessmentSurveysApi.getByAssessment(assessment.id).catch(() => null),
      surveyTemplatesApi.getAll(true).catch(() => null),
    ]);

    const loaded = surveysRes?.data ?? [];
    setSurveys(loaded);
    setAvailableTemplates(templatesRes?.data ?? []);

    if (loaded.length > 0 && activeTab === null) {
      setActiveTab(loaded[0].id);
    }

    const answerMap: Record<string, Record<string, string>> = {};
    loaded.forEach(s => {
      answerMap[s.id] = {};
      (s.responses ?? []).forEach(r => {
        answerMap[s.id][r.questionId] = r.answer ?? '';
      });
    });
    setLocalAnswers(answerMap);
  };

  const handleAddSurvey = async () => {
    if (!selectedTemplateId) return;
    try {
      const res = await assessmentSurveysApi.add(assessment.id, { templateId: selectedTemplateId });
      if (res.data) {
        const added = res.data;
        setSurveys(prev => [...prev, added]);
        const answerMap: Record<string, string> = {};
        (added.responses ?? []).forEach(r => { answerMap[r.questionId] = r.answer ?? ''; });
        setLocalAnswers(prev => ({ ...prev, [added.id]: answerMap }));
        setActiveTab(added.id);
      }
      setShowAddModal(false);
      setSelectedTemplateId('');
    } catch {
      // silently fail
    }
  };

  const handleRemoveConfirmed = async () => {
    if (!surveyToRemove) return;
    setRemovingSurvey(true);
    try {
      await assessmentSurveysApi.remove(assessment.id, surveyToRemove);
      setSurveys(prev => {
        const updated = prev.filter(s => s.id !== surveyToRemove);
        if (activeTab === surveyToRemove) {
          setActiveTab(updated.length > 0 ? updated[0].id : null);
        }
        return updated;
      });
      setLocalAnswers(prev => {
        const next = { ...prev };
        delete next[surveyToRemove];
        return next;
      });
    } catch {
      // silently fail
    } finally {
      setRemovingSurvey(false);
      setSurveyToRemove(null);
    }
  };

  const saveAnswers = useCallback(async (surveyId: string, answers: Record<string, string>) => {
    const survey = surveys.find(s => s.id === surveyId);
    if (!survey) return;
    const responses: SurveyResponse[] = (survey.responses ?? []).map(r => ({
      ...r,
      answer: answers[r.questionId] ?? r.answer,
    }));
    try {
      const res = await assessmentSurveysApi.update(assessment.id, surveyId, { responses });
      if (res.data) setSurveys(prev => prev.map(s => s.id === surveyId ? res.data! : s));
    } catch {
      // silently fail
    }
  }, [assessment.id, surveys]);

  const handleAnswerChange = (surveyId: string, questionId: string, answer: string) => {
    setLocalAnswers(prev => {
      const updated = { ...prev[surveyId], [questionId]: answer };
      const key = `${surveyId}-${questionId}`;
      clearTimeout(debounceTimers[key]);
      debounceTimers[key] = setTimeout(() => saveAnswers(surveyId, updated), 800);
      return { ...prev, [surveyId]: updated };
    });
  };

  const handleMarkComplete = async (surveyId: string, complete: boolean) => {
    setSaving(prev => ({ ...prev, [surveyId]: true }));
    try {
      const res = await assessmentSurveysApi.update(assessment.id, surveyId, { complete });
      if (res.data) setSurveys(prev => prev.map(s => s.id === surveyId ? res.data! : s));
    } catch {
      // silently fail
    } finally {
      setSaving(prev => ({ ...prev, [surveyId]: false }));
    }
  };

  const alreadyAddedIds = new Set(surveys.map(s => s.templateId));
  const templateOptions = availableTemplates.filter(t => !alreadyAddedIds.has(t.id));
  const activeSurvey = surveys.find(s => s.id === activeTab);

  return (
    <section className="content-section survey-section-content">
      <div className="section-header">
        <h3>Surveys</h3>
        {!isFinalized && canEdit && (
          <Button size="sm" variant="secondary" icon={Plus} onClick={() => setShowAddModal(true)}>
            Add Survey
          </Button>
        )}
      </div>

      {surveys.length === 0 ? (
        <p className="survey-empty">No surveys attached to this assessment.</p>
      ) : (
        <>
          <div className="survey-tabs">
            {surveys.map(s => (
              <div
                key={s.id}
                className={`survey-tab${activeTab === s.id ? ' survey-tab--active' : ''}`}
                onClick={() => setActiveTab(s.id)}
              >
                <span>{s.templateName}</span>
                <span className={`survey-tab-status${s.status === 'COMPLETE' ? ' complete' : ''}`}>
                  {s.status === 'COMPLETE' ? <CheckCircle2 size={12} /> : <Circle size={12} />}
                </span>
                {!isFinalized && canEdit && (
                  <button
                    className="survey-tab-remove"
                    title="Remove survey"
                    onClick={(e) => { e.stopPropagation(); setSurveyToRemove(s.id); }}
                  >
                    <X size={12} />
                  </button>
                )}
              </div>
            ))}
          </div>

          {activeSurvey && (
            <div className="survey-tab-content">
              <div className="survey-meta-row">
                <span className={`survey-status-badge survey-status-badge--${activeSurvey.status.toLowerCase()}`}>
                  {activeSurvey.status === 'COMPLETE' ? 'Complete' : 'Incomplete'}
                  {activeSurvey.completedBy ? ` · ${activeSurvey.completedBy}` : ''}
                </span>
                {!isFinalized && canComplete && (
                  <Button
                    size="sm"
                    variant={activeSurvey.status === 'COMPLETE' ? 'secondary' : 'primary'}
                    onClick={() => handleMarkComplete(activeSurvey.id, activeSurvey.status !== 'COMPLETE')}
                    disabled={saving[activeSurvey.id]}
                  >
                    {saving[activeSurvey.id]
                      ? 'Saving…'
                      : activeSurvey.status === 'COMPLETE'
                      ? 'Mark Incomplete'
                      : 'Mark Complete'}
                  </Button>
                )}
              </div>

              <div className="survey-questions-list">
                {(activeSurvey.responses ?? [])
                  .slice()
                  .sort((a, b) => a.order - b.order)
                  .map(response => {
                    const answer = localAnswers[activeSurvey.id]?.[response.questionId] ?? '';
                    const isReadOnly = isFinalized || !canComplete;

                    return (
                      <div key={response.questionId} className="survey-question-item">
                        <label className="survey-question-label">{response.questionText}</label>

                        {response.fieldType === 'TEXTAREA' && (
                          <textarea
                            className="form-input survey-answer-textarea"
                            value={answer}
                            onChange={(e) => handleAnswerChange(activeSurvey.id, response.questionId, e.target.value)}
                            disabled={isReadOnly}
                            rows={3}
                            placeholder="Enter your answer…"
                          />
                        )}

                        {response.fieldType === 'YES_NO' && (
                          <div className="survey-yn-group">
                            {['Yes', 'No'].map(opt => (
                              <button
                                key={opt}
                                type="button"
                                className={`survey-yn-btn${answer === opt ? ' selected' : ''}`}
                                onClick={() => !isReadOnly && handleAnswerChange(activeSurvey.id, response.questionId, opt)}
                                disabled={isReadOnly}
                              >
                                {opt}
                              </button>
                            ))}
                          </div>
                        )}

                        {response.fieldType === 'DROPDOWN' && (
                          <Select
                            value={answer}
                            onChange={(e) => handleAnswerChange(activeSurvey.id, response.questionId, e.target.value)}
                            disabled={isReadOnly}
                          >
                            <option value="">Select an option…</option>
                            {(response.dropdownOptions ?? []).map(opt => (
                              <option key={opt} value={opt}>{opt}</option>
                            ))}
                          </Select>
                        )}
                      </div>
                    );
                  })}
              </div>
            </div>
          )}
        </>
      )}

      <Modal
        isOpen={showAddModal}
        onClose={() => { setShowAddModal(false); setSelectedTemplateId(''); }}
        title="Add Survey"
        size="sm"
        footer={
          <>
            <Button variant="secondary" onClick={() => { setShowAddModal(false); setSelectedTemplateId(''); }}>
              Cancel
            </Button>
            <Button variant="primary" onClick={handleAddSurvey} disabled={!selectedTemplateId}>
              Add
            </Button>
          </>
        }
      >
        {templateOptions.length === 0 ? (
          <p>No available survey templates. Create one in Survey Config.</p>
        ) : (
          <FormGroup>
            <FormLabel>Select Template</FormLabel>
            <Select
              value={selectedTemplateId}
              onChange={(e) => setSelectedTemplateId(e.target.value)}
            >
              <option value="">Choose a template…</option>
              {templateOptions.map(t => (
                <option key={t.id} value={t.id}>{t.name}</option>
              ))}
            </Select>
          </FormGroup>
        )}
      </Modal>

      <ConfirmDialog
        isOpen={!!surveyToRemove}
        onClose={() => setSurveyToRemove(null)}
        onConfirm={handleRemoveConfirmed}
        title="Remove Survey"
        message="Are you sure you want to remove this survey from the assessment?"
        confirmText="Remove"
        variant="danger"
        isLoading={removingSurvey}
      />
    </section>
  );
}
