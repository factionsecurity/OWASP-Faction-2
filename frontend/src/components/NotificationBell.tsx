import { useEffect, useRef, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bell, CheckCheck, Trash2 } from 'lucide-react';
import { notificationsApi } from '../api';
import type { Notification } from '../types';
import { useNotificationStream } from '../context/NotificationStreamContext';
import ConfirmDialog from './ConfirmDialog';
import './NotificationBell.css';

/** Mentions and thread replies also sit behind the Mentions badge, so they move both. */
function isMention(n?: Notification): boolean {
  return n?.type === 'MENTION' || n?.type === 'COMMENT_ADDED';
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

export default function NotificationBell() {
  const [open, setOpen] = useState(false);
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [loading, setLoading] = useState(false);
  const [confirmClearAll, setConfirmClearAll] = useState(false);
  const [clearing, setClearing] = useState(false);
  // Counts and the live stream are shared with the Mentions sidebar badge, so the bell
  // reads them rather than holding a second connection of its own.
  const { unreadCount, version, adjustUnread, setCounts } = useNotificationStream();
  const dropdownRef = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();

  const loadNotifications = useCallback(async () => {
    setLoading(true);
    try {
      const res = await notificationsApi.getAll();
      if (res.data) setNotifications(res.data);
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  }, []);

  // The shared stream reports arrivals; an open dropdown refreshes itself so a new
  // notification appears without closing and reopening it.
  useEffect(() => {
    if (version > 0) loadNotifications();
  }, [version, loadNotifications]);

  // Load on open
  useEffect(() => {
    if (open) loadNotifications();
  }, [open, loadNotifications]);

  // Close on outside click
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  const handleMarkRead = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    const target = notifications.find(x => x.id === id);
    await notificationsApi.markRead(id);
    setNotifications(prev => prev.map(n => n.id === id ? { ...n, read: true } : n));
    adjustUnread(-1, isMention(target) ? -1 : 0);
  };

  const handleDelete = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    await notificationsApi.delete(id);
    const n = notifications.find(x => x.id === id);
    setNotifications(prev => prev.filter(x => x.id !== id));
    if (n && !n.read) adjustUnread(-1, isMention(n) ? -1 : 0);
  };

  const handleDeleteAll = async () => {
    setClearing(true);
    try {
      await notificationsApi.deleteAll();
      setNotifications([]);
      setCounts(0, 0);
    } catch {
      // ignore — the list stays as it was
    } finally {
      setClearing(false);
      setConfirmClearAll(false);
    }
  };

  const handleMarkAllRead = async () => {
    await notificationsApi.markAllRead();
    setNotifications(prev => prev.map(n => ({ ...n, read: true })));
    setCounts(0, 0);
  };

  const handleClick = (n: Notification) => {
    if (!n.read) {
      notificationsApi.markRead(n.id);
      setNotifications(prev => prev.map(x => x.id === n.id ? { ...x, read: true } : x));
      adjustUnread(-1, isMention(n) ? -1 : 0);
    }
    if (n.link) {
      navigate(n.link);
    }
    setOpen(false);
  };

  return (
    <div className="notif-bell" ref={dropdownRef}>
      <button
        className="notif-bell-btn"
        onClick={() => setOpen(prev => !prev)}
        title="Notifications"
        aria-label={`Notifications${unreadCount > 0 ? `, ${unreadCount} unread` : ''}`}
      >
        <Bell size={18} />
        {unreadCount > 0 && (
          <span className="notif-badge">{unreadCount > 99 ? '99+' : unreadCount}</span>
        )}
      </button>

      {open && (
        <div className="notif-dropdown">
          <div className="notif-dropdown-header">
            <span>Notifications</span>
            <div className="notif-header-actions">
              {unreadCount > 0 && (
                <button className="notif-mark-all" onClick={handleMarkAllRead} title="Mark all as read">
                  <CheckCheck size={15} />
                  <span>Mark all read</span>
                </button>
              )}
              {notifications.length > 0 && (
                <button
                  className="notif-mark-all notif-mark-all--danger"
                  onClick={() => setConfirmClearAll(true)}
                  title="Delete all notifications"
                >
                  <Trash2 size={15} />
                  <span>Delete all</span>
                </button>
              )}
            </div>
          </div>

          <div className="notif-list">
            {loading && notifications.length === 0 && (
              <div className="notif-empty">Loading…</div>
            )}
            {!loading && notifications.length === 0 && (
              <div className="notif-empty">No notifications</div>
            )}
            {notifications.map(n => (
              <div
                key={n.id}
                className={`notif-item${n.read ? '' : ' notif-item--unread'}`}
                onClick={() => handleClick(n)}
              >
                {!n.read && <div className="notif-dot" />}
                <div className="notif-content">
                  <div className="notif-title">{n.title}</div>
                  <div className="notif-message">{n.message}</div>
                  <div className="notif-time">{timeAgo(n.createdAt)}</div>
                </div>
                <div className="notif-actions">
                  {!n.read && (
                    <button
                      className="notif-action-btn"
                      onClick={(e) => handleMarkRead(n.id, e)}
                      title="Mark as read"
                    >
                      <CheckCheck size={13} />
                    </button>
                  )}
                  <button
                    className="notif-action-btn notif-action-btn--delete"
                    onClick={(e) => handleDelete(n.id, e)}
                    title="Delete"
                  >
                    <Trash2 size={13} />
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Outside the dropdown block on purpose: the outside-click handler closes the
          dropdown as soon as the dialog is clicked, and the confirmation has to survive
          that. */}
      <ConfirmDialog
        isOpen={confirmClearAll}
        onClose={() => setConfirmClearAll(false)}
        onConfirm={handleDeleteAll}
        title="Delete All Notifications"
        message="Are you sure you want to delete all of your notifications, read and unread? This cannot be undone."
        confirmText="Delete All"
        variant="danger"
        isLoading={clearing}
      />
    </div>
  );
}
