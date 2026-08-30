import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { CheckCircle2, XCircle, Loader2, MailX } from 'lucide-react';
import api from '../api';
import './UnsubscribePage.css';

/**
 * Landing page for the "Remove me from this conversation" link in an email.
 *
 * Unauthenticated: the token in the link is the authority, and requiring a login to stop
 * receiving mail is how people mark messages as spam instead.
 *
 * The removal happens on a click rather than on page load, because mail clients and
 * security scanners prefetch links — a mutation on load would unsubscribe people who
 * merely received the email.
 */
export default function UnsubscribePage() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';

  const [state, setState] = useState<'idle' | 'working' | 'done' | 'error'>('idle');
  const [message, setMessage] = useState('');

  useEffect(() => {
    if (!token) {
      setState('error');
      setMessage('This unsubscribe link is missing its token.');
    }
  }, [token]);

  const confirm = async () => {
    setState('working');
    try {
      const res = await api.post('/email/unsubscribe', { token });
      const result = res.data?.data;
      setMessage(result?.message ?? 'You have been removed from the conversation.');
      setState(result?.success ? 'done' : 'error');
    } catch {
      setState('error');
      setMessage('Something went wrong. Please try again, or change this in Faction directly.');
    }
  };

  return (
    <div className="unsub-page">
      <div className="unsub-card">
        <div className="unsub-icon"><MailX size={28} /></div>
        <h1 className="unsub-title">Stop emails about this conversation?</h1>

        {state === 'idle' && (
          <>
            <p className="unsub-text">
              You will no longer be notified when someone comments on this item. You can
              rejoin at any time from the item in Faction.
            </p>
            <button className="unsub-btn" onClick={confirm} disabled={!token}>
              Remove me from this conversation
            </button>
          </>
        )}

        {state === 'working' && (
          <p className="unsub-text"><Loader2 size={16} className="spin" /> Working…</p>
        )}

        {state === 'done' && (
          <p className="unsub-result unsub-result--ok">
            <CheckCircle2 size={18} /> {message}
          </p>
        )}

        {state === 'error' && (
          <p className="unsub-result unsub-result--error">
            <XCircle size={18} /> {message}
          </p>
        )}
      </div>
    </div>
  );
}
