import { useCallback, useEffect, useRef, useState } from 'react';
import { X, FileText, FileType2, ClipboardList } from 'lucide-react';
import { reportsApi } from '../api';
import type { ReportDocumentInfo } from '../types';
import AssessmentChecklistsView from './AssessmentChecklistsView';
import './ReportPreviewDrawer.css';

/**
 * All the drawer needs of an assessment is its identity. `Assessment` satisfies this
 * structurally, so pages holding a full record keep passing it unchanged, and pages holding
 * only a list row — the peer review queue — pass the fields they already have.
 */
export interface ReportPreviewTarget {
  id: string;
  name: string;
  reportGeneratedAt?: string | null;
}

type Tab = 'report' | 'checklists';

interface Props {
  assessment: ReportPreviewTarget | null;
  onClose: () => void;
  onLoadingChange?: (loading: boolean) => void;
  /** Adds a Checklists tab alongside the report preview. */
  showChecklists?: boolean;
  /** Tab the drawer opens on. Ignored unless `showChecklists`. */
  initialTab?: Tab;
}

/** Save-as name for the client-side PDF fallback; mirrors the backend's sanitising. */
function pdfFileName(assessmentName: string): string {
  const safe = assessmentName.replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '');
  return `${safe || 'report'}.pdf`;
}

export default function ReportPreviewDrawer({
  assessment,
  onClose,
  onLoadingChange,
  showChecklists = false,
  initialTab = 'report',
}: Props) {
  const [pdfObjectUrl, setPdfObjectUrl] = useState('');
  const [documents, setDocuments] = useState<ReportDocumentInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [tab, setTab] = useState<Tab>(initialTab);

  const objectUrlRef = useRef('');
  /** Assessment id the preview currently holds — so toggling tabs doesn't re-convert. */
  const loadedIdRef = useRef<string | null>(null);

  const revoke = useCallback(() => {
    if (objectUrlRef.current) {
      URL.revokeObjectURL(objectUrlRef.current);
      objectUrlRef.current = '';
    }
  }, []);

  useEffect(() => revoke, [revoke]);

  // Opening the drawer on a different row starts from that row's requested tab and drops the
  // previous row's preview before anything can render against it.
  useEffect(() => {
    setTab(showChecklists ? initialTab : 'report');
    loadedIdRef.current = null;
    revoke();
    setPdfObjectUrl('');
    setDocuments([]);
    setError('');
    setLoading(false);
    onLoadingChange?.(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [assessment?.id]);

  useEffect(() => {
    // Rendering the report means a LibreOffice conversion on the server, so it waits until the
    // report tab is actually shown — a reviewer who only wants the checklists never pays for it.
    if (!assessment || tab !== 'report') return;
    if (loadedIdRef.current === assessment.id) return;
    loadedIdRef.current = assessment.id;

    let cancelled = false;
    const assessmentId = assessment.id;

    const load = async () => {
      setLoading(true);
      onLoadingChange?.(true);
      setError('');

      // The artifact list drives the download buttons and is independent of the preview
      // conversion, so a failure there must not blank out the preview (or the reverse).
      const documentsPromise = reportsApi.getDocuments(assessmentId)
        .then((res) => (res.success && res.data ? res.data.documents ?? [] : []))
        .catch(() => [] as ReportDocumentInfo[]);

      try {
        const pdfBlob = await reportsApi.getPdf(assessmentId);
        if (cancelled) return;

        revoke();
        objectUrlRef.current = URL.createObjectURL(pdfBlob);
        setPdfObjectUrl(objectUrlRef.current);
      } catch (err: any) {
        if (!cancelled) {
          setError(err?.response?.status === 404
            ? 'No report has been generated for this assessment yet.'
            : 'Failed to load report preview.');
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
          onLoadingChange?.(false);
        }
      }

      const docs = await documentsPromise;
      if (!cancelled) setDocuments(docs);
    };

    load();

    return () => {
      cancelled = true;
      onLoadingChange?.(false);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [assessment?.id, tab]);

  const isOpen = !!assessment;
  const docxAvailable = documents.some((d) => d.type === 'DOCX' && d.available);
  const storedPdfAvailable = documents.some((d) => d.type === 'PDF' && d.available);

  return (
    <>
      {isOpen && <div className="drawer-overlay" onClick={onClose} />}

      <div className={`report-drawer${isOpen ? ' open' : ''}`}>
        <div className="report-drawer-header">
          <div className="report-drawer-title">
            <span title={assessment?.name}>{assessment?.name ?? 'Report Preview'}</span>
            {assessment?.reportGeneratedAt && (
              <span className="report-drawer-generated">
                Generated {new Date(assessment.reportGeneratedAt).toLocaleString()}
              </span>
            )}
          </div>
          <div className="report-drawer-actions">
            {assessment && docxAvailable && (
              <a
                href={reportsApi.getDownloadUrl(assessment.id, 'DOCX')}
                target="_blank"
                rel="noopener noreferrer"
                className="report-drawer-download"
                title="Download the editable Word report"
              >
                <FileType2 size={15} />
                DOCX
              </a>
            )}
            {/* The stored PDF is the artifact of record; the converted preview stands in for it
                while that artifact is still generating or has failed. */}
            {assessment && (storedPdfAvailable ? (
              <a
                href={reportsApi.getDownloadUrl(assessment.id, 'PDF')}
                target="_blank"
                rel="noopener noreferrer"
                className="report-drawer-download"
                title="Download the PDF report"
              >
                <FileText size={15} />
                PDF
              </a>
            ) : pdfObjectUrl && (
              <a
                href={pdfObjectUrl}
                download={pdfFileName(assessment.name)}
                className="report-drawer-download"
                title="Download the previewed PDF"
              >
                <FileText size={15} />
                PDF
              </a>
            ))}
            <button className="drawer-close" onClick={onClose} title="Close">
              <X size={18} />
            </button>
          </div>
        </div>

        {showChecklists && (
          <div className="report-drawer-tabs">
            <button
              type="button"
              className={`report-drawer-tab${tab === 'report' ? ' report-drawer-tab--active' : ''}`}
              onClick={() => setTab('report')}
            >
              <FileText size={14} />
              Report
            </button>
            <button
              type="button"
              className={`report-drawer-tab${tab === 'checklists' ? ' report-drawer-tab--active' : ''}`}
              onClick={() => setTab('checklists')}
            >
              <ClipboardList size={14} />
              Checklists
            </button>
          </div>
        )}

        <div className="report-drawer-body">
          {tab === 'checklists' && assessment ? (
            <AssessmentChecklistsView assessmentId={assessment.id} />
          ) : (
            <>
              {loading && (
                <div className="drawer-loading">
                  <div className="spinner-border text-primary" role="status" />
                  <span>Converting to PDF…</span>
                </div>
              )}
              {!loading && error && (
                <div className="drawer-error">{error}</div>
              )}
              {!loading && pdfObjectUrl && (
                <iframe
                  src={pdfObjectUrl}
                  title="Report Preview"
                  className="report-pdf-frame"
                />
              )}
            </>
          )}
        </div>
      </div>
    </>
  );
}
