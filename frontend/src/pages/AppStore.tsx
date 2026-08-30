import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { QuotaNotice } from '../components/PaidFeature';
import { useEdition } from '../context/EditionContext';
import {
  AlertTriangle,
  Blocks,
  ClipboardList,
  ExternalLink,
  FileText,
  Loader2,
  Package,
  RefreshCw,
  ScrollText,
  ShieldAlert,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import { usePageTitle } from '../context/PageTitleContext';
import { extensionsApi, EXTENSION_SECRET_MASK } from '../api';
import type {
  Extension,
  ExtensionConfigEntry,
  ExtensionLog,
  UpdateExtensionRequest,
} from '../types';
import { usePermissions } from '../utils/permissions';
import Page from '../components/Page';
import { Button, ConfirmDialog, ErrorMessage, FormLabel, Input, Checkbox } from '../components';
import './AppStore.css';

/**
 * The five extension hooks, in the order they are shown on a card.
 *
 * `provides` is what the JAR declares and cannot be changed; `enabled` is the
 * operator's switch. Keeping them separate lets the UI say "this JAR has a Report
 * hook, currently off" rather than conflating absent with disabled.
 */
const HOOKS = [
  {
    key: 'report' as const,
    label: 'Report',
    icon: FileText,
    provides: 'providesReport' as const,
    enabled: 'reportEnabled' as const,
    hint: 'Rewrites report rich text as the report is generated — used for charts and graphics.',
  },
  {
    key: 'assessment' as const,
    label: 'Assessment',
    icon: ClipboardList,
    provides: 'providesAssessment' as const,
    enabled: 'assessmentEnabled' as const,
    hint: 'Fires on create, update, delete, finalize and peer review — used to push findings out.',
  },
  {
    key: 'vulnerability' as const,
    label: 'Vulnerability',
    icon: ShieldAlert,
    provides: 'providesVulnerability' as const,
    enabled: 'vulnerabilityEnabled' as const,
    hint: 'Fires when a single finding is created, updated or deleted.',
  },
  {
    key: 'verification' as const,
    label: 'Retest',
    icon: RefreshCw,
    provides: 'providesVerification' as const,
    enabled: 'verificationEnabled' as const,
    hint: 'Fires when a retest is assigned, cancelled, passed or failed.',
  },
  {
    key: 'inventory' as const,
    label: 'Inventory',
    icon: Package,
    provides: 'providesInventory' as const,
    enabled: 'inventoryEnabled' as const,
    hint: 'Supplies applications from an external system of record to the application picker.',
  },
];

export default function AppStore() {
  const { atLimit, refresh: refreshEdition } = useEdition();
  const { setPageTitle } = usePageTitle();
  const { permissions } = usePermissions();
  const canManage = permissions.canManageExtensions;

  const [extensions, setExtensions] = useState<Extension[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [busyId, setBusyId] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const [configDraft, setConfigDraft] = useState<Record<string, string>>({});
  const [savingConfig, setSavingConfig] = useState(false);
  const [configError, setConfigError] = useState<string | null>(null);
  const [configSaved, setConfigSaved] = useState(false);

  const [logs, setLogs] = useState<ExtensionLog[]>([]);
  const [loadingLogs, setLoadingLogs] = useState(false);

  const [installing, setInstalling] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState<Extension | null>(null);
  const [deleting, setDeleting] = useState(false);

  const installInputRef = useRef<HTMLInputElement>(null);
  const upgradeInputRef = useRef<HTMLInputElement>(null);
  const descriptionRef = useRef<HTMLDivElement>(null);

  /** The description image being viewed full size, if any. */
  const [lightbox, setLightbox] = useState<{ src: string; alt: string } | null>(null);

  useEffect(() => setPageTitle('App Store'), [setPageTitle]);

  const load = useCallback(async () => {
    try {
      setLoading(true);
      const response = await extensionsApi.getAll();
      setExtensions(response.data ?? []);
      setError(null);
    } catch (err) {
      setError(messageOf(err, 'Could not load extensions'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const expanded = useMemo(
    () => extensions.find((e) => e.id === expandedId) ?? null,
    [extensions, expandedId]
  );

  // ── Description image lightbox ────────────────────────────────────────────
  // Descriptions are injected HTML, so the images cannot carry React handlers.
  // Delegate from the container instead, and mark each image up after render so
  // it is reachable by keyboard rather than mouse-only.

  useEffect(() => {
    const root = descriptionRef.current;
    if (!root) return;
    root.querySelectorAll('img').forEach((img) => {
      img.tabIndex = 0;
      img.setAttribute('role', 'button');
      img.setAttribute('aria-label', `Enlarge image${img.alt ? `: ${img.alt}` : ''}`);
    });
  }, [expandedId, expanded?.description]);

  const openLightboxFromEvent = (e: React.MouseEvent<HTMLDivElement>) => {
    const img = imageTarget(e.target);
    if (img) setLightbox({ src: img.currentSrc || img.src, alt: img.alt });
  };

  const openLightboxFromKey = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    const img = imageTarget(e.target);
    if (!img) return;
    e.preventDefault(); // Space would otherwise scroll the page
    setLightbox({ src: img.currentSrc || img.src, alt: img.alt });
  };

  useEffect(() => {
    if (!lightbox) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setLightbox(null);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [lightbox]);

  /** Replaces one extension in place so the card list does not reorder mid-edit. */
  const replace = (updated: Extension) =>
    setExtensions((current) => current.map((e) => (e.id === updated.id ? updated : e)));

  const openDetail = async (extension: Extension) => {
    if (expandedId === extension.id) {
      setExpandedId(null);
      return;
    }
    setExpandedId(extension.id);
    setConfigError(null);
    setConfigSaved(false);
    setConfigDraft(toDraft(extension.config));

    setLoadingLogs(true);
    try {
      const response = await extensionsApi.getLogs(extension.id);
      setLogs(response.data ?? []);
    } catch {
      setLogs([]);
    } finally {
      setLoadingLogs(false);
    }
  };

  const patch = async (extension: Extension, changes: UpdateExtensionRequest) => {
    setBusyId(extension.id);
    setError(null);
    try {
      const response = await extensionsApi.update(extension.id, changes);
      if (response.data) replace(response.data);
    } catch (err) {
      setError(messageOf(err, 'Could not update the extension'));
    } finally {
      setBusyId(null);
    }
  };

  const saveConfig = async () => {
    if (!expanded) return;
    setSavingConfig(true);
    setConfigError(null);
    setConfigSaved(false);
    try {
      const response = await extensionsApi.updateConfig(expanded.id, configDraft);
      if (response.data) {
        replace(response.data);
        // Re-seed from the response so saved passwords come back as the mask.
        setConfigDraft(toDraft(response.data.config));
      }
      setConfigSaved(true);
    } catch (err) {
      setConfigError(messageOf(err, 'Could not save the configuration'));
    } finally {
      setSavingConfig(false);
    }
  };

  const install = async (file: File) => {
    setInstalling(true);
    setError(null);
    try {
      await extensionsApi.install(file);
      await load();
      await refreshEdition();
    } catch (err) {
      setError(messageOf(err, 'Could not install the extension'));
    } finally {
      setInstalling(false);
      if (installInputRef.current) installInputRef.current.value = '';
    }
  };

  const upgrade = async (file: File) => {
    if (!expanded) return;
    setBusyId(expanded.id);
    setError(null);
    try {
      const response = await extensionsApi.upgrade(expanded.id, file);
      if (response.data) replace(response.data);
    } catch (err) {
      setError(messageOf(err, 'Could not upgrade the extension'));
    } finally {
      setBusyId(null);
      if (upgradeInputRef.current) upgradeInputRef.current.value = '';
    }
  };

  const uninstall = async () => {
    if (!confirmDelete) return;
    setDeleting(true);
    try {
      await extensionsApi.uninstall(confirmDelete.id);
      if (expandedId === confirmDelete.id) setExpandedId(null);
      setConfirmDelete(null);
      await load();
      await refreshEdition();
    } catch (err) {
      setError(messageOf(err, 'Could not uninstall the extension'));
    } finally {
      setDeleting(false);
    }
  };

  return (
    <Page>
      <div className="page-header">
        <div />
        {canManage && (
          <>
            <input
              ref={installInputRef}
              type="file"
              accept=".jar"
              className="appstore-file-input"
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) void install(file);
              }}
            />
            <QuotaNotice quota="extensions" noun="installed" />
            <Button
              variant="primary"
              icon={installing ? Loader2 : Upload}
              disabled={installing || atLimit('extensions')}
              onClick={() => installInputRef.current?.click()}
              title={atLimit('extensions')
                ? 'This edition is limited to 2 integrations'
                : undefined}
            >
              {installing ? 'Installing…' : 'Install Extension'}
            </Button>
          </>
        )}
      </div>

      <div className="appstore-notice">
        <AlertTriangle size={16} />
        <span>
          An extension runs inside the Faction server with full access to its data. Only install
          JARs you have built or trust.
        </span>
      </div>

      {error && <ErrorMessage>{error}</ErrorMessage>}

      {loading ? (
        <div className="appstore-empty">
          <Loader2 className="appstore-spin" size={20} />
          <span>Loading extensions…</span>
        </div>
      ) : extensions.length === 0 ? (
        <div className="appstore-empty">
          <Blocks size={28} />
          <h3>No extensions installed</h3>
          <p>
            Build a JAR against the <code>faction-extender</code> API with the
            <code> jar-with-dependencies</code> assembly descriptor, then install it here.
          </p>
        </div>
      ) : (
        <div className="appstore-grid">
          {extensions.map((extension) => (
            <div
              key={extension.id}
              className={`appstore-card${extension.enabled ? ' appstore-card--on' : ''}`}
            >
              <div className="appstore-card-head">
                {extension.logoBase64 ? (
                  <img
                    className="appstore-logo"
                    src={`data:${extension.logoMimeType ?? 'image/png'};base64,${extension.logoBase64}`}
                    alt=""
                  />
                ) : (
                  <div className="appstore-logo appstore-logo--fallback">
                    <Blocks size={22} />
                  </div>
                )}

                <div className="appstore-identity">
                  <h3>{extension.name}</h3>
                  <div className="appstore-meta">
                    {extension.version && <span>v{extension.version}</span>}
                    {extension.author && <span>{extension.author}</span>}
                    {extension.url && (
                      <a href={extension.url} target="_blank" rel="noreferrer noopener">
                        Home <ExternalLink size={11} />
                      </a>
                    )}
                  </div>
                </div>

                <label className="appstore-switch" title={extension.enabled ? 'Enabled' : 'Disabled'}>
                  <input
                    type="checkbox"
                    checked={extension.enabled}
                    disabled={!canManage || busyId === extension.id}
                    onChange={(e) => void patch(extension, { enabled: e.target.checked })}
                  />
                  <span />
                </label>
              </div>

              <div className="appstore-hooks">
                {HOOKS.filter((hook) => extension[hook.provides]).map((hook) => {
                  const on = extension.enabled && extension[hook.enabled];
                  const Icon = hook.icon;
                  return (
                    <span
                      key={hook.key}
                      className={`appstore-hook${on ? ' appstore-hook--on' : ''}`}
                      title={hook.hint}
                    >
                      <Icon size={12} />
                      {hook.label}
                    </span>
                  );
                })}
              </div>

              <div className="appstore-card-actions">
                <Button variant="secondary" onClick={() => void openDetail(extension)}>
                  {expandedId === extension.id ? 'Hide details' : 'Configure'}
                </Button>
                {canManage && (
                  <Button
                    variant="danger"
                    icon={Trash2}
                    onClick={() => setConfirmDelete(extension)}
                  >
                    Uninstall
                  </Button>
                )}
              </div>

              {expandedId === extension.id && expanded && (
                <div className="appstore-detail">
                  {expanded.description && (
                    <div
                      ref={descriptionRef}
                      className="appstore-description"
                      onClick={openLightboxFromEvent}
                      onKeyDown={openLightboxFromKey}
                      dangerouslySetInnerHTML={{ __html: renderDescription(expanded.description) }}
                    />
                  )}

                  <section className="appstore-section">
                    <h4>Hooks</h4>
                    {HOOKS.filter((hook) => expanded[hook.provides]).map((hook) => (
                      <div key={hook.key} className="appstore-hook-row">
                        <Checkbox
                          label={hook.label}
                          checked={!!expanded[hook.enabled]}
                          disabled={!canManage || busyId === expanded.id}
                          onChange={(e) =>
                            void patch(expanded, { [hook.enabled]: e.target.checked })
                          }
                        />
                        <span className="appstore-hook-hint">{hook.hint}</span>
                      </div>
                    ))}
                  </section>

                  {Object.keys(expanded.config ?? {}).length > 0 && (
                    <section className="appstore-section">
                      <h4>Configuration</h4>
                      <div className="appstore-config-grid">
                        {Object.entries(expanded.config).map(([key, entry]) => {
                          const isSecret = entry?.type?.toLowerCase() === 'password';
                          return (
                            <div key={key} className="appstore-config-field">
                              <FormLabel htmlFor={`cfg-${key}`}>{key}</FormLabel>
                              <Input
                                id={`cfg-${key}`}
                                type={isSecret ? 'password' : 'text'}
                                value={configDraft[key] ?? ''}
                                disabled={!canManage}
                                onChange={(e) =>
                                  setConfigDraft((draft) => ({ ...draft, [key]: e.target.value }))
                                }
                              />
                              {isSecret && configDraft[key] === EXTENSION_SECRET_MASK && (
                                <span className="appstore-config-hint">
                                  Saved. Type a new value to replace it.
                                </span>
                              )}
                            </div>
                          );
                        })}
                      </div>

                      {configError && <ErrorMessage>{configError}</ErrorMessage>}

                      {canManage && (
                        <div className="appstore-config-actions">
                          <Button variant="primary" disabled={savingConfig} onClick={() => void saveConfig()}>
                            {savingConfig ? 'Saving…' : 'Save configuration'}
                          </Button>
                          {configSaved && <span className="appstore-saved">Saved</span>}
                        </div>
                      )}
                    </section>
                  )}

                  {canManage && (
                    <section className="appstore-section">
                      <h4>Upgrade</h4>
                      <p className="appstore-hook-hint">
                        Replaces the JAR and keeps the values configured above.
                      </p>
                      <input
                        ref={upgradeInputRef}
                        type="file"
                        accept=".jar"
                        className="appstore-file-input"
                        onChange={(e) => {
                          const file = e.target.files?.[0];
                          if (file) void upgrade(file);
                        }}
                      />
                      <Button
                        variant="secondary"
                        icon={Upload}
                        disabled={busyId === expanded.id}
                        onClick={() => upgradeInputRef.current?.click()}
                      >
                        Upload new JAR
                      </Button>
                    </section>
                  )}

                  <section className="appstore-section">
                    <h4>
                      <ScrollText size={14} /> Recent logs
                    </h4>
                    {loadingLogs ? (
                      <p className="appstore-hook-hint">Loading…</p>
                    ) : logs.length === 0 ? (
                      <p className="appstore-hook-hint">Nothing logged yet.</p>
                    ) : (
                      <div className="appstore-logs">
                        {logs.map((entry) => (
                          <div
                            key={entry.id}
                            className={`appstore-log appstore-log--${entry.level?.toLowerCase()}`}
                          >
                            <span className="appstore-log-time">
                              {new Date(entry.timestamp).toLocaleString()}
                            </span>
                            <span className="appstore-log-level">{entry.level}</span>
                            <span className="appstore-log-event">{entry.eventType}</span>
                            <span className="appstore-log-message">{entry.message}</span>
                          </div>
                        ))}
                      </div>
                    )}
                  </section>
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {lightbox && (
        <div
          className="appstore-lightbox"
          role="dialog"
          aria-modal="true"
          aria-label={lightbox.alt || 'Extension image'}
          onClick={() => setLightbox(null)}
        >
          <button
            type="button"
            className="appstore-lightbox-close"
            aria-label="Close image"
            onClick={() => setLightbox(null)}
            autoFocus
          >
            <X size={18} />
          </button>
          {/* Clicking the image itself closes too — the whole overlay is the
              dismiss target, so there is no dead zone to get stuck in. */}
          <img src={lightbox.src} alt={lightbox.alt} />
        </div>
      )}

      <ConfirmDialog
        isOpen={!!confirmDelete}
        onClose={() => setConfirmDelete(null)}
        onConfirm={() => void uninstall()}
        title="Uninstall Extension"
        message={
          confirmDelete
            ? `Uninstall "${confirmDelete.name}"? Its JAR, configuration and logs will be removed. This cannot be undone.`
            : ''
        }
        confirmText="Uninstall"
        variant="danger"
        isLoading={deleting}
      />
    </Page>
  );
}

/** True when an event landed on a description image rather than the prose around it. */
function imageTarget(target: EventTarget | null): HTMLImageElement | null {
  return target instanceof HTMLImageElement ? target : null;
}

/**
 * Renders an extension's `description.md`.
 *
 * <p>The file is markdown, and extension authors write it as such — the bundled
 * bar-chart extension uses `__bold__` and a numbered list. Injecting it verbatim
 * printed that syntax literally instead of formatting it.
 *
 * <p>Descriptions also embed raw HTML: a `<center><img src="data:image/png;base64,…">`
 * preview of what the extension produces. `marked` passes that through, and
 * DOMPurify keeps data-URI images on `<img>` while dropping anything active. Same
 * marked → DOMPurify pairing RichTextEditor uses for editor content.
 */
function renderDescription(markdown: string): string {
  return DOMPurify.sanitize(String(marked.parse(markdown)));
}

/** Flattens the declared config document into the flat map the form edits. */
function toDraft(config?: Record<string, ExtensionConfigEntry>): Record<string, string> {
  const entries = Object.entries(config ?? {});
  return Object.fromEntries(entries.map(([key, entry]) => [key, entry?.value ?? '']));
}

function messageOf(err: unknown, fallback: string): string {
  const response = (err as { response?: { data?: { message?: string; error?: string } } })?.response;
  return response?.data?.message ?? response?.data?.error ?? fallback;
}
