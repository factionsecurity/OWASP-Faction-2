import { useEffect, useMemo, useState } from 'react';
import { QuotaNotice } from '../components/PaidFeature';
import { useEdition } from '../context/EditionContext';
import { usePageTitle } from '../context/PageTitleContext';
import { aiConfigApi } from '../api';
import type {
  AiModelInfo,
  AiPromptScope,
  AiPromptTemplate,
  AiProviderConfig,
  AiProviderType,
  WebSearchConfig,
  WebSearchProviderType,
  AiAnonymizationConfig,
} from '../types';
import { Button, IconButton, Input, Textarea, FormLabel, Select, Modal, ConfirmDialog, Toast } from '../components';
import Page from '../components/Page';
import { AiTokenUsageCard } from '@enterprise';
import {
  Bot,
  CheckCircle2,
  XCircle,
  Loader2,
  Sparkles,
  Plus,
  Pencil,
  Trash2,
  X,
  PlugZap,
  Globe,
  ShieldCheck,
  ScrollText,
} from 'lucide-react';
import { usePermissions } from '../utils/permissions';
import './AiConfigPage.css';

const MASKED = '••••••••';

interface ProviderMeta {
  label: string;
  defaultBaseUrl?: string;
  requiresBaseUrl: boolean;
  baseUrlPlaceholder: string;
  hint?: string;
}

const PROVIDER_META: Record<AiProviderType, ProviderMeta> = {
  OPENAI: {
    label: 'OpenAI',
    defaultBaseUrl: 'https://api.openai.com/v1',
    requiresBaseUrl: false,
    baseUrlPlaceholder: 'https://api.openai.com/v1 (default)',
  },
  ANTHROPIC: {
    label: 'Anthropic',
    defaultBaseUrl: 'https://api.anthropic.com/v1',
    requiresBaseUrl: false,
    baseUrlPlaceholder: 'https://api.anthropic.com/v1 (default)',
  },
  OPENROUTER: {
    label: 'OpenRouter',
    defaultBaseUrl: 'https://openrouter.ai/api/v1',
    requiresBaseUrl: false,
    baseUrlPlaceholder: 'https://openrouter.ai/api/v1 (default)',
  },
  AZURE_OPENAI: {
    label: 'Azure OpenAI',
    requiresBaseUrl: true,
    baseUrlPlaceholder: 'https://your-resource.openai.azure.com',
    hint: 'Use your Azure resource endpoint. Requests target deployments, so add your deployment names as models.',
  },
  OPENAI_COMPATIBLE: {
    label: 'OpenAI-Compatible',
    requiresBaseUrl: true,
    baseUrlPlaceholder: 'http://localhost:11434/v1',
    hint: 'Any endpoint implementing the OpenAI API shape (Ollama, vLLM, LM Studio, LiteLLM, …). API key is optional if the endpoint does not require one.',
  },
};

const PROVIDER_TYPES = Object.keys(PROVIDER_META) as AiProviderType[];

interface TestResult {
  success: boolean;
  message: string;
  details?: string;
}

interface EditorState {
  id?: string;
  name: string;
  providerType: AiProviderType;
  baseUrl: string;
  apiKey: string;
  apiVersion: string;
  models: string[];
  defaultModel: string;
  enabled: boolean;
}

const emptyEditor = (): EditorState => ({
  name: '',
  providerType: 'OPENAI',
  baseUrl: '',
  apiKey: '',
  apiVersion: '',
  models: [],
  defaultModel: '',
  enabled: true,
});

const SCOPE_LABELS: Record<AiPromptScope, string> = {
  ASSESSMENT: 'Assessments',
  VULNERABILITY: 'Vulnerabilities',
};

interface PromptEditorState {
  id?: string;
  name: string;
  description: string;
  scope: AiPromptScope;
  prompt: string;
  providerId: string;
  model: string;
  allowWebAccess: boolean;
  enabled: boolean;
}

const emptyPromptEditor = (): PromptEditorState => ({
  name: '',
  description: '',
  scope: 'ASSESSMENT',
  prompt: '',
  providerId: '',
  model: '',
  allowWebAccess: false,
  enabled: true,
});

export default function AiConfigPage() {
  const { atLimit, refresh: refreshEdition, hasFeature } = useEdition();
  const hasAiObservability = hasFeature('ai_observability');
  const { setPageTitle } = usePageTitle();
  const { permissions: perms } = usePermissions();
  const canManage = perms.canManageAiConfig;

  const [providers, setProviders] = useState<AiProviderConfig[]>([]);
  const [loading, setLoading] = useState(true);
  const [toast, setToast] = useState<{ type: 'success' | 'danger'; message: string } | null>(null);

  // Editor modal
  const [editorOpen, setEditorOpen] = useState(false);
  const [editor, setEditor] = useState<EditorState>(emptyEditor());
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  // Connection test / model discovery
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<TestResult | null>(null);
  const [fetchedModels, setFetchedModels] = useState<AiModelInfo[]>([]);
  const [modelFilter, setModelFilter] = useState('');
  const [manualModel, setManualModel] = useState('');

  // Delete confirmation
  const [deleteTarget, setDeleteTarget] = useState<AiProviderConfig | null>(null);
  const [deleting, setDeleting] = useState(false);

  // Prompt templates
  const [prompts, setPrompts] = useState<AiPromptTemplate[]>([]);
  const [promptEditorOpen, setPromptEditorOpen] = useState(false);
  const [promptEditor, setPromptEditor] = useState<PromptEditorState>(emptyPromptEditor());
  const [promptSaving, setPromptSaving] = useState(false);
  const [promptFormError, setPromptFormError] = useState<string | null>(null);
  const [deletePromptTarget, setDeletePromptTarget] = useState<AiPromptTemplate | null>(null);
  const [deletingPrompt, setDeletingPrompt] = useState(false);

  // Web search config
  const [webEnabled, setWebEnabled] = useState(false);
  const [webAllowInAskAi, setWebAllowInAskAi] = useState(false);
  const [webProvider, setWebProvider] = useState<WebSearchProviderType>('BRAVE');
  const [webApiKey, setWebApiKey] = useState('');
  const [webSaving, setWebSaving] = useState(false);
  const [webSaveSuccess, setWebSaveSuccess] = useState(false);

  // Anonymization (data privacy) config
  const [anonEnabled, setAnonEnabled] = useState(false);
  const [anonPresidioUrl, setAnonPresidioUrl] = useState('');
  const [anonThreshold, setAnonThreshold] = useState(0.5);
  const [anonSaving, setAnonSaving] = useState(false);
  const [anonSaveSuccess, setAnonSaveSuccess] = useState(false);

  // Request logging config
  const [logEnabled, setLogEnabled] = useState(false);
  const [logRetentionDays, setLogRetentionDays] = useState(30);
  const [logSaving, setLogSaving] = useState(false);
  const [logSaveSuccess, setLogSaveSuccess] = useState(false);

  useEffect(() => {
    setPageTitle('AI Configuration');
    loadProviders();
    loadPrompts();
    loadWebSearch();
    loadAnonymization();
    if (hasAiObservability) loadLogging();
  }, []);

  const loadProviders = async () => {
    setLoading(true);
    try {
      const res = await aiConfigApi.getProviders();
      setProviders(res.data || []);
    } catch {
      setToast({ type: 'danger', message: 'Failed to load AI providers.' });
    } finally {
      setLoading(false);
    }
  };

  const openCreate = () => {
    setEditor(emptyEditor());
    resetEditorScratch();
    setEditorOpen(true);
  };

  const openEdit = (p: AiProviderConfig) => {
    setEditor({
      id: p.id,
      name: p.name,
      providerType: p.providerType,
      baseUrl: p.baseUrl || '',
      apiKey: p.apiKey || '',
      apiVersion: p.apiVersion || '',
      models: p.models || [],
      defaultModel: p.defaultModel || '',
      enabled: p.enabled,
    });
    resetEditorScratch();
    setEditorOpen(true);
  };

  const resetEditorScratch = () => {
    setFormError(null);
    setTestResult(null);
    setFetchedModels([]);
    setModelFilter('');
    setManualModel('');
  };

  const setField = <K extends keyof EditorState>(key: K, value: EditorState[K]) => {
    setEditor(prev => ({ ...prev, [key]: value }));
  };

  const meta = PROVIDER_META[editor.providerType];

  const handleTypeChange = (type: AiProviderType) => {
    setEditor(prev => ({ ...prev, providerType: type }));
    setTestResult(null);
    setFetchedModels([]);
  };

  const addModel = (model: string) => {
    const m = model.trim();
    if (!m) return;
    setEditor(prev => {
      if (prev.models.includes(m)) return prev;
      const models = [...prev.models, m];
      return { ...prev, models, defaultModel: prev.defaultModel || m };
    });
  };

  const removeModel = (model: string) => {
    setEditor(prev => {
      const models = prev.models.filter(m => m !== model);
      return {
        ...prev,
        models,
        defaultModel: prev.defaultModel === model ? (models[0] || '') : prev.defaultModel,
      };
    });
  };

  const handleTest = async () => {
    setTesting(true);
    setTestResult(null);
    setFetchedModels([]);
    try {
      const res = await aiConfigApi.test({
        id: editor.id,
        providerType: editor.providerType,
        baseUrl: editor.baseUrl.trim() || undefined,
        apiKey: editor.apiKey || undefined,
        apiVersion: editor.apiVersion.trim() || undefined,
      });
      if (res.data) {
        setTestResult({ success: res.data.success, message: res.data.message, details: res.data.details });
        setFetchedModels(res.data.models || []);
      }
    } catch {
      setTestResult({ success: false, message: 'Request failed. Check your network connection.' });
    } finally {
      setTesting(false);
    }
  };

  const handleSave = async () => {
    if (!editor.name.trim()) {
      setFormError('Provider name is required.');
      return;
    }
    if (meta.requiresBaseUrl && !editor.baseUrl.trim()) {
      setFormError(`${meta.label} providers require a base URL.`);
      return;
    }
    setFormError(null);
    setSaving(true);
    try {
      const payload = {
        name: editor.name.trim(),
        providerType: editor.providerType,
        baseUrl: editor.baseUrl.trim(),
        apiKey: editor.apiKey || undefined,
        apiVersion: editor.apiVersion.trim(),
        models: editor.models,
        defaultModel: editor.defaultModel,
        enabled: editor.enabled,
      };
      if (editor.id) {
        await aiConfigApi.updateProvider(editor.id, payload);
      } else {
        await aiConfigApi.createProvider(payload);
      }
      setEditorOpen(false);
      setToast({ type: 'success', message: `AI provider ${editor.id ? 'updated' : 'added'}.` });
      loadProviders();
      void refreshEdition();
    } catch (err: any) {
      setFormError(err?.response?.data?.message || 'Failed to save provider.');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await aiConfigApi.deleteProvider(deleteTarget.id);
      setDeleteTarget(null);
      setToast({ type: 'success', message: 'AI provider deleted.' });
      loadProviders();
      void refreshEdition();
    } catch {
      setToast({ type: 'danger', message: 'Failed to delete provider.' });
    } finally {
      setDeleting(false);
    }
  };

  const loadPrompts = async () => {
    try {
      const res = await aiConfigApi.getPrompts();
      setPrompts(res.data || []);
    } catch {
      // ignore — the card just shows empty
    }
  };

  const openCreatePrompt = () => {
    setPromptEditor(emptyPromptEditor());
    setPromptFormError(null);
    setPromptEditorOpen(true);
  };

  const openEditPrompt = (p: AiPromptTemplate) => {
    setPromptEditor({
      id: p.id,
      name: p.name,
      description: p.description || '',
      scope: p.scope,
      prompt: p.prompt,
      providerId: p.providerId || '',
      model: p.model || '',
      allowWebAccess: p.allowWebAccess,
      enabled: p.enabled,
    });
    setPromptFormError(null);
    setPromptEditorOpen(true);
  };

  const setPromptField = <K extends keyof PromptEditorState>(key: K, value: PromptEditorState[K]) => {
    setPromptEditor(prev => ({ ...prev, [key]: value }));
  };

  const handleSavePrompt = async () => {
    if (!promptEditor.name.trim()) {
      setPromptFormError('Prompt name is required.');
      return;
    }
    if (!promptEditor.prompt.trim()) {
      setPromptFormError('Prompt text is required.');
      return;
    }
    setPromptFormError(null);
    setPromptSaving(true);
    try {
      const payload = {
        name: promptEditor.name.trim(),
        description: promptEditor.description.trim(),
        scope: promptEditor.scope,
        prompt: promptEditor.prompt,
        providerId: promptEditor.providerId,
        model: promptEditor.model.trim(),
        allowWebAccess: promptEditor.allowWebAccess,
        enabled: promptEditor.enabled,
      };
      if (promptEditor.id) {
        await aiConfigApi.updatePrompt(promptEditor.id, payload);
      } else {
        await aiConfigApi.createPrompt(payload);
      }
      setPromptEditorOpen(false);
      setToast({ type: 'success', message: `Prompt ${promptEditor.id ? 'updated' : 'added'}.` });
      loadPrompts();
      void refreshEdition();
    } catch (err: any) {
      setPromptFormError(err?.response?.data?.message || 'Failed to save prompt.');
    } finally {
      setPromptSaving(false);
    }
  };

  const handleDeletePrompt = async () => {
    if (!deletePromptTarget) return;
    setDeletingPrompt(true);
    try {
      await aiConfigApi.deletePrompt(deletePromptTarget.id);
      setDeletePromptTarget(null);
      setToast({ type: 'success', message: 'Prompt deleted.' });
      loadPrompts();
      void refreshEdition();
    } catch {
      setToast({ type: 'danger', message: 'Failed to delete prompt.' });
    } finally {
      setDeletingPrompt(false);
    }
  };

  const loadWebSearch = async () => {
    try {
      const res = await aiConfigApi.getWebSearchConfig();
      if (res.data) populateWebSearch(res.data);
    } catch {
      // ignore — card shows defaults
    }
  };

  const populateWebSearch = (c: WebSearchConfig) => {
    setWebEnabled(c.enabled);
    setWebAllowInAskAi(c.allowInAskAi);
    setWebProvider(c.provider || 'BRAVE');
    setWebApiKey(c.apiKey || '');
  };

  const handleSaveWebSearch = async () => {
    setWebSaving(true);
    setWebSaveSuccess(false);
    try {
      const res = await aiConfigApi.updateWebSearchConfig({
        enabled: webEnabled,
        allowInAskAi: webAllowInAskAi,
        provider: webProvider,
        apiKey: webApiKey || undefined,
      });
      if (res.data) populateWebSearch(res.data);
      setWebSaveSuccess(true);
      setTimeout(() => setWebSaveSuccess(false), 3000);
    } catch {
      setToast({ type: 'danger', message: 'Failed to save web search settings.' });
    } finally {
      setWebSaving(false);
    }
  };

  const loadAnonymization = async () => {
    try {
      const res = await aiConfigApi.getAnonymizationConfig();
      if (res.data) populateAnonymization(res.data);
    } catch {
      // ignore — card shows defaults
    }
  };

  const populateAnonymization = (c: AiAnonymizationConfig) => {
    setAnonEnabled(c.enabled);
    setAnonPresidioUrl(c.presidioUrl || '');
    setAnonThreshold(typeof c.scoreThreshold === 'number' ? c.scoreThreshold : 0.5);
  };

  const handleSaveAnonymization = async () => {
    setAnonSaving(true);
    setAnonSaveSuccess(false);
    try {
      const res = await aiConfigApi.updateAnonymizationConfig({
        enabled: anonEnabled,
        presidioUrl: anonPresidioUrl.trim(),
        scoreThreshold: anonThreshold,
      });
      if (res.data) populateAnonymization(res.data);
      setAnonSaveSuccess(true);
      setTimeout(() => setAnonSaveSuccess(false), 3000);
    } catch {
      setToast({ type: 'danger', message: 'Failed to save data privacy settings.' });
    } finally {
      setAnonSaving(false);
    }
  };

  const loadLogging = async () => {
    try {
      const res = await aiConfigApi.getLoggingConfig();
      if (res.data) {
        setLogEnabled(res.data.enabled);
        setLogRetentionDays(res.data.retentionDays ?? 30);
      }
    } catch {
      // ignore — card shows defaults
    }
  };

  const handleSaveLogging = async () => {
    setLogSaving(true);
    setLogSaveSuccess(false);
    try {
      const res = await aiConfigApi.updateLoggingConfig({ enabled: logEnabled });
      if (res.data) {
        setLogEnabled(res.data.enabled);
        setLogRetentionDays(res.data.retentionDays ?? 30);
      }
      setLogSaveSuccess(true);
      setTimeout(() => setLogSaveSuccess(false), 3000);
    } catch {
      setToast({ type: 'danger', message: 'Failed to save logging settings.' });
    } finally {
      setLogSaving(false);
    }
  };

  const promptProvider = providers.find(p => p.id === promptEditor.providerId);

  const visibleFetchedModels = useMemo(() => {
    const filter = modelFilter.trim().toLowerCase();
    if (!filter) return fetchedModels;
    return fetchedModels.filter(
      m => m.id.toLowerCase().includes(filter) || m.name.toLowerCase().includes(filter)
    );
  }, [fetchedModels, modelFilter]);

  if (loading) {
    return (
      <Page variant="narrow" className="ai-config-page">
        <div className="ai-config-loading">
          <Loader2 size={24} className="spin" /> Loading…
        </div>
      </Page>
    );
  }

  return (
    <Page variant="narrow" className="ai-config-page">
      {/* Token accounting runs in every build; only this reporting view is edition-specific. */}
      {hasAiObservability && <AiTokenUsageCard />}

      <div className="ai-config-card">
        <div className="ai-config-card-header">
          <Sparkles size={18} />
          <span>AI Providers</span>
          {canManage && (
            <div className="ai-config-header-actions">
              <QuotaNotice quota="ai_providers" noun="providers" />
              <Button
                variant="primary"
                size="sm"
                onClick={openCreate}
                disabled={atLimit('ai_providers')}
                title={atLimit('ai_providers')
                  ? 'This edition supports one AI provider'
                  : undefined}
              >
                <Plus size={14} /> Add Provider
              </Button>
            </div>
          )}
        </div>
        <div className="ai-config-body">
          {providers.length === 0 ? (
            <div className="ai-config-empty">
              No AI providers configured yet.
              {canManage && ' Click "Add Provider" to connect OpenAI, Anthropic, OpenRouter, Azure OpenAI, or any OpenAI-compatible endpoint.'}
            </div>
          ) : (
            <div className="ai-provider-list">
              {providers.map(p => (
                <div key={p.id} className={`ai-provider-row${p.enabled ? '' : ' ai-provider-row--disabled'}`}>
                  <div className="ai-provider-info">
                    <div className="ai-provider-name">
                      {p.name}
                      <span className="ai-provider-type">{PROVIDER_META[p.providerType]?.label || p.providerType}</span>
                      {!p.enabled && <span className="ai-provider-badge">Disabled</span>}
                    </div>
                    <div className="ai-provider-detail">
                      {p.baseUrl || PROVIDER_META[p.providerType]?.defaultBaseUrl || '—'}
                      {' · '}
                      {p.models.length} model{p.models.length === 1 ? '' : 's'}
                      {p.defaultModel && ` · default: ${p.defaultModel}`}
                    </div>
                  </div>
                  {canManage && (
                    <div className="ai-provider-actions">
                      <IconButton icon={Pencil} variant="edit" title="Edit provider" onClick={() => openEdit(p)} />
                      <IconButton icon={Trash2} variant="delete" title="Delete provider" onClick={() => setDeleteTarget(p)} />
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      <div className="ai-config-card">
        <div className="ai-config-card-header">
          <Bot size={18} />
          <span>Prompts</span>
          {canManage && (
            <div className="ai-config-header-actions">
              <QuotaNotice quota="ai_prompts" noun="prompts" />
              <Button
                variant="primary"
                size="sm"
                onClick={openCreatePrompt}
                disabled={atLimit('ai_prompts')}
                title={atLimit('ai_prompts')
                  ? 'This edition is limited to 4 prompts'
                  : undefined}
              >
                <Plus size={14} /> Add Prompt
              </Button>
            </div>
          )}
        </div>
        <div className="ai-config-body">
          <p className="ai-config-hint" style={{ margin: 0 }}>
            Prompts appear in the AI menu of rich text editors. "Assessments" prompts show on
            assessment rich text fields; "Vulnerabilities" prompts show on vulnerability editors.
            The AI can read data from the current assessment only.
          </p>
          {prompts.length === 0 ? (
            <div className="ai-config-empty">
              No prompts configured yet.
              {canManage && ' Click "Add Prompt" to create one, e.g. "Executive Summary" or "Risk Analysis".'}
            </div>
          ) : (
            <div className="ai-provider-list">
              {prompts.map(p => (
                <div key={p.id} className={`ai-provider-row${p.enabled ? '' : ' ai-provider-row--disabled'}`}>
                  <div className="ai-provider-info">
                    <div className="ai-provider-name">
                      {p.name}
                      <span className="ai-provider-type">{SCOPE_LABELS[p.scope]}</span>
                      {!p.enabled && <span className="ai-provider-badge">Disabled</span>}
                    </div>
                    <div className="ai-provider-detail">
                      {p.description || p.prompt}
                      {' · '}
                      {p.providerId
                        ? (providers.find(pr => pr.id === p.providerId)?.name || 'Unknown provider')
                        : 'Default provider'}
                      {p.model && ` · ${p.model}`}
                    </div>
                  </div>
                  {canManage && (
                    <div className="ai-provider-actions">
                      <IconButton icon={Pencil} variant="edit" title="Edit prompt" onClick={() => openEditPrompt(p)} />
                      <IconButton icon={Trash2} variant="delete" title="Delete prompt" onClick={() => setDeletePromptTarget(p)} />
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      <div className="ai-config-card">
        <div className="ai-config-card-header">
          <Globe size={18} />
          <span>Web Search</span>
          <label className="ai-toggle" style={{ marginLeft: 'auto' }}>
            <input
              type="checkbox"
              checked={webEnabled}
              onChange={e => setWebEnabled(e.target.checked)}
              disabled={!canManage}
            />
            <span className="ai-toggle-track" />
            <span className="ai-toggle-label">{webEnabled ? 'Enabled' : 'Disabled'}</span>
          </label>
        </div>
        <div className="ai-config-body">
          <p className="ai-config-hint" style={{ margin: 0 }}>
            The search backend used by prompts that have web access enabled. Get an API key from your
            chosen provider. Fetching individual URLs works without this; only web search needs a key.
          </p>
          {!webEnabled && webApiKey === '••••••••' && (
            <div className="ai-test-result ai-test-result--error">
              <XCircle size={16} />
              <div className="ai-test-result-message">
                A key is saved but Web Search is disabled — prompts with web access can't search until
                you turn on the Enabled toggle above and save.
              </div>
            </div>
          )}
          <div className="ai-config-row">
            <div className="form-group" style={{ flex: 1 }}>
              <FormLabel>Provider</FormLabel>
              <Select
                value={webProvider}
                onChange={e => setWebProvider(e.target.value as WebSearchProviderType)}
                disabled={!canManage}
              >
                <option value="BRAVE">Brave Search</option>
                <option value="TAVILY">Tavily</option>
                <option value="SERPER">Serper (Google)</option>
              </Select>
            </div>
            <div className="form-group" style={{ flex: 2 }}>
              <FormLabel>API Key</FormLabel>
              <Input
                type="password"
                value={webApiKey}
                onChange={e => setWebApiKey(e.target.value)}
                placeholder={webApiKey === '••••••••' ? 'Leave unchanged' : 'Enter API key'}
                autoComplete="new-password"
                disabled={!canManage}
              />
              {webApiKey === '••••••••' && (
                <p className="ai-config-hint">A key is stored. Enter a new value to replace it.</p>
              )}
            </div>
          </div>
          <div className="form-group">
            <label className="ai-inline-toggle">
              <input
                type="checkbox"
                checked={webAllowInAskAi}
                onChange={e => setWebAllowInAskAi(e.target.checked)}
                disabled={!canManage}
              />
              <span>Allow web search in "Ask AI" queries</span>
            </label>
            <p className="ai-config-hint">
              By default only admin-defined prompts with web access can search. Enable this to also let
              users' freeform "Ask AI" requests search the web and fetch pages.
            </p>
          </div>
          {canManage && (
            <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', justifyContent: 'flex-end' }}>
              {webSaveSuccess && (
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: '0.35rem', color: '#22c55e', fontSize: '0.85rem' }}>
                  <CheckCircle2 size={15} /> Saved
                </span>
              )}
              <Button variant="primary" size="sm" onClick={handleSaveWebSearch} disabled={webSaving}>
                {webSaving ? <><Loader2 size={14} className="spin" /> Saving…</> : 'Save Web Search'}
              </Button>
            </div>
          )}
        </div>
      </div>

      <div className="ai-config-card">
        <div className="ai-config-card-header">
          <ShieldCheck size={18} />
          <span>Data Privacy</span>
          <label className="ai-toggle" style={{ marginLeft: 'auto' }}>
            <input
              type="checkbox"
              checked={anonEnabled}
              onChange={e => setAnonEnabled(e.target.checked)}
              disabled={!canManage}
            />
            <span className="ai-toggle-track" />
            <span className="ai-toggle-label">{anonEnabled ? 'Enabled' : 'Disabled'}</span>
          </label>
        </div>
        <div className="ai-config-body">
          <p className="ai-config-hint" style={{ margin: 0 }}>
            When enabled, secrets and PII are masked with placeholders before any text is sent to the
            AI provider, and the real values are restored in the generated output. Built-in patterns
            catch common secrets (API keys, tokens, private keys, credentials, emails) with no extra
            setup; add a Presidio URL below for broader PII detection (names, phone numbers, etc.).
          </p>
          <div className="ai-config-row">
            <div className="form-group" style={{ flex: 2 }}>
              <FormLabel>Presidio Analyzer URL (optional)</FormLabel>
              <Input
                value={anonPresidioUrl}
                onChange={e => setAnonPresidioUrl(e.target.value)}
                placeholder="http://localhost:5002"
                autoComplete="off"
                disabled={!canManage}
              />
              <p className="ai-config-hint">
                A self-hosted Presidio service — no data leaves your infrastructure. Leave blank to use
                only the built-in secret patterns. If set but unreachable, AI generation is blocked
                rather than sending unmasked data.
              </p>
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <FormLabel>Match Threshold</FormLabel>
              <Input
                type="number"
                value={anonThreshold}
                onChange={e => setAnonThreshold(Number(e.target.value))}
                min={0}
                max={1}
                step={0.05}
                disabled={!canManage || !anonPresidioUrl.trim()}
              />
              <p className="ai-config-hint">Presidio confidence (0–1).</p>
            </div>
          </div>
          {canManage && (
            <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', justifyContent: 'flex-end' }}>
              {anonSaveSuccess && (
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: '0.35rem', color: '#22c55e', fontSize: '0.85rem' }}>
                  <CheckCircle2 size={15} /> Saved
                </span>
              )}
              <Button variant="primary" size="sm" onClick={handleSaveAnonymization} disabled={anonSaving}>
                {anonSaving ? <><Loader2 size={14} className="spin" /> Saving…</> : 'Save Data Privacy'}
              </Button>
            </div>
          )}
        </div>
      </div>

      {hasAiObservability && (
      <div className="ai-config-card">
        <div className="ai-config-card-header">
          <ScrollText size={18} />
          <span>Request Logging</span>
          <label className="ai-toggle" style={{ marginLeft: 'auto' }}>
            <input
              type="checkbox"
              checked={logEnabled}
              onChange={e => setLogEnabled(e.target.checked)}
              disabled={!canManage}
            />
            <span className="ai-toggle-track" />
            <span className="ai-toggle-label">{logEnabled ? 'Enabled' : 'Disabled'}</span>
          </label>
        </div>
        <div className="ai-config-body">
          <p className="ai-config-hint" style={{ margin: 0 }}>
            When enabled, every AI request is recorded — including the exact payload sent to the provider
            — so you can verify what data leaves the system. Records are retained for {logRetentionDays} days,
            then purged automatically. View them under Administration → Logs.
          </p>
          {canManage && (
            <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', justifyContent: 'flex-end' }}>
              {logSaveSuccess && (
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: '0.35rem', color: '#22c55e', fontSize: '0.85rem' }}>
                  <CheckCircle2 size={15} /> Saved
                </span>
              )}
              <Button variant="primary" size="sm" onClick={handleSaveLogging} disabled={logSaving}>
                {logSaving ? <><Loader2 size={14} className="spin" /> Saving…</> : 'Save Logging'}
              </Button>
            </div>
          )}
        </div>
      </div>
      )}

      {/* Prompt add / edit modal */}
      <Modal
        isOpen={promptEditorOpen}
        onClose={() => setPromptEditorOpen(false)}
        title={promptEditor.id ? 'Edit Prompt' : 'Add Prompt'}
        size="lg"
        footer={
          <>
            <Button variant="secondary" onClick={() => setPromptEditorOpen(false)} disabled={promptSaving}>
              Cancel
            </Button>
            <Button variant="primary" onClick={handleSavePrompt} disabled={promptSaving}>
              {promptSaving ? <><Loader2 size={14} className="spin" /> Saving…</> : promptEditor.id ? 'Save Changes' : 'Add Prompt'}
            </Button>
          </>
        }
      >
        <div className="ai-editor">
          <div className="ai-config-row">
            <div className="form-group" style={{ flex: 2 }}>
              <FormLabel required>Name</FormLabel>
              <Input
                value={promptEditor.name}
                onChange={e => setPromptField('name', e.target.value)}
                placeholder="e.g. Executive Summary"
              />
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <FormLabel required>Applies To</FormLabel>
              <Select
                value={promptEditor.scope}
                onChange={e => setPromptField('scope', e.target.value as AiPromptScope)}
              >
                <option value="ASSESSMENT">Assessments</option>
                <option value="VULNERABILITY">Vulnerabilities</option>
              </Select>
            </div>
          </div>

          <div className="form-group">
            <FormLabel>Description</FormLabel>
            <Input
              value={promptEditor.description}
              onChange={e => setPromptField('description', e.target.value)}
              placeholder="Shown as a hint in the editor AI menu (optional)"
            />
          </div>

          <div className="form-group">
            <FormLabel required>Prompt</FormLabel>
            <Textarea
              value={promptEditor.prompt}
              onChange={e => setPromptField('prompt', e.target.value)}
              rows={8}
              placeholder={'e.g. Take all the vulnerabilities in this assessment and write an executive summary. '
                + 'Open with a one-paragraph overview of the engagement, then summarize the key findings by '
                + 'severity, and close with prioritized recommendations.'}
            />
            <p className="ai-config-hint">
              The AI can look up this assessment's details and vulnerabilities on its own — describe
              the output you want, including any formatting rules.
            </p>
          </div>

          <div className="form-group">
            <label className="ai-inline-toggle">
              <input
                type="checkbox"
                checked={promptEditor.allowWebAccess}
                onChange={e => setPromptField('allowWebAccess', e.target.checked)}
              />
              <Globe size={14} />
              <span>Allow web search &amp; page fetch</span>
            </label>
            <p className="ai-config-hint">
              Lets this prompt search the web and fetch pages — e.g. to add authoritative reference
              links (OWASP, CWE, CVE). Requires a configured Web Search provider below.
            </p>
          </div>

          <div className="ai-config-row">
            <div className="form-group" style={{ flex: 1 }}>
              <FormLabel>Provider</FormLabel>
              <Select
                value={promptEditor.providerId}
                onChange={e => {
                  setPromptField('providerId', e.target.value);
                  setPromptField('model', '');
                }}
              >
                <option value="">Default provider</option>
                {providers.map(p => (
                  <option key={p.id} value={p.id}>{p.name}</option>
                ))}
              </Select>
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <FormLabel>Model</FormLabel>
              {promptProvider && promptProvider.models.length > 0 ? (
                <Select
                  value={promptEditor.model}
                  onChange={e => setPromptField('model', e.target.value)}
                >
                  <option value="">Provider default</option>
                  {promptProvider.models.map(m => (
                    <option key={m} value={m}>{m}</option>
                  ))}
                </Select>
              ) : (
                <Input
                  value={promptEditor.model}
                  onChange={e => setPromptField('model', e.target.value)}
                  placeholder="Provider default model"
                />
              )}
            </div>
            <div className="form-group" style={{ flex: 1, justifyContent: 'flex-end', display: 'flex', flexDirection: 'column' }}>
              <label className="ai-inline-toggle">
                <input
                  type="checkbox"
                  checked={promptEditor.enabled}
                  onChange={e => setPromptField('enabled', e.target.checked)}
                />
                <span>Prompt enabled</span>
              </label>
            </div>
          </div>

          {promptFormError && (
            <div className="ai-test-result ai-test-result--error">
              <XCircle size={16} />
              <div className="ai-test-result-message">{promptFormError}</div>
            </div>
          )}
        </div>
      </Modal>

      <ConfirmDialog
        isOpen={!!deletePromptTarget}
        onClose={() => setDeletePromptTarget(null)}
        onConfirm={handleDeletePrompt}
        title="Delete Prompt"
        message={`Are you sure you want to delete "${deletePromptTarget?.name}"? This cannot be undone.`}
        confirmText="Delete"
        variant="danger"
        isLoading={deletingPrompt}
      />

      {/* Add / edit modal */}
      <Modal
        isOpen={editorOpen}
        onClose={() => setEditorOpen(false)}
        title={editor.id ? 'Edit AI Provider' : 'Add AI Provider'}
        size="lg"
        footer={
          <>
            <Button variant="secondary" onClick={() => setEditorOpen(false)} disabled={saving}>
              Cancel
            </Button>
            <Button variant="primary" onClick={handleSave} disabled={saving}>
              {saving ? <><Loader2 size={14} className="spin" /> Saving…</> : editor.id ? 'Save Changes' : 'Add Provider'}
            </Button>
          </>
        }
      >
        <div className="ai-editor">
          <div className="ai-config-row">
            <div className="form-group" style={{ flex: 1 }}>
              <FormLabel required>Name</FormLabel>
              <Input
                value={editor.name}
                onChange={e => setField('name', e.target.value)}
                placeholder="e.g. OpenAI (production)"
              />
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <FormLabel required>Provider</FormLabel>
              <Select
                value={editor.providerType}
                onChange={e => handleTypeChange(e.target.value as AiProviderType)}
              >
                {PROVIDER_TYPES.map(t => (
                  <option key={t} value={t}>{PROVIDER_META[t].label}</option>
                ))}
              </Select>
            </div>
          </div>

          <div className="ai-config-row">
            <div className="form-group" style={{ flex: 2 }}>
              <FormLabel required={meta.requiresBaseUrl}>Base URL</FormLabel>
              <Input
                value={editor.baseUrl}
                onChange={e => setField('baseUrl', e.target.value)}
                placeholder={meta.baseUrlPlaceholder}
                autoComplete="off"
              />
            </div>
            {editor.providerType === 'AZURE_OPENAI' && (
              <div className="form-group" style={{ flex: 1 }}>
                <FormLabel>API Version</FormLabel>
                <Input
                  value={editor.apiVersion}
                  onChange={e => setField('apiVersion', e.target.value)}
                  placeholder="2024-10-21 (default)"
                  autoComplete="off"
                />
              </div>
            )}
          </div>

          <div className="form-group">
            <FormLabel>API Key</FormLabel>
            <Input
              type="password"
              value={editor.apiKey}
              onChange={e => setField('apiKey', e.target.value)}
              placeholder={editor.apiKey === MASKED ? 'Leave unchanged' : 'Enter API key'}
              autoComplete="new-password"
            />
            {editor.apiKey === MASKED && (
              <p className="ai-config-hint">An API key is stored. Enter a new value to replace it.</p>
            )}
          </div>

          {meta.hint && <p className="ai-config-hint">{meta.hint}</p>}

          {/* Test connection */}
          <div className="ai-test-row">
            <Button variant="secondary" onClick={handleTest} disabled={testing}>
              {testing
                ? <><Loader2 size={14} className="spin" /> Testing…</>
                : <><PlugZap size={14} /> Test Connection &amp; Fetch Models</>}
            </Button>
          </div>

          {testResult && (
            <div className={`ai-test-result${testResult.success ? ' ai-test-result--success' : ' ai-test-result--error'}`}>
              {testResult.success ? <CheckCircle2 size={16} /> : <XCircle size={16} />}
              <div>
                <div className="ai-test-result-message">{testResult.message}</div>
                {testResult.details && <div className="ai-test-result-details">{testResult.details}</div>}
              </div>
            </div>
          )}

          {fetchedModels.length > 0 && (
            <div className="form-group">
              <FormLabel>Available Models — click to add</FormLabel>
              <Input
                value={modelFilter}
                onChange={e => setModelFilter(e.target.value)}
                placeholder="Filter models…"
              />
              <div className="ai-model-picker">
                {visibleFetchedModels.map(m => {
                  const selected = editor.models.includes(m.id);
                  return (
                    <button
                      key={m.id}
                      type="button"
                      className={`ai-model-option${selected ? ' ai-model-option--selected' : ''}`}
                      onClick={() => (selected ? removeModel(m.id) : addModel(m.id))}
                    >
                      {selected ? <CheckCircle2 size={13} /> : <Plus size={13} />}
                      <span className="ai-model-option-id">{m.id}</span>
                      {m.name && m.name !== m.id && <span className="ai-model-option-name">{m.name}</span>}
                    </button>
                  );
                })}
                {visibleFetchedModels.length === 0 && (
                  <div className="ai-config-hint" style={{ padding: '0.5rem' }}>No models match the filter.</div>
                )}
              </div>
            </div>
          )}

          {/* Selected models + manual entry */}
          <div className="form-group">
            <FormLabel>Enabled Models</FormLabel>
            {editor.models.length > 0 ? (
              <div className="ai-model-chips">
                {editor.models.map(m => (
                  <span key={m} className="ai-model-chip">
                    {m}
                    <button type="button" onClick={() => removeModel(m)} title={`Remove ${m}`}>
                      <X size={12} />
                    </button>
                  </span>
                ))}
              </div>
            ) : (
              <p className="ai-config-hint">
                No models yet — fetch them from the endpoint above or add one manually below.
              </p>
            )}
            <div className="ai-manual-model">
              <Input
                value={manualModel}
                onChange={e => setManualModel(e.target.value)}
                placeholder="Add a model manually, e.g. gpt-4o or my-azure-deployment"
                onKeyDown={e => {
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    addModel(manualModel);
                    setManualModel('');
                  }
                }}
              />
              <Button
                variant="secondary"
                size="sm"
                onClick={() => { addModel(manualModel); setManualModel(''); }}
                disabled={!manualModel.trim()}
              >
                <Plus size={14} /> Add
              </Button>
            </div>
          </div>

          <div className="ai-config-row">
            <div className="form-group" style={{ flex: 1 }}>
              <FormLabel>Default Model</FormLabel>
              <Select
                value={editor.defaultModel}
                onChange={e => setField('defaultModel', e.target.value)}
                disabled={editor.models.length === 0}
              >
                <option value="">— none —</option>
                {editor.models.map(m => (
                  <option key={m} value={m}>{m}</option>
                ))}
              </Select>
            </div>
            <div className="form-group" style={{ flex: 1, justifyContent: 'flex-end', display: 'flex', flexDirection: 'column' }}>
              <label className="ai-inline-toggle">
                <input
                  type="checkbox"
                  checked={editor.enabled}
                  onChange={e => setField('enabled', e.target.checked)}
                />
                <span>Provider enabled</span>
              </label>
            </div>
          </div>

          {formError && (
            <div className="ai-test-result ai-test-result--error">
              <XCircle size={16} />
              <div className="ai-test-result-message">{formError}</div>
            </div>
          )}
        </div>
      </Modal>

      <ConfirmDialog
        isOpen={!!deleteTarget}
        onClose={() => setDeleteTarget(null)}
        onConfirm={handleDelete}
        title="Delete AI Provider"
        message={`Are you sure you want to delete "${deleteTarget?.name}"? This cannot be undone.`}
        confirmText="Delete"
        variant="danger"
        isLoading={deleting}
      />

      {toast && (
        <Toast
          message={toast.message}
          onDone={() => setToast(null)}
          variant={toast.type === 'success' ? 'success' : 'danger'}
        />
      )}
    </Page>
  );
}
