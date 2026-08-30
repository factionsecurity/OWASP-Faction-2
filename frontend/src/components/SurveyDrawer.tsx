import { useEffect, useState, useRef, useCallback } from 'react';
import { X, ArrowLeft, CheckCircle2, Clock } from 'lucide-react';
import { assessmentSurveysApi } from '../api';
import type { Assessment, AssessmentSurvey, SurveyResponse } from '../types';
import './SurveyDrawer.css';

interface Props {
  assessment: Assessment | null;
  onClose: () => void;
  readOnly?: boolean;
  initialSurveyId?: string;
}

function isAnswered(r: SurveyResponse): boolean {
  if (r.fieldType === 'YES_NO') return r.answer === 'Yes' || r.answer === 'No';
  if (r.fieldType === 'DROPDOWN') return !!r.answer;
  return (r.answer ?? '').trim().length > 0;
}

function calcPct(responses: SurveyResponse[]): number {
  if (!responses.length) return 0;
  return Math.round((responses.filter(isAnswered).length / responses.length) * 100);
}

export default function SurveyDrawer({ assessment, onClose, readOnly = false, initialSurveyId }: Props) {
  const [surveys, setSurveys] = useState<AssessmentSurvey[]>([]);
  const [loading, setLoading] = useState(false);
  const [activeSurveyId, setActiveSurveyId] = useState<string | null>(null);
  const [localAnswers, setLocalAnswers] = useState<Record<string, Record<string, string>>>({});
  const [submitting, setSubmitting] = useState(false);
  const debounceTimers = useRef<Record<string, ReturnType<typeof setTimeout>>>({});

  const isOpen = !!assessment;

  useEffect(() => {
    if (!assessment) {
      setSurveys([]);
      setActiveSurveyId(null);
      setLocalAnswers({});
      return;
    }
    setLoading(true);
    setActiveSurveyId(initialSurveyId ?? null);
    assessmentSurveysApi.getByAssessment(assessment.id)
      .then(res => {
        const loaded = res.data ?? [];
        setSurveys(loaded);
        const map: Record<string, Record<string, string>> = {};
        loaded.forEach(s => {
          map[s.id] = {};
          (s.responses ?? []).forEach(r => { map[s.id][r.questionId] = r.answer ?? ''; });
        });
        setLocalAnswers(map);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [assessment?.id, initialSurveyId]);

  const activeSurvey = surveys.find(s => s.id === activeSurveyId) ?? null;

  // Build live responses for active survey (merge stored responses with local answers)
  const liveResponses = activeSurvey
    ? (activeSurvey.responses ?? []).map(r => ({
        ...r,
        answer: localAnswers[activeSurvey.id]?.[r.questionId] ?? r.answer ?? '',
      }))
    : [];

  const livePct = activeSurvey ? calcPct(liveResponses) : 0;
  const allAnswered = liveResponses.length > 0 && liveResponses.every(isAnswered);

  const saveAnswers = useCallback(async (surveyId: string, answers: Record<string, string>) => {
    if (!assessment) return;
    const survey = surveys.find(s => s.id === surveyId);
    if (!survey) return;
    const responses: SurveyResponse[] = (survey.responses ?? []).map(r => ({
      ...r,
      answer: answers[r.questionId] ?? r.answer ?? '',
    }));
    try {
      const res = await assessmentSurveysApi.update(assessment.id, surveyId, { responses });
      if (res.data) {
        setSurveys(prev => prev.map(s => s.id === surveyId ? res.data! : s));
      }
    } catch { /* silently fail */ }
  }, [assessment, surveys]);

  const handleAnswerChange = (questionId: string, answer: string) => {
    if (!activeSurveyId) return;
    setLocalAnswers(prev => {
      const updated = { ...prev[activeSurveyId], [questionId]: answer };
      clearTimeout(debounceTimers.current[activeSurveyId]);
      debounceTimers.current[activeSurveyId] = setTimeout(() => saveAnswers(activeSurveyId, updated), 600);
      return { ...prev, [activeSurveyId]: updated };
    });
  };

  const handleSubmit = async () => {
    if (!assessment || !activeSurveyId || !allAnswered) return;
    setSubmitting(true);
    try {
      // Save answers first, then mark complete
      const answers = localAnswers[activeSurveyId] ?? {};
      const survey = surveys.find(s => s.id === activeSurveyId)!;
      const responses: SurveyResponse[] = (survey.responses ?? []).map(r => ({
        ...r,
        answer: answers[r.questionId] ?? r.answer ?? '',
      }));
      const res = await assessmentSurveysApi.update(assessment.id, activeSurveyId, { responses, complete: true });
      if (res.data) {
        setSurveys(prev => prev.map(s => s.id === activeSurveyId ? res.data! : s));
        setActiveSurveyId(null); // go back to table
      }
    } catch { /* silently fail */ } finally {
      setSubmitting(false);
    }
  };

  const surveyPct = (s: AssessmentSurvey) => {
    const answers = localAnswers[s.id] ?? {};
    const responses = (s.responses ?? []).map(r => ({ ...r, answer: answers[r.questionId] ?? r.answer ?? '' }));
    return calcPct(responses);
  };

  return (
    <>
      {isOpen && <div className="drawer-overlay" onClick={onClose} />}
      <div className={`survey-drawer${isOpen ? ' open' : ''}`}>
        <div className="survey-drawer-header">
          {activeSurveyId ? (
            <button className="survey-drawer-back" onClick={() => setActiveSurveyId(null)} title="Back to surveys">
              <ArrowLeft size={16} />
            </button>
          ) : null}
          <div className="survey-drawer-title">
            <span>{assessment?.name ?? ''}</span>
            <span className="survey-drawer-subtitle">
              {activeSurvey ? activeSurvey.templateName : 'Surveys'}
            </span>
          </div>
          <button className="survey-drawer-close" onClick={onClose} title="Close">
            <X size={18} />
          </button>
        </div>

        <div className="survey-drawer-body">
          {loading ? (
            <p className="survey-drawer-empty">Loading surveys…</p>
          ) : surveys.length === 0 ? (
            <p className="survey-drawer-empty">No surveys attached to this assessment.</p>
          ) : activeSurvey ? (
            /* ── Question view ── */
            <div className="survey-questions-view">
              <div className="survey-progress-bar-wrap">
                <div className="survey-progress-bar">
                  <div className="survey-progress-fill" style={{ width: `${livePct}%` }} />
                </div>
                <span className="survey-progress-label">{livePct}% complete</span>
              </div>

              <div className="survey-question-list">
                {liveResponses.slice().sort((a, b) => a.order - b.order).map(r => (
                  <div key={r.questionId} className="survey-q-item">
                    <label className="survey-q-label">
                      {r.questionText}
                      {!isAnswered(r) && <span className="survey-q-required">*</span>}
                    </label>

                    {r.fieldType === 'TEXTAREA' && (
                      <textarea
                        className="form-input survey-q-textarea"
                        rows={3}
                        value={r.answer ?? ''}
                        onChange={e => !readOnly && handleAnswerChange(r.questionId, e.target.value)}
                        placeholder={readOnly ? '—' : 'Enter your answer…'}
                        readOnly={readOnly}
                      />
                    )}

                    {r.fieldType === 'YES_NO' && (
                      <div className="survey-yn-group">
                        {['Yes', 'No'].map(opt => (
                          <button
                            key={opt}
                            type="button"
                            className={`survey-yn-btn${r.answer === opt ? ' selected' : ''}`}
                            onClick={() => !readOnly && handleAnswerChange(r.questionId, opt)}
                            disabled={readOnly}
                          >
                            {opt}
                          </button>
                        ))}
                      </div>
                    )}

                    {r.fieldType === 'DROPDOWN' && (
                      <select
                        className="form-input"
                        value={r.answer ?? ''}
                        onChange={e => !readOnly && handleAnswerChange(r.questionId, e.target.value)}
                        disabled={readOnly}
                      >
                        <option value="">Select an option…</option>
                        {(r.dropdownOptions ?? []).map(opt => (
                          <option key={opt} value={opt}>{opt}</option>
                        ))}
                      </select>
                    )}
                  </div>
                ))}
              </div>

              {!readOnly && (
                <div className="survey-submit-row">
                  {!allAnswered && (
                    <p className="survey-submit-hint">Answer all questions to submit.</p>
                  )}
                  <button
                    className={`btn btn-primary btn-md${!allAnswered || submitting ? ' btn-disabled' : ''}`}
                    onClick={handleSubmit}
                    disabled={!allAnswered || submitting}
                  >
                    {submitting ? 'Submitting…' : 'Submit Survey'}
                  </button>
                </div>
              )}
            </div>
          ) : (
            /* ── Survey table ── */
            <table className="survey-list-table">
              <thead>
                <tr>
                  <th>Survey</th>
                  <th>Status</th>
                  <th>Complete</th>
                  <th>Completed</th>
                </tr>
              </thead>
              <tbody>
                {surveys.map(s => {
                  const pct = surveyPct(s);
                  const isDone = s.status === 'COMPLETE';
                  return (
                    <tr
                      key={s.id}
                      className="survey-list-row"
                      onClick={() => setActiveSurveyId(s.id)}
                    >
                      <td className="survey-list-name">{s.templateName}</td>
                      <td>
                        {isDone ? (
                          <span className="survey-status-chip complete">
                            <CheckCircle2 size={13} /> Complete
                          </span>
                        ) : (
                          <span className="survey-status-chip pending">
                            <Clock size={13} /> Needs Attention
                          </span>
                        )}
                      </td>
                      <td>
                        <div className="survey-list-pct-wrap">
                          <div className="survey-list-pct-bar">
                            <div className="survey-list-pct-fill" style={{ width: `${pct}%` }} />
                          </div>
                          <span className="survey-list-pct-label">{pct}%</span>
                        </div>
                      </td>
                      <td className="survey-list-date">
                        {s.completedAt ? new Date(s.completedAt).toLocaleDateString() : '—'}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </>
  );
}
