import { useEffect, useMemo, useState } from 'react';
import { Check, Minus, Search, X } from 'lucide-react';
import { assessmentChecklistsApi } from '../api';
import type { AssessmentChecklist, ChecklistResponse, ChecklistResult } from '../types';
import './AssessmentChecklistsView.css';

interface Props {
  assessmentId: string;
}

const RESULT_LABEL: Record<ChecklistResult, string> = {
  PASS: 'Pass',
  FAIL: 'Fail',
  NA: 'N/A',
};

function ResultPill({ result }: { result: ChecklistResult | null }) {
  if (!result) {
    return <span className="clv-pill clv-pill--none">Unanswered</span>;
  }
  const icon = result === 'PASS' ? <Check size={12} strokeWidth={3} />
    : result === 'FAIL' ? <X size={12} strokeWidth={3} />
    : <Minus size={12} strokeWidth={3} />;
  return (
    <span className={`clv-pill clv-pill--${result.toLowerCase()}`}>
      {icon}
      {RESULT_LABEL[result]}
    </span>
  );
}

/**
 * Read-only view of an assessment's checklists — the reviewer's counterpart to the assessor's
 * editable {@code AssessmentChecklistSection}. Nothing here writes: a peer reviewer inspects the
 * assessor's answers, they don't change them.
 */
export default function AssessmentChecklistsView({ assessmentId }: Props) {
  const [checklists, setChecklists] = useState<AssessmentChecklist[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [searchQuery, setSearchQuery] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    setSearchQuery('');

    assessmentChecklistsApi.getByAssessment(assessmentId)
      .then((res) => {
        if (cancelled) return;
        const loaded = res.data ?? [];
        setChecklists(loaded);
        setActiveId(loaded.length > 0 ? loaded[0].id : null);
      })
      .catch(() => {
        if (!cancelled) setError('Failed to load checklists.');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => { cancelled = true; };
  }, [assessmentId]);

  const active = checklists.find((cl) => cl.id === activeId) ?? null;
  const responses: ChecklistResponse[] = active?.responses ?? [];

  const counts = useMemo(() => {
    const tally = { PASS: 0, FAIL: 0, NA: 0, unanswered: 0 };
    responses.forEach((r) => {
      if (r.result) tally[r.result] += 1;
      else tally.unanswered += 1;
    });
    return tally;
  }, [responses]);

  const filtered = useMemo(() => {
    const q = searchQuery.toLowerCase().trim();
    const sorted = responses.slice().sort((a, b) => a.order - b.order);
    if (!q) return sorted;
    return sorted.filter((r) =>
      r.questionText.toLowerCase().includes(q) || (r.comment || '').toLowerCase().includes(q));
  }, [responses, searchQuery]);

  if (loading) {
    return (
      <div className="clv-state">
        <div className="spinner-border text-primary" role="status" />
        <span>Loading checklists…</span>
      </div>
    );
  }

  if (error) {
    return <div className="clv-error">{error}</div>;
  }

  if (checklists.length === 0) {
    return <div className="clv-state">No checklists attached to this assessment.</div>;
  }

  return (
    <div className="clv">
      {checklists.length > 1 && (
        <div className="clv-tabs">
          {checklists.map((cl) => (
            <button
              key={cl.id}
              type="button"
              className={`clv-tab${cl.id === activeId ? ' clv-tab--active' : ''}`}
              onClick={() => { setActiveId(cl.id); setSearchQuery(''); }}
            >
              {cl.templateName}
            </button>
          ))}
        </div>
      )}

      <div className="clv-toolbar">
        <div className="clv-search">
          <Search size={14} className="clv-search-icon" />
          <input
            type="text"
            className="form-input clv-search-input"
            placeholder="Search questions…"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
          {searchQuery && (
            <button type="button" className="clv-search-clear" onClick={() => setSearchQuery('')}>
              <X size={13} />
            </button>
          )}
        </div>
        <div className="clv-counts">
          <span className="clv-count clv-count--pass">{counts.PASS} pass</span>
          <span className="clv-count clv-count--fail">{counts.FAIL} fail</span>
          <span className="clv-count clv-count--na">{counts.NA} n/a</span>
          {counts.unanswered > 0 && (
            <span className="clv-count clv-count--none">{counts.unanswered} unanswered</span>
          )}
        </div>
      </div>

      <div className="clv-rows">
        {filtered.map((r) => (
          <div key={r.questionId} className="clv-row">
            <div className="clv-row-head">
              <span className="clv-question">{r.questionText}</span>
              <ResultPill result={r.result} />
            </div>
            {r.comment && <p className="clv-comment">{r.comment}</p>}
          </div>
        ))}
        {filtered.length === 0 && (
          <div className="clv-state">No questions match your search.</div>
        )}
      </div>
    </div>
  );
}
