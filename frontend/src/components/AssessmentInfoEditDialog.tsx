import { useEffect, useMemo, useState } from 'react';
import { Plus, X } from 'lucide-react';
import Modal from './Modal';
import { Button } from './Button';
import { Input, Select, FormGroup, FormLabel, FormHint } from './FormControls';
import RichTextEditor from './RichTextEditor';
import {
  assessmentsApi, applicationsApi, assessmentTypesApi, campaignsApi, assessmentAssignmentApi,
} from '../api';
import type {
  Assessment, Application, AssessmentType, AssignableUser, Campaign, EngagementUrl,
} from '../types';
import './AssessmentInfoEditDialog.css';

export interface AssessmentInfoEditDialogProps {
  isOpen: boolean;
  onClose: () => void;
  assessment: Assessment;
  application: Application | null;
  /** When true the dialog also edits the application's description and tech stack. */
  canEditApplication: boolean;
  /** Called after a successful save so the page can refresh both records. */
  onSaved: () => void | Promise<void>;
}

/** `datetime-local` wants `yyyy-MM-ddTHH:mm`; the API hands back ISO with a seconds/zone tail. */
const toLocalInput = (iso?: string | null): string => (iso ? iso.slice(0, 16) : '');
const toIso = (local: string): string | undefined => (local ? `${local}:00` : undefined);

/**
 * Edits the assessment's own details from the Assessment Info section — type, campaign, dates,
 * assessors, engagement manager, scope and in-scope URLs — plus, for callers who may edit
 * applications, the application's description and tech stack.
 *
 * <p>The two records are saved with separate calls because they are separate resources with
 * separate permissions; the assessment is saved first so a failure to update the application
 * never silently discards the assessment edits.
 */
export default function AssessmentInfoEditDialog({
  isOpen, onClose, assessment, application, canEditApplication, onSaved,
}: AssessmentInfoEditDialogProps) {
  const [assessmentTypes, setAssessmentTypes] = useState<AssessmentType[]>([]);
  const [campaigns, setCampaigns] = useState<Campaign[]>([]);
  const [users, setUsers] = useState<AssignableUser[]>([]);

  const [assessmentTypeId, setAssessmentTypeId] = useState('');
  const [campaignId, setCampaignId] = useState('');
  const [startDate, setStartDate] = useState('');
  const [plannedEndDate, setPlannedEndDate] = useState('');
  const [assessorIds, setAssessorIds] = useState<string[]>([]);
  const [engagementManagerId, setEngagementManagerId] = useState('');
  const [scope, setScope] = useState('');
  const [urls, setUrls] = useState<EngagementUrl[]>([]);
  const [description, setDescription] = useState('');
  const [technologies, setTechnologies] = useState<string[]>([]);
  const [techDraft, setTechDraft] = useState('');

  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Reset the form from the records every time the dialog opens, so a cancelled edit is discarded.
  useEffect(() => {
    if (!isOpen) return;
    setAssessmentTypeId(assessment.assessmentTypeId ?? '');
    setCampaignId(assessment.campaignId ?? '');
    setStartDate(toLocalInput(assessment.startDate));
    setPlannedEndDate(toLocalInput(assessment.plannedEndDate));
    setAssessorIds(assessment.assessorIds ?? []);
    setEngagementManagerId(assessment.engagementManagerId ?? '');
    setScope(assessment.scope ?? '');
    setUrls(assessment.engagementUrls ?? []);
    setDescription(application?.description ?? '');
    setTechnologies(application?.technologies ?? []);
    setTechDraft('');
    setError(null);
  }, [isOpen, assessment, application]);

  // Option lists. Loaded once the dialog is first opened rather than with the page.
  useEffect(() => {
    if (!isOpen) return;
    assessmentTypesApi.getAll(0, 200)
      .then(r => setAssessmentTypes((r.data ?? []).filter(t => t.active || t.id === assessment.assessmentTypeId)))
      .catch(() => setAssessmentTypes([]));
    campaignsApi.getAll(0, 200).then(r => setCampaigns(r.data ?? [])).catch(() => setCampaigns([]));
    // Candidates come from the assessment's team (all internal users when it has no team), resolved
    // server-side — so the picker never offers someone outside the team that owns the work.
    assessmentAssignmentApi.getAssignableAssessors(assessment.id)
      .then(r => setUsers(r.data ?? []))
      .catch(() => setUsers([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen]);

  /**
   * id → display name. Seeded from the assessment's own assessorNames/engagementManagerName
   * (index-aligned with assessorIds) and only then enriched from the user directory, because
   * /users requires users:read — which the Pentester role doesn't have. Without the seed the
   * dialog fell back to rendering raw ids for exactly the people most likely to open it.
   */
  const nameById = useMemo(() => {
    const map = new Map<string, string>();
    (assessment.assessorIds ?? []).forEach((id, i) => {
      const name = assessment.assessorNames?.[i];
      if (name) map.set(id, name);
    });
    if (assessment.engagementManagerId && assessment.engagementManagerName) {
      map.set(assessment.engagementManagerId, assessment.engagementManagerName);
    }
    users.forEach(u => map.set(u.id, u.displayName));
    return map;
  }, [assessment, users]);

  const displayName = (id: string) => nameById.get(id) ?? id;

  const availableAssessors = useMemo(
    () => users.filter(u => !assessorIds.includes(u.id)),
    [users, assessorIds],
  );

  // No candidates at all — an empty team, or the request failed. Existing assessors still render
  // by name from the seed above; only the "add someone" pickers need this list.
  const noCandidates = users.length === 0;

  const addTechnology = () => {
    const value = techDraft.trim();
    if (!value || technologies.includes(value)) { setTechDraft(''); return; }
    setTechnologies(prev => [...prev, value]);
    setTechDraft('');
  };

  const handleSave = async () => {
    if (startDate && plannedEndDate && plannedEndDate < startDate) {
      setError('The planned end date cannot be before the start date.');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await assessmentsApi.update(assessment.id, {
        assessmentTypeId: assessmentTypeId || undefined,
        // An empty campaignId clears the assignment (the API treats "" as "unset").
        campaignId,
        startDate: toIso(startDate),
        plannedEndDate: toIso(plannedEndDate),
        assessorIds,
        engagementManagerId,
        scope,
        engagementUrls: urls.filter(u => u.url.trim()),
      });

      if (canEditApplication && application) {
        await applicationsApi.update(application.id, {
          ...application,
          description,
          technologies,
        });
      }

      await onSaved();
      onClose();
    } catch (err: any) {
      setError(err.response?.data?.message || err.response?.data?.error || 'Failed to save changes');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={() => { if (!saving) onClose(); }}
      title="Edit Assessment"
      size="lg"
      closeOnOverlayClick={!saving}
      footer={
        <div style={{ display: 'flex', gap: '0.75rem', justifyContent: 'flex-end', width: '100%' }}>
          <Button variant="secondary" onClick={onClose} disabled={saving}>Cancel</Button>
          <Button onClick={handleSave} disabled={saving}>{saving ? 'Saving…' : 'Save Changes'}</Button>
        </div>
      }
    >
      <div className="aie-form">
        <div className="aie-row">
          <FormGroup>
            <FormLabel>Assessment Type</FormLabel>
            <Select value={assessmentTypeId} onChange={e => setAssessmentTypeId(e.target.value)}>
              {assessmentTypes.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
            </Select>
            <FormHint>The report template must belong to the selected type.</FormHint>
          </FormGroup>
          <FormGroup>
            <FormLabel>Campaign</FormLabel>
            <Select value={campaignId} onChange={e => setCampaignId(e.target.value)}>
              <option value="">No campaign</option>
              {campaigns.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
            </Select>
          </FormGroup>
        </div>

        <div className="aie-row">
          <FormGroup>
            <FormLabel>Start Date</FormLabel>
            <Input type="datetime-local" value={startDate} onChange={e => setStartDate(e.target.value)} />
          </FormGroup>
          <FormGroup>
            <FormLabel>Planned End Date</FormLabel>
            <Input type="datetime-local" value={plannedEndDate} onChange={e => setPlannedEndDate(e.target.value)} />
          </FormGroup>
        </div>

        <FormGroup>
          <FormLabel>Engagement Manager</FormLabel>
          <Select value={engagementManagerId} onChange={e => setEngagementManagerId(e.target.value)}>
            <option value="">None</option>
            {/* Keep the current manager selectable even when the directory can't be loaded,
                otherwise opening the dialog would silently clear them on save. */}
            {engagementManagerId && !users.some(u => u.id === engagementManagerId) && (
              <option value={engagementManagerId}>{displayName(engagementManagerId)}</option>
            )}
            {users.map(u => <option key={u.id} value={u.id}>{u.displayName}</option>)}
          </Select>
        </FormGroup>

        <FormGroup>
          <FormLabel>Assessors</FormLabel>
          <div className="aie-chips">
            {assessorIds.map(id => {
              return (
                <span key={id} className="aie-chip">
                  {displayName(id)}
                  <button type="button" onClick={() => setAssessorIds(prev => prev.filter(a => a !== id))}
                    title="Remove assessor">
                    <X size={12} />
                  </button>
                </span>
              );
            })}
            {assessorIds.length === 0 && <span className="aie-empty">No assessors assigned</span>}
          </div>
          {!noCandidates && (
            <Select
              value=""
              onChange={e => { if (e.target.value) setAssessorIds(prev => [...prev, e.target.value]); }}
            >
              <option value="">Add an assessor…</option>
              {availableAssessors.map(u => <option key={u.id} value={u.id}>{u.displayName}</option>)}
            </Select>
          )}
          <FormHint>
            {noCandidates
              ? 'No one is available to add — the assessment\'s team has no other members.'
              : assessment.teamId
                ? 'Only members of the assessment\'s team can be added as assessors.'
                : 'This assessment has no team, so any internal user can be added.'}
          </FormHint>
        </FormGroup>

        <FormGroup>
          <FormLabel>Scope</FormLabel>
          <RichTextEditor value={scope} onChange={setScope} placeholder="Describe the engagement scope…" />
        </FormGroup>

        <FormGroup>
          <FormLabel>URLs in Scope</FormLabel>
          <div className="aie-urls">
            {urls.map((u, i) => (
              <div key={i} className="aie-url-row">
                <Input
                  value={u.url}
                  onChange={e => setUrls(prev => prev.map((x, j) => j === i ? { ...x, url: e.target.value } : x))}
                  placeholder="https://example.com"
                />
                <Input
                  value={u.description}
                  onChange={e => setUrls(prev => prev.map((x, j) => j === i ? { ...x, description: e.target.value } : x))}
                  placeholder="Description (optional)"
                />
                <Button variant="secondary" size="sm" onClick={() => setUrls(prev => prev.filter((_, j) => j !== i))}>
                  <X size={14} />
                </Button>
              </div>
            ))}
            <Button variant="secondary" size="sm" onClick={() => setUrls(prev => [...prev, { url: '', description: '' }])}>
              <Plus size={14} /> Add URL
            </Button>
          </div>
        </FormGroup>

        {canEditApplication && application && (
          <>
            <div className="aie-divider">Application — {application.name}</div>
            <FormGroup>
              <FormLabel>Description</FormLabel>
              <RichTextEditor value={description} onChange={setDescription}
                placeholder="Describe the application…" />
            </FormGroup>
            <FormGroup>
              <FormLabel>Tech Stack</FormLabel>
              <div className="aie-chips">
                {technologies.map(t => (
                  <span key={t} className="aie-chip">
                    {t}
                    <button type="button" onClick={() => setTechnologies(prev => prev.filter(x => x !== t))}
                      title="Remove technology">
                      <X size={12} />
                    </button>
                  </span>
                ))}
                {technologies.length === 0 && <span className="aie-empty">No technologies listed</span>}
              </div>
              <div className="aie-url-row">
                <Input
                  value={techDraft}
                  onChange={e => setTechDraft(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); addTechnology(); } }}
                  placeholder="Add a technology and press Enter"
                />
                <Button variant="secondary" size="sm" onClick={addTechnology}><Plus size={14} /></Button>
              </div>
            </FormGroup>
          </>
        )}

        {error && <div className="aie-error">{error}</div>}
      </div>
    </Modal>
  );
}
