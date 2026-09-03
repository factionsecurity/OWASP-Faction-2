import { useState, useEffect } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import { PageTitleProvider } from './context/PageTitleContext';
import { authApi } from './api';
import { getCurrentUser } from './utils/permissions';
import { BrandingProvider } from './context/BrandingContext';
import { EditionProvider } from './context/EditionContext';
import UpgradeDialog from './components/UpgradeDialog';
import { PaidFeature } from './components/PaidFeature';
import { SsoConfig, BrandingPage, InboundEmailConfigPage } from '@enterprise';
import Login from './components/Login';
import ForgotPassword from './pages/ForgotPassword';
import ResetPassword from './pages/ResetPassword';
import DashboardLayout from './components/DashboardLayout';
import ProtectedRoute from './components/ProtectedRoute';
import Dashboard from './pages/Dashboard';
import MentionsPage from './pages/MentionsPage';
import Users from './pages/Users';
import Teams from './pages/Teams';
import ManagerDashboard from './pages/ManagerDashboard';
import Roles from './pages/Roles';
import MyApiKeys from './pages/MyApiKeys';
import ProfilePage from './pages/ProfilePage';
import AssessmentConfig from './pages/AssessmentConfig';
import DefaultVulnerabilities from './pages/DefaultVulnerabilities';
import DefaultVulnerabilityForm from './pages/DefaultVulnerabilityForm';
import Organizations from './pages/Organizations';
import OrganizationEdit from './pages/OrganizationEdit';
import Applications from './pages/Applications';
import ApplicationEdit from './pages/ApplicationEdit';
import ReportDesigner from './pages/ReportDesigner';
import Engagements from './pages/Engagements';
import CreateAssessment from './pages/CreateAssessment';
import Assessments from './pages/Assessments';
import AssessmentDetail from './pages/AssessmentDetail';
import PeerReviewQueue from './pages/PeerReviewQueue';
import PeerReviewEditor from './pages/PeerReviewEditor';
import VulnerabilitiesPage from './pages/VulnerabilitiesPage';
import OrgConfig from './pages/OrgConfig';
import RetestsPage from './pages/RetestsPage';
import RetestDetailPage from './pages/RetestDetailPage';
import ScheduleRetestPage from './pages/ScheduleRetestPage';
import RemediationPage from './pages/RemediationPage';
import EmailConfigPage from './pages/EmailConfigPage';
import EmailNotificationsPage from './pages/EmailNotificationsPage';
import AppStore from './pages/AppStore';
import UnsubscribePage from './pages/UnsubscribePage';
import AiConfigPage from './pages/AiConfigPage';
import ContentTemplates from './pages/ContentTemplates';
import Logs from './pages/Logs';
import ApplicationIdConfig from './pages/ApplicationIdConfig';

function App() {
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const isTokenExpired = (token: string): boolean => {
      try {
        const payload = JSON.parse(atob(token.split('.')[1]));
        return typeof payload.exp === 'number' && payload.exp * 1000 < Date.now();
      } catch {
        return true;
      }
    };

    const checkAuth = () => {
      const token = localStorage.getItem('token');
      if (!token || isTokenExpired(token)) {
        localStorage.removeItem('token');
        localStorage.removeItem('user');
        setIsAuthenticated(false);
      } else {
        setIsAuthenticated(true);
      }
      setLoading(false);
    };

    checkAuth();

    // Re-check when the tab regains focus (catches expiry while backgrounded)
    window.addEventListener('focus', checkAuth);
    // Listen for storage changes (including logout from other tabs)
    window.addEventListener('storage', checkAuth);
    // Custom event for logout from same tab
    window.addEventListener('logout', checkAuth);

    return () => {
      window.removeEventListener('focus', checkAuth);
      window.removeEventListener('storage', checkAuth);
      window.removeEventListener('logout', checkAuth);
    };
  }, []);

  // Sessions that signed in before the login response carried the internal-user flag have
  // no `isInternal` in localStorage, so internal-only controls would stay hidden until the
  // next sign-in. Backfill it once from /auth/me rather than making people log out.
  useEffect(() => {
    if (!isAuthenticated) return;
    const stored = getCurrentUser();
    if (!stored || stored.isInternal !== undefined) return;
    authApi.getMe()
      .then(me => {
        const current = getCurrentUser();
        if (current) {
          localStorage.setItem('user', JSON.stringify({
            ...current, isInternal: me.isInternal ?? false }));
        }
      })
      .catch(() => { /* the API is the real gate; a missing flag only hides a control */ });
  }, [isAuthenticated]);

  const handleLoginSuccess = () => {
    setIsAuthenticated(true);
  };

  if (loading) {
    return (
      <div style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: '100vh',
        color: 'var(--text-muted)'
      }}>
        Loading...
      </div>
    );
  }

  return (
    <BrandingProvider>
    <EditionProvider>
    <PageTitleProvider>
    <UpgradeDialog />
    <Router>
      <Routes>
        <Route
          path="/login"
          element={
            isAuthenticated ? (
              <Navigate to="/dashboard" replace />
            ) : (
              <Login onLoginSuccess={handleLoginSuccess} />
            )
          }
        />

        <Route path="/forgot-password" element={<ForgotPassword />} />
        <Route path="/reset-password" element={<ResetPassword />} />

        <Route
          path="/"
          element={
            isAuthenticated ? (
              <Navigate to="/dashboard" replace />
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/dashboard"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <Dashboard />
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/mentions"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <MentionsPage />
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/users"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewUsers">
                  <Users />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/account/profile"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProfilePage />
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/account/api-keys"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canManageOwnApiKeys">
                  <MyApiKeys />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/teams"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewTeams">
                  <Teams />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/manager-dashboard"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewManagerDashboard">
                  <ManagerDashboard />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/roles"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewRoles">
                  <Roles />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/assessment-config"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewAssessmentConfig">
                  <AssessmentConfig />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/default-vulnerabilities"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewDefaultVulnerabilities">
                  <DefaultVulnerabilities />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/default-vulnerabilities/new"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canCreateDefaultVulnerabilities">
                  <DefaultVulnerabilityForm />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/default-vulnerabilities/:id/edit"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canEditDefaultVulnerabilities">
                  <DefaultVulnerabilityForm />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/report-designer"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewReportDesigner">
                  <ReportDesigner />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/organizations"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewOrganizations">
                  <Organizations />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/organizations/:id/edit"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canEditOrganizations">
                  <OrganizationEdit />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/applications"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewApplications">
                  <Applications />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/applications/:id/edit"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canEditApplications">
                  <ApplicationEdit />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/assessments"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewAssessments">
                  <Assessments />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/assessments/:id"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewAssessments">
                  <AssessmentDetail />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/peer-review"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <PeerReviewQueue />
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/peer-reviews/:reviewId"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <PeerReviewEditor />
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/vulnerabilities"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewVulnerabilities">
                  <VulnerabilitiesPage />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/retests"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewRetests">
                  <RetestsPage />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/retests/schedule"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canScheduleRetests">
                  <ScheduleRetestPage />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/retests/:id"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewRetests">
                  <RetestDetailPage />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/scheduling"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewEngagement">
                  <Engagements />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/scheduling/create"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewEngagement">
                  <CreateAssessment />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/scheduling/edit/:id"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewEngagement">
                  <CreateAssessment />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />


        <Route
          path="/remediation"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewRemediation">
                  <RemediationPage />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/org-config"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewOrgConfig">
                  <OrgConfig />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/sso-config"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewSsoConfig">
                  <PaidFeature
                    feature="sso"
                    title="Single Sign-On"
                    description="Sign-in through SAML 2.0 or OpenID Connect, with accounts provisioned from your identity provider."
                  >
                    <SsoConfig />
                  </PaidFeature>
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/email-config"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewSsoConfig">
                  <EmailConfigPage />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        {/* Public: reached from an email link, so it must work without a session. */}
        <Route path="/unsubscribe" element={<UnsubscribePage />} />

        <Route
          path="/branding"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewSsoConfig">
                  <PaidFeature
                    feature="branding"
                    title="Custom Branding"
                    description="Replace the shipped logos, sign-in backgrounds and favicon with your own."
                  >
                    <BrandingPage />
                  </PaidFeature>
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/email-notifications"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewSsoConfig">
                  <EmailNotificationsPage />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/inbound-email-config"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewSsoConfig">
                  <PaidFeature
                    feature="inbound_email"
                    title="Email Inbox Monitoring"
                    description="Watches a mailbox so replies to notification emails land back on the thread they came from."
                  >
                    <InboundEmailConfigPage />
                  </PaidFeature>
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/app-store"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewExtensions">
                  <AppStore />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/ai-config"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewAiConfig">
                  <AiConfigPage />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/content-templates"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canManageContentTemplates">
                  <ContentTemplates />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/logs"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canViewAuditLogs">
                  <Logs />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route
          path="/application-id-config"
          element={
            isAuthenticated ? (
              <DashboardLayout>
                <ProtectedRoute requiredPermission="canManageApplicationIdConfig">
                  <ApplicationIdConfig />
                </ProtectedRoute>
              </DashboardLayout>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />

        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Router>
    </PageTitleProvider>
    </EditionProvider>
    </BrandingProvider>
  );
}

export default App;
