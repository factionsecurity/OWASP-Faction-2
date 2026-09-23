import { useState } from 'react';
import { Download } from 'lucide-react';
import { assessmentsApi } from '../api';
import type { AssessmentImportPreview, AssessmentImportResult } from '../types';
import { Modal, Button, Badge, Checkbox, FormGroup, FormLabel } from '.';
import './AssessmentImportModal.css';

interface AssessmentImportModalProps {
  isOpen: boolean;
  onClose: () => void;
  /** Called after a successful import so the page behind can reload. */
  onImported: () => void;
}

type Step = 'choose' | 'preview' | 'done';

export default function AssessmentImportModal({ isOpen, onClose, onImported }: AssessmentImportModalProps) {
  const [step, setStep] = useState<Step>('choose');
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<AssessmentImportPreview | null>(null);
  const [result, setResult] = useState<AssessmentImportResult | null>(null);
  const [notify, setNotify] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const reset = () => {
    setStep('choose');
    setFile(null);
    setPreview(null);
    setResult(null);
    setNotify(false);
    setError('');
  };

  const close = () => {
    // Closing mid-request would leave the import running with nobody to see how it ended.
    if (busy) return;
    reset();
    onClose();
  };

  const handleDownloadTemplate = async () => {
    try {
      const blob = await assessmentsApi.importTemplate();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'assessment-import-template.csv';
      document.body.appendChild(a);
      a.click();
      window.URL.revokeObjectURL(url);
      document.body.removeChild(a);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to download the template');
    }
  };

  const handlePreview = async () => {
    if (!file) return;
    try {
      setBusy(true);
      setError('');
      const response = await assessmentsApi.previewImport(file);
      setPreview(response.data ?? null);
      setStep('preview');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to read the file');
    } finally {
      setBusy(false);
    }
  };

  const handleImport = async () => {
    if (!file) return;
    try {
      setBusy(true);
      setError('');
      const response = await assessmentsApi.importCsv(file, notify);
      setResult(response.data ?? null);
      setStep('done');
      onImported();
    } catch (err: any) {
      const data = err.response?.data;
      if (data?.data?.rows) {
        // Something changed since the preview; show the fresh one.
        setPreview(data.data as AssessmentImportPreview);
        setStep('preview');
      }
      setError(data?.message || 'Failed to import assessments');
    } finally {
      setBusy(false);
    }
  };

  const footer = (
    <>
      {step === 'preview' && (
        <Button
          variant="secondary"
          onClick={() => {
            // The file input comes back empty, so drop the file too rather than preview it unseen.
            setFile(null);
            setStep('choose');
            setError('');
          }}
          disabled={busy}
        >
          Back
        </Button>
      )}
      <Button variant="secondary" onClick={close} disabled={busy}>{step === 'done' ? 'Close' : 'Cancel'}</Button>
      {step === 'choose' && (
        <Button variant="primary" onClick={handlePreview} disabled={!file || busy}>
          {busy ? 'Reading…' : 'Preview'}
        </Button>
      )}
      {step === 'preview' && preview && (
        <Button variant="primary" onClick={handleImport} disabled={!preview.valid || busy}>
          {busy ? 'Creating…' : `Create ${preview.total} assessment${preview.total === 1 ? '' : 's'}`}
        </Button>
      )}
    </>
  );

  return (
    <Modal
      isOpen={isOpen}
      onClose={close}
      closeOnOverlayClick={!busy}
      title="Import Assessments from CSV"
      size="xl"
      footer={footer}
    >
      <div className="asmt-import">
        {step === 'choose' && (
          <>
            <p className="asmt-import-lede">
              One assessment per row. Columns are matched by header name, and every lookup ignores
              case. <strong>name</strong>, <strong>assessmentType</strong>, <strong>startDate</strong>,
              an <strong>appId</strong> or <strong>applicationName</strong>, and an{' '}
              <strong>endDate</strong> or <strong>durationDays</strong> are required. Dates
              are <code>YYYY-MM-DD</code>.
            </p>
            <p className="asmt-import-lede">
              People are usernames or emails; list several assessors separated by <code>;</code>.
              Applications that don't exist yet are created, and so are campaigns if you can create
              campaigns. Assessors must be internal users. Any extra column is an
              assessment custom field, named by its variable name. Nothing is created until you
              review the preview.
            </p>
            <Button variant="secondary" icon={Download} onClick={handleDownloadTemplate}>
              Download Template
            </Button>
            <FormGroup>
              <FormLabel>CSV file</FormLabel>
              <input
                type="file"
                accept=".csv,text/csv"
                className="asmt-import-file"
                onChange={(e) => {
                  setFile(e.target.files?.[0] ?? null);
                  setError('');
                }}
              />
            </FormGroup>
          </>
        )}

        {error && <div className="error-message">{error}</div>}

        {step === 'preview' && preview && (
          <>
            <div className="asmt-import-counts">
              <Badge variant="info">{preview.total} {preview.total === 1 ? 'row' : 'rows'}</Badge>
              <Badge variant="success">{preview.validCount} valid</Badge>
              <Badge variant={preview.errorCount ? 'danger' : 'info'}>{preview.errorCount} with errors</Badge>
              {preview.newApplicationCount > 0 && (
                <Badge variant="warning">{preview.newApplicationCount} new {preview.newApplicationCount === 1 ? 'application' : 'applications'}</Badge>
              )}
              {preview.newCampaignCount > 0 && (
                <Badge variant="warning">{preview.newCampaignCount} new {preview.newCampaignCount === 1 ? 'campaign' : 'campaigns'}</Badge>
              )}
            </div>

            <div className="asmt-import-table-wrap">
              <table className="asmt-import-table">
                <thead>
                  <tr>
                    <th>Line</th><th>Name</th><th>Application</th><th>Type</th>
                    <th>Dates</th><th>Assessors</th><th>Campaign</th><th>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {preview.rows.map((row) => (
                    <tr key={row.line} className={row.errors.length ? 'asmt-import-row-error' : ''}>
                      <td>{row.line}</td>
                      <td>{row.name || '—'}</td>
                      <td>
                        {row.application || '—'}
                        {row.newApplication && <Badge variant="warning">New</Badge>}
                      </td>
                      <td>{row.assessmentType || '—'}</td>
                      <td className="asmt-import-nowrap">
                        {row.startDate || '?'} – {row.endDate || '?'}
                      </td>
                      <td>{row.assessors.join(', ') || '—'}</td>
                      <td>
                        {row.campaign || '—'}
                        {row.newCampaign && <Badge variant="warning">New</Badge>}
                      </td>
                      <td>
                        {row.errors.length === 0
                          ? <span className="text-muted">Ready</span>
                          : <ul className="asmt-import-errors">{row.errors.map((e, i) => <li key={i}>{e}</li>)}</ul>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {preview.valid ? (
              <Checkbox
                label="Notify stakeholders — send the assessment-created notifications and emails to assessors, managers and stakeholders"
                checked={notify}
                onChange={(e) => setNotify(e.target.checked)}
              />
            ) : (
              <p className="asmt-import-lede">Fix the errors in your file and preview again.</p>
            )}
          </>
        )}

        {step === 'done' && result && (
          <div className="asmt-import-result">
            <p><strong>Created {result.created} assessment{result.created === 1 ? '' : 's'}.</strong></p>
            {result.createdApplications.length > 0 && (
              <p className="text-sm">New applications: {result.createdApplications.join(', ')}</p>
            )}
            {result.createdCampaigns.length > 0 && (
              <p className="text-sm">New campaigns: {result.createdCampaigns.join(', ')}</p>
            )}
          </div>
        )}
      </div>
    </Modal>
  );
}
