import { useEffect, useState } from 'react';
import { Users, Building2, AppWindow, Search } from 'lucide-react';
import { Navigate } from 'react-router-dom';
import Page from '../components/Page';
import { permissions, isSuperAdmin } from '../utils/permissions';
import './Dashboard.css';
import PentesterDashboard from './PentesterDashboard';
import AppOwnerDashboard from './AppOwnerDashboard';
import ManagerDashboard from './ManagerDashboard';
// Not one of the switchable views below — only the Remediation role's landing page.
import VulnerabilityDashboard from './VulnerabilityDashboard';
import { authApi } from '../api';
import { getCurrentUser } from '../utils/permissions';
import { usePageTitle } from '../context/PageTitleContext';
import './Dashboard.css';

// Super admins can view every role dashboard; Pentester is the first/default view.
const ADMIN_DASHBOARD_VIEWS = [
  { key: 'pentester', label: 'Pentester', Component: PentesterDashboard },
  { key: 'app-owner', label: 'App Owner', Component: AppOwnerDashboard },
  { key: 'operational', label: 'Operational', Component: ManagerDashboard },
] as const;

type AdminDashboardView = (typeof ADMIN_DASHBOARD_VIEWS)[number]['key'];

const ADMIN_VIEW_STORAGE_KEY = 'superAdminDashboardView';

function SuperAdminDashboard() {
  const { setPageTitle } = usePageTitle();
  const [view, setView] = useState<AdminDashboardView>(() => {
    const saved = localStorage.getItem(ADMIN_VIEW_STORAGE_KEY);
    return ADMIN_DASHBOARD_VIEWS.some(v => v.key === saved)
      ? (saved as AdminDashboardView)
      : 'pentester';
  });
  const Selected = ADMIN_DASHBOARD_VIEWS.find(v => v.key === view)!.Component;

  const selectView = (key: AdminDashboardView) => {
    if (key === view) return;
    localStorage.setItem(ADMIN_VIEW_STORAGE_KEY, key);
    // The title reset in DashboardLayout only fires on route changes — clear the
    // previous dashboard's header title here (only ManagerDashboard sets one).
    setPageTitle('');
    setView(key);
  };

  return (
    <>
      <div className="dashboard-view-switcher">
        <span className="dashboard-view-label">Dashboard</span>
        {ADMIN_DASHBOARD_VIEWS.map(v => (
          <button
            key={v.key}
            className={`dashboard-view-btn${view === v.key ? ' active' : ''}`}
            onClick={() => selectView(v.key)}
          >
            {v.label}
          </button>
        ))}
      </div>
      <Selected />
    </>
  );
}

export default function Dashboard() {
  const [roles, setRoles] = useState<string[] | null>(() => getCurrentUser()?.roles ?? null);

  // Sessions created before role names were added to the login response have no
  // roles in localStorage — backfill them from /auth/me once.
  useEffect(() => {
    if (roles !== null) return;
    authApi
      .getMe()
      .then(me => {
        const user = getCurrentUser();
        if (user) {
          localStorage.setItem('user', JSON.stringify({ ...user, roles: me.roles ?? [] }));
        }
        setRoles(me.roles ?? []);
      })
      .catch(() => setRoles([]));
  }, [roles]);

  if (roles === null) return null;

  if (isSuperAdmin(getCurrentUser()?.authorities ?? [])) {
    return <SuperAdminDashboard />;
  }

  // Every seeded pentester role — the default one and the scoped Pentester-Team /
  // Pentester-Assessment variants — lands on the pentester dashboard.
  if (roles.some(r => r === 'Pentester' || r.startsWith('Pentester-'))) {
    return <PentesterDashboard />;
  }

  // Remediation roles work findings to closure across assessments rather than running
  // assessments, so the vulnerability dashboard is their landing page. Checked after Pentester:
  // someone holding both is primarily an assessor.
  if (roles.some(r => r === 'Remediation' || r.startsWith('Remediation-'))) {
    return <VulnerabilityDashboard />;
  }

  if (roles.includes('App Owner')) {
    return <AppOwnerDashboard />;
  }

  // Admins and users with the manager-dashboard grant land on the Operational
  // Dashboard by default — the generic dashboard below is still a placeholder.
  if (permissions.canViewManagerDashboard(getCurrentUser()?.authorities ?? [])) {
    return <Navigate to="/manager-dashboard" replace />;
  }

  return (
    <Page className="dashboard-page">
      <div className="stats-grid">
        <div className="stat-card">
          <div className="stat-icon primary">
            <Users size={28} />
          </div>
          <div className="stat-info">
            <p className="stat-label">Total Users</p>
            <p className="stat-value">--</p>
          </div>
        </div>

        <div className="stat-card">
          <div className="stat-icon success">
            <Building2 size={28} />
          </div>
          <div className="stat-info">
            <p className="stat-label">Organizations</p>
            <p className="stat-value">--</p>
          </div>
        </div>

        <div className="stat-card">
          <div className="stat-icon warning">
            <AppWindow size={28} />
          </div>
          <div className="stat-info">
            <p className="stat-label">Applications</p>
            <p className="stat-value">--</p>
          </div>
        </div>

        <div className="stat-card">
          <div className="stat-icon danger">
            <Search size={28} />
          </div>
          <div className="stat-info">
            <p className="stat-label">Active Assessments</p>
            <p className="stat-value">--</p>
          </div>
        </div>
      </div>

      <div className="dashboard-grid">
        <div className="dashboard-card">
          <h2 className="card-title">Recent Activity</h2>
          <div className="placeholder-content">
            <p className="text-muted">Activity feed will be displayed here</p>
          </div>
        </div>

        <div className="dashboard-card">
          <h2 className="card-title">Quick Actions</h2>
          <div className="placeholder-content">
            <p className="text-muted">Quick action buttons will be displayed here</p>
          </div>
        </div>
      </div>

      <div className="dashboard-card">
        <h2 className="card-title">System Overview</h2>
        <div className="placeholder-content">
          <p className="text-muted">System metrics and charts will be displayed here</p>
        </div>
      </div>
    </Page>
  );
}
