import { useState } from 'react';
import { Check, Copy } from 'lucide-react';
import './CopyEmail.css';

/**
 * Copies text to the clipboard and reports whether it worked. `navigator.clipboard` only
 * exists in a secure context, and self-hosted installs are often served over plain http,
 * so fall back to a hidden textarea and `execCommand('copy')`, which browsers still honor
 * inside a click handler.
 */
export async function copyText(text: string): Promise<boolean> {
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // Permission denied; try the fallback below.
    }
  }
  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.setAttribute('readonly', '');
  textarea.style.position = 'fixed';
  textarea.style.opacity = '0';
  document.body.appendChild(textarea);
  textarea.select();
  try {
    return document.execCommand('copy');
  } catch {
    return false;
  } finally {
    document.body.removeChild(textarea);
  }
}

function useCopied() {
  const [copied, setCopied] = useState(false);
  const copy = async (text: string) => {
    if (await copyText(text)) {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    }
  };
  return { copied, copy };
}

/** An email address that copies itself when clicked. */
export function CopyableEmail({ email }: { email: string }) {
  const { copied, copy } = useCopied();
  return (
    <button
      type="button"
      className={`copyable-email${copied ? ' copied' : ''}`}
      onClick={() => copy(email)}
      title={copied ? 'Copied' : `Copy ${email}`}
    >
      <span className="copyable-email__text">{email}</span>
      {copied ? <Check size={12} /> : <Copy size={12} />}
    </button>
  );
}

/**
 * Copies every address at once, comma-separated, which is what a mail client's To: field
 * takes. Renders nothing when there are no addresses.
 */
export function CopyAllEmailsButton({ emails }: { emails: (string | undefined)[] }) {
  const { copied, copy } = useCopied();
  const addresses = emails.filter((e): e is string => !!e).join(', ');
  if (!addresses) return null;
  return (
    <button
      type="button"
      className={`copy-all-emails${copied ? ' copied' : ''}`}
      onClick={() => copy(addresses)}
      title="Copy every stakeholder email"
    >
      {copied ? <Check size={12} /> : <Copy size={12} />}
      <span>{copied ? 'Copied' : 'Copy all'}</span>
    </button>
  );
}
