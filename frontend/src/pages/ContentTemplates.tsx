import { useEffect, useMemo, useState } from 'react';
import { Edit2, Plus, Trash2 } from 'lucide-react';
import { contentTemplatesApi } from '../api';
import type { ContentTemplate, ContentTemplateScope } from '../types';
import DataTable, { Column, PaginationInfo } from '../components/DataTable';
import {
  ActionButtons,
  Badge,
  Button,
  Checkbox,
  ConfirmDialog,
  ErrorMessage,
  FormGroup,
  FormHint,
  FormLabel,
  IconButton,
  Input,
  Modal,
  RichTextEditor,
  Select,
} from '../components';
import Page from '../components/Page';
import './Teams.css';

const SCOPE_LABELS: Record<ContentTemplateScope, string> = {
  ASSESSMENT: 'Assessments',
  VULNERABILITY: 'Vulnerabilities',
};

interface EditorState {
  id: string | null;
  name: string;
  description: string;
  scope: ContentTemplateScope;
  content: string;
  enabled: boolean;
}

const emptyEditor = (): EditorState => ({
  id: null,
  name: '',
  description: '',
  scope: 'ASSESSMENT',
  content: '',
  enabled: true,
});

// Bodies are rich HTML — the list column shows plain text so markup never leaks into the table
function stripHtml(html: string): string {
  return html.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
}

export default function ContentTemplates() {
  const [templates, setTemplates] = useState<ContentTemplate[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(10);

  const [showModal, setShowModal] = useState(false);
  const [editor, setEditor] = useState<EditorState>(emptyEditor());
  const [formError, setFormError] = useState('');
  const [saving, setSaving] = useState(false);

  const [deleteTarget, setDeleteTarget] = useState<ContentTemplate | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState('');

  useEffect(() => {
    loadTemplates();
  }, []);

  const loadTemplates = async () => {
    try {
      setLoading(true);
      const res = await contentTemplatesApi.getAll();
      setTemplates(res.data || []);
      setError('');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load templates');
    } finally {
      setLoading(false);
    }
  };

  // The endpoint returns every template at once (they are few and admin-authored), so
  // search and paging are local.
  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return templates;
    return templates.filter(t =>
      t.name.toLowerCase().includes(q) ||
      (t.description || '').toLowerCase().includes(q) ||
      stripHtml(t.content).toLowerCase().includes(q)
    );
  }, [templates, search]);

  const pageItems = filtered.slice(page * pageSize, page * pageSize + pageSize);

  const pagination: PaginationInfo = {
    page,
    pageSize,
    total: filtered.length,
    totalPages: Math.max(1, Math.ceil(filtered.length / pageSize)),
  };

  const openCreate = () => {
    setEditor(emptyEditor());
    setFormError('');
    setShowModal(true);
  };

  const openEdit = (t: ContentTemplate) => {
    setEditor({
      id: t.id,
      name: t.name,
      description: t.description || '',
      scope: t.scope,
      content: t.content,
      enabled: t.enabled,
    });
    setFormError('');
    setShowModal(true);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editor.name.trim()) {
      setFormError('Template name is required.');
      return;
    }
    if (!stripHtml(editor.content)) {
      setFormError('Template content is required.');
      return;
    }
    setFormError('');
    setSaving(true);
    try {
      const payload = {
        name: editor.name.trim(),
        description: editor.description.trim(),
        scope: editor.scope,
        content: editor.content,
        enabled: editor.enabled,
      };
      if (editor.id) await contentTemplatesApi.update(editor.id, payload);
      else await contentTemplatesApi.create(payload);
      setShowModal(false);
      await loadTemplates();
    } catch (err: any) {
      setFormError(err.response?.data?.message || 'Failed to save template');
    } finally {
      setSaving(false);
    }
  };

  const handleConfirmedDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    setDeleteError('');
    try {
      await contentTemplatesApi.delete(deleteTarget.id);
      setDeleteTarget(null);
      await loadTemplates();
    } catch (err: any) {
      // Kept in the dialog, which stays open — a message behind it would never be read
      setDeleteError(err.response?.data?.message || 'Failed to delete template.');
    } finally {
      setDeleting(false);
    }
  };

  const columns: Column<ContentTemplate>[] = [
    {
      header: 'Title',
      accessor: 'name',
      render: t => (
        <div>
          <div className="font-medium">{t.name}</div>
          {t.description && <div className="text-sm text-muted">{t.description}</div>}
        </div>
      ),
    },
    {
      header: 'Shows On',
      render: t => <Badge variant="info">{SCOPE_LABELS[t.scope]}</Badge>,
    },
    {
      header: 'Preview',
      render: t => {
        const text = stripHtml(t.content);
        return (
          <span className="text-sm text-muted">
            {text.length > 90 ? text.slice(0, 90).trimEnd() + '…' : text}
          </span>
        );
      },
    },
    {
      header: 'Status',
      render: t => t.enabled
        ? <Badge variant="success">Enabled</Badge>
        : <Badge variant="secondary">Disabled</Badge>,
    },
    {
      header: 'Author',
      render: t => <span className="text-sm text-muted">{t.createdBy || '—'}</span>,
    },
    {
      header: 'Actions',
      width: '110px',
      render: t => (
        <ActionButtons>
          <IconButton icon={Edit2} variant="edit" title="Edit template" onClick={() => openEdit(t)} />
          <IconButton icon={Trash2} variant="delete" title="Delete template" onClick={() => { setDeleteError(''); setDeleteTarget(t); }} />
        </ActionButtons>
      ),
    },
  ];

  return (
    <Page className="teams-page">
      <div className="page-header">
        <div />
        <Button variant="primary" icon={Plus} onClick={openCreate}>
          Create Template
        </Button>
      </div>

      <p className="text-sm text-muted" style={{ marginTop: 0 }}>
        Templates appear behind the template button in rich text editors. "Assessments" templates
        show on assessment rich text fields; "Vulnerabilities" templates show on vulnerability
        editors. Whoever inserts one chooses whether it overwrites, prepends to, or appends to the
        text already there.
      </p>

      {error && !showModal && <ErrorMessage>{error}</ErrorMessage>}

      <DataTable
        columns={columns}
        data={pageItems}
        loading={loading}
        pagination={pagination}
        onPageChange={setPage}
        onPageSizeChange={size => { setPageSize(size); setPage(0); }}
        onSearchChange={value => { setSearch(value); setPage(0); }}
        searchPlaceholder="Search templates"
        emptyMessage="No templates yet. Create one to offer it in the editors."
        idAccessor="id"
      />

      <Modal
        isOpen={showModal}
        onClose={() => setShowModal(false)}
        title={editor.id ? 'Edit Template' : 'Create Template'}
        size="xl"
        closeOnOverlayClick={false}
        onSubmit={handleSubmit}
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowModal(false)}>Cancel</Button>
            <Button type="submit" variant="primary" disabled={saving}>
              {saving ? 'Saving…' : editor.id ? 'Save Changes' : 'Create'}
            </Button>
          </>
        }
      >
        {formError && <ErrorMessage>{formError}</ErrorMessage>}

        <FormGroup>
          <FormLabel required>Title</FormLabel>
          <Input
            type="text"
            value={editor.name}
            onChange={e => setEditor({ ...editor, name: e.target.value })}
            placeholder="e.g. Standard Testing Methodology"
            required
          />
        </FormGroup>

        <FormGroup>
          <FormLabel>Description</FormLabel>
          <Input
            type="text"
            value={editor.description}
            onChange={e => setEditor({ ...editor, description: e.target.value })}
            placeholder="One line shown under the title in the picker"
          />
        </FormGroup>

        <FormGroup>
          <FormLabel required>Shows On</FormLabel>
          <Select
            value={editor.scope}
            onChange={e => setEditor({ ...editor, scope: e.target.value as ContentTemplateScope })}
          >
            <option value="ASSESSMENT">Assessments</option>
            <option value="VULNERABILITY">Vulnerabilities</option>
          </Select>
          <FormHint>Which editors offer this template.</FormHint>
        </FormGroup>

        <FormGroup>
          <FormLabel required>Content</FormLabel>
          <RichTextEditor
            value={editor.content}
            onChange={html => setEditor(prev => ({ ...prev, content: html }))}
            placeholder="Write the boilerplate exactly as it should appear in the editor…"
          />
        </FormGroup>

        <FormGroup>
          <Checkbox
            checked={editor.enabled}
            onChange={e => setEditor({ ...editor, enabled: e.target.checked })}
            label="Enabled"
          />
          <FormHint>Disabled templates stay here but disappear from the editors' picker.</FormHint>
        </FormGroup>
      </Modal>

      <ConfirmDialog
        isOpen={!!deleteTarget}
        onClose={() => setDeleteTarget(null)}
        onConfirm={handleConfirmedDelete}
        title="Delete Template"
        message={deleteError
          || `Delete "${deleteTarget?.name}"? Editors stop offering it immediately. Text already inserted from it is untouched.`}
        confirmText="Delete"
        variant="danger"
        isLoading={deleting}
      />
    </Page>
  );
}
