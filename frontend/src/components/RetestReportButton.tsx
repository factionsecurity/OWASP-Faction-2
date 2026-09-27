import { useCallback, useEffect, useRef, useState } from 'react';
import { Download, FileOutput, Loader2 } from 'lucide-react';
import { reportsApi } from '../api';
import type { ReportDocumentInfo } from '../types';
import { Button } from './Button';
import ReportPasswordField from './ReportPasswordField';
import './RetestReportButton.css';

interface Props {
  assessmentId: string;
  hasRetestTemplate: boolean;
  /** Button text; "Generate" on the ready lists, "Regenerate" where a report already exists. */
  label?: string;
  /** Called once the run finishes (successfully or not), with the retest documents it produced. */
  onGenerated?: (docs: ReportDocumentInfo[]) => void;
  /** Show inline download links once a run completes; false keeps this a Generate/Regenerate control and leaves downloads to the caller (default true). */
  showLinks?: boolean;
}

const POLL_INTERVAL_MS = 3000;
const MAX_POLL_ATTEMPTS = 100; // ~5 minutes, same as the main report panel

const LINK_LABELS: Partial<Record<ReportDocumentInfo['type'], string>> = {
  RETEST_DOCX: 'DOCX',
  RETEST_PDF: 'PDF',
  RETEST_ENCRYPTED_PDF: 'Encrypted PDF',
};

/** Generates an assessment's retest report and, once done, offers its downloads in place. */
export default function RetestReportButton({ assessmentId, hasRetestTemplate, label = 'Generate', onGenerated, showLinks = true }: Props) {
  const [running, setRunning] = useState(false);
  const [error, setError] = useState('');
  const [docs, setDocs] = useState<ReportDocumentInfo[] | null>(null);
  const [reportPassword, setReportPassword] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  const stop = useCallback(() => {
    if (timer.current) { clearInterval(timer.current); timer.current = null; }
  }, []);
  useEffect(() => stop, [stop]);

  const handleGenerate = async (e: React.MouseEvent<HTMLButtonElement>) => {
    e.stopPropagation(); // rows that hold this button are themselves clickable
    setRunning(true);
    setError('');
    setDocs(null);
    try {
      await reportsApi.generateRetest(assessmentId);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to start the retest report');
      setRunning(false);
      return;
    }
    let attempts = 0;
    timer.current = setInterval(async () => {
      attempts++;
      try {
        const res = await reportsApi.getDocuments(assessmentId);
        const retestDocs = res.data?.retestDocuments ?? [];
        if (!retestDocs.some((d) => d.status === 'GENERATING')) {
          stop();
          setRunning(false);
          if (showLinks) {
            setDocs(retestDocs);
            setReportPassword(res.data?.reportPassword ?? null);
          }
          const failed = retestDocs.find((d) => d.status === 'FAILED');
          if (failed) setError(failed.errorMessage || 'Retest report generation failed');
          onGenerated?.(retestDocs);
        }
      } catch { /* keep polling */ }
      if (attempts >= MAX_POLL_ATTEMPTS) {
        stop();
        setRunning(false);
        setError('Retest report generation timed out. Refresh to check its status.');
      }
    }, POLL_INTERVAL_MS);
  };

  const available = showLinks ? (docs ?? []).filter((d) => d.available && LINK_LABELS[d.type]) : [];

  return (
    <span className="retest-report-button" onClick={(e) => e.stopPropagation()}>
      {available.length > 0 ? (
        <>
          {available.map((d) => (
            <a key={d.type} className="retest-report-link" href={reportsApi.getDownloadUrl(assessmentId, d.type)}>
              <Download size={13} /> {LINK_LABELS[d.type]}
            </a>
          ))}
          {reportPassword && available.some((d) => d.type === 'RETEST_ENCRYPTED_PDF') && (
            <ReportPasswordField password={reportPassword} inline />
          )}
        </>
      ) : (
        <Button
          variant="secondary"
          size="sm"
          onClick={handleGenerate}
          disabled={running || !hasRetestTemplate}
          title={hasRetestTemplate ? undefined : "No retest template uploaded for this assessment's report template"}
        >
          {running
            ? <Loader2 size={14} className="retest-report-spinner" />
            : <FileOutput size={14} />}
          {running ? 'Generating…' : label}
        </Button>
      )}
      {error && <span className="retest-report-error">{error}</span>}
    </span>
  );
}
