import SearchableSelect from './SearchableSelect';
import type { SelectOption } from './SearchableSelect';
import { useTerminology } from '../context/TerminologyContext';
import { VULNERABILITY_SEVERITIES } from '../utils/vulnSeverity';
import type { VulnerabilitySeverity } from '../types';

/** The five severities, uppercase, for recognising a stored value whatever case it was written in. */
const CANONICAL = new Map<string, VulnerabilitySeverity>(
  VULNERABILITY_SEVERITIES.map(s => [s, s]));

/**
 * The stored value as one of the five severities, or null if it is something else.
 *
 * <p>Case-insensitive because the same field has been written both ways: the finding screens have
 * always stored "CRITICAL", while the default-vulnerability form stored "Critical". Both mean the
 * same level and both must land on the same option, or the select renders blank and the next save
 * quietly clears a rating nobody touched.
 */
export function canonicalSeverity(value?: string | null): VulnerabilitySeverity | null {
  const raw = String(value ?? '').trim();
  if (!raw) return null;
  return CANONICAL.get(raw.toUpperCase()) ?? null;
}

interface Props {
  value?: string | null;
  onChange: (value: string) => void;
  /** Offers a blank entry, for a rating that can be left unset or cleared. */
  allowEmpty?: boolean;
  emptyLabel?: string;
  disabled?: boolean;
  /** Extra classes for the control wrapper; it always stretches to fill its field. */
  className?: string;
  id?: string;
}

/**
 * The one picker for a severity level — overall severity, likelihood, and impact alike.
 *
 * <p>These were three different controls offering three different things: the finding drawer
 * listed five raw enum names, the default-vulnerability form listed four title-cased words with no
 * Informational at all, and the retest panel listed the configured labels. The same rating
 * therefore read differently, and was stored differently, depending on which screen set it.
 *
 * <p>The option values are always the enum, so what gets stored no longer depends on the screen
 * and a rename never touches it. A stored value that is <em>not</em> one of the five — a legacy
 * "3", or free text from an import — is kept as its own option rather than dropped, so opening a
 * finding and saving it cannot silently discard a rating this control does not offer.
 *
 * <p>Renders as the shared searchable dropdown (SearchableSelect), like the rest of the app's
 * pickers: type to filter the levels, or take one at a glance — five rows need no searching, but
 * the control stays uniform wherever it appears.
 */
export default function SeverityLevelSelect({
  value,
  onChange,
  allowEmpty = false,
  emptyLabel = '—',
  disabled = false,
  className,
  id,
}: Props) {
  const { severityOptions, severityLabel } = useTerminology();

  const raw = String(value ?? '').trim();
  const canonical = canonicalSeverity(raw);
  // An unrecognised value selects an option of its own; a recognised one selects the enum, whatever
  // case it was stored in. Nothing is written back here — a rating the user never touched keeps
  // exactly the bytes it had.
  const selected = canonical ?? raw;

  const options: SelectOption[] = severityOptions.map(o => ({ value: o.value, label: o.label }));
  if (!canonical && raw) options.push({ value: raw, label: severityLabel(raw) });

  return (
    <SearchableSelect
      id={id}
      className={`ss-wrap--block${className ? ' ' + className : ''}`}
      value={selected}
      onChange={onChange}
      options={options}
      placeholder={emptyLabel}
      disabled={disabled}
      showClear={allowEmpty || !raw}
    />
  );
}
