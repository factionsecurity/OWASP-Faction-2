import { useState } from 'react';
import { Check, Copy, Eye, EyeOff, Lock } from 'lucide-react';
import './ReportDocumentsPanel.css';

interface Props {
  password: string;
  /** Sits inline beside download links instead of as its own row under a document list. */
  inline?: boolean;
}

/**
 * The password that opens an assessment's encrypted PDFs — the main report's and the retest
 * report's share it. Masked until revealed, with a copy button, wherever an encrypted PDF is
 * offered for download.
 */
export default function ReportPasswordField({ password, inline = false }: Props) {
  const [showPassword, setShowPassword] = useState(false);
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(password);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      /* clipboard unavailable */
    }
  };

  return (
    <div className={`report-docs-password${inline ? ' report-docs-password--inline' : ''}`}>
      <span className="report-docs-password-label">
        <Lock size={13} /> PDF Password
      </span>
      <code className="report-docs-password-value">
        {showPassword ? password : '•'.repeat(password.length)}
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
        onClick={handleCopy}
        title="Copy password"
      >
        {copied ? <Check size={14} /> : <Copy size={14} />}
      </button>
    </div>
  );
}
