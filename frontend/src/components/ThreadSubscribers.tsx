import { useEffect, useRef, useState } from 'react';
import { UserPlus, X, Loader2 } from 'lucide-react';
import { usersApi } from '../api';
import UserAvatar from './UserAvatar';
import './ThreadSubscribers.css';

/**
 * The two calls that mutate membership. Injected rather than chosen inside, because both
 * applications and vulnerabilities have a thread and their endpoints differ only in shape —
 * a `targetType` switch here would put routing knowledge in a presentational component.
 */
export interface ThreadSubscriberApi {
  add: (username: string) => Promise<string[] | undefined>;
  remove: (username: string) => Promise<string[] | undefined>;
}

interface Props {
  api: ThreadSubscriberApi;
  /** Usernames from the loaded parent; this component owns the list from then on. */
  initial: string[];
  /** The signed-in user, so the list can offer "Assign me" rather than a search. */
  currentUsername: string;
  disabled?: boolean;
  onChange?: (subscribers: string[]) => void;
}

interface Candidate {
  username: string;
  display: string;
}

/**
 * Who is on a discussion — a finding's or an application's. Everyone listed is notified in app and by email when
 * a comment is added, and can reply to that email to post back.
 *
 * Explicit membership rather than inferred from who has commented: derived membership is
 * invisible, so people cannot tell whether they are on a thread, and cannot get off it.
 */
export default function ThreadSubscribers({
  api, initial, currentUsername, disabled = false, onChange,
}: Props) {
  const [subscribers, setSubscribers] = useState<string[]>(initial);
  const [adding, setAdding] = useState(false);
  const [query, setQuery] = useState('');
  const [candidates, setCandidates] = useState<Candidate[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const searchTimer = useRef<ReturnType<typeof setTimeout>>();
  const pickerRef = useRef<HTMLDivElement>(null);

  useEffect(() => setSubscribers(initial), [initial]);

  // Debounced directory search, matching the @mention picker's behaviour.
  useEffect(() => {
    if (!adding) return;
    clearTimeout(searchTimer.current);
    searchTimer.current = setTimeout(async () => {
      try {
        const res = await usersApi.getAll(0, 10, query);
        setCandidates((res.data ?? []).map((u: { username: string; firstName: string; lastName: string }) => ({
          username: u.username,
          display: [u.firstName, u.lastName].filter(Boolean).join(' ') || u.username,
        })));
      } catch {
        setCandidates([]);
      }
    }, 150);
    return () => clearTimeout(searchTimer.current);
  }, [query, adding]);

  useEffect(() => {
    if (!adding) return;
    const close = (e: MouseEvent) => {
      if (!pickerRef.current?.contains(e.target as Node)) setAdding(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [adding]);

  const apply = (next: string[]) => {
    setSubscribers(next);
    onChange?.(next);
  };

  const add = async (username: string) => {
    setError(null);
    setBusy(username);
    try {
      const next = await api.add(username);
      if (next) apply(next);
      setAdding(false);
      setQuery('');
    } catch {
      setError('Could not add that person to the conversation.');
    } finally {
      setBusy(null);
    }
  };

  const remove = async (username: string) => {
    setError(null);
    setBusy(username);
    try {
      const next = await api.remove(username);
      if (next) apply(next);
    } catch {
      setError('Could not remove that person from the conversation.');
    } finally {
      setBusy(null);
    }
  };

  const onThread = subscribers.includes(currentUsername);
  const available = candidates.filter(c => !subscribers.includes(c.username));

  return (
    <div className="thread-subs">
      <div className="thread-subs-header">
        <span className="thread-subs-title">On this conversation</span>
        {!disabled && (
          <div className="thread-subs-actions">
            {onThread ? (
              <button
                type="button"
                className="thread-subs-btn"
                onClick={() => remove(currentUsername)}
                disabled={busy === currentUsername}
              >
                Leave
              </button>
            ) : (
              <button
                type="button"
                className="thread-subs-btn"
                onClick={() => add(currentUsername)}
                disabled={busy === currentUsername}
              >
                Assign me
              </button>
            )}
            <button
              type="button"
              className="thread-subs-btn thread-subs-btn--icon"
              onClick={() => { setAdding(v => !v); setQuery(''); }}
              title="Add someone to this conversation"
            >
              <UserPlus size={13} /> Add
            </button>
          </div>
        )}
      </div>

      {subscribers.length === 0 ? (
        <div className="thread-subs-empty">
          Nobody is following this yet. Anyone added here is notified — in the app and by
          email — whenever a comment is posted.
        </div>
      ) : (
        <div className="thread-subs-list">
          {subscribers.map(username => (
            <span key={username} className="thread-subs-chip">
              <UserAvatar userId={username} name={username} size={20} />
              <span className="thread-subs-name">{username}</span>
              {!disabled && (
                <button
                  type="button"
                  className="thread-subs-remove"
                  onClick={() => remove(username)}
                  disabled={busy === username}
                  title={`Remove ${username} from this conversation`}
                >
                  {busy === username ? <Loader2 size={11} className="spin" /> : <X size={11} />}
                </button>
              )}
            </span>
          ))}
        </div>
      )}

      {adding && (
        <div className="thread-subs-picker" ref={pickerRef}>
          <input
            autoFocus
            className="thread-subs-search"
            placeholder="Search people…"
            value={query}
            onChange={e => setQuery(e.target.value)}
          />
          <div className="thread-subs-results">
            {available.length === 0 ? (
              <div className="thread-subs-no-results">No matching people</div>
            ) : available.map(c => (
              <button
                key={c.username}
                type="button"
                className="thread-subs-result"
                onClick={() => add(c.username)}
                disabled={busy === c.username}
              >
                <UserAvatar userId={c.username} name={c.display} size={20} />
                <span className="thread-subs-result-name">{c.display}</span>
                <span className="thread-subs-result-username">@{c.username}</span>
              </button>
            ))}
          </div>
        </div>
      )}

      {error && <div className="thread-subs-error">{error}</div>}
    </div>
  );
}
