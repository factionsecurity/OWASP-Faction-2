import { ReactNode, useState, useEffect, useRef } from 'react';
import { usePageTitle } from '../context/PageTitleContext';
import { useNavigate, useLocation, Link } from 'react-router-dom';
import {
  LayoutDashboard,
  Blocks,
  Building2,
  AppWindow,
  ShieldAlert,
  Users,
  UserCog,
  Shield,
  Calendar,
  CheckSquare,
  Settings,
  Sliders,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  LogOut,
  FileText,
  Sun,
  Moon,
  BookOpen,
  DatabaseZap,
  ClipboardCheck,
  ClipboardList,
  RefreshCw,
  Mail,
  KeyRound,
  UserCircle,
  Hash,
  PenLine,
  Sparkles,
  ScrollText,
  Inbox,
  BellRing,
  Palette,
  AtSign,
} from 'lucide-react';
import NotificationBell from './NotificationBell';
import GliderIcon from './icons/GliderIcon';
import { permissions } from '../utils/permissions';
import UserAvatar from './UserAvatar';
import { authApi, queueCountsApi, statusApi } from '../api';
import { NotificationStreamProvider, useNotificationStream } from '../context/NotificationStreamContext';
import { formatCompact } from '../utils/formatNumber';
import NoAccess from './NoAccess';
import { useBranding } from '../context/BrandingContext';
import { useEdition } from '../context/EditionContext';
import type { FeatureKey } from '../types';
import './DashboardLayout.css';

interface DashboardLayoutProps {
  children: ReactNode;
}

interface MenuItem {
  name: string;
  path?: string;
  icon: React.ElementType;
  subItems?: MenuItem[];
  /**
   * Capability this item belongs to. The item is omitted entirely when the build does
   * not include it — an admin section listing settings that cannot be opened is worse
   * than one that simply does not offer them.
   *
   * <p>The route itself stays, so an old bookmark still lands on a page explaining what
   * the feature is rather than a 404.
   */
  feature?: FeatureKey;
}

const menuItems: MenuItem[] = [
  { name: 'Dashboard', path: '/dashboard', icon: LayoutDashboard },
  { name: 'Mentions', path: '/mentions', icon: AtSign },
  { name: 'Applications', path: '/applications', icon: AppWindow },
  { name: 'Assessments', path: '/assessments', icon: GliderIcon },
  { name: 'Peer Review Queue', path: '/peer-review', icon: ClipboardCheck },
  { name: 'Vulnerabilities', path: '/vulnerabilities', icon: ShieldAlert },
  { name: 'Retests', path: '/retests', icon: RefreshCw },
  { name: 'Scheduling', path: '/scheduling', icon: Calendar },
  { name: 'Remediation', path: '/remediation', icon: CheckSquare },
  {
    name: 'Administration',
    icon: Settings,
    subItems: [
      { name: 'Organizations', path: '/organizations', icon: Building2 },
      { name: 'Users', path: '/users', icon: Users },
      { name: 'Teams', path: '/teams', icon: UserCog },
      { name: 'Roles', path: '/roles', icon: Shield },
      { name: 'Assessment Config', path: '/assessment-config', icon: Sliders },
      { name: 'Default Vulnerabilities', path: '/default-vulnerabilities', icon: BookOpen },
      { name: 'Content Templates', path: '/content-templates', icon: ClipboardList },
      { name: 'Report Designer', path: '/report-designer', icon: FileText },
      { name: 'Organization Config', path: '/org-config', icon: DatabaseZap },
      { name: 'SSO Config', path: '/sso-config', icon: Shield, feature: 'sso' },
      { name: 'Email Config', path: '/email-config', icon: Mail },
      { name: 'Branding', path: '/branding', icon: Palette, feature: 'branding' },
      { name: 'Email Notifications', path: '/email-notifications', icon: BellRing },
      { name: 'Inbound Email', path: '/inbound-email-config', icon: Inbox, feature: 'inbound_email' },
      { name: 'AI Configuration', path: '/ai-config', icon: Sparkles },
      { name: 'App Store', path: '/app-store', icon: Blocks },
      { name: 'Logs', path: '/logs', icon: ScrollText },
      { name: 'Application IDs', path: '/application-id-config', icon: Hash },
    ],
  },
];

const QUEUE_BADGE_COLORS: Record<string, string> = {
  'Mentions': 'nav-badge--amber',
  'Assessments': 'nav-badge--purple',
  'Peer Review Queue': 'nav-badge--blue',
  'Vulnerabilities': 'nav-badge--red',
  'Retests': 'nav-badge--green',
  'Remediation': 'nav-badge--sky',
};

type Theme = 'dark' | 'light';

// Default view for every RichTextEditor across the platform. Same localStorage
// pattern as the theme — RichTextEditor reads 'rte-default-view' on mount.
type EditorView = 'rich' | 'markdown' | 'split';

const EDITOR_VIEW_OPTIONS: Array<{ value: EditorView; label: string }> = [
  { value: 'rich', label: 'WYSIWYG' },
  { value: 'markdown', label: 'Markdown' },
  { value: 'split', label: 'Split' },
];

function getUserInitials(username: string): string {
  if (!username) return 'U';
  const parts = username.split(/[\s._-]+/);
  if (parts.length >= 2) {
    return (parts[0][0] + parts[1][0]).toUpperCase();
  }
  return username.slice(0, 2).toUpperCase();
}

/**
 * The sidebar/topbar chrome. Wrapped by {@link DashboardLayout} so everything inside it —
 * the Mentions badge here, the bell, and the Mentions page rendered as children — shares
 * one notification stream.
 */
function DashboardChrome({ children }: DashboardLayoutProps) {
  const {
    menuLogoLarge, menuLogoSmall,
    menuLogoLargeHeight, menuLogoSmallHeight,
    hasCustomMenuLogoLarge, hasCustomMenuLogoSmall,
  } = useBranding();
  const { hasFeature } = useEdition();
  const [sidebarOpen, setSidebarOpen] = useState(
    () => localStorage.getItem('sidebarOpen') !== 'false'
  );
  const [expandedMenus, setExpandedMenus] = useState<string[]>(['Administration']);
  const [userDropdownOpen, setUserDropdownOpen] = useState(false);
  // Flyout for a submenu group when the sidebar is collapsed
  const [flyout, setFlyout] = useState<{ name: string; top: number; left: number } | null>(null);
  const [theme, setTheme] = useState<Theme>(() => {
    const saved = (localStorage.getItem('theme') as Theme) || 'dark';
    document.documentElement.setAttribute('data-theme', saved);
    return saved;
  });
  const [editorView, setEditorView] = useState<EditorView>(() => {
    const saved = localStorage.getItem('rte-default-view');
    return saved === 'markdown' || saved === 'split' ? saved : 'rich';
  });
  const dropdownRef = useRef<HTMLDivElement>(null);
  const sidebarRef = useRef<HTMLElement>(null);
  const flyoutRef = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const location = useLocation();
  // Live unread mentions, pushed over SSE — the Mentions badge updates itself.
  const { mentionsUnread } = useNotificationStream();

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('theme', theme);
  }, [theme]);

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setUserDropdownOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  // Close the collapsed-sidebar flyout on outside click or Escape
  useEffect(() => {
    if (!flyout) return;
    const handleClick = (event: MouseEvent) => {
      const target = event.target as Node;
      // Clicks inside the sidebar are handled by the nav buttons themselves
      if (sidebarRef.current?.contains(target)) return;
      if (flyoutRef.current?.contains(target)) return;
      setFlyout(null);
    };
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setFlyout(null);
    };
    document.addEventListener('mousedown', handleClick);
    document.addEventListener('keydown', handleKey);
    return () => {
      document.removeEventListener('mousedown', handleClick);
      document.removeEventListener('keydown', handleKey);
    };
  }, [flyout]);

  // Close the flyout whenever the route changes (e.g. after selecting an option)
  useEffect(() => {
    setFlyout(null);
  }, [location.pathname]);

  const handleLogout = () => {
    // Best-effort: clearing the media cookie needs a round trip, but the local
    // session must go regardless of whether it succeeds.
    authApi.logout().catch(() => {});

    localStorage.removeItem('token');
    localStorage.removeItem('user');

    // Dispatch custom event to notify App component
    window.dispatchEvent(new Event('logout'));

    navigate('/login');
  };

  const toggleMenu = (menuName: string) => {
    setExpandedMenus((prev) =>
      prev.includes(menuName)
        ? prev.filter((name) => name !== menuName)
        : [...prev, menuName]
    );
  };

  const isMenuActive = (item: MenuItem): boolean => {
    if (item.path) {
      return location.pathname === item.path || location.pathname.startsWith(item.path + '/');
    }
    if (item.subItems) {
      return item.subItems.some((subItem) =>
        subItem.path
          ? location.pathname === subItem.path || location.pathname.startsWith(subItem.path + '/')
          : false
      );
    }
    return false;
  };

  const { pageTitle, setPageTitle, breadcrumbs, setBreadcrumbs } = usePageTitle();

  // Reset the title/breadcrumbs when leaving a route so stale ones don't persist.
  // The reset lives in the effect's CLEANUP: React runs all cleanups (old page,
  // then this) before any new effects, so a page that sets its title/breadcrumbs
  // on mount isn't wiped by this effect running after it — which is what happened
  // when the reset was in the effect body.
  useEffect(() => {
    return () => {
      setPageTitle('');
      setBreadcrumbs(null);
    };
  }, [location.pathname]);
  const user = JSON.parse(localStorage.getItem('user') || '{}');
  const authorities = user.authorities || [];

  // Deployed release version for the footer. Comes from the backend's public
  // /status endpoint, which reports the tag the release workflow stamped into
  // the image (APP_VERSION) — so it always matches what's actually running.
  const [appVersion, setAppVersion] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    statusApi.get()
      .then(s => { if (!cancelled) setAppVersion(s.version); })
      .catch(() => { /* footer just omits the version */ });
    return () => { cancelled = true; };
  }, []);

  // Queue-size badges next to Assessments / Peer Review Queue / Vulnerabilities / Retests.
  // Gates mirror hasPermission below (which isn't defined yet at this point —
  // this hook must run before the NoAccess early return).
  const [queueCounts, setQueueCounts] = useState<Record<string, number>>({});
  useEffect(() => {
    const isAdmin = authorities.includes('super_admin');
    const fetchers: Array<[string, boolean, () => Promise<number>]> = [
      ['Assessments', isAdmin || authorities.some((a: string) => a.match(/^assessments:read/)),
        queueCountsApi.activeAssessments],
      ['Peer Review Queue', isAdmin || authorities.some((a: string) =>
        a === 'peerreview:read:all' || a === 'peerreview:edit:all'),
        queueCountsApi.peerReviewQueue],
      // Any vulnerability read tier can see the badge — the summary endpoint scopes the
      // count to that caller's slice, so the number always matches the page it opens.
      ['Vulnerabilities', isAdmin || authorities.some((a: string) => a.match(/^vulnerabilities:read/) !== null),
        queueCountsApi.pastDueVulnerabilities],
      ['Retests', isAdmin || authorities.some((a: string) =>
        a === 'vulnerabilities:read:all' || a === 'vulnerabilities:read:team' || a === 'vulnerabilities:read:assessment'),
        queueCountsApi.openRetests],
      ['Remediation', isAdmin || authorities.some((a: string) =>
        a === 'vulnerabilities:read:all' || a === 'vulnerabilities:read:team'),
        queueCountsApi.remediationQueue],
    ];
    let cancelled = false;
    fetchers.forEach(([name, allowed, fetchCount]) => {
      if (!allowed) return;
      fetchCount()
        .then((count) => {
          if (!cancelled) setQueueCounts((prev) => ({ ...prev, [name]: count }));
        })
        .catch(() => {});
    });
    return () => { cancelled = true; };
  }, [location.pathname]);

  // Users with no roles/permissions (e.g. SSO birthright accounts) get an
  // "ask your administrator" screen instead of an empty dashboard.
  if (authorities.length === 0) {
    return <NoAccess username={user.username} onLogout={handleLogout} />;
  }

  // External users hold only :org (whole-organization) or :owned (assigned
  // applications/organizations) scopes — never :all or super_admin
  const isExternalUser = !authorities.includes('super_admin') &&
    !authorities.some((a: string) => a.endsWith(':all')) &&
    authorities.some((a: string) => a.endsWith(':org') || a.endsWith(':owned'));

  // Check if user has permission to view a menu item
  const hasPermission = (menuName: string): boolean => {
    // Super admin can see everything
    if (authorities.includes('super_admin')) {
      return true;
    }

    // External users never see admin section items
    const adminItems = ['organizations', 'users', 'teams', 'roles', 'assessment config',
      'default vulnerabilities', 'report designer', 'organization config', 'sso config',
      'email config', 'ai configuration', 'logs', 'survey config'];
    
    if (isExternalUser && adminItems.includes(menuName.toLowerCase())) {
      return false;
    }

    // Check specific permissions for each menu item
    switch (menuName.toLowerCase()) {
      case 'dashboard':
        return true; // Dashboard is always visible

      case 'mentions':
        // Anyone can be @mentioned or pulled into a thread, whatever they can read.
        return true;

      case 'organizations':
        return authorities.some((auth: string) =>
          auth.match(/^organizations:read/)
        );

      case 'applications':
        return authorities.some((auth: string) =>
          auth.match(/^applications:read/)
        );

      case 'assessments':
        // Pentester-facing scopes only. App owners (:org/:owned) reach their
        // assessments through Applications instead.
        return authorities.some((auth: string) =>
          auth === 'assessments:read:all' ||
          auth === 'assessments:read:team' ||
          auth === 'assessments:read:assigned'
        );

      case 'vulnerabilities':
        return authorities.some((auth: string) =>
          auth.match(/^vulnerabilities:read/)
        );

      case 'scheduling':
        return authorities.some((auth: string) =>
          auth === 'assessments:create:all' || auth === 'assessments:create:team'
        );

      case 'remediation':
        return authorities.some((auth: string) =>
          auth === 'vulnerabilities:read:all' || auth === 'vulnerabilities:read:team' ||
          auth === 'vulnerabilities:retest:org' || auth === 'vulnerabilities:retest:owned'
        );

      // Matches canViewRetests (the route gate) and the queue-count fetcher above, so the item,
      // its badge and the page it opens all agree. Without this case Retests fell through to the
      // default and was hidden from everyone but super admins.
      case 'retests':
        return authorities.some((auth: string) =>
          auth === 'vulnerabilities:read:all' || auth === 'vulnerabilities:read:team' ||
          auth === 'vulnerabilities:read:assessment'
        );

      case 'users':
        return authorities.some((auth: string) =>
          auth === 'users:read:team' || auth === 'users:read:all'
        );

      case 'teams':
        return authorities.some((auth: string) =>
          auth === 'users:read:team' || auth === 'users:read:all'
        );

      case 'roles':
        return authorities.some((auth: string) =>
          auth.match(/^roles:read/)
        );

      case 'assessment config':
        return authorities.some((auth: string) =>
          auth === 'assessments:create:team' || auth === 'assessments:create:all'
        );

      case 'default vulnerabilities':
        return true; // all authenticated internal users can view

      case 'content templates':
        return authorities.includes('super_admin') ||
          authorities.some((auth: string) => auth.match(/^content-templates:/));

      case 'report designer':
        return authorities.some((auth: string) =>
          auth === 'assessments:create:team' || auth === 'assessments:create:all'
        );

      case 'organization config':
        // Super-admin only: the page is an editor whose entity-field and
        // region writes all require super_admin (handled by the top-level check).
        return authorities.includes('super_admin');

      case 'sso config':
        return authorities.includes('super_admin') ||
          authorities.some((auth: string) => auth.match(/^sso:config/));

      case 'ai configuration':
        return authorities.includes('super_admin') ||
          authorities.some((auth: string) => auth.match(/^ai:config/));

      case 'logs':
        return authorities.includes('super_admin') ||
          authorities.some((auth: string) => auth.match(/^audit:logs/));

      case 'survey config':
        return authorities.includes('super_admin') ||
          authorities.some((auth: string) => auth.match(/^survey:/));

      case 'peer review queue':
        return authorities.some((auth: string) =>
          auth === 'peerreview:read:all' || auth === 'peerreview:edit:all'
        );

      default:
        return false; // Default to hiding menu items without specific permission requirements
    }
  };

  // Filter menu items based on permissions (including subitems)
  const visibleMenuItems = menuItems
    .map((item) => {
      // If item has subitems, filter them
      if (item.subItems) {
        const visibleSubItems = item.subItems.filter((subItem) =>
          hasPermission(subItem.name) && (!subItem.feature || hasFeature(subItem.feature))
        );
        // Only show parent if it has visible subitems
        return visibleSubItems.length > 0
          ? { ...item, subItems: visibleSubItems }
          : null;
      }
      // Regular item - check permission
      if (item.feature && !hasFeature(item.feature)) return null;
      return hasPermission(item.name) ? item : null;
    })
    .filter((item): item is MenuItem => item !== null);

  return (
    <div className="dashboard-layout">
      <aside ref={sidebarRef} className={`sidebar ${sidebarOpen ? 'open' : 'closed'}`}>
        <div className="sidebar-header">
          {/* The stylesheet crops the shipped wordmark with negative margins to remove
              whitespace baked into that image. A custom logo has no such whitespace, so
              the crop would chop its bottom off — hence the modifier class. */}
          <img
            src={sidebarOpen ? menuLogoLarge : menuLogoSmall}
            alt=""
            className={[
              'sidebar-logo',
              // The taller OWASP lockup needs its own sizing; the collapsed rail uses the
              // standalone F in both editions, so only the expanded state takes it.
              sidebarOpen && __FACTION_EDITION__ !== 'enterprise' && !hasCustomMenuLogoLarge
                ? 'sidebar-logo--lockup' : '',
              (sidebarOpen ? hasCustomMenuLogoLarge : hasCustomMenuLogoSmall)
                ? 'sidebar-logo--custom' : '',
            ].filter(Boolean).join(' ')}
            style={{ height: sidebarOpen ? menuLogoLargeHeight : menuLogoSmallHeight }}
          />
        </div>

        <nav className="sidebar-nav">
          {visibleMenuItems.map((item) => {
            const Icon = item.icon;
            const isExpanded = expandedMenus.includes(item.name);
            const isActive = isMenuActive(item);

            if (item.subItems) {
              return (
                <div key={item.name} className="nav-group">
                  <button
                    onClick={(e) => {
                      if (sidebarOpen) {
                        toggleMenu(item.name);
                        return;
                      }
                      // Collapsed: pop a flyout next to the icon
                      const rect = e.currentTarget.getBoundingClientRect();
                      const subCount = item.subItems?.length ?? 0;
                      const estHeight = subCount * 36 + 48;
                      const top = Math.max(8, Math.min(rect.top, window.innerHeight - estHeight - 8));
                      setFlyout((prev) =>
                        prev?.name === item.name
                          ? null
                          : { name: item.name, top, left: rect.right }
                      );
                    }}
                    className={`nav-item ${isActive ? 'active' : ''}`}
                    title={item.name}
                  >
                    <Icon className="nav-icon" size={20} />
                    {sidebarOpen && (
                      <>
                        <span className="nav-label">{item.name}</span>
                        <ChevronDown
                          className={`nav-arrow ${isExpanded ? 'expanded' : ''}`}
                          size={16}
                        />
                      </>
                    )}
                  </button>
                  {sidebarOpen && isExpanded && (
                    <div className="nav-submenu">
                      {item.subItems.map((subItem) => {
                        const SubIcon = subItem.icon;
                        return (
                          <button
                            key={subItem.path}
                            onClick={() => subItem.path && navigate(subItem.path)}
                            className={`nav-subitem ${subItem.path && (location.pathname === subItem.path || location.pathname.startsWith(subItem.path + '/')) ? 'active' : ''}`}
                            title={subItem.name}
                          >
                            <SubIcon className="nav-icon" size={18} />
                            <span className="nav-label">{subItem.name}</span>
                          </button>
                        );
                      })}
                    </div>
                  )}
                  {!sidebarOpen && flyout?.name === item.name && (
                    <div
                      ref={flyoutRef}
                      className="nav-flyout"
                      style={{ top: flyout.top, left: flyout.left }}
                    >
                      <div className="nav-flyout-title">{item.name}</div>
                      {item.subItems.map((subItem) => {
                        const SubIcon = subItem.icon;
                        const subActive =
                          subItem.path &&
                          (location.pathname === subItem.path ||
                            location.pathname.startsWith(subItem.path + '/'));
                        return (
                          <button
                            key={subItem.path}
                            onClick={() => {
                              if (subItem.path) navigate(subItem.path);
                              setFlyout(null);
                            }}
                            className={`nav-flyout-item ${subActive ? 'active' : ''}`}
                          >
                            <SubIcon size={16} />
                            <span>{subItem.name}</span>
                          </button>
                        );
                      })}
                    </div>
                  )}
                </div>
              );
            }

            // Mentions is fed by the live stream rather than the polled queue counts.
            const queueCount =
              item.name === 'Mentions' ? mentionsUnread : queueCounts[item.name] ?? 0;
            return (
              <button
                key={item.path}
                onClick={() => item.path && navigate(item.path)}
                className={`nav-item ${location.pathname === item.path ? 'active' : ''}`}
                title={item.name}
              >
                <Icon className="nav-icon" size={20} />
                {sidebarOpen && <span className="nav-label">{item.name}</span>}
                {queueCount > 0 && (
                  <span
                    className={`nav-badge ${QUEUE_BADGE_COLORS[item.name] ?? ''}`}
                    title={queueCount.toLocaleString()}
                  >
                    {formatCompact(queueCount)}
                  </span>
                )}
              </button>
            );
          })}
        </nav>

        {/* Straddles the sidebar's right border, level with the page title in the top bar. */}
        <button
          className="sidebar-toggle-bubble"
          onClick={() => {
            const next = !sidebarOpen;
            localStorage.setItem('sidebarOpen', String(next));
            setSidebarOpen(next);
            setFlyout(null);
          }}
          title={sidebarOpen ? 'Collapse sidebar' : 'Expand sidebar'}
          aria-label={sidebarOpen ? 'Collapse sidebar' : 'Expand sidebar'}
          aria-expanded={sidebarOpen}
        >
          {sidebarOpen ? <ChevronLeft size={14} /> : <ChevronRight size={14} />}
        </button>
      </aside>

      <div className="main-content">
        <header className="top-bar">
          <div className="top-bar-left">
            {breadcrumbs && breadcrumbs.length > 0 ? (
              <nav className="page-breadcrumbs" aria-label="Breadcrumb">
                {breadcrumbs.map((crumb, i) => {
                  // Lead the trail with the same icon shown for this section in the sidebar
                  const SectionIcon = i === 0 && crumb.to
                    ? (menuItems.find(item => item.path === crumb.to)?.icon
                        || menuItems.flatMap(item => item.subItems || []).find(sub => sub.path === crumb.to)?.icon)
                    : undefined;
                  return (
                    <span key={i} className="page-breadcrumb">
                      {i > 0 && <ChevronRight size={16} className="page-breadcrumb-sep" />}
                      {crumb.to ? (
                        <Link to={crumb.to} className="page-breadcrumb-link">
                          {SectionIcon && <SectionIcon size={16} className="page-breadcrumb-icon" />}
                          {crumb.label}
                        </Link>
                      ) : (
                        <span className="page-breadcrumb-current">{crumb.label}</span>
                      )}
                    </span>
                  );
                })}
              </nav>
            ) : (() => {
              const CurrentIcon = menuItems.find(item => item.path === location.pathname)?.icon
                || menuItems.flatMap(item => item.subItems || []).find(subItem => subItem.path === location.pathname)?.icon
                || LayoutDashboard;
              return (
                <h1 className="page-title">
                  <CurrentIcon size={18} className="page-breadcrumb-icon" />
                  {pageTitle ||
                   menuItems.find(item => item.path === location.pathname)?.name ||
                   menuItems.flatMap(item => item.subItems || []).find(subItem => subItem.path === location.pathname)?.name ||
                   'Dashboard'}
                </h1>
              );
            })()}
          </div>

          <div className="top-bar-right">
            <NotificationBell />
            <div className="user-dropdown" ref={dropdownRef}>
              <button
                className="user-dropdown-trigger"
                onClick={() => setUserDropdownOpen(!userDropdownOpen)}
              >
                {user.username ? (
                  // Seeded by username so the identicon matches discussion comments
                  <UserAvatar userId={user.username} name={user.username} size={32} className="user-avatar-img" />
                ) : (
                  <div className="user-avatar">
                    {getUserInitials(user.username || '')}
                  </div>
                )}
                <span className="user-name">{user.username}</span>
                <ChevronDown
                  className={`dropdown-arrow ${userDropdownOpen ? 'open' : ''}`}
                  size={16}
                />
              </button>

              {userDropdownOpen && (
                <div className="user-dropdown-menu">
                  <div className="dropdown-header">
                    <div className="dropdown-user-info">
                      {user.username ? (
                        <UserAvatar userId={user.username} name={user.username} size={40} className="user-avatar-img" />
                      ) : (
                        <div className="user-avatar large">
                          {getUserInitials(user.username || '')}
                        </div>
                      )}
                      <div>
                        <div className="dropdown-username">{user.username}</div>
                        <div className="dropdown-role">
                          {authorities.includes('super_admin')
                            ? 'Super Admin'
                            : authorities.some((a: string) => a.includes(':all'))
                            ? 'Admin'
                            : 'User'}
                        </div>
                      </div>
                    </div>
                  </div>

                  <div className="dropdown-divider" />

                  <div className="dropdown-section">
                    <button
                      className="dropdown-item"
                      onClick={() => {
                        setUserDropdownOpen(false);
                        navigate('/account/profile');
                      }}
                    >
                      <UserCircle size={16} />
                      <span>My Profile</span>
                    </button>
                  </div>

                  <div className="dropdown-divider" />

                  {permissions.canManageOwnApiKeys(authorities) && (
                    <>
                      <div className="dropdown-section">
                        <button
                          className="dropdown-item"
                          onClick={() => {
                            setUserDropdownOpen(false);
                            navigate('/account/api-keys');
                          }}
                        >
                          <KeyRound size={16} />
                          <span>API Keys</span>
                        </button>
                      </div>

                      <div className="dropdown-divider" />
                    </>
                  )}

                  <div className="dropdown-section">
                    <div className="theme-toggle-row">
                      <div className="theme-toggle-label">
                        {theme === 'light' ? <Sun size={16} /> : <Moon size={16} />}
                        <span>Light Mode</span>
                      </div>
                      <label className="toggle-switch">
                        <input
                          type="checkbox"
                          checked={theme === 'light'}
                          onChange={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
                        />
                        <span className="toggle-track" />
                      </label>
                    </div>
                  </div>

                  <div className="dropdown-divider" />

                  <div className="dropdown-section">
                    <div className="editor-view-label">
                      <PenLine size={16} />
                      <span>Default editor view</span>
                    </div>
                    <div className="editor-view-options" role="radiogroup" aria-label="Default editor view">
                      {EDITOR_VIEW_OPTIONS.map(opt => (
                        <label key={opt.value} className="editor-view-option">
                          <input
                            type="radio"
                            name="editor-default-view"
                            value={opt.value}
                            checked={editorView === opt.value}
                            onChange={() => {
                              setEditorView(opt.value);
                              localStorage.setItem('rte-default-view', opt.value);
                            }}
                          />
                          <span>{opt.label}</span>
                        </label>
                      ))}
                    </div>
                  </div>

                  <div className="dropdown-divider" />

                  <div className="dropdown-section">
                    <button className="dropdown-item logout-item" onClick={handleLogout}>
                      <LogOut size={16} />
                      <span>Sign out</span>
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>
        </header>

        <main className="content-area">
          {children}
        </main>

        <footer className="app-footer">
          &copy; {new Date().getFullYear()} Faction Security
          {appVersion && <> &middot; v{appVersion.replace(/^v/, '')}</>}
        </footer>
      </div>
    </div>
  );
}

export default function DashboardLayout({ children }: DashboardLayoutProps) {
  return (
    <NotificationStreamProvider>
      <DashboardChrome>{children}</DashboardChrome>
    </NotificationStreamProvider>
  );
}
