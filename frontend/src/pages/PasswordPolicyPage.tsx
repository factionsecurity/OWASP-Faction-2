import { useEffect, useState } from 'react';
import { KeyRound, Save } from 'lucide-react';
import { passwordPolicyApi } from '../api';
import type { PasswordPolicy } from '../types';
import { Button, Checkbox, ErrorMessage, FormGroup, FormHint, FormLabel, Input } from '../components';
import Page from '../components/Page';
import './PasswordPolicyPage.css';

const DEFAULTS: PasswordPolicy = {
  maxFailedLoginAttempts: 5,
  lockoutDurationMinutes: 15,
  minimumLength: 12,
  requireUppercase: true,
  requireLowercase: true,
  requireDigit: true,
  requireSymbol: false,
};

/** A whole number from an input, floored at zero — an empty box means 0, not NaN. */
const asCount = (value: string): number => {
  const parsed = parseInt(value, 10);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : 0;
};

export default function PasswordPolicyPage() {
  const [policy, setPolicy] = useState<PasswordPolicy>(DEFAULTS);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    passwordPolicyApi.getPolicy()
      .then(res => { if (res.data) setPolicy(res.data); })
      .catch(() => setError('Could not load the password policy.'))
      .finally(() => setLoading(false));
  }, []);

  const set = <K extends keyof PasswordPolicy>(key: K, value: PasswordPolicy[K]) => {
    setPolicy(prev => ({ ...prev, [key]: value }));
    setSaved(false);
  };

  const handleSave = async () => {
    setSaving(true);
    setError('');
    try {
      const res = await passwordPolicyApi.updatePolicy(policy);
      if (res.data) setPolicy(res.data);
      setSaved(true);
    } catch (err: any) {
      setError(err?.response?.data?.message || 'Could not save the password policy.');
    } finally {
      setSaving(false);
    }
  };

  const lockoutOff = policy.maxFailedLoginAttempts === 0;
  const locksUntilAdmin = policy.lockoutDurationMinutes === 0;

  if (loading) {
    return <Page variant="narrow"><p>Loading…</p></Page>;
  }

  return (
    <Page variant="narrow" className="password-policy-page">
      <div className="page-header">
        <h2><KeyRound size={18} /> Password Policy</h2>
        <p className="password-policy-intro">
          Applies to every password set on this installation — by a user, by an administrator, and
          through a reset link.
        </p>
      </div>

      {error && <ErrorMessage>{error}</ErrorMessage>}

      <section className="password-policy-card">
        <h3>Signing in</h3>

        <FormGroup>
          <FormLabel>Failed attempts before lockout</FormLabel>
          <Input
            type="number"
            min={0}
            value={policy.maxFailedLoginAttempts}
            onChange={e => set('maxFailedLoginAttempts', asCount(e.target.value))}
          />
          <FormHint>
            Consecutive wrong passwords, counted per account and reset by a successful sign-in.
            Set 0 to never lock an account out.
          </FormHint>
        </FormGroup>

        <FormGroup>
          <FormLabel>Lockout lasts</FormLabel>
          <div className="password-policy-inline">
            <Input
              type="number"
              min={0}
              disabled={lockoutOff}
              value={policy.lockoutDurationMinutes}
              onChange={e => set('lockoutDurationMinutes', asCount(e.target.value))}
            />
            <span>minutes</span>
          </div>
          <FormHint>
            Set 0 to keep the account locked until an administrator re-enables it from the Users
            page.
          </FormHint>
          {/* The choice with a real trade-off, so it says so rather than leaving it to be
              discovered. There is no rate limiting in front of the sign-in endpoint. */}
          {locksUntilAdmin && !lockoutOff && (
            <div className="password-policy-warning">
              <strong>Locked until an administrator acts.</strong> This is the stronger control, but
              anyone who knows a username can lock that account out by guessing at it, and only an
              administrator can lift it — including for your own admin accounts. A short cooldown
              slows guessing just as effectively without that.
            </div>
          )}
          {lockoutOff && (
            <div className="password-policy-warning">
              <strong>Lockout is off.</strong> Passwords can be guessed at without limit.
            </div>
          )}
        </FormGroup>
      </section>

      <section className="password-policy-card">
        <h3>Password requirements</h3>

        <FormGroup>
          <FormLabel>Minimum length</FormLabel>
          <Input
            type="number"
            min={1}
            max={256}
            value={policy.minimumLength}
            onChange={e => set('minimumLength', asCount(e.target.value))}
          />
          <FormHint>Length does more for strength than any other rule here.</FormHint>
        </FormGroup>

        <FormGroup>
          <FormLabel>Must contain</FormLabel>
          <div className="password-policy-checks">
            <Checkbox
              id="requireUppercase"
              checked={policy.requireUppercase}
              onChange={e => set('requireUppercase', e.target.checked)}
              label="An uppercase letter"
            />
            <Checkbox
              id="requireLowercase"
              checked={policy.requireLowercase}
              onChange={e => set('requireLowercase', e.target.checked)}
              label="A lowercase letter"
            />
            <Checkbox
              id="requireDigit"
              checked={policy.requireDigit}
              onChange={e => set('requireDigit', e.target.checked)}
              label="A number"
            />
            <Checkbox
              id="requireSymbol"
              checked={policy.requireSymbol}
              onChange={e => set('requireSymbol', e.target.checked)}
              label="A symbol"
            />
          </div>
        </FormGroup>
      </section>

      <div className="password-policy-actions">
        <Button variant="primary" icon={Save} onClick={handleSave} disabled={saving}>
          {saving ? 'Saving…' : 'Save Policy'}
        </Button>
        {saved && <span className="password-policy-saved">Saved</span>}
      </div>
    </Page>
  );
}
