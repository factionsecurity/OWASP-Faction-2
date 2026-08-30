import { useEffect, useState } from 'react';
import { usePageTitle } from '../context/PageTitleContext';
import { emailConfigApi } from '../api';
import type { EmailConfig } from '../types';
import { Button, Input, FormLabel, Select } from '../components';
import Page from '../components/Page';
import { CheckCircle2, XCircle, Loader2, Mail, Send, ImagePlus, X } from 'lucide-react';
import './EmailConfigPage.css';

const MASKED = '••••••••';

interface Provider {
  label: string;
  host: string;
  port: number;
  security: string;
  note?: string;
}

const PROVIDERS: Record<string, Provider> = {
  GMAIL: {
    label: 'Gmail',
    host: 'smtp.gmail.com',
    port: 587,
    security: 'STARTTLS',
    note: 'Use an App Password if 2-Step Verification is enabled.',
  },
  OUTLOOK: {
    label: 'Outlook / Hotmail',
    host: 'smtp-mail.outlook.com',
    port: 587,
    security: 'STARTTLS',
  },
  OFFICE365: {
    label: 'Microsoft 365',
    host: 'smtp.office365.com',
    port: 587,
    security: 'STARTTLS',
    note: 'Requires SMTP AUTH enabled on the mailbox.',
  },
  YAHOO: {
    label: 'Yahoo Mail',
    host: 'smtp.mail.yahoo.com',
    port: 587,
    security: 'STARTTLS',
    note: 'Use an App Password.',
  },
  SENDGRID: {
    label: 'SendGrid',
    host: 'smtp.sendgrid.net',
    port: 587,
    security: 'STARTTLS',
    note: 'Use "apikey" as username and your API key as password.',
  },
  MAILGUN: {
    label: 'Mailgun',
    host: 'smtp.mailgun.org',
    port: 587,
    security: 'STARTTLS',
  },
  CUSTOM: {
    label: 'Custom SMTP',
    host: '',
    port: 587,
    security: 'STARTTLS',
  },
};

interface TestResult {
  success: boolean;
  message: string;
  details?: string;
}

export default function EmailConfigPage() {
  const { setPageTitle } = usePageTitle();

  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<TestResult | null>(null);
  const [togglingEnabled, setTogglingEnabled] = useState(false);

  // Form state
  const [enabled, setEnabled] = useState(false);
  const [provider, setProvider] = useState('CUSTOM');
  const [host, setHost] = useState('');
  const [port, setPort] = useState(587);
  const [security, setSecurity] = useState('STARTTLS');
  const [authEnabled, setAuthEnabled] = useState(true);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [fromName, setFromName] = useState('');
  const [fromEmail, setFromEmail] = useState('');
  const [logoBase64, setLogoBase64] = useState('');
  const [logoMimeType, setLogoMimeType] = useState('');
  const [testRecipient, setTestRecipient] = useState('');

  useEffect(() => {
    setPageTitle('Email Configuration');
    loadConfig();
  }, []);

  const loadConfig = async () => {
    setLoading(true);
    try {
      const res = await emailConfigApi.getConfig();
      if (res.data) populate(res.data);
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  };

  const populate = (c: EmailConfig) => {
    setEnabled(c.enabled);
    setProvider(c.provider || 'CUSTOM');
    setHost(c.host || '');
    setPort(c.port || 587);
    setSecurity(c.security || 'STARTTLS');
    setAuthEnabled(c.authEnabled);
    setUsername(c.username || '');
    setPassword(c.password || '');
    setFromName(c.fromName || '');
    setFromEmail(c.fromEmail || '');
    setLogoBase64(c.logoBase64 || '');
    setLogoMimeType(c.logoMimeType || '');
  };

  const applyPreset = (key: string) => {
    setProvider(key);
    const p = PROVIDERS[key];
    if (!p) return;
    if (p.host) setHost(p.host);
    setPort(p.port);
    setSecurity(p.security);
    setAuthEnabled(true);
  };

  /**
   * The enable switch saves on its own rather than waiting for "Save Configuration" —
   * it reads as a live on/off control, so leaving it staged was silently keeping email
   * switched off for anyone who toggled it and navigated away.
   *
   * Only `enabled` is sent: the rest of the form may hold unsaved edits, and flipping a
   * switch must not quietly commit those too.
   */
  const handleToggleEnabled = async (next: boolean) => {
    setEnabled(next); // optimistic, so the switch responds immediately
    setSaveError(null);
    setSaveSuccess(false);
    setTogglingEnabled(true);
    try {
      const res = await emailConfigApi.updateConfig({ enabled: next });
      // Deliberately not populate() — that would overwrite in-progress edits to the
      // other fields with the server's values.
      if (res.data) setEnabled(res.data.enabled);
      setSaveSuccess(true);
      setTimeout(() => setSaveSuccess(false), 3000);
    } catch {
      setEnabled(!next); // roll back so the switch never lies about the stored state
      setSaveError(`Could not turn email ${next ? 'on' : 'off'}. Check your connection and try again.`);
    } finally {
      setTogglingEnabled(false);
    }
  };

  const handleSave = async () => {
    setSaving(true);
    setSaveSuccess(false);
    setSaveError(null);
    try {
      const res = await emailConfigApi.updateConfig({
        enabled,
        provider,
        host,
        port,
        security,
        authEnabled,
        username: username || undefined,
        password: password || undefined,
        fromName: fromName || undefined,
        fromEmail: fromEmail || undefined,
        logoBase64: logoBase64 || '',
        logoMimeType: logoMimeType || undefined,
      });
      if (res.data) populate(res.data);
      setSaveSuccess(true);
      setTimeout(() => setSaveSuccess(false), 3000);
    } catch {
      // Previously swallowed, which is why a failed save looked like a no-op.
      setSaveError('Could not save the configuration. Check your connection and try again.');
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async () => {
    setTesting(true);
    setTestResult(null);
    try {
      const res = await emailConfigApi.test({ to: testRecipient.trim() });
      if (res.data) setTestResult(res.data);
    } catch {
      setTestResult({ success: false, message: 'Request failed. Check your network connection.' });
    } finally {
      setTesting(false);
    }
  };

  const currentPreset = PROVIDERS[provider];

  if (loading) {
    return (
      <div className="email-config-page">
        <div className="email-config-loading">
          <Loader2 size={24} className="spin" /> Loading…
        </div>
      </div>
    );
  }

  return (
    <Page variant="narrow" className="email-config-page">
      {/* Enable toggle */}
      <div className="email-config-card">
        <div className="email-config-card-header">
          <Mail size={18} />
          <span>SMTP Email</span>
          <label className="email-toggle" style={{ marginLeft: 'auto' }}>
            <input
              type="checkbox"
              checked={enabled}
              disabled={togglingEnabled}
              onChange={e => handleToggleEnabled(e.target.checked)}
            />
            <span className="email-toggle-track" />
            <span className="email-toggle-label">
              {togglingEnabled ? 'Saving…' : enabled ? 'Enabled' : 'Disabled'}
            </span>
          </label>
        </div>

        {saveError && (
          <div className="email-config-body">
            <div className="email-test-result email-test-result--error">
              <div className="email-test-result-message">{saveError}</div>
            </div>
          </div>
        )}

        {/* Provider picker */}
        <div className="email-config-body">
          <div className="form-group">
            <FormLabel>Email Provider</FormLabel>
            <div className="email-provider-grid">
              {Object.entries(PROVIDERS).map(([key, p]) => (
                <button
                  key={key}
                  type="button"
                  className={`email-provider-btn${provider === key ? ' email-provider-btn--active' : ''}`}
                  onClick={() => applyPreset(key)}
                >
                  {p.label}
                </button>
              ))}
            </div>
            {currentPreset?.note && (
              <p className="email-config-hint">{currentPreset.note}</p>
            )}
          </div>

          {/* Connection */}
          <h4 className="email-config-section-title">Connection</h4>
          <div className="email-config-row">
            <div className="form-group" style={{ flex: 3 }}>
              <FormLabel required>SMTP Host</FormLabel>
              <Input
                value={host}
                onChange={e => setHost(e.target.value)}
                placeholder="smtp.example.com"
              />
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <FormLabel required>Port</FormLabel>
              <Input
                type="number"
                value={port}
                onChange={e => setPort(Number(e.target.value))}
                placeholder="587"
              />
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <FormLabel>Security</FormLabel>
              <Select value={security} onChange={e => setSecurity(e.target.value)}>
                <option value="STARTTLS">STARTTLS</option>
                <option value="SSL_TLS">SSL / TLS</option>
                <option value="NONE">None</option>
              </Select>
            </div>
          </div>

          {/* Auth */}
          <h4 className="email-config-section-title">Authentication</h4>
          <label className="email-inline-toggle">
            <input type="checkbox" checked={authEnabled} onChange={e => setAuthEnabled(e.target.checked)} />
            <span>Require authentication</span>
          </label>

          {authEnabled && (
            <div className="email-config-row" style={{ marginTop: '0.75rem' }}>
              <div className="form-group" style={{ flex: 1 }}>
                <FormLabel>Username</FormLabel>
                <Input
                  value={username}
                  onChange={e => setUsername(e.target.value)}
                  placeholder="user@example.com"
                  autoComplete="off"
                />
              </div>
              <div className="form-group" style={{ flex: 1 }}>
                <FormLabel>Password</FormLabel>
                <Input
                  type="password"
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  placeholder={password === MASKED ? 'Leave unchanged' : 'Enter password'}
                  autoComplete="new-password"
                />
                {password === MASKED && (
                  <p className="email-config-hint">A password is stored. Enter a new value to replace it.</p>
                )}
              </div>
            </div>
          )}

          {/* Sender identity */}
          <h4 className="email-config-section-title">Sender Identity</h4>
          <div className="email-config-row">
            <div className="form-group" style={{ flex: 1 }}>
              <FormLabel>From Name</FormLabel>
              <Input
                value={fromName}
                onChange={e => setFromName(e.target.value)}
                placeholder="Faction Security"
              />
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <FormLabel>From Email</FormLabel>
              <Input
                type="email"
                value={fromEmail}
                onChange={e => setFromEmail(e.target.value)}
                placeholder="noreply@example.com"
              />
            </div>
          </div>

          {/* Email Logo */}
          <h4 className="email-config-section-title">Email Logo</h4>
          <p className="email-config-hint" style={{ marginBottom: '0.5rem' }}>
            Shown in the header of notification emails. Defaults to the Faction logo if not set.
          </p>
          {logoBase64 ? (
            <div className="email-logo-preview">
              <img
                src={`data:${logoMimeType || 'image/png'};base64,${logoBase64}`}
                alt="Email logo preview"
                className="email-logo-img"
              />
              <button
                type="button"
                className="email-logo-remove"
                onClick={() => { setLogoBase64(''); setLogoMimeType(''); }}
                title="Remove logo"
              >
                <X size={14} /> Remove
              </button>
            </div>
          ) : (
            <label className="email-logo-upload">
              <input
                type="file"
                accept="image/*"
                style={{ display: 'none' }}
                onChange={e => {
                  const file = e.target.files?.[0];
                  if (!file) return;
                  const reader = new FileReader();
                  reader.onload = ev => {
                    const result = ev.target?.result as string;
                    // result is "data:image/png;base64,ABC123..."
                    const [header, b64] = result.split(',');
                    const mime = header.replace('data:', '').replace(';base64', '');
                    setLogoBase64(b64);
                    setLogoMimeType(mime);
                  };
                  reader.readAsDataURL(file);
                  e.target.value = '';
                }}
              />
              <ImagePlus size={16} />
              <span>Upload logo</span>
            </label>
          )}

          <div className="email-config-actions">
            {saveSuccess && (
              <span className="email-config-saved">
                <CheckCircle2 size={15} /> Saved
              </span>
            )}
            <Button variant="primary" onClick={handleSave} disabled={saving}>
              {saving ? <><Loader2 size={14} className="spin" /> Saving…</> : 'Save Configuration'}
            </Button>
          </div>
        </div>
      </div>

      {/* Test connection */}
      <div className="email-config-card">
        <div className="email-config-card-header">
          <Send size={18} />
          <span>Test Connection</span>
        </div>
        <div className="email-config-body">
          <p className="email-config-hint" style={{ marginBottom: '1rem' }}>
            Save your configuration first, then send a test email to verify the connection.
            Leave the recipient blank to test the SMTP handshake only.
          </p>
          <div className="email-config-row" style={{ alignItems: 'flex-end' }}>
            <div className="form-group" style={{ flex: 1 }}>
              <FormLabel>Test Recipient (optional)</FormLabel>
              <Input
                type="email"
                value={testRecipient}
                onChange={e => setTestRecipient(e.target.value)}
                placeholder="you@example.com"
              />
            </div>
            <div style={{ marginBottom: '0.5rem' }}>
            <Button
              variant="secondary"
              onClick={handleTest}
              disabled={testing}
            >
              {testing
                ? <><Loader2 size={14} className="spin" /> Testing…</>
                : testRecipient.trim()
                  ? 'Send Test Email'
                  : 'Test Connection'}
            </Button>
            </div>
          </div>

          {testResult && (
            <div className={`email-test-result${testResult.success ? ' email-test-result--success' : ' email-test-result--error'}`}>
              {testResult.success
                ? <CheckCircle2 size={16} />
                : <XCircle size={16} />}
              <div>
                <div className="email-test-result-message">{testResult.message}</div>
                {testResult.details && (
                  <div className="email-test-result-details">{testResult.details}</div>
                )}
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Encryption notice */}
      <div className="email-config-notice">
        Passwords are encrypted at rest using AES-256-GCM with the key from the{' '}
        <code>SSO_ENCRYPTION_KEY</code> environment variable. Ensure this is set before saving
        credentials.
      </div>
    </Page>
  );
}
