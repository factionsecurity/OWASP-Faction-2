import { useEffect, useState } from 'react';
import { terminologyApi } from '../api';
import type { TerminologyConfig, VulnerabilitySeverity } from '../types';
import { Button, ErrorMessage, Input } from '../components';
import Page from '../components/Page';
import SeverityBadge from '../components/SeverityBadge';
import { useTerminology } from '../context/TerminologyContext';
import { VULNERABILITY_SEVERITIES, SEVERITY_LABELS } from '../utils/vulnSeverity';
import './SeverityNamesConfig.css';

interface Props {
  /**
   * Rendered inside another routed page's tab rather than on its own route. Skips the {@code Page}
   * wrapper, since the host page already provides one.
   */
  embedded?: boolean;
}

/** The terminology field holding each severity's label. */
const FIELD: Record<VulnerabilitySeverity, keyof TerminologyConfig> = {
  CRITICAL: 'severityCritical',
  HIGH: 'severityHigh',
  MEDIUM: 'severityMedium',
  LOW: 'severityLow',
  INFORMATIONAL: 'severityInformational',
};

/**
 * What this installation calls each severity.
 *
 * <p>Renaming is wording only. The five levels, their order and their colours are fixed, and a
 * finding recorded as Critical stays Critical to every filter, report token and export — so the
 * page says as much rather than leaving an administrator to guess how far the change reaches.
 *
 * <p>Saved explicitly rather than on a debounce, unlike the organization labels: these names end
 * up in generated reports, and a half-typed "Sev" landing in the database between keystrokes is a
 * worse failure than an extra click.
 */
export default function SeverityNamesConfig({ embedded = false }: Props) {
  const { refresh } = useTerminology();
  const [config, setConfig] = useState<TerminologyConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    terminologyApi.getConfig()
      .then(res => { if (res.data) setConfig(res.data); })
      .catch(() => setError('Could not load the severity names.'))
      .finally(() => setLoading(false));
  }, []);

  const set = (key: keyof TerminologyConfig, value: string) => {
    setConfig(prev => (prev ? { ...prev, [key]: value } : prev));
    setSaved(false);
  };

  const handleSave = async () => {
    if (!config) return;
    setSaving(true);
    setError('');
    try {
      // The whole config goes back, organization labels included — they share one record, and
      // sending only the severities would be a partial update of a row this page does not own.
      const res = await terminologyApi.updateConfig(config);
      if (res.data) setConfig(res.data);
      await refresh();
      setSaved(true);
    } catch (err: any) {
      setError(err?.response?.data?.message || 'Could not save the severity names.');
    } finally {
      setSaving(false);
    }
  };

  const handleReset = () => {
    if (!config) return;
    setConfig({
      ...config,
      severityCritical: SEVERITY_LABELS.CRITICAL,
      severityHigh: SEVERITY_LABELS.HIGH,
      severityMedium: SEVERITY_LABELS.MEDIUM,
      severityLow: SEVERITY_LABELS.LOW,
      severityInformational: SEVERITY_LABELS.INFORMATIONAL,
    });
    setSaved(false);
  };

  const isDefault = config != null && VULNERABILITY_SEVERITIES.every(
    s => config[FIELD[s]] === SEVERITY_LABELS[s]);

  const content = loading || !config ? (
    <p>Loading…</p>
  ) : (
    <>
      <h2>Severity Names</h2>
      <p className="sevnames-intro">
        What this installation calls each severity. Wording only — the five levels, their order and
        their colours do not change, and a finding recorded as Critical is still Critical to every
        filter, report token and export.
      </p>

      {error && <ErrorMessage>{error}</ErrorMessage>}

      <div className="sevnames-card">
        <div className="sevnames-head">
          <span>Severity</span>
          <span>Your name for it</span>
          <span>Preview</span>
        </div>

        {VULNERABILITY_SEVERITIES.map(severity => {
          const field = FIELD[severity];
          const value = config[field] as string;
          return (
            <div key={severity} className="sevnames-row">
              <label htmlFor={`severity-${severity}`} className="sevnames-name">
                {SEVERITY_LABELS[severity]}
              </label>
              <Input
                id={`severity-${severity}`}
                value={value}
                placeholder={SEVERITY_LABELS[severity]}
                onChange={e => set(field, e.target.value)}
              />
              {/* The badge is the control this actually changes, so it is shown rather than
                  described — including that the colour stays put when the word does not. The
                  colour comes from the severity, the text from the box, so neither the half-typed
                  word nor the saved label can stand in for the other. */}
              <SeverityBadge
                severity={severity}
                label={value.trim() || SEVERITY_LABELS[severity]}
                size="sm"
              />
            </div>
          );
        })}

        <p className="sevnames-hint">
          Leave a field empty to keep the name it already has.
        </p>
      </div>

      <div className="sevnames-actions">
        <Button variant="primary" onClick={handleSave} disabled={saving}>
          {saving ? 'Saving…' : 'Save Severity Names'}
        </Button>
        <Button variant="secondary" onClick={handleReset} disabled={saving || isDefault}>
          Reset to defaults
        </Button>
        {saved && <span className="sevnames-saved">Saved</span>}
      </div>
    </>
  );

  return embedded ? content : <Page variant="narrow">{content}</Page>;
}
