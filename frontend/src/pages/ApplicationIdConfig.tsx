import { useState, useEffect, useCallback } from 'react';
import { applicationIdConfigApi } from '../api';
import type { ApplicationIdConfig } from '../types';
import { Button } from '../components';
import Toast from '../components/Toast';
import Page from '../components/Page';
import './ApplicationIdConfig.css';

type ToastType = 'success' | 'error';

export default function ApplicationIdConfig() {
  const [config, setConfig] = useState<ApplicationIdConfig | null>(null);
  const [prefix, setPrefix] = useState('');
  const [nextNumber, setNextNumber] = useState(1);
  const [enabled, setEnabled] = useState(true);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [toast, setToast] = useState<{ type: ToastType; message: string } | null>(null);
  const [preview, setPreview] = useState<string[]>([]);

  const loadConfig = useCallback(async () => {
    setLoading(true);
    try {
      const res = await applicationIdConfigApi.getConfig();
      if (res.success && res.data) {
        setConfig(res.data);
        setPrefix(res.data.prefix);
        setNextNumber(res.data.nextNumber);
        setEnabled(res.data.enabled);
      }
    } catch (err: any) {
      setToast({ type: 'error', message: err.response?.data?.message || 'Failed to load configuration' });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadConfig();
  }, [loadConfig]);

  useEffect(() => {
    const loadPreview = async () => {
      try {
        const res = await applicationIdConfigApi.getPreviewNext(5);
        if (res.success && res.data) {
          setPreview(res.data);
        }
      } catch {
        // ignore preview errors
      }
    };
    loadPreview();
  }, [prefix, nextNumber, enabled]);

  const handleSave = async () => {
    if (!config) return;
    setSaving(true);
    try {
      const res = await applicationIdConfigApi.updateConfig({ prefix, nextNumber: Number(nextNumber), enabled });
      if (res.success && res.data) {
        setToast({ type: 'success', message: 'Configuration saved successfully' });
        await loadConfig();
      } else {
        setToast({ type: 'error', message: res.message || 'Failed to save configuration' });
      }
    } catch (err: any) {
      setToast({ type: 'error', message: err.response?.data?.message || 'Failed to save configuration' });
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <Page><div className="aic-loading">Loading...</div></Page>;
  }

  return (
    <Page>
      <h2>Application ID Configuration</h2>
      {toast && <Toast message={toast.message} onDone={() => setToast(null)} variant={toast.type === 'success' ? 'success' : 'danger'} />}
      <div className="aic-card">
        <div className="aic-form">
          <div className="aic-field">
            <label>Prefix</label>
            <input
              type="text"
              value={prefix}
              onChange={(e) => setPrefix(e.target.value)}
              placeholder="ASMT"
            />
          </div>
          <div className="aic-field">
            <label>Next Number</label>
            <input
              type="number"
              value={nextNumber}
              onChange={(e) => setNextNumber(Number(e.target.value))}
              min={1}
            />
          </div>
          <div className="aic-field aic-checkbox-field">
            <label>
              <input
                type="checkbox"
                checked={enabled}
                onChange={(e) => setEnabled(e.target.checked)}
              />
              Enable auto-generated appIds
            </label>
          </div>
          <div className="aic-actions">
            <Button onClick={handleSave} disabled={saving}>
              {saving ? 'Saving...' : 'Save Configuration'}
            </Button>
          </div>
        </div>
        {preview.length > 0 && (
          <div className="aic-preview">
            <h3>Preview</h3>
            <ul>
              {preview.map((id) => (
                <li key={id}>{id}</li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </Page>
  );
}
