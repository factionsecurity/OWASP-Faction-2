import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AtSign, AppWindow, ShieldAlert, NotebookPen, CheckCheck, Trash2 } from 'lucide-react';
import { notificationsApi } from '../api';
import Page from '../components/Page';
import ConfirmDialog from '../components/ConfirmDialog';
import { useNotificationStream } from '../context/NotificationStreamContext';
import { getCurrentUser, hasPermissionPattern } from '../utils/permissions';
import type { Notification, NotificationTargetType } from '../types';
import './MentionsPage.css';

/**
 * Everything that has been said *to* the current user: @mentions, and replies on comment
 * threads they follow. Both arrive as notifications already — this page is the readable
 * view of that slice, grouped by the kind of item the conversation is on.
 */

interface Group {
  key: NotificationTargetType | 'OTHER';
  label: string;
  Icon: typeof AppWindow;
  empty: string;
  /**
   * A section needs at least one privilege on the resource behind it. Being @mentioned
   * somewhere does not imply access to it — an app owner can be named in an assessment
   * note they cannot open — so the section is hidden rather than offering dead links.
   * The backend filters the feed the same way; this keeps the page from rendering an
   * empty card for a section that can never receive a row.
   */
  canView: (authorities: string[]) => boolean;
}

/**
 * Notebook mentions land on an assessment, so the section is named for where the reader
 * ends up rather than for the entity behind it. OTHER catches rows recorded before the
 * target was captured and whose link shape no longer identifies one — without it they
 * would silently vanish from the feed.
 */
const GROUPS: Group[] = [
  {
    key: 'APPLICATION', label: 'Applications', Icon: AppWindow,
    empty: 'No application mentions.',
    canView: a => hasPermissionPattern(a, /^applications:/),
  },
  {
    key: 'VULNERABILITY', label: 'Vulnerabilities', Icon: ShieldAlert,
    empty: 'No vulnerability mentions.',
    canView: a => hasPermissionPattern(a, /^vulnerabilities:/),
  },
  {
    key: 'NOTEBOOK', label: 'Assessment Notes', Icon: NotebookPen,
    empty: 'No note mentions.',
    canView: a => hasPermissionPattern(a, /^assessments:/),
  },
  // The catch-all is the reader's own pre-context rows, so it is gated on having
  // something in it rather than on a privilege.
  { key: 'OTHER', label: 'Other', Icon: AtSign, empty: 'Nothing else.', canView: () => true },
];

/** Unread mentions left once one section — or the whole feed — has been cleared. */
function mentionsUnreadAfter(items: Notification[], scope: Group['key'] | 'ALL'): number {
  if (scope === 'ALL') return 0;
  return items.filter(n => !n.read && groupKeyOf(n) !== scope).length;
}

/** Rows with no target — or one this page has no section for — fall to the catch-all. */
function groupKeyOf(n: Notification): Group['key'] {
  return n.targetType && GROUPS.some(g => g.key === n.targetType)
    ? (n.targetType as Group['key'])
    : 'OTHER';
}

function timeAgo(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const m = Math.floor(diff / 60_000);
  if (m < 1) return 'just now';
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}

export default function MentionsPage() {
  const navigate = useNavigate();
  const { version, adjustUnread, setCounts, unreadCount: allUnread } = useNotificationStream();

  const sections = useMemo(() => {
    const authorities = getCurrentUser()?.authorities ?? [];
    return GROUPS.filter(g => g.canView(authorities));
  }, []);

  const [items, setItems] = useState<Notification[]>([]);
  const [loading, setLoading] = useState(true);
  const [unreadOnly, setUnreadOnly] = useState(false);
  // Which "delete all" is being confirmed: the whole feed, or one section of it.
  const [confirmClear, setConfirmClear] = useState<Group['key'] | 'ALL' | null>(null);
  const [clearing, setClearing] = useState(false);

  // Reloads when the stream reports a new notification, so a mention that arrives while
  // this page is open appears in its section without a refresh.
  useEffect(() => {
    notificationsApi
      .getMentions()
      .then(res => { if (res.data) setItems(res.data); })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [version]);

  const unreadCount = items.filter(n => !n.read).length;

  const visible = useMemo(
    () => (unreadOnly ? items.filter(n => !n.read) : items),
    [items, unreadOnly]
  );

  const grouped = useMemo(() => {
    const byKey = new Map<Group['key'], Notification[]>(sections.map(g => [g.key, []]));
    for (const n of visible) {
      const rows = byKey.get(groupKeyOf(n));
      // A row for a hidden section is dropped rather than folded into "Other" — the
      // backend already filters those out, so this only covers a stale in-flight list.
      if (rows) rows.push(n);
    }
    return byKey;
  }, [visible, sections]);

  const markRead = useCallback((id: string) => {
    notificationsApi.markRead(id).catch(() => {});
    setItems(prev => prev.map(n => (n.id === id ? { ...n, read: true } : n)));
    // Dropped locally as well as by the stream's push: selecting a mention has to clear
    // its badge immediately, not a round trip later.
    adjustUnread(-1, -1);
  }, [adjustUnread]);

  const handleRowClick = (n: Notification) => {
    if (!n.read) markRead(n.id);
    if (n.link) navigate(n.link);
  };

  const handleMarkRead = (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    markRead(id);
  };

  const handleDeleteAll = () => {
    if (!confirmClear) return;
    const scope = confirmClear;
    setClearing(true);
    notificationsApi
      .deleteAllMentions(scope === 'ALL' ? undefined : scope)
      .then(() => {
        const removedUnread = items.filter(
          n => !n.read && (scope === 'ALL' || groupKeyOf(n) === scope)
        ).length;
        setItems(prev =>
          scope === 'ALL' ? [] : prev.filter(n => groupKeyOf(n) !== scope)
        );
        setCounts(allUnread - removedUnread, mentionsUnreadAfter(items, scope));
      })
      .catch(() => {})
      .finally(() => {
        setClearing(false);
        setConfirmClear(null);
      });
  };

  const handleDelete = (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    const target = items.find(n => n.id === id);
    notificationsApi.delete(id).catch(() => {});
    setItems(prev => prev.filter(n => n.id !== id));
    if (target && !target.read) adjustUnread(-1, -1);
  };

  const confirmScopeLabel = GROUPS.find(g => g.key === confirmClear)?.label ?? 'these';
  // Stated in the dialog because the unread filter can be hiding rows that this deletes.
  const confirmCount =
    confirmClear === 'ALL'
      ? items.length
      : items.filter(n => groupKeyOf(n) === confirmClear).length;

  return (
    <Page className="mentions-dashboard">
      <div className="md-toolbar">
        <h2 className="md-page-title">
          <AtSign size={16} />
          Mentions &amp; Replies
          {unreadCount > 0 && <span className="md-count md-count--unread">{unreadCount}</span>}
        </h2>
        <div className="md-toolbar-actions">
          <label className="md-toggle">
            <input
              type="checkbox"
              checked={unreadOnly}
              onChange={e => setUnreadOnly(e.target.checked)}
            />
            Unread only
          </label>
          {items.length > 0 && (
            <button className="md-action-link" onClick={() => setConfirmClear('ALL')}>
              <Trash2 size={13} />
              Delete all
            </button>
          )}
        </div>
      </div>

      <div className="md-sections">
        {sections.map(({ key, label, Icon, empty }) => {
          const rows = grouped.get(key) ?? [];
          // Counted off the unfiltered feed: "Delete all" clears the section itself, not
          // the rows the unread filter happens to be showing.
          const sectionTotal = items.filter(n => groupKeyOf(n) === key).length;
          // The catch-all only earns a section when something actually landed in it.
          if (key === 'OTHER' && sectionTotal === 0) return null;
          const groupUnread = rows.filter(n => !n.read).length;

          return (
            <section className="md-card" key={key}>
              <header className="md-card-header">
                <h3 className="md-card-title">
                  <Icon size={16} />
                  {label}
                  {rows.length > 0 && <span className="md-count">{rows.length}</span>}
                  {groupUnread > 0 && (
                    <span className="md-count md-count--unread">{groupUnread}</span>
                  )}
                </h3>
                {sectionTotal > 0 && (
                  <button
                    className="md-action-link"
                    onClick={() => setConfirmClear(key)}
                    title={`Delete all ${label.toLowerCase()} mentions`}
                  >
                    <Trash2 size={13} />
                    Delete all
                  </button>
                )}
              </header>
              <div className="md-card-body">
                {loading ? (
                  <div className="md-empty">Loading…</div>
                ) : rows.length === 0 ? (
                  <div className="md-empty">{unreadOnly ? 'Nothing unread.' : empty}</div>
                ) : (
                  <table className="md-table">
                    <thead>
                      <tr>
                        <th className="md-col-dot" />
                        <th className="md-col-item">Item</th>
                        <th className="md-col-from">From</th>
                        <th>What was said</th>
                        <th className="md-col-when">When</th>
                        <th className="md-col-actions" />
                      </tr>
                    </thead>
                    <tbody>
                      {rows.map(n => (
                        <tr
                          key={n.id}
                          className={n.read ? undefined : 'md-row--unread'}
                          onClick={() => handleRowClick(n)}
                        >
                          <td className="md-col-dot">
                            {!n.read && <span className="md-dot" />}
                          </td>
                          <td>
                            <div className="md-primary-text md-clamp">
                              {n.targetName ?? '—'}
                            </div>
                            {n.type === 'MENTION' && (
                              <span className="md-tag">
                                <AtSign size={10} /> mentioned you
                              </span>
                            )}
                          </td>
                          <td className="md-sub-text md-clamp">
                            {n.actorName ?? n.actorUsername ?? '—'}
                          </td>
                          <td className="md-clamp-2">
                            {n.excerpt ?? <span className="md-sub-text">{n.message}</span>}
                          </td>
                          <td className="md-sub-text md-nowrap">{timeAgo(n.createdAt)}</td>
                          <td className="md-col-actions">
                            <div className="md-row-actions">
                              {!n.read && (
                                <button
                                  className="md-icon-btn"
                                  onClick={e => handleMarkRead(n.id, e)}
                                  title="Mark as read"
                                >
                                  <CheckCheck size={13} />
                                </button>
                              )}
                              <button
                                className="md-icon-btn md-icon-btn--danger"
                                onClick={e => handleDelete(n.id, e)}
                                title="Delete"
                              >
                                <Trash2 size={13} />
                              </button>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            </section>
          );
        })}
      </div>

      <ConfirmDialog
        isOpen={confirmClear !== null}
        onClose={() => setConfirmClear(null)}
        onConfirm={handleDeleteAll}
        title={confirmClear === 'ALL' ? 'Delete All Mentions' : `Delete ${confirmScopeLabel} Mentions`}
        message={
          confirmClear === 'ALL'
            ? `Delete all ${confirmCount} mentions and thread replies, read and unread? Your other notifications are not affected. This cannot be undone.`
            : `Delete all ${confirmCount} mentions and thread replies under ${confirmScopeLabel}, read and unread? The other sections and your other notifications are not affected. This cannot be undone.`
        }
        confirmText="Delete All"
        variant="danger"
        isLoading={clearing}
      />
    </Page>
  );
}
