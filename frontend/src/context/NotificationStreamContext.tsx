import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  ReactNode,
} from 'react';
import { notificationsApi } from '../api';
import { createSseParser } from '../utils/sse';

/**
 * One SSE connection per tab, shared by everything that reacts to notifications: the
 * bell badge, the Mentions sidebar badge, and the Mentions page itself.
 *
 * <p>Each consumer opening its own stream would multiply the emitters the backend holds
 * per user, and every heartbeat with them — so the connection lives here and the counts
 * are handed down.
 */

interface NotificationStream {
  /** Unread notifications of every kind — the bell badge. */
  unreadCount: number;
  /** Unread @mentions and thread replies — the Mentions sidebar badge. */
  mentionsUnread: number;
  /**
   * Bumped every time a notification arrives. Lists watch this to reload themselves
   * rather than each holding their own copy of the stream.
   */
  version: number;
  /** Local correction so a badge drops the instant a row is read, before the push lands. */
  adjustUnread: (deltaAll: number, deltaMentions: number) => void;
  /** Sets both counts directly — for bulk actions where the delta is the whole list. */
  setCounts: (unread: number, mentions: number) => void;
}

const NotificationStreamContext = createContext<NotificationStream>({
  unreadCount: 0,
  mentionsUnread: 0,
  version: 0,
  adjustUnread: () => {},
  setCounts: () => {},
});

export function useNotificationStream() {
  return useContext(NotificationStreamContext);
}

export function NotificationStreamProvider({ children }: { children: ReactNode }) {
  const [unreadCount, setUnreadCount] = useState(0);
  const [mentionsUnread, setMentionsUnread] = useState(0);
  const [version, setVersion] = useState(0);

  // Read inside the stream callback, which is created once — a ref keeps that callback
  // from having to be rebuilt (and the connection torn down) on every count change.
  const handlerRef = useRef<(type: string, data: string) => void>(() => {});
  handlerRef.current = (type: string, data: string) => {
    if (type === 'unread_count') {
      const count = Number(data);
      if (!Number.isNaN(count)) setUnreadCount(count);
    } else if (type === 'mentions_unread_count') {
      const count = Number(data);
      if (!Number.isNaN(count)) setMentionsUnread(count);
    } else if (type === 'notification') {
      setVersion(v => v + 1);
    }
  };

  // Seeded from the REST endpoints too: a browser that cannot hold the stream open
  // (proxy, sleep, a failed reconnect) still shows correct badges on load.
  useEffect(() => {
    notificationsApi.getUnreadCount()
      .then(res => { if (res.data != null) setUnreadCount(res.data); })
      .catch(() => {});
    notificationsApi.getMentionsUnreadCount()
      .then(res => { if (res.data != null) setMentionsUnread(res.data); })
      .catch(() => {});
  }, []);

  useEffect(() => {
    const controller = new AbortController();

    const connect = async () => {
      while (!controller.signal.aborted) {
        try {
          const token = localStorage.getItem('token') ?? '';
          const response = await fetch('/api/v1/notifications/stream', {
            headers: {
              Authorization: `Bearer ${token}`,
              Accept: 'text/event-stream',
              'Cache-Control': 'no-cache',
            },
            signal: controller.signal,
          });

          if (!response.ok || !response.body) break;

          const reader = response.body.getReader();
          const decoder = new TextDecoder();
          const feed = createSseParser((type, data) => handlerRef.current(type, data));

          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            feed(decoder.decode(value, { stream: true }));
          }
        } catch {
          if (controller.signal.aborted) break;
          await new Promise(r => setTimeout(r, 5000));
        }
      }
    };

    connect();
    return () => controller.abort();
  }, []);

  const adjustUnread = useCallback((deltaAll: number, deltaMentions: number) => {
    setUnreadCount(prev => Math.max(0, prev + deltaAll));
    setMentionsUnread(prev => Math.max(0, prev + deltaMentions));
  }, []);

  const setCounts = useCallback((unread: number, mentions: number) => {
    setUnreadCount(Math.max(0, unread));
    setMentionsUnread(Math.max(0, mentions));
  }, []);

  const value = useMemo(
    () => ({ unreadCount, mentionsUnread, version, adjustUnread, setCounts }),
    [unreadCount, mentionsUnread, version, adjustUnread, setCounts]
  );

  return (
    <NotificationStreamContext.Provider value={value}>
      {children}
    </NotificationStreamContext.Provider>
  );
}
