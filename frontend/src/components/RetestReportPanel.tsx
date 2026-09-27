import { useCallback, useEffect, useState } from 'react';
import { AlertCircle, Download, FileText, Loader2, Lock } from 'lucide-react';
import type { ReportDocumentInfo, ReportDocumentType } from '../types';
import { reportsApi } from '../api';
import { Button } from './Button';
import RetestReportButton from './RetestReportButton';
import ReportPasswordField from './ReportPasswordField';
import './ReportDocumentsPanel.css';

interface Props {
  assessmentId: string;
}

const LABELS: Partial<Record<ReportDocumentType, { label: string; description: string }>> = {
  RETEST_DOCX: { label: 'Word Document', description: 'Editable retest report (.docx)' },
  RETEST_PDF: { label: 'PDF', description: 'Read-only retest report (.pdf)' },
  RETEST_ENCRYPTED_PDF: { label: 'Encrypted PDF', description: 'Password-protected retest report (.pdf)' },
};

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

/**
 * Finalize-tab counterpart to ReportDocumentsPanel for the retest report: the DOCX/PDF/encrypted
 * PDF generated from an assessment's completed retests. Available whether or not the assessment
 * itself is completed — unlike the main report, a retest report can be regenerated any time more
 * retests close out.
 */
export default function RetestReportPanel({ assessmentId }: Props) {
  const [retestDocuments, setRetestDocuments] = useState<ReportDocumentInfo[]>([]);
  const [retestTemplateAvailable, setRetestTemplateAvailable] = useState(false);
  const [reportPassword, setReportPassword] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);

  const fetchDocuments = useCallback(async () => {
    try {
      const res = await reportsApi.getDocuments(assessmentId);
      if (res.success && res.data) {
        setRetestDocuments(res.data.retestDocuments ?? []);
        setRetestTemplateAvailable(!!res.data.retestTemplateAvailable);
        setReportPassword(res.data.reportPassword ?? null);
      }
    } finally {
      setLoaded(true);
    }
  }, [assessmentId]);

  useEffect(() => {
    fetchDocuments();
  }, [fetchDocuments]);

  const handleDownload = (type: ReportDocumentType) => {
    window.open(reportsApi.getDownloadUrl(assessmentId, type), '_blank', 'noopener,noreferrer');
  };

  const renderStatus = (doc: ReportDocumentInfo) => {
    if (doc.status === 'GENERATING') {
      return (
        <span className="report-doc-status report-doc-status--generating">
          <Loader2 size={14} className="report-doc-spinner" />
          Generating…
        </span>
      );
    }
    if (doc.status === 'FAILED') {
      return (
        <span className="report-doc-status report-doc-status--failed">
          <AlertCircle size={13} />
          <span className="report-doc-status-text">{doc.errorMessage || 'Generation failed'}</span>
        </span>
      );
    }
    const generated = formatDateTime(doc.generatedAt);
    return (
      <span className="report-doc-status report-doc-status--muted">
        {generated ? `Last generated ${generated}` : 'Generated'}
      </span>
    );
  };

  // Not loaded yet: render nothing rather than a flash of an empty card.
  if (!loaded) return null;
  // Nothing to offer and nothing generated: the assessment's report template has no retest
  // template attached, and no retest report was ever produced, so the card would be dead weight.
  if (!retestTemplateAvailable && retestDocuments.length === 0) return null;

  return (
    <div className="report-docs-card">
      <div className="report-docs-header">
        <h4 className="finalize-actions-title">Retest Report</h4>
        <div className="report-docs-header-actions">
          <RetestReportButton
            assessmentId={assessmentId}
            hasRetestTemplate={retestTemplateAvailable}
            label={retestDocuments.length > 0 ? 'Regenerate' : 'Generate'}
            onGenerated={() => { fetchDocuments(); }}
            showLinks={false}
          />
        </div>
      </div>

      {retestDocuments.length > 0 && (
        <div className="report-docs-rows">
          {retestDocuments.map((doc) => (
            <div key={doc.type} className="report-doc-row">
              <div className="report-doc-icon">
                {doc.type === 'RETEST_ENCRYPTED_PDF' ? <Lock size={16} /> : <FileText size={16} />}
              </div>
              <div className="report-doc-info">
                <span className="report-doc-name">{LABELS[doc.type]?.label ?? doc.type}</span>
                {LABELS[doc.type] && <span className="report-doc-desc">{LABELS[doc.type]!.description}</span>}
              </div>
              <div className="report-doc-state">{renderStatus(doc)}</div>
              <Button
                size="sm"
                variant="secondary"
                disabled={!doc.available}
                onClick={() => handleDownload(doc.type)}
              >
                <Download size={14} />
                Download
              </Button>
            </div>
          ))}
        </div>
      )}

      {reportPassword && retestDocuments.some((d) => d.type === 'RETEST_ENCRYPTED_PDF' && d.available) && (
        <ReportPasswordField password={reportPassword} />
      )}
    </div>
  );
}
