import { useEffect, useMemo, useState } from 'react';
import { usePageTitle } from '../context/PageTitleContext';
import { emailNotificationConfigApi } from '../api';
import type {
  EmailNotificationAudience,
  EmailNotificationConfig,
  EmailNotificationEvent,
  UpdateEmailNotificationConfigRequest,
} from '../types';
import { Input } from '../components';
import Page from '../components/Page';
import { AlertTriangle, BellRing, Loader2, MessageSquareText, X } from 'lucide-react';
import './EmailNotificationsPage.css';

/** Column order, and the only place an audience's heading is written down. */
const AUDIENCES: Array<{ key: EmailNotificationAudience; label: string; field: SwitchField }> = [
  { key: 'ASSESSORS', label: 'Assessors', field: 'notifyAssessors' },
  { key: 'STAKEHOLDERS', label: 'Stakeholders', field: 'notifyStakeholders' },
  { key: 'APP_OWNER', label: 'App owner', field: 'notifyAppOwner' },
  { key: 'MENTIONED_USERS', label: 'Mentioned users', field: 'includeMentionedUsers' },
  { key: 'ORG_USERS', label: 'Org access', field: 'notifyOrgUsers' },
];

type SwitchField =
  | 'notifyAssessors'
  | 'notifyStakeholders'
  | 'notifyAppOwner'
  | 'includeMentionedUsers'
  | 'notifyOrgUsers';

/** Sections, in the order they read: the assessment lifecycle, then what follows from it. */
const GROUPS: Array<{ title: string; hint: string; match: (e: EmailNotificationEvent) => boolean }> = [
  {
    title: 'Assessments',
    hint: 'Sent as the engagement moves through its lifecycle.',
    match: e => e.event.startsWith('ASSESSMENT_'),
  },
  {
    title: 'Retests',
    hint: 'Sent when a finding is scheduled for retest, and when the result is in.',
    match: e => e.event.startsWith('RETEST_'),
  },
  {
    title: 'Vulnerabilities',
    hint: 'Due-date reminders are digests — one email covering every finding across all '
        + 'applications and organizations, never one email per finding. Findings in the '
        + 'Exception state are never included.',
    match: e => e.event.startsWith('VULNERABILITY_'),
  },
];

export default function EmailNotificationsPage() {
  const { setPageTitle } = usePageTitle();

  const [config, setConfig] = useState<EmailNotificationConfig | null>(null);
  const [error, setError] = useState<string | null>(null);
  /** Keyed `${eventKey}:${field}` so only the switch in flight shows as busy. */
  const [saving, setSaving] = useState<Set<string>>(new Set());
  /** Event keys whose message editor is open. */
  const [editingMessage, setEditingMessage] = useState<Set<string>>(new Set());
  /** Draft message text, so typing does not fire a save per keystroke. */
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [repeatCount, setRepeatCount] = useState('0');
  const [repeatInterval, setRepeatInterval] = useState('7');

  useEffect(() => {
    setPageTitle('Email Notifications');
    emailNotificationConfigApi.getConfig()
      .then(res => { if (res.data) apply(res.data); })
      .catch(() => setError('Could not load the notification settings.'));
  }, []);

  const apply = (next: EmailNotificationConfig) => {
    setConfig(next);
    setRepeatCount(String(next.pastDueRepeatCount));
    setRepeatInterval(String(next.pastDueRepeatIntervalDays));
  };

  const setBusy = (key: string, busy: boolean) =>
    setSaving(prev => {
      const next = new Set(prev);
      if (busy) next.add(key); else next.delete(key);
      return next;
    });

  /**
   * Every save sends only what changed. The page never round-trips the whole table, so
   * flipping one switch can't quietly overwrite a change someone else just made.
   */
  const save = async (request: UpdateEmailNotificationConfigRequest, busyKey: string) => {
    setError(null);
    setBusy(busyKey, true);
    try {
      const res = await emailNotificationConfigApi.updateConfig(request);
      if (res.data) apply(res.data);
    } catch {
      // Reload rather than guess: a failed save leaves the page showing the stored truth.
      setError('Could not save that change. Check your connection and try again.');
      try {
        const res = await emailNotificationConfigApi.getConfig();
        if (res.data) apply(res.data);
      } catch { /* the error message already says what happened */ }
    } finally {
      setBusy(busyKey, false);
    }
  };

  const toggleMaster = (enabled: boolean) => {
    setConfig(prev => prev && { ...prev, enabled }); // optimistic, so the switch responds at once
    save({ enabled }, 'master');
  };

  const toggleAudience = (event: EmailNotificationEvent, field: SwitchField, next: boolean) => {
    setConfig(prev => prev && {
      ...prev,
      events: prev.events.map(e => (e.key === event.key ? { ...e, [field]: next } : e)),
    });
    save({ events: [{ key: event.key, [field]: next }] }, `${event.key}:${field}`);
  };

  const saveMessage = (event: EmailNotificationEvent) => {
    const draft = drafts[event.key] ?? '';
    if (draft === (event.customMessage ?? '')) return; // nothing actually changed
    save({ events: [{ key: event.key, customMessage: draft }] }, `${event.key}:message`);
  };

  const toggleMessageEditor = (event: EmailNotificationEvent) => {
    setDrafts(prev => ({ ...prev, [event.key]: prev[event.key] ?? event.customMessage ?? '' }));
    setEditingMessage(prev => {
      const next = new Set(prev);
      if (next.has(event.key)) next.delete(event.key); else next.add(event.key);
      return next;
    });
  };

  const saveRepeats = () => {
    if (!config) return;
    const count = Math.max(0, Number(repeatCount) || 0);
    const interval = Math.max(1, Number(repeatInterval) || 1);
    if (count === config.pastDueRepeatCount && interval === config.pastDueRepeatIntervalDays) return;
    save({ pastDueRepeatCount: count, pastDueRepeatIntervalDays: interval }, 'repeats');
  };

  const grouped = useMemo(() => {
    if (!config) return [];
    return GROUPS
      .map(group => ({ ...group, events: config.events.filter(group.match) }))
      .filter(group => group.events.length > 0);
  }, [config]);

  if (!config) {
    return (
      <Page variant="narrow" className="email-notifications-page">
        <div className="email-notifications-loading">
          {error ? error : <><Loader2 size={20} className="spin" /> Loading…</>}
        </div>
      </Page>
    );
  }

  return (
    <Page variant="narrow" className="email-notifications-page">
      <div className="email-notifications-card">
        <div className="email-notifications-card-header">
          <BellRing size={18} />
          <span>Notification emails</span>
          <label className="email-toggle" style={{ marginLeft: 'auto' }}>
            <input
              type="checkbox"
              checked={config.enabled}
              disabled={saving.has('master')}
              onChange={e => toggleMaster(e.target.checked)}
            />
            <span className="email-toggle-track" />
            <span className="email-toggle-label">
              {saving.has('master') ? 'Saving…' : config.enabled ? 'Enabled' : 'Disabled'}
            </span>
          </label>
        </div>

        <div className="email-notifications-body">
          <p className="email-notifications-intro">
            Choose who is emailed about each event. Stakeholders and app owners are the
            addresses recorded on the assessment and its application — they do not need an
            account. <strong>Org access</strong> covers the external users assigned to the
            application's organization — they hear about everything in it. An external user
            restricted to specific applications only hears about those. People who <em>do</em> have accounts can
            still mute what they receive from their own profile.
          </p>

          {error && (
            <div className="email-notifications-banner email-notifications-banner--error">
              <AlertTriangle size={15} /> {error}
            </div>
          )}

          {!config.smtpConfigured && (
            <div className="email-notifications-banner email-notifications-banner--warning">
              <AlertTriangle size={15} />
              SMTP is not configured or is switched off, so nothing here will send. Set it
              up under Email Config first.
            </div>
          )}
        </div>
      </div>

      {grouped.map(group => (
        <div className="email-notifications-card" key={group.title}>
          <div className="email-notifications-card-header">
            <span>{group.title}</span>
          </div>

          <div className="email-notifications-body">
            <p className="email-notifications-hint">{group.hint}</p>

            <div className="email-notifications-table-scroll">
            <table className="email-notifications-table">
              <thead>
                <tr>
                  <th>Event</th>
                  {AUDIENCES.map(a => (
                    <th key={a.key} className="email-notifications-audience">{a.label}</th>
                  ))}
                  <th className="email-notifications-audience">Message</th>
                </tr>
              </thead>
              <tbody>
                {group.events.map(event => {
                  const editing = editingMessage.has(event.key);
                  return [
                    <tr key={event.key}>
                      <td>
                        <div className="email-notifications-label">{event.label}</div>
                        <div className="email-notifications-description">{event.description}</div>
                      </td>

                      {AUDIENCES.map(audience => (
                        <td key={audience.key} className="email-notifications-audience">
                          {event.audiences.includes(audience.key) ? (
                            <label className="notif-toggle">
                              <input
                                type="checkbox"
                                checked={event[audience.field]}
                                disabled={saving.has(`${event.key}:${audience.field}`)}
                                onChange={e => toggleAudience(event, audience.field, e.target.checked)}
                                aria-label={`${event.label} — ${audience.label}`}
                              />
                              <span className="notif-toggle-track" />
                            </label>
                          ) : (
                            // Not offered for this event, rather than off — a dash says so
                            // without implying it could be switched on.
                            <span className="email-notifications-na" aria-hidden="true">—</span>
                          )}
                        </td>
                      ))}

                      <td className="email-notifications-audience">
                        <button
                          type="button"
                          className={`email-notifications-message-btn${
                            event.customMessage ? ' email-notifications-message-btn--set' : ''}`}
                          onClick={() => toggleMessageEditor(event)}
                          title={event.customMessage || 'Add wording of your own'}
                          aria-label={`${event.label} — custom message`}
                        >
                          {editing ? <X size={14} /> : <MessageSquareText size={14} />}
                        </button>
                      </td>
                    </tr>,

                    editing && (
                      <tr key={`${event.key}-message`} className="email-notifications-message-row">
                        <td colSpan={AUDIENCES.length + 2}>
                          <label className="email-notifications-message-label">
                            Wording added to this email
                          </label>
                          <textarea
                            className="email-notifications-message-input"
                            rows={2}
                            value={drafts[event.key] ?? ''}
                            placeholder="e.g. Please schedule remediation, or to avoid escalation schedule a retest and update the team."
                            onChange={e =>
                              setDrafts(prev => ({ ...prev, [event.key]: e.target.value }))}
                            onBlur={() => saveMessage(event)}
                          />
                          <p className="email-notifications-hint">
                            Shown as the call to action at the end of the email.
                            {saving.has(`${event.key}:message`) && ' Saving…'}
                          </p>
                        </td>
                      </tr>
                    ),
                  ];
                })}
              </tbody>
            </table>
            </div>

            {group.title === 'Vulnerabilities' && (
              <div className="email-notifications-repeats">
                <div className="email-notifications-label">Past-due reminders</div>
                <p className="email-notifications-hint">
                  A finding is reported once when it breaches its SLA. Repeats chase it
                  after that, then stop.
                </p>
                <div className="email-notifications-repeat-fields">
                  <label>
                    <span>Number of repeats</span>
                    <Input
                      type="number"
                      min={0}
                      value={repeatCount}
                      disabled={saving.has('repeats')}
                      onChange={e => setRepeatCount(e.target.value)}
                      onBlur={saveRepeats}
                    />
                  </label>
                  <label>
                    <span>Days between repeats</span>
                    <Input
                      type="number"
                      min={1}
                      value={repeatInterval}
                      disabled={saving.has('repeats')}
                      onChange={e => setRepeatInterval(e.target.value)}
                      onBlur={saveRepeats}
                    />
                  </label>
                </div>
              </div>
            )}
          </div>
        </div>
      ))}
    </Page>
  );
}
