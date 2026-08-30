import { useEffect, useRef, useState } from 'react';
import { Upload, Trash2, KeyRound } from 'lucide-react';
import { usePageTitle } from '../context/PageTitleContext';
import { profileApi } from '../api';
import type { User } from '../types';
import {
  Button,
  ConfirmDialog,
  ErrorMessage,
  FormGroup,
  FormHint,
  FormLabel,
  Input,
  Toast,
} from '../components';
import Page from '../components/Page';
import UserAvatar from '../components/UserAvatar';
import NotificationPreferencesSection from '../components/NotificationPreferencesSection';
import { refreshProfileImageMap } from '../utils/avatars';
import './ProfilePage.css';

export default function ProfilePage() {
  const { setPageTitle } = usePageTitle();
  const [user, setUser] = useState<User | null>(null);
  const [loadError, setLoadError] = useState('');
  const [toast, setToast] = useState<string | null>(null);

  // Avatar
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [imageError, setImageError] = useState('');
  const [confirmRemove, setConfirmRemove] = useState(false);
  const [removing, setRemoving] = useState(false);
  // Bumps the avatar img key so a replaced upload re-renders immediately
  const [avatarVersion, setAvatarVersion] = useState(0);

  // Change password
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [passwordError, setPasswordError] = useState('');
  const [changingPassword, setChangingPassword] = useState(false);

  useEffect(() => {
    setPageTitle('My Profile');
    profileApi
      .me()
      .then((res) => setUser(res.data ?? null))
      .catch(() => setLoadError('Could not load your profile.'));
  }, [setPageTitle]);

  const handleFileChosen = async (file: File | undefined) => {
    if (!file) return;
    setImageError('');
    setUploading(true);
    try {
      const res = await profileApi.uploadImage(file);
      await refreshProfileImageMap();
      setUser((u) => (u ? { ...u, profileImageId: res.data?.profileImageId } : u));
      setAvatarVersion((v) => v + 1);
      setToast('Profile image updated');
    } catch (err: any) {
      setImageError(err?.response?.data?.message || 'Could not upload the image.');
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const handleRemoveImage = async () => {
    setRemoving(true);
    try {
      await profileApi.removeImage();
      await refreshProfileImageMap();
      setUser((u) => (u ? { ...u, profileImageId: null } : u));
      setAvatarVersion((v) => v + 1);
      setConfirmRemove(false);
      setToast('Profile image removed');
    } catch (err: any) {
      setImageError(err?.response?.data?.message || 'Could not remove the image.');
      setConfirmRemove(false);
    } finally {
      setRemoving(false);
    }
  };

  const handleChangePassword = async (e: React.FormEvent) => {
    e.preventDefault();
    setPasswordError('');
    if (newPassword.length < 8) {
      setPasswordError('New password must be at least 8 characters.');
      return;
    }
    if (newPassword !== confirmPassword) {
      setPasswordError('New passwords do not match.');
      return;
    }
    setChangingPassword(true);
    try {
      await profileApi.changePassword(currentPassword, newPassword);
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
      setToast('Password changed');
    } catch (err: any) {
      setPasswordError(err?.response?.data?.message || 'Could not change your password.');
    } finally {
      setChangingPassword(false);
    }
  };

  const isSsoManaged = user != null && user.loginOption !== 'NATIVE';

  return (
    <Page variant="narrow">
      {loadError && <ErrorMessage>{loadError}</ErrorMessage>}

      <section className="profile-card">
        <h2 className="profile-card-title">Profile Image</h2>
        <div className="profile-avatar-row">
          {user && (
            <UserAvatar
              key={avatarVersion}
              userId={user.username}
              name={user.username}
              size={72}
              className="profile-avatar-img"
            />
          )}
          <div className="profile-avatar-info">
            <p className="profile-avatar-hint">
              Shown next to your comments in vulnerability and application discussions.
              Without an upload, your generated avatar (shown here) is used.
            </p>
            <div className="profile-avatar-actions">
              <Button
                variant="secondary"
                size="sm"
                onClick={() => fileInputRef.current?.click()}
                disabled={uploading}
              >
                <Upload size={14} />
                {uploading ? 'Uploading…' : user?.profileImageId ? 'Replace image' : 'Upload image'}
              </Button>
              {user?.profileImageId && (
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => setConfirmRemove(true)}
                  disabled={uploading}
                >
                  <Trash2 size={14} />
                  Remove
                </Button>
              )}
            </div>
            <FormHint>PNG, JPEG, GIF or WebP, up to 2 MB.</FormHint>
            {imageError && <ErrorMessage>{imageError}</ErrorMessage>}
          </div>
          <input
            ref={fileInputRef}
            type="file"
            accept="image/png,image/jpeg,image/gif,image/webp"
            style={{ display: 'none' }}
            onChange={(e) => handleFileChosen(e.target.files?.[0])}
          />
        </div>
      </section>

      <section className="profile-card">
        <h2 className="profile-card-title">
          <KeyRound size={16} />
          Change Password
        </h2>
        {isSsoManaged ? (
          <p className="profile-sso-note">
            Your password is managed by your identity provider and cannot be changed here.
          </p>
        ) : (
          <form className="profile-password-form" onSubmit={handleChangePassword}>
            <FormGroup>
              <FormLabel htmlFor="current-password">Current password</FormLabel>
              <Input
                id="current-password"
                type="password"
                autoComplete="current-password"
                value={currentPassword}
                onChange={(e) => setCurrentPassword(e.target.value)}
                required
              />
            </FormGroup>
            <FormGroup>
              <FormLabel htmlFor="new-password">New password</FormLabel>
              <Input
                id="new-password"
                type="password"
                autoComplete="new-password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                required
              />
              <FormHint>At least 8 characters.</FormHint>
            </FormGroup>
            <FormGroup>
              <FormLabel htmlFor="confirm-password">Confirm new password</FormLabel>
              <Input
                id="confirm-password"
                type="password"
                autoComplete="new-password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                required
              />
            </FormGroup>
            {passwordError && <ErrorMessage>{passwordError}</ErrorMessage>}
            <div className="profile-password-actions">
              <Button
                type="submit"
                disabled={changingPassword || !currentPassword || !newPassword || !confirmPassword}
              >
                {changingPassword ? 'Changing…' : 'Change password'}
              </Button>
            </div>
          </form>
        )}
      </section>

      <ConfirmDialog
        isOpen={confirmRemove}
        onClose={() => setConfirmRemove(false)}
        onConfirm={handleRemoveImage}
        title="Remove Profile Image"
        message="Your avatar will revert to the default generated image."
        confirmText="Remove"
        variant="warning"
        isLoading={removing}
      />

      <NotificationPreferencesSection />

      {toast && <Toast message={toast} onDone={() => setToast(null)} />}
    </Page>
  );
}
