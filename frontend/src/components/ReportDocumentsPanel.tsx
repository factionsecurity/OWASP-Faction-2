import { useCallback, useEffect, useRef, useState } from 'react';
import { useEdition } from '../context/EditionContext';
import {
  AlertCircle,
  Check,
  Copy,
  Download,
  Eye,
  EyeOff,
  FileOutput,
  FileText,
  Loader2,
  Lock,
  Upload,
} from 'lucide-react';
import type { Assessment, ReportDocumentInfo, ReportDocumentType } from '../types';
import { assessmentsApi, reportsApi } from '../api';
import { Button } from './Button';
import Modal from './Modal';
import './ReportDocumentsPanel.css';

interface Props {
  assessmentId: string;
  onAssessmentUpdated?: (updated: Assessment) => void;
  /**
   * Completed assessment: the report is the issued deliverable, so generating or replacing it is
   * blocked (the server returns 409). Downloads stay available.
   */
  readOnly?: boolean;
}

const DOC_ROWS: { type: ReportDocumentType; label: string; description: string; encrypted?: boolean }[] = [
  { type: 'DOCX', label: 'Word Document', description: 'Editable report (.docx)' },
  { type: 'PDF', label: 'PDF', description: 'Read-only report (.pdf)' },
  { type: 'ENCRYPTED_PDF', label: 'Encrypted PDF', description: 'Password-protected report (.pdf)', encrypted: true },
];

const POLL_INTERVAL_MS = 3000;
const MAX_POLL_ATTEMPTS = 100; // ~5 minutes

function formatDateTime(iso?: string | null): string | null {
  if (!iso) return null;
  return new Date(iso).toLocaleString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
}

const DOCX_CONTENT_TYPE = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
const PDF_CONTENT_TYPE = 'application/pdf';

export default function ReportDocumentsPanel({ assessmentId, onAssessmentUpdated, readOnly = false }: Props) {
  const [documents, setDocuments] = useState<ReportDocumentInfo[]>([]);
  const encryptedPdfAvailable = useEdition().hasFeature('encrypted_pdf');
  const [reportPassword, setReportPassword] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [copied, setCopied] = useState(false);
  const [errorDetail, setErrorDetail] = useState<{ label: string; message: string } | null>(null);
  const pollTimer = useRef<ReturnType<typeof setInterval> | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const anyGenerating = documents.some((d) => d.status === 'GENERATING');

  const stopPolling = useCallback(() => {
    if (pollTimer.current) {
      clearInterval(pollTimer.current);
      pollTimer.current = null;
    }
  }, []);

  const fetchDocuments = useCallback(async (): Promise<ReportDocumentInfo[]> => {
    const res = await reportsApi.getDocuments(assessmentId);
    if (res.success && res.data) {
      setDocuments(res.data.documents ?? []);
      setReportPassword(res.data.reportPassword ?? null);
      return res.data.documents ?? [];
    }
    return [];
  }, [assessmentId]);

  const startPolling = useCallback(() => {
    stopPolling();
    let attempts = 0;
    pollTimer.current = setInterval(async () => {
      attempts++;
      try {
        const docs = await fetchDocuments();
        const stillGenerating = docs.some((d) => d.status === 'GENERATING');
        if (!stillGenerating) {
          stopPolling();
          // Refresh the assessment so reportGeneratedAt (timeline) updates
          const updated = await assessmentsApi.getById(assessmentId).catch(() => null);
          if (updated?.success && updated.data) {
            onAssessmentUpdated?.(updated.data);
          }
        }
      } catch {
        /* ignore poll errors */
      }
      if (attempts >= MAX_POLL_ATTEMPTS) {
        stopPolling();
        setError('Report generation timed out — refresh to check its status.');
      }
    }, POLL_INTERVAL_MS);
  }, [assessmentId, fetchDocuments, onAssessmentUpdated, stopPolling]);

  useEffect(() => {
    fetchDocuments()
      .then((docs) => {
        // Resume polling if a generation run is already in flight (e.g. page reload)
        if (docs.some((d) => d.status === 'GENERATING')) startPolling();
      })
      .catch(() => setError('Failed to load report documents'));
    return stopPolling;
  }, [fetchDocuments, startPolling, stopPolling]);

  const handleGenerate = async () => {
    setStarting(true);
    setError('');
    try {
      await reportsApi.generate(assessmentId);
      await fetchDocuments();
      startPolling();
    } catch {
      setError('Failed to start report generation');
    } finally {
      setStarting(false);
    }
  };

  const handleUploadClick = () => {
    fileInputRef.current?.click();
  };

  const handleFileSelected = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;

    if (file.type !== DOCX_CONTENT_TYPE && file.type !== PDF_CONTENT_TYPE) {
      setError('File must be a DOCX or PDF document');
      return;
    }

    setUploading(true);
    setError('');
    try {
      await reportsApi.uploadReport(assessmentId, file);
      await fetchDocuments();
      startPolling();
    } catch (err: any) {
      const message = err.response?.data?.message || err.response?.data?.error || 'Failed to upload report';
      setError(message);
    } finally {
      setUploading(false);
    }
  };

  const handleDownload = (type: ReportDocumentType) => {
    setError('');
    window.open(reportsApi.getDownloadUrl(assessmentId, type), '_blank', 'noopener,noreferrer');
  };

  const handleCopyPassword = async () => {
    if (!reportPassword) return;
    try {
      await navigator.clipboard.writeText(reportPassword);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      /* clipboard unavailable */
    }
  };

  const renderStatus = (doc?: ReportDocumentInfo) => {
    if (!doc) {
      return <span className="report-doc-status report-doc-status--muted">Not generated yet</span>;
    }
    if (doc.status === 'GENERATING') {
      return (
        <span className="report-doc-status report-doc-status--generating">
          <Loader2 size={14} className="report-doc-spinner" />
          Generating…
        </span>
      );
    }
    if (doc.status === 'FAILED') {
      const label = DOC_ROWS.find((r) => r.type === doc.type)?.label ?? doc.type;
      const message = doc.errorMessage || 'Generation failed';
      return (
        <button
          type="button"
          className="report-doc-status report-doc-status--failed"
          title="Click to see the full error"
          onClick={() => setErrorDetail({ label, message })}
        >
          <AlertCircle size={13} />
          <span className="report-doc-status-text">{message}</span>
        </button>
      );
    }
    const generated = formatDateTime(doc.generatedAt);
    return (
      <span className="report-doc-status report-doc-status--muted">
        {generated ? `Last generated ${generated}` : 'Generated'}
      </span>
    );
  };

  return (
    <div className="report-docs-card">
      <div className="report-docs-header">
        <h4 className="finalize-actions-title">Report Documents</h4>
        <div className="report-docs-header-actions">
          <input
            ref={fileInputRef}
            type="file"
            accept=".docx,.pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/pdf"
            onChange={handleFileSelected}
            hidden
          />
          <Button
            size="sm"
            variant="secondary"
            onClick={handleUploadClick}
            disabled={readOnly || uploading || starting || anyGenerating}
          >
            {uploading
              ? <Loader2 size={14} className="report-doc-spinner" />
              : <Upload size={14} />}
            {uploading ? 'Uploading…' : 'Upload Report'}
          </Button>
          <Button
            size="sm"
            variant="secondary"
            onClick={handleGenerate}
            disabled={readOnly || starting || anyGenerating || uploading}
          >
            {starting || anyGenerating
              ? <Loader2 size={14} className="report-doc-spinner" />
              : <FileOutput size={14} />}
            {starting || anyGenerating ? 'Generating…' : 'Generate Report'}
          </Button>
        </div>
      </div>

      {error && <div className="finalize-error">{error}</div>}

      <div className="report-docs-rows">
        {/* The encrypted variant is only listed where it can actually be produced —
            a permanently "not generated" row reads as a broken report run. */}
        {DOC_ROWS.filter((row) => !row.encrypted || encryptedPdfAvailable).map((row) => {
          const doc = documents.find((d) => d.type === row.type);
          return (
            <div key={row.type} className="report-doc-row">
              <div className="report-doc-icon">
                {row.encrypted ? <Lock size={16} /> : <FileText size={16} />}
              </div>
              <div className="report-doc-info">
                <span className="report-doc-name">{row.label}</span>
                <span className="report-doc-desc">{row.description}</span>
              </div>
              <div className="report-doc-state">{renderStatus(doc)}</div>
              <Button
                size="sm"
                variant="secondary"
                disabled={!doc?.available}
                onClick={() => handleDownload(row.type)}
              >
                <Download size={14} />
                Download
              </Button>
            </div>
          );
        })}
      </div>

      {encryptedPdfAvailable && reportPassword && (
        <div className="report-docs-password">
          <span className="report-docs-password-label">
            <Lock size={13} /> PDF Password
          </span>
          <code className="report-docs-password-value">
            {showPassword ? reportPassword : '•'.repeat(reportPassword.length)}
          </code>
          <button
            type="button"
            className="report-docs-password-btn"
            onClick={() => setShowPassword((v) => !v)}
            title={showPassword ? 'Hide password' : 'Show password'}
          >
            {showPassword ? <EyeOff size={14} /> : <Eye size={14} />}
          </button>
          <button
            type="button"
            className="report-docs-password-btn"
            onClick={handleCopyPassword}
            title="Copy password"
          >
            {copied ? <Check size={14} /> : <Copy size={14} />}
          </button>
        </div>
      )}

      <Modal
        isOpen={!!errorDetail}
        onClose={() => setErrorDetail(null)}
        title={`${errorDetail?.label ?? ''} — Generation Failed`}
      >
        <pre className="report-doc-error-detail">{errorDetail?.message}</pre>
      </Modal>
    </div>
  );
}
