import { useEffect, useState } from 'react';
import { Bell, Loader2 } from 'lucide-react';
import { notificationPreferencesApi } from '../api';
import type { NotificationCategory, NotificationPreference } from '../types';
import { ErrorMessage } from '../components';

/**
 * Per-category notification opt-out, split by channel.
 *
 * Each toggle saves on its own rather than behind a Save button — these read as live
 * switches, and staging them is exactly what made the SMTP enable switch look broken.
 * Only the one changed category and channel is sent, so a toggle can never commit
 * something else the user did not intend.
 */
export default function NotificationPreferencesSection() {
  const [preferences, setPreferences] = useState<NotificationPreference[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  /** Keyed `${category}:${channel}` so only the switch in flight shows as busy. */
  const [saving, setSaving] = useState<Set<string>>(new Set());

  useEffect(() => {
    notificationPreferencesApi.get()
      .then(res => setPreferences(res.data ?? []))
      .catch(() => setError('Could not load your notification preferences.'));
  }, []);

  const setBusy = (key: string, busy: boolean) =>
    setSaving(prev => {
      const next = new Set(prev);
      if (busy) next.add(key); else next.delete(key);
      return next;
    });

  const toggle = async (
    category: NotificationCategory,
    channel: 'inAppEnabled' | 'emailEnabled',
    next: boolean,
  ) => {
    const key = `${category}:${channel}`;
    setError(null);
    setBusy(key, true);

    // Optimistic, so the switch responds immediately.
    setPreferences(prev => prev?.map(p =>
      p.category === category ? { ...p, [channel]: next } : p) ?? prev);

    try {
      const res = await notificationPreferencesApi.update({
        preferences: [{ category, [channel]: next }],
      });
      if (res.data) setPreferences(res.data);
    } catch {
      // Roll back, so a switch never claims a setting that was not stored.
      setPreferences(prev => prev?.map(p =>
        p.category === category ? { ...p, [channel]: !next } : p) ?? prev);
      setError('Could not save that change. Check your connection and try again.');
    } finally {
      setBusy(key, false);
    }
  };

  return (
    <section className="profile-card">
      <h2 className="profile-card-title">
        <Bell size={16} /> Notifications
      </h2>

      {error && <ErrorMessage>{error}</ErrorMessage>}

      {preferences === null ? (
        <div className="notif-pref-loading"><Loader2 size={16} className="spin" /> Loading…</div>
      ) : (
        <table className="notif-pref-table">
          <thead>
            <tr>
              <th>Notify me about</th>
              <th className="notif-pref-channel">In app</th>
              <th className="notif-pref-channel">Email</th>
            </tr>
          </thead>
          <tbody>
            {preferences.map(pref => (
              <tr key={pref.category}>
                <td>
                  <div className="notif-pref-label">{pref.label}</div>
                  <div className="notif-pref-description">{pref.description}</div>
                </td>
                {(['inAppEnabled', 'emailEnabled'] as const).map(channel => (
                  <td key={channel} className="notif-pref-channel">
                    <label className="notif-toggle">
                      <input
                        type="checkbox"
                        checked={pref[channel]}
                        disabled={saving.has(`${pref.category}:${channel}`)}
                        onChange={e => toggle(pref.category, channel, e.target.checked)}
                        aria-label={`${pref.label} — ${channel === 'emailEnabled' ? 'email' : 'in app'}`}
                      />
                      <span className="notif-toggle-track" />
                    </label>
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
