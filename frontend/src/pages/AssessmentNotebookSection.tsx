import { useEffect, useState, useRef, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  BookOpen,
  Plus,
  ChevronRight,
  ChevronDown,
  Trash2,
  Search,
  X,
  FileText,
  Download,
  Upload,
  File,
} from 'lucide-react';
import { notebookApi, inlineImagesApi, uploadFileContent } from '../api';
import type { NotebookNode, NotebookAttachment, NotebookSearchResult } from '../types';
import RichTextEditor from '../components/RichTextEditor';
import ConfirmDialog from '../components/ConfirmDialog';
import './AssessmentNotebookSection.css';

interface Props {
  applicationId: string;
  assessmentId: string;
}

const MAX_DEPTH = 5;

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleDateString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return iso;
  }
}

// ── Tree Node Component ────────────────────────────────────────────────────────

interface TreeNodeProps {
  node: NotebookNode;
  selectedId: string | null;
  onSelect: (node: NotebookNode) => void;
  onAddChild: (parentNode: NotebookNode) => void;
  onDelete: (node: NotebookNode) => void;
}

function TreeNodeItem({ node, selectedId, onSelect, onAddChild, onDelete }: TreeNodeProps) {
  const [expanded, setExpanded] = useState(true);
  const hasChildren = (node.children && node.children.length > 0) || node.hasChildren;
  const isSelected = selectedId === node.id;

  return (
    <div className="nb-tree-node">
      <div
        className={`nb-tree-row${isSelected ? ' nb-tree-row--selected' : ''}${node.depth === 0 ? ' nb-tree-row--root' : ''}`}
        style={{ paddingLeft: `${node.depth * 16 + 8}px` }}
      >
        <button
          className="nb-tree-expand"
          onClick={(e) => { e.stopPropagation(); setExpanded(v => !v); }}
          style={{ visibility: hasChildren ? 'visible' : 'hidden' }}
          aria-label={expanded ? 'Collapse' : 'Expand'}
        >
          {expanded ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
        </button>

        <button
          className="nb-tree-title"
          onClick={() => onSelect(node)}
          title={node.title}
        >
          {node.depth === 0 ? <BookOpen size={13} className="nb-tree-icon" /> : <FileText size={13} className="nb-tree-icon" />}
          <span className={node.depth === 0 ? 'nb-tree-title-text nb-tree-title-text--bold' : 'nb-tree-title-text'}>
            {node.title || '(Untitled)'}
          </span>
        </button>

        <div className="nb-tree-actions">
          {node.depth < MAX_DEPTH && (
            <button
              className="nb-tree-btn"
              title="Add sub-note"
              onClick={(e) => { e.stopPropagation(); onAddChild(node); }}
            >
              <Plus size={12} />
            </button>
          )}
          <button
            className="nb-tree-btn nb-tree-btn--danger"
            title="Delete note"
            onClick={(e) => { e.stopPropagation(); onDelete(node); }}
          >
            <Trash2 size={12} />
          </button>
        </div>
      </div>

      {expanded && node.children && node.children.length > 0 && (
        <div className="nb-tree-children">
          {node.children.map(child => (
            <TreeNodeItem
              key={child.id}
              node={child}
              selectedId={selectedId}
              onSelect={onSelect}
              onAddChild={onAddChild}
              onDelete={onDelete}
            />
          ))}
        </div>
      )}
    </div>
  );
}

// ── Main Component ─────────────────────────────────────────────────────────────

export default function AssessmentNotebookSection({ applicationId, assessmentId }: Props) {
  const [searchParams, setSearchParams] = useSearchParams();
  const [tree, setTree] = useState<NotebookNode[]>([]);
  const [selectedNode, setSelectedNode] = useState<NotebookNode | null>(null);
  const [editTitle, setEditTitle] = useState('');
  const [editContent, setEditContent] = useState('');
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [confirmDeleteNode, setConfirmDeleteNode] = useState<NotebookNode | null>(null);
  const [deletingNode, setDeletingNode] = useState(false);
  const [confirmDeleteFileId, setConfirmDeleteFileId] = useState<string | null>(null);
  const [deletingFile, setDeletingFile] = useState(false);
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<NotebookSearchResult[]>([]);
  const [searching, setSearching] = useState(false);

  const saveTimer = useRef<ReturnType<typeof setTimeout>>();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const searchTimer = useRef<ReturnType<typeof setTimeout>>();

  // ── Load ──────────────────────────────────────────────────────────────────

  const loadTree = useCallback(async () => {
    const res = await notebookApi.getTree(applicationId).catch(() => null);
    if (res?.success && res.data) {
      setTree(res.data);
    }
  }, [applicationId]);

  useEffect(() => {
    loadTree();
  }, [loadTree]);

  // ── Find node in tree ─────────────────────────────────────────────────────

  function findNodeById(nodes: NotebookNode[], id: string): NotebookNode | null {
    for (const node of nodes) {
      if (node.id === id) return node;
      if (node.children) {
        const found = findNodeById(node.children, id);
        if (found) return found;
      }
    }
    return null;
  }

  // ── Build breadcrumb ──────────────────────────────────────────────────────

  function buildBreadcrumb(nodes: NotebookNode[], targetId: string, path: string[] = []): string[] | null {
    for (const node of nodes) {
      const current = [...path, node.title || '(Untitled)'];
      if (node.id === targetId) return current;
      if (node.children) {
        const found = buildBreadcrumb(node.children, targetId, current);
        if (found) return found;
      }
    }
    return null;
  }

  // ── Node selection ────────────────────────────────────────────────────────

  const handleSelectNode = useCallback(async (node: NotebookNode) => {
    clearTimeout(saveTimer.current);
    // Load fresh node data
    const res = await notebookApi.getNode(node.id).catch(() => null);
    const fresh = res?.data ?? node;
    setSelectedNode(fresh);
    setEditTitle(fresh.title);
    setEditContent(fresh.content || '');
  }, []);

  // Auto-select node when ?node= param is present (e.g. from notification link)
  const autoOpenNodeDoneRef = useRef(false);
  useEffect(() => {
    if (autoOpenNodeDoneRef.current || tree.length === 0) return;
    const nodeId = searchParams.get('node');
    if (!nodeId) return;
    const node = findNodeById(tree, nodeId);
    if (!node) return;
    autoOpenNodeDoneRef.current = true;
    handleSelectNode(node);
    setSearchParams(prev => { prev.delete('node'); prev.delete('section'); return prev; }, { replace: true });
  }, [tree, searchParams, handleSelectNode, setSearchParams]);

  // ── Save title ────────────────────────────────────────────────────────────

  const saveTitleOnBlur = async () => {
    if (!selectedNode || editTitle === selectedNode.title) return;
    setSaving(true);
    try {
      const res = await notebookApi.updateNode(selectedNode.id, { title: editTitle });
      if (res.success && res.data) {
        setSelectedNode(res.data);
        await loadTree();
      }
    } finally {
      setSaving(false);
    }
  };

  // ── Save content (debounced) ──────────────────────────────────────────────

  const saveNode = useCallback(async () => {
    if (!selectedNode) return;
    setSaving(true);
    try {
      const res = await notebookApi.updateNode(selectedNode.id, {
        title: editTitle,
        content: editContent,
      });
      if (res.success && res.data) {
        setSelectedNode(res.data);
      }
    } finally {
      setSaving(false);
    }
  }, [selectedNode, editTitle, editContent]);

  const handleContentChange = (html: string) => {
    setEditContent(html);
    clearTimeout(saveTimer.current);
    saveTimer.current = setTimeout(() => saveNode(), 1500);
  };

  // ── Add nodes ─────────────────────────────────────────────────────────────

  const handleAddRootNode = async () => {
    const res = await notebookApi.createNode(applicationId, {
      title: 'New Note',
      content: '',
      parentId: undefined,
    }).catch(() => null);
    if (res?.success && res.data) {
      await loadTree();
      handleSelectNode(res.data);
    }
  };

  const handleAddChildNode = async (parentNode: NotebookNode) => {
    const res = await notebookApi.createNode(applicationId, {
      title: 'New Note',
      content: '',
      parentId: parentNode.id,
    }).catch(() => null);
    if (res?.success && res.data) {
      await loadTree();
      handleSelectNode(res.data);
    }
  };

  // ── Delete node ───────────────────────────────────────────────────────────

  const handleDeleteNodeConfirmed = async () => {
    if (!confirmDeleteNode) return;
    setDeletingNode(true);
    try {
      await notebookApi.deleteNode(confirmDeleteNode.id);
      if (selectedNode?.id === confirmDeleteNode.id) {
        setSelectedNode(null);
        setEditTitle('');
        setEditContent('');
      }
      await loadTree();
    } finally {
      setDeletingNode(false);
      setConfirmDeleteNode(null);
    }
  };

  // ── File upload ───────────────────────────────────────────────────────────

  const handleFileUpload = async (file: File) => {
    if (!selectedNode) return;
    setUploading(true);
    try {
      const prepareRes = await notebookApi.prepareFile(selectedNode.id, {
        fileName: file.name,
        contentType: file.type,
        fileSize: file.size,
      });
      if (!prepareRes.success || !prepareRes.data) throw new Error('Failed to get upload URL');
      await uploadFileContent(prepareRes.data.uploadUrl, file);
      const confirmRes = await notebookApi.confirmFile(selectedNode.id, {
        fileId: prepareRes.data.fileId,
        fileName: file.name,
        contentType: file.type,
        fileSize: file.size,
      });
      if (confirmRes.success && confirmRes.data) {
        setSelectedNode(prev =>
          prev ? { ...prev, attachments: [...prev.attachments, confirmRes.data!] } : prev
        );
      }
    } catch {
      // silently fail
    } finally {
      setUploading(false);
    }
  };

  // ── File download ─────────────────────────────────────────────────────────

  const handleFileDownload = (attachment: NotebookAttachment) => {
    if (!selectedNode) return;
    window.open(
      notebookApi.getFileDownloadUrl(selectedNode.id, attachment.id),
      '_blank', 'noopener,noreferrer');
  };

  // ── File delete ───────────────────────────────────────────────────────────

  const handleDeleteFileConfirmed = async () => {
    if (!confirmDeleteFileId || !selectedNode) return;
    setDeletingFile(true);
    try {
      await notebookApi.deleteFile(selectedNode.id, confirmDeleteFileId);
      setSelectedNode(prev =>
        prev
          ? { ...prev, attachments: prev.attachments.filter(a => a.id !== confirmDeleteFileId) }
          : prev
      );
    } finally {
      setDeletingFile(false);
      setConfirmDeleteFileId(null);
    }
  };

  // ── Search ────────────────────────────────────────────────────────────────

  const runSearch = useCallback(async (q: string) => {
    if (!q.trim()) {
      setSearchResults([]);
      return;
    }
    setSearching(true);
    try {
      const res = await notebookApi.search(applicationId, { q }).catch(() => null);
      setSearchResults(res?.data ?? []);
    } finally {
      setSearching(false);
    }
  }, [applicationId]);

  const handleSearchChange = (q: string) => {
    setSearchQuery(q);
    clearTimeout(searchTimer.current);
    searchTimer.current = setTimeout(() => runSearch(q), 400);
  };

  const handleSearchResultClick = async (result: NotebookSearchResult) => {
    setSearchOpen(false);
    setSearchQuery('');
    setSearchResults([]);
    handleSelectNode(result.node);
  };

  // ── Image upload for rich text editor ────────────────────────────────────

  const handleImageUpload = async (file: File): Promise<string> => {
    const res = await inlineImagesApi.upload(assessmentId, file);
    if (res.success && res.data) return res.data.url;
    throw new Error('Image upload failed');
  };

  // ── Breadcrumb for selected node ──────────────────────────────────────────

  const breadcrumb = selectedNode ? buildBreadcrumb(tree, selectedNode.id) ?? [selectedNode.title] : [];

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <section className="content-section nb-section">
      <div className="section-header">
        <h3>Notebook</h3>
        <div className="section-header-actions">
          {saving && <span className="nb-saving-indicator">Saving…</span>}
          <button
            className={`nb-icon-btn${searchOpen ? ' nb-icon-btn--active' : ''}`}
            title={searchOpen ? 'Close search' : 'Search notes'}
            onClick={() => { setSearchOpen(v => !v); if (searchOpen) { setSearchQuery(''); setSearchResults([]); } }}
          >
            {searchOpen ? <X size={16} /> : <Search size={16} />}
          </button>
        </div>
      </div>

      {/* Search panel */}
      {searchOpen && (
        <div className="nb-search-panel">
          <div className="nb-search-input-wrap">
            <Search size={15} className="nb-search-icon" />
            <input
              className="form-input nb-search-input"
              type="text"
              placeholder="Search notes…"
              value={searchQuery}
              onChange={e => handleSearchChange(e.target.value)}
              autoFocus
            />
            {searchQuery && (
              <button className="nb-search-clear" onClick={() => { setSearchQuery(''); setSearchResults([]); }}>
                <X size={14} />
              </button>
            )}
          </div>

          {searching && <p className="nb-search-status">Searching…</p>}

          {!searching && searchResults.length === 0 && searchQuery.trim() && (
            <p className="nb-search-status">No results found.</p>
          )}

          {searchResults.length > 0 && (
            <ul className="nb-search-results">
              {searchResults.map(result => (
                <li key={result.node.id} className="nb-search-result" onClick={() => handleSearchResultClick(result)}>
                  <div className="nb-search-result-breadcrumb">
                    {result.breadcrumb.join(' › ')}
                    {result.assessmentName && (
                      <span className="nb-search-result-assessment"> — {result.assessmentName}</span>
                    )}
                  </div>
                  <div className="nb-search-result-title">{result.node.title || '(Untitled)'}</div>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      <div className="nb-workspace">
        {/* ── Tree Sidebar ── */}
        <div className="nb-sidebar">
          <div className="nb-sidebar-header">
            <button
              className="nb-add-root-btn"
              onClick={handleAddRootNode}
              title="Add root note for this assessment"
            >
              <Plus size={13} />
              <span>New Note</span>
            </button>
          </div>

          <div className="nb-tree">
            {tree.length === 0 ? (
              <p className="nb-tree-empty">No notes yet. Click "New Note" to get started.</p>
            ) : (
              tree.map(node => (
                <TreeNodeItem
                  key={node.id}
                  node={node}
                  selectedId={selectedNode?.id ?? null}
                  onSelect={handleSelectNode}
                  onAddChild={handleAddChildNode}
                  onDelete={setConfirmDeleteNode}
                />
              ))
            )}
          </div>
        </div>

        {/* ── Editor Pane ── */}
        <div className="nb-editor-pane">
          {!selectedNode ? (
            <div className="nb-editor-empty">
              <BookOpen size={40} className="nb-editor-empty-icon" />
              <p>Select a note from the tree, or create a new one.</p>
            </div>
          ) : (
            <>
              {/* Scrollable writing area */}
              <div className="nb-editor-body">
                {/* Breadcrumb */}
                {breadcrumb.length > 1 && (
                  <div className="nb-breadcrumb">
                    {breadcrumb.map((crumb, i) => (
                      <span key={i}>
                        {i > 0 && <ChevronRight size={12} className="nb-breadcrumb-sep" />}
                        <span className={i === breadcrumb.length - 1 ? 'nb-breadcrumb-current' : 'nb-breadcrumb-part'}>
                          {crumb || '(Untitled)'}
                        </span>
                      </span>
                    ))}
                  </div>
                )}

                {/* Title row */}
                <div className="nb-title-row">
                  <input
                    className="nb-title-input"
                    type="text"
                    value={editTitle}
                    onChange={e => setEditTitle(e.target.value)}
                    onBlur={saveTitleOnBlur}
                    placeholder="Note title…"
                  />
                  {selectedNode.depth < MAX_DEPTH && (
                    <button
                      className="nb-add-subnote-btn"
                      title="Add sub-note under this note"
                      onClick={() => handleAddChildNode(selectedNode)}
                    >
                      <Plus size={14} />
                      Sub-note
                    </button>
                  )}
                </div>

                {/* Content editor */}
                <div className="nb-editor-content">
                  <RichTextEditor
                    value={editContent}
                    onChange={handleContentChange}
                    onImageUpload={handleImageUpload}
                    placeholder="Start writing…"
                    mentions
                  />
                </div>
              </div>

              {/* Metadata */}
              <div className="nb-metadata">
                <div className="nb-metadata-row">
                  <span className="nb-metadata-label">Created by</span>
                  <span className="nb-metadata-value">
                    {selectedNode.createdByName} on {formatDate(selectedNode.createdAt)}
                  </span>
                </div>
                <div className="nb-metadata-row">
                  <span className="nb-metadata-label">Last modified</span>
                  <span className="nb-metadata-value">{formatDate(selectedNode.lastModifiedAt)}</span>
                </div>
                {selectedNode.modifiedBy && selectedNode.modifiedBy.length > 0 && (
                  <div className="nb-metadata-row nb-metadata-row--modifiers">
                    <span className="nb-metadata-label">Modified by</span>
                    <span className="nb-metadata-value">
                      {[...new Set(selectedNode.modifiedBy.map(m => m.userName))].map((name, i, arr) => (
                        <span key={name} className="nb-modifier">
                          {name}
                          {i < arr.length - 1 ? ', ' : ''}
                        </span>
                      ))}
                    </span>
                  </div>
                )}
              </div>

              {/* Attachments */}
              <div className="nb-attachments">
                <div className="nb-attachments-header">
                  <span className="nb-attachments-title">Attachments</span>
                  <button
                    className="nb-icon-btn"
                    title="Upload file"
                    disabled={uploading}
                    onClick={() => fileInputRef.current?.click()}
                  >
                    <Upload size={14} />
                    {uploading ? <span className="nb-uploading-text">Uploading…</span> : <span>Upload</span>}
                  </button>
                  <input
                    ref={fileInputRef}
                    type="file"
                    style={{ display: 'none' }}
                    onChange={e => {
                      const file = e.target.files?.[0];
                      e.target.value = '';
                      if (file) handleFileUpload(file);
                    }}
                  />
                </div>

                {selectedNode.attachments.length === 0 ? (
                  <p className="nb-attachments-empty">No attachments.</p>
                ) : (
                  <ul className="nb-attachment-list">
                    {selectedNode.attachments.map(att => (
                      <li key={att.id} className="nb-attachment-item">
                        <File size={14} className="nb-attachment-icon" />
                        <div className="nb-attachment-info">
                          <span className="nb-attachment-name" title={att.fileName}>{att.fileName}</span>
                          <span className="nb-attachment-meta">
                            {formatBytes(att.fileSize)} · {att.uploadedByName} · {formatDate(att.uploadedAt)}
                          </span>
                        </div>
                        <div className="nb-attachment-actions">
                          <button
                            className="nb-icon-btn"
                            title="Download"
                            onClick={() => handleFileDownload(att)}
                          >
                            <Download size={13} />
                          </button>
                          <button
                            className="nb-icon-btn nb-icon-btn--danger"
                            title="Delete attachment"
                            onClick={() => setConfirmDeleteFileId(att.id)}
                          >
                            <Trash2 size={13} />
                          </button>
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </>
          )}
        </div>
      </div>

      {/* Delete node confirm */}
      <ConfirmDialog
        isOpen={!!confirmDeleteNode}
        onClose={() => setConfirmDeleteNode(null)}
        onConfirm={handleDeleteNodeConfirmed}
        title="Delete Note"
        message={
          (confirmDeleteNode?.hasChildren || (confirmDeleteNode?.children && confirmDeleteNode.children.length > 0))
            ? `"${confirmDeleteNode?.title || 'This note'}" has sub-notes that will also be deleted. Are you sure?`
            : `Are you sure you want to delete "${confirmDeleteNode?.title || 'this note'}"? This cannot be undone.`
        }
        confirmText="Delete"
        variant="danger"
        isLoading={deletingNode}
      />

      {/* Delete file confirm */}
      <ConfirmDialog
        isOpen={!!confirmDeleteFileId}
        onClose={() => setConfirmDeleteFileId(null)}
        onConfirm={handleDeleteFileConfirmed}
        title="Delete Attachment"
        message="Are you sure you want to delete this attachment? This cannot be undone."
        confirmText="Delete"
        variant="danger"
        isLoading={deletingFile}
      />
    </section>
  );
}
