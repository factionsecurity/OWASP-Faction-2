import { useState, FormEvent, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { authApi, ssoConfigApi } from '../api';
import type { SsoStatus } from '../types';
import { useBranding, useRandomLoginBackground } from '../context/BrandingContext';
import './Login.css';

interface LoginProps {
  onLoginSuccess: () => void;
}

export default function Login({ onLoginSuccess }: LoginProps) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [ssoStatus, setSsoStatus] = useState<SsoStatus | null>(null);

  useEffect(() => {
    // Handle SSO callback — token delivered via query param
    const params = new URLSearchParams(window.location.search);
    const ssoToken = params.get('sso_token');
    const ssoUserId = params.get('userId');
    const ssoUsername = params.get('username');
    const ssoError = params.get('sso_error');

    if (ssoToken) {
      // Complete SSO login
      localStorage.setItem('token', ssoToken);
      localStorage.setItem('user', JSON.stringify({
        id: ssoUserId || '',
        username: ssoUsername || '',
        authorities: [],
        roles: [],
      }));
      // Clean up URL
      window.history.replaceState({}, '', '/login');
      // Fetch full user info using the token
      authApi.getMe()
        .then((me) => {
          if (me?.username) {
            localStorage.setItem('user', JSON.stringify({
              id: me.id || ssoUserId || '',
              username: me.username || ssoUsername || '',
              authorities: me.authorities || [],
              roles: me.roles || [],
              isInternal: me.isInternal ?? false,
            }));
          }
        })
        .catch(() => {})
        .finally(() => onLoginSuccess());
      return;
    }

    if (ssoError) {
      const messages: Record<string, string> = {
        user_not_found: 'No account found with that email address. Contact your administrator.',
        account_disabled: 'Your account is disabled.',
        invalid_state: 'Authentication session expired. Please try again.',
        no_email: 'Could not retrieve email from identity provider.',
        processing_failed: 'SSO authentication failed. Please try again.',
      };
      setError(messages[ssoError] || 'SSO authentication failed.');
      window.history.replaceState({}, '', '/login');
    }

    // Load SSO status to show/hide buttons
    ssoConfigApi.getStatus()
      .then(res => { if (res.data) setSsoStatus(res.data); })
      .catch(() => {}); // non-critical
  }, []);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (loading) return;
    setError('');
    setLoading(true);
    try {
      const response = await authApi.login({ username, password });
      if (response.token) {
        localStorage.setItem('token', response.token);
        localStorage.setItem('user', JSON.stringify({
          id: response.userId,
          username: response.username,
          authorities: response.authorities,
          roles: response.roles || [],
          isInternal: response.isInternal ?? false,
        }));
        onLoginSuccess();
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Invalid credentials');
    } finally {
      setLoading(false);
    }
  };

  const handleSamlLogin = () => {
    window.location.href = '/api/v1/auth/saml/initiate';
  };

  const handleOidcLogin = () => {
    window.location.href = '/api/v1/auth/oidc/initiate';
  };

  const hasSsoOptions = ssoStatus?.saml2Enabled || ssoStatus?.oidcEnabled;

  const { loginLogo, loginLogoHeight } = useBranding();
  const background = useRandomLoginBackground();

  return (
    <div
      className={`login-container${background ? ' login-container--image' : ''}`}
      // Inline rather than a CSS class: the URL is chosen at runtime, so there is no
      // stylesheet rule that could express it.
      style={background ? { backgroundImage: `url(${background})` } : undefined}
    >
      <div className="login-card">
        <div className="login-header">
          <img
            src={loginLogo}
            alt=""
            className="login-logo"
            style={{ height: loginLogoHeight }}
          />
        </div>

        <form onSubmit={handleSubmit} className="login-form">
          <div className="form-group">
            <label htmlFor="username" className="form-label">Username</label>
            <input
              id="username"
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              className="form-input"
              placeholder="Enter your username"
              required
              autoFocus
            />
          </div>

          <div className="form-group">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
              <label htmlFor="password" className="form-label">Password</label>
              {/* Out of the tab order deliberately: tabbing from the username field should
                  land on the password field, not detour through this link. It stays
                  reachable by click and to screen readers, which do not navigate by Tab. */}
              <Link to="/forgot-password" className="login-forgot-link" tabIndex={-1}>Forgot password?</Link>
            </div>
            <input
              id="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="form-input"
              placeholder="Enter your password"
              required
            />
          </div>

          {error && <div className="error-message">{error}</div>}

          <button type="submit" className="submit-button" disabled={loading}>
            {loading ? 'Signing in...' : 'Sign In'}
          </button>

          {hasSsoOptions && (
            <div className="sso-login-divider">
              <span>or continue with</span>
            </div>
          )}

          {hasSsoOptions && (
            <div className="sso-login-buttons">
              {ssoStatus?.saml2Enabled && (
                <button
                  type="button"
                  className="sso-login-btn"
                  onClick={handleSamlLogin}
                >
                  {ssoStatus.saml2ButtonLabel || 'Login with SAML'}
                </button>
              )}
              {ssoStatus?.oidcEnabled && (
                <button
                  type="button"
                  className="sso-login-btn"
                  onClick={handleOidcLogin}
                >
                  {ssoStatus.oidcButtonLabel || 'Login with OpenID Connect'}
                </button>
              )}
            </div>
          )}
        </form>

        <div className="login-footer">
          <p className="text-sm text-muted">
          </p>
        </div>
      </div>
    </div>
  );
}
