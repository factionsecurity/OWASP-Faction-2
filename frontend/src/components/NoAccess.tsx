import { Lock, LogOut } from 'lucide-react';
import './NoAccess.css';

interface NoAccessProps {
  username?: string;
  onLogout: () => void;
}

/**
 * Shown to authenticated users who have no roles or permissions yet
 * (e.g. accounts auto-provisioned through SSO birthright access).
 */
export default function NoAccess({ username, onLogout }: NoAccessProps) {
  return (
    <div className="no-access-container">
      <div className="no-access-card">
        <div className="no-access-icon">
          <Lock size={28} />
        </div>
        <h1>Access Not Yet Granted</h1>
        <p>
          {username ? <>You are signed in as <strong>{username}</strong>, but your</> : 'Your'} account
          does not have any roles or permissions assigned. Ask your administrator to grant
          you access, then sign out and sign back in.
        </p>
        <button type="button" className="no-access-logout" onClick={onLogout}>
          <LogOut size={15} />
          Sign Out
        </button>
      </div>
    </div>
  );
}
