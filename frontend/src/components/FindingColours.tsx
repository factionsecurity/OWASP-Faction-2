import { useState } from 'react';
import { Copy, Check } from 'lucide-react';
import { useTerminology } from '../context/TerminologyContext';
import { VULNERABILITY_SEVERITIES } from '../utils/vulnSeverity';
import type { ColourPair, ReportPalette, UserDefinedField } from '../types';
import './FindingColours.css';

/**
 * The colours a report template renders its findings in, and the hexes to paint to get them.
 *
 * These used to live inside the DOCX as `${color …}` marker paragraphs the author had to hide,
 * keyed on the *displayed* severity label — so renaming a severity returned every finding to
 * black, and changing a colour meant opening Word. Severity is now keyed on the enum name, and
 * every colour is edited here.
 *
 * The legend is the other half of the feature. A reserved hex is meaningless on its own; without
 * somewhere to read it off, the mechanism is folklore that lives in the docs site.
 */

/** The reserved hexes. Mirrors ColourSentinels.java — light and dark mean the same dimension. */
const SLOT_SEVERITY = 1;
const SLOT_LIKELIHOOD = 2;
const SLOT_IMPACT = 3;

export const lightSentinel = (slot: number) =>
  `FAC7${slot.toString(16).toUpperCase().padStart(2, '0')}`;
export const darkSentinel = (slot: number) =>
  `1A07${slot.toString(16).toUpperCase().padStart(2, '0')}`;

type RatingDimension = 'severity' | 'likelihood' | 'impact';

const FALLBACK_TEXT = '000000';
const FALLBACK_FILL = 'FFFFFF';

const hash = (hex?: string) => `#${(hex || '').replace('#', '')}`;
const bare = (value: string) => value.replace('#', '').toUpperCase();

const emptyPalette = (): ReportPalette => ({
  severity: {}, likelihood: {}, impact: {}, customFields: {},
});

interface Props {
  palette?: ReportPalette;
  onChange: (palette: ReportPalette) => void;
  /** The template's fields, so a dropdown field can offer its own options as colourable values. */
  fields: UserDefinedField[];
  disabled?: boolean;
}

export default function FindingColours({ palette, onChange, fields, disabled }: Props) {
  const { severityLabel } = useTerminology();
  const [copied, setCopied] = useState<string | null>(null);

  const current = palette ?? emptyPalette();

  const copy = (hex: string) => {
    navigator.clipboard?.writeText(`#${hex}`).then(
      () => { setCopied(hex); window.setTimeout(() => setCopied(null), 1200); },
      () => { /* clipboard blocked; the hex is on screen to read off anyway */ },
    );
  };

  const separate = current.separateRatingColours ?? false;

  /** Replaces one value's colour in one dimension, leaving everything else as it was. */
  const setColour = (
    dimension: RatingDimension,
    value: string,
    part: keyof ColourPair,
    hex: string,
  ) => {
    onChange({
      ...current,
      [dimension]: {
        ...current[dimension],
        [value]: { ...(current[dimension]?.[value] ?? {}), [part]: bare(hex) },
      },
    });
  };

  /**
   * Writes the same colour into all three rating dimensions. They stay genuinely separate maps in
   * the palette — the generator resolves each independently — so keeping them in step here is what
   * makes the single combined control honest about what will be saved.
   */
  const setAllRatings = (value: string, part: keyof ColourPair, hex: string) => {
    const write = (existing: Record<string, ColourPair> | undefined) => ({
      ...existing,
      [value]: { ...(existing?.[value] ?? {}), [part]: bare(hex) },
    });
    onChange({
      ...current,
      severity: write(current.severity),
      likelihood: write(current.likelihood),
      impact: write(current.impact),
    });
  };

  /**
   * Splitting changes nothing until something is edited — the three maps are already in step.
   * Re-joining copies severity over the other two, so the combined view cannot claim a colour that
   * is not actually what a likelihood cell would render in.
   */
  const setSeparate = (next: boolean) => {
    onChange(next
      ? { ...current, separateRatingColours: true }
      : {
          ...current,
          separateRatingColours: false,
          likelihood: { ...current.severity },
          impact: { ...current.severity },
        });
  };

  const setFieldColour = (
    variableName: string,
    value: string,
    part: keyof ColourPair,
    hex: string,
  ) => {
    const existing = current.customFields?.[variableName];
    // Allocate on first use, mirroring ReportPalette.allocateSlot: the counter only goes up, so a
    // retired slot is never handed to a different field whose sentinel may still be painted.
    const nextSlot = current.nextCustomSlot ?? 4;
    const slot = existing?.slot ?? nextSlot;

    onChange({
      ...current,
      nextCustomSlot: existing?.slot ? current.nextCustomSlot : slot + 1,
      customFields: {
        ...current.customFields,
        [variableName]: {
          slot,
          values: {
            ...(existing?.values ?? {}),
            [value]: { ...(existing?.values?.[value] ?? {}), [part]: bare(hex) },
          },
        },
      },
    });
  };

  const row = (key: string, label: string, pair: ColourPair | undefined,
               onText: (hex: string) => void, onFill: (hex: string) => void) => {
    const text = pair?.text || FALLBACK_TEXT;
    const fill = pair?.fill || FALLBACK_FILL;
    return (
      <div className="fc-row" key={key}>
        <span className="fc-row-label">{label}</span>
        <label className="fc-swatch">
          <input type="color" value={hash(text)} disabled={disabled}
                 onChange={(e) => onText(e.target.value)}
                 aria-label={`${label} text on color`} />
          <span className="fc-swatch-hex">{text}</span>
        </label>
        <label className="fc-swatch">
          <input type="color" value={hash(fill)} disabled={disabled}
                 onChange={(e) => onFill(e.target.value)}
                 aria-label={`${label} color`} />
          <span className="fc-swatch-hex">{fill}</span>
        </label>
        {/* The preview is the point of showing both at once: an unreadable pair is visible here
            rather than in a delivered report. */}
        <span className="fc-preview" style={{ color: hash(text), background: hash(fill) }}>
          {label}
        </span>
      </div>
    );
  };

  const levelRows = (dimension: RatingDimension) =>
    VULNERABILITY_SEVERITIES.map((level) =>
      row(`${dimension}-${level}`, severityLabel(level), current[dimension]?.[level],
          (hex) => setColour(dimension, level, 'text', hex),
          (hex) => setColour(dimension, level, 'fill', hex)));

  /** The combined control: one row per level, writing through to all three dimensions. */
  const combinedRows = () =>
    VULNERABILITY_SEVERITIES.map((level) =>
      row(`rating-${level}`, severityLabel(level), current.severity?.[level],
          (hex) => setAllRatings(level, 'text', hex),
          (hex) => setAllRatings(level, 'fill', hex)));

  /** Only a dropdown field has a known set of values to colour; a free-text one has none. */
  const colourableFields = (fields ?? []).filter(
    (f) => f.fieldType === 'DROPDOWN' && (f.dropdownOptions?.length ?? 0) > 0);

  const legend = (label: string, slot: number) => (
    <tr key={label}>
      <td>{label}</td>
      {[lightSentinel(slot), darkSentinel(slot)].map((hex) => (
        <td key={hex}>
          <button type="button" className="fc-hex" onClick={() => copy(hex)}
                  title={`Copy #${hex}`}>
            <span className="fc-hex-chip" style={{ background: hash(hex) }} />
            <code>#{hex}</code>
            {copied === hex ? <Check size={12} /> : <Copy size={12} />}
          </button>
        </td>
      ))}
    </tr>
  );

  return (
    <div className="finding-colours">
      <div className="fc-help">
        <p>
          Paint one of the reserved colors below in your DOCX template and Faction replaces it with
          the color for that finding&rsquo;s value. They work on cell fills, font colors and table
          borders alike.
        </p>
        <p>
          Each value has two colors. <strong>Color</strong> is what the thing is &mdash; a cell
          fill, a border, or text that is not sitting on a colored background.{' '}
          <strong>Text on color</strong> is used only for text that <em>is</em> on one, so it can
          contrast with it. Text in an unfilled cell, or outside a table, takes the color itself.
        </p>
      </div>

      {separate ? (
        <>
          <div className="fc-group">
            <div className="fc-group-header">
              <span>Severity</span>
              <span className="fc-group-legend">text on color &middot; color &middot; preview</span>
            </div>
            {levelRows('severity')}
          </div>

          <div className="fc-group">
            <div className="fc-group-header"><span>Likelihood</span></div>
            {levelRows('likelihood')}
          </div>

          <div className="fc-group">
            <div className="fc-group-header"><span>Impact</span></div>
            {levelRows('impact')}
          </div>
        </>
      ) : (
        <div className="fc-group">
          <div className="fc-group-header">
            <span>Severity, Likelihood &amp; Impact</span>
            <span className="fc-group-legend">text on color &middot; color &middot; preview</span>
          </div>
          {combinedRows()}
        </div>
      )}

      <label className="fc-toggle">
        <input
          type="checkbox"
          checked={separate}
          disabled={disabled}
          onChange={(e) => setSeparate(e.target.checked)}
        />
        <span>
          Use different colors for Likelihood and Impact
          <span className="fc-toggle-hint">
            All three use the same five levels, so one set of colors covers them unless you
            need them to differ. Unticking this copies the Severity colors back over the other two.
          </span>
        </span>
      </label>

      {colourableFields.map((field) => (
        <div className="fc-group" key={field.id}>
          <div className="fc-group-header">
            <span>{field.displayName || field.variableName}</span>
            <span className="fc-group-legend">
              {current.customFields?.[field.variableName]?.slot
                ? `#${lightSentinel(current.customFields[field.variableName].slot!)}`
                  + ` / #${darkSentinel(current.customFields[field.variableName].slot!)}`
                : 'set a color to assign its hexes'}
            </span>
          </div>
          {(field.dropdownOptions ?? []).map((option) =>
            row(`${field.variableName}-${option}`, option,
                current.customFields?.[field.variableName]?.values?.[option],
                (hex) => setFieldColour(field.variableName, option, 'text', hex),
                (hex) => setFieldColour(field.variableName, option, 'fill', hex)))}
        </div>
      ))}

      <div className="fc-group">
        <div className="fc-group-header">
          <span>Colors to paint</span>
          <span className="fc-group-legend">
            both mean the same thing &mdash; paint the dark one on text, the light one on cells
          </span>
        </div>
        <table className="fc-legend">
          <thead>
            <tr>
              <th>Dimension</th>
              <th>Light</th>
              <th>Dark</th>
            </tr>
          </thead>
          <tbody>
            {legend('Severity', SLOT_SEVERITY)}
            {legend('Likelihood', SLOT_LIKELIHOOD)}
            {legend('Impact', SLOT_IMPACT)}
            {colourableFields
              .filter((f) => current.customFields?.[f.variableName]?.slot)
              .map((f) => legend(f.displayName || f.variableName,
                                 current.customFields[f.variableName].slot!))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
