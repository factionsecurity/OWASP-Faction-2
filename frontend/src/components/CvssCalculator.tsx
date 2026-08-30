import { useEffect, useMemo, useState } from 'react';
import { CVSS31 } from '@pandatix/js-cvss';
import { CVSS40 } from '@pandatix/js-cvss';
import { Modal, Button, Badge } from '../components';
import { SEVERITY_COLORS, SEVERITY_BADGE_VARIANT } from '../utils/vulnSeverity';
import './CvssCalculator.css';

export type VulnerabilitySeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFORMATIONAL';

export interface CvssApplyResult {
  v31: { score: number; vectorString: string };
  v40: { score: number; vectorString: string };
  severity: VulnerabilitySeverity;
  activeVersion: '3.1' | '4.0';
}

export interface CvssCalculatorProps {
  isOpen: boolean;
  onClose: () => void;
  onApply: (result: CvssApplyResult) => void;
  lockedVersion?: '3.1' | '4.0';
  initialVector?: string;
  initialVersion?: '3.1' | '4.0';
}

type CvssVersion = '3.1' | '4.0';

// ── CVSS 3.1 metric state ─────────────────────────────────────────────────────
interface Cvss31Metrics {
  // Base
  AV: string; AC: string; PR: string; UI: string; S: string;
  C: string; I: string; A: string;
  // Temporal
  E: string; RL: string; RC: string;
  // Environmental – Security Requirements
  CR: string; IR: string; AR: string;
  // Environmental – Modified Base
  MAV: string; MAC: string; MPR: string; MUI: string; MS: string;
  MC: string; MI: string; MA: string;
}

const DEFAULT_31: Cvss31Metrics = {
  AV: 'N', AC: 'L', PR: 'N', UI: 'N', S: 'U', C: 'N', I: 'N', A: 'N',
  E: 'X', RL: 'X', RC: 'X',
  CR: 'X', IR: 'X', AR: 'X',
  MAV: 'X', MAC: 'X', MPR: 'X', MUI: 'X', MS: 'X', MC: 'X', MI: 'X', MA: 'X',
};

// ── CVSS 4.0 metric state ─────────────────────────────────────────────────────
interface Cvss40Metrics {
  // Base
  AV: string; AC: string; AT: string; PR: string; UI: string;
  VC: string; VI: string; VA: string; SC: string; SI: string; SA: string;
  // Threat
  E: string;
  // Environmental – Security Requirements
  CR: string; IR: string; AR: string;
  // Environmental – Modified Base
  MAV: string; MAC: string; MAT: string; MPR: string; MUI: string;
  MVC: string; MVI: string; MVA: string; MSC: string; MSI: string; MSA: string;
  // Supplemental
  S: string; AU: string; R: string; V: string; RE: string; U: string;
}

const DEFAULT_40: Cvss40Metrics = {
  AV: 'N', AC: 'L', AT: 'N', PR: 'N', UI: 'N',
  VC: 'N', VI: 'N', VA: 'N', SC: 'N', SI: 'N', SA: 'N',
  E: 'X',
  CR: 'X', IR: 'X', AR: 'X',
  MAV: 'X', MAC: 'X', MAT: 'X', MPR: 'X', MUI: 'X',
  MVC: 'X', MVI: 'X', MVA: 'X', MSC: 'X', MSI: 'X', MSA: 'X',
  S: 'X', AU: 'X', R: 'X', V: 'X', RE: 'X', U: 'X',
};

// ── Severity helpers ──────────────────────────────────────────────────────────
function ratingToSeverity(rating: string): VulnerabilitySeverity {
  return rating === 'NONE' ? 'INFORMATIONAL' : (rating as VulnerabilitySeverity);
}

// ── Vector builders (omit X / Not Defined optional metrics) ──────────────────
function buildVector31(m: Cvss31Metrics): string {
  const p: string[] = [
    'CVSS:3.1',
    `AV:${m.AV}`, `AC:${m.AC}`, `PR:${m.PR}`, `UI:${m.UI}`, `S:${m.S}`,
    `C:${m.C}`, `I:${m.I}`, `A:${m.A}`,
  ];
  const opt: [string, string][] = [
    ['E', m.E], ['RL', m.RL], ['RC', m.RC],
    ['CR', m.CR], ['IR', m.IR], ['AR', m.AR],
    ['MAV', m.MAV], ['MAC', m.MAC], ['MPR', m.MPR], ['MUI', m.MUI], ['MS', m.MS],
    ['MC', m.MC], ['MI', m.MI], ['MA', m.MA],
  ];
  for (const [k, v] of opt) if (v !== 'X') p.push(`${k}:${v}`);
  return p.join('/');
}

function buildVector40(m: Cvss40Metrics): string {
  const p: string[] = [
    'CVSS:4.0',
    `AV:${m.AV}`, `AC:${m.AC}`, `AT:${m.AT}`, `PR:${m.PR}`, `UI:${m.UI}`,
    `VC:${m.VC}`, `VI:${m.VI}`, `VA:${m.VA}`, `SC:${m.SC}`, `SI:${m.SI}`, `SA:${m.SA}`,
  ];
  const opt: [string, string][] = [
    ['E', m.E],
    ['CR', m.CR], ['IR', m.IR], ['AR', m.AR],
    ['MAV', m.MAV], ['MAC', m.MAC], ['MAT', m.MAT], ['MPR', m.MPR], ['MUI', m.MUI],
    ['MVC', m.MVC], ['MVI', m.MVI], ['MVA', m.MVA], ['MSC', m.MSC], ['MSI', m.MSI], ['MSA', m.MSA],
    ['S', m.S], ['AU', m.AU], ['R', m.R], ['V', m.V], ['RE', m.RE], ['U', m.U],
  ];
  for (const [k, v] of opt) if (v !== 'X') p.push(`${k}:${v}`);
  return p.join('/');
}

// ── MetricRow component ───────────────────────────────────────────────────────
interface MetricOption { value: string; label: string; }

function MetricRow({ abbr, label, options, value, onChange }: {
  abbr: string; label: string;
  options: MetricOption[];
  value: string;
  onChange: (v: string) => void;
}) {
  return (
    <div className="cvss-metric-row">
      <div className="cvss-metric-label">
        <span className="cvss-metric-abbr">{abbr}</span>
        <span className="cvss-metric-name">{label}</span>
      </div>
      <div className="cvss-radio-group">
        {options.map(opt => (
          <label key={opt.value} className={`cvss-radio-btn${value === opt.value ? ' selected' : ''}`}>
            <input
              type="radio"
              name={`cvss-${abbr}`}
              value={opt.value}
              checked={value === opt.value}
              onChange={() => onChange(opt.value)}
            />
            {opt.label}
          </label>
        ))}
      </div>
    </div>
  );
}

// ── CVSS 3.1 metric definitions ───────────────────────────────────────────────
const ND = { value: 'X', label: 'Not Defined' };
const avOpts  = [{ value: 'N', label: 'Network' }, { value: 'A', label: 'Adjacent' }, { value: 'L', label: 'Local' }, { value: 'P', label: 'Physical' }];
const nlhOpts = [{ value: 'N', label: 'None' }, { value: 'L', label: 'Low' }, { value: 'H', label: 'High' }];

interface SectionDef {
  title: string;
  note?: string;
  metrics: { abbr: string; label: string; key: string; options: MetricOption[] }[];
}

const SECTIONS_31: SectionDef[] = [
  {
    title: 'Base Score Metrics',
    metrics: [
      { abbr: 'AV', label: 'Attack Vector',         key: 'AV', options: avOpts },
      { abbr: 'AC', label: 'Attack Complexity',      key: 'AC', options: [{ value: 'L', label: 'Low' }, { value: 'H', label: 'High' }] },
      { abbr: 'PR', label: 'Privileges Required',    key: 'PR', options: nlhOpts },
      { abbr: 'UI', label: 'User Interaction',       key: 'UI', options: [{ value: 'N', label: 'None' }, { value: 'R', label: 'Required' }] },
      { abbr: 'S',  label: 'Scope',                  key: 'S',  options: [{ value: 'U', label: 'Unchanged' }, { value: 'C', label: 'Changed' }] },
      { abbr: 'C',  label: 'Confidentiality Impact', key: 'C',  options: nlhOpts },
      { abbr: 'I',  label: 'Integrity Impact',       key: 'I',  options: nlhOpts },
      { abbr: 'A',  label: 'Availability Impact',    key: 'A',  options: nlhOpts },
    ],
  },
  {
    title: 'Temporal Score Metrics',
    metrics: [
      { abbr: 'E',  label: 'Exploit Code Maturity', key: 'E',  options: [ND, { value: 'U', label: 'Unproven' }, { value: 'P', label: 'Proof-of-Concept' }, { value: 'F', label: 'Functional' }, { value: 'H', label: 'High' }] },
      { abbr: 'RL', label: 'Remediation Level',     key: 'RL', options: [ND, { value: 'O', label: 'Official Fix' }, { value: 'T', label: 'Temporary Fix' }, { value: 'W', label: 'Workaround' }, { value: 'U', label: 'Unavailable' }] },
      { abbr: 'RC', label: 'Report Confidence',     key: 'RC', options: [ND, { value: 'U', label: 'Unknown' }, { value: 'R', label: 'Reasonable' }, { value: 'C', label: 'Confirmed' }] },
    ],
  },
  {
    title: 'Environmental Score Metrics',
    note: 'Security Requirements',
    metrics: [
      { abbr: 'CR', label: 'Confidentiality Requirement', key: 'CR', options: [ND, { value: 'L', label: 'Low' }, { value: 'M', label: 'Medium' }, { value: 'H', label: 'High' }] },
      { abbr: 'IR', label: 'Integrity Requirement',       key: 'IR', options: [ND, { value: 'L', label: 'Low' }, { value: 'M', label: 'Medium' }, { value: 'H', label: 'High' }] },
      { abbr: 'AR', label: 'Availability Requirement',    key: 'AR', options: [ND, { value: 'L', label: 'Low' }, { value: 'M', label: 'Medium' }, { value: 'H', label: 'High' }] },
    ],
  },
  {
    title: 'Modified Base Metrics',
    metrics: [
      { abbr: 'MAV', label: 'Modified Attack Vector',         key: 'MAV', options: [ND, ...avOpts] },
      { abbr: 'MAC', label: 'Modified Attack Complexity',     key: 'MAC', options: [ND, { value: 'L', label: 'Low' }, { value: 'H', label: 'High' }] },
      { abbr: 'MPR', label: 'Modified Privileges Required',   key: 'MPR', options: [ND, ...nlhOpts] },
      { abbr: 'MUI', label: 'Modified User Interaction',      key: 'MUI', options: [ND, { value: 'N', label: 'None' }, { value: 'R', label: 'Required' }] },
      { abbr: 'MS',  label: 'Modified Scope',                 key: 'MS',  options: [ND, { value: 'U', label: 'Unchanged' }, { value: 'C', label: 'Changed' }] },
      { abbr: 'MC',  label: 'Modified Confidentiality',       key: 'MC',  options: [ND, { value: 'N', label: 'None' }, { value: 'L', label: 'Low' }, { value: 'H', label: 'High' }] },
      { abbr: 'MI',  label: 'Modified Integrity',             key: 'MI',  options: [ND, { value: 'N', label: 'None' }, { value: 'L', label: 'Low' }, { value: 'H', label: 'High' }] },
      { abbr: 'MA',  label: 'Modified Availability',          key: 'MA',  options: [ND, { value: 'N', label: 'None' }, { value: 'L', label: 'Low' }, { value: 'H', label: 'High' }] },
    ],
  },
];

// ── CVSS 4.0 metric definitions ───────────────────────────────────────────────
const av40Opts = avOpts; // same values
const nlhN = [{ value: 'N', label: 'None' }, { value: 'L', label: 'Low' }, { value: 'H', label: 'High' }];
const xnlh  = [ND, { value: 'N', label: 'None / Negligible' }, { value: 'L', label: 'Low' }, { value: 'H', label: 'High' }];

const SECTIONS_40: SectionDef[] = [
  {
    title: 'Base Score Metrics',
    metrics: [
      { abbr: 'AV', label: 'Attack Vector',                      key: 'AV', options: av40Opts },
      { abbr: 'AC', label: 'Attack Complexity',                  key: 'AC', options: [{ value: 'L', label: 'Low' }, { value: 'H', label: 'High' }] },
      { abbr: 'AT', label: 'Attack Requirements',                key: 'AT', options: [{ value: 'N', label: 'None' }, { value: 'P', label: 'Present' }] },
      { abbr: 'PR', label: 'Privileges Required',                key: 'PR', options: nlhN },
      { abbr: 'UI', label: 'User Interaction',                   key: 'UI', options: [{ value: 'N', label: 'None' }, { value: 'P', label: 'Passive' }, { value: 'A', label: 'Active' }] },
      { abbr: 'VC', label: 'Vuln. System Confidentiality',       key: 'VC', options: nlhN },
      { abbr: 'VI', label: 'Vuln. System Integrity',             key: 'VI', options: nlhN },
      { abbr: 'VA', label: 'Vuln. System Availability',          key: 'VA', options: nlhN },
      { abbr: 'SC', label: 'Subseq. System Confidentiality',     key: 'SC', options: [{ value: 'N', label: 'Negligible' }, { value: 'L', label: 'Low' }, { value: 'H', label: 'High' }] },
      { abbr: 'SI', label: 'Subseq. System Integrity',           key: 'SI', options: [{ value: 'N', label: 'Negligible' }, { value: 'L', label: 'Low' }, { value: 'H', label: 'High' }] },
      { abbr: 'SA', label: 'Subseq. System Availability',        key: 'SA', options: [{ value: 'N', label: 'Negligible' }, { value: 'L', label: 'Low' }, { value: 'H', label: 'High' }] },
    ],
  },
  {
    title: 'Threat Metrics',
    metrics: [
      { abbr: 'E', label: 'Exploit Maturity', key: 'E', options: [ND, { value: 'U', label: 'Unreported' }, { value: 'P', label: 'Proof-of-Concept' }, { value: 'A', label: 'Attacked' }] },
    ],
  },
  {
    title: 'Environmental Metrics',
    note: 'Security Requirements',
    metrics: [
      { abbr: 'CR', label: 'Confidentiality Requirement', key: 'CR', options: [ND, { value: 'L', label: 'Low' }, { value: 'M', label: 'Medium' }, { value: 'H', label: 'High' }] },
      { abbr: 'IR', label: 'Integrity Requirement',       key: 'IR', options: [ND, { value: 'L', label: 'Low' }, { value: 'M', label: 'Medium' }, { value: 'H', label: 'High' }] },
      { abbr: 'AR', label: 'Availability Requirement',    key: 'AR', options: [ND, { value: 'L', label: 'Low' }, { value: 'M', label: 'Medium' }, { value: 'H', label: 'High' }] },
    ],
  },
  {
    title: 'Modified Base Metrics',
    metrics: [
      { abbr: 'MAV', label: 'Modified Attack Vector',            key: 'MAV', options: [ND, ...av40Opts] },
      { abbr: 'MAC', label: 'Modified Attack Complexity',        key: 'MAC', options: [ND, { value: 'L', label: 'Low' }, { value: 'H', label: 'High' }] },
      { abbr: 'MAT', label: 'Modified Attack Requirements',      key: 'MAT', options: [ND, { value: 'N', label: 'None' }, { value: 'P', label: 'Present' }] },
      { abbr: 'MPR', label: 'Modified Privileges Required',      key: 'MPR', options: [ND, ...nlhN] },
      { abbr: 'MUI', label: 'Modified User Interaction',         key: 'MUI', options: [ND, { value: 'N', label: 'None' }, { value: 'P', label: 'Passive' }, { value: 'A', label: 'Active' }] },
      { abbr: 'MVC', label: 'Modified Vuln. Confidentiality',    key: 'MVC', options: xnlh },
      { abbr: 'MVI', label: 'Modified Vuln. Integrity',          key: 'MVI', options: xnlh },
      { abbr: 'MVA', label: 'Modified Vuln. Availability',       key: 'MVA', options: xnlh },
      { abbr: 'MSC', label: 'Modified Subseq. Confidentiality',  key: 'MSC', options: xnlh },
      { abbr: 'MSI', label: 'Modified Subseq. Integrity',        key: 'MSI', options: [ND, { value: 'S', label: 'Safety' }, { value: 'H', label: 'High' }, { value: 'L', label: 'Low' }, { value: 'N', label: 'Negligible' }] },
      { abbr: 'MSA', label: 'Modified Subseq. Availability',     key: 'MSA', options: [ND, { value: 'S', label: 'Safety' }, { value: 'H', label: 'High' }, { value: 'L', label: 'Low' }, { value: 'N', label: 'Negligible' }] },
    ],
  },
  {
    title: 'Supplemental Metrics',
    note: 'Do not affect the score',
    metrics: [
      { abbr: 'S',  label: 'Safety',                       key: 'S',  options: [ND, { value: 'N', label: 'Negligible' }, { value: 'P', label: 'Present' }] },
      { abbr: 'AU', label: 'Automatable',                  key: 'AU', options: [ND, { value: 'N', label: 'No' }, { value: 'Y', label: 'Yes' }] },
      { abbr: 'R',  label: 'Recovery',                     key: 'R',  options: [ND, { value: 'A', label: 'Automatic' }, { value: 'U', label: 'User' }, { value: 'I', label: 'Irrecoverable' }] },
      { abbr: 'V',  label: 'Value Density',                key: 'V',  options: [ND, { value: 'D', label: 'Diffuse' }, { value: 'C', label: 'Concentrated' }] },
      { abbr: 'RE', label: 'Vulnerability Response Effort', key: 'RE', options: [ND, { value: 'L', label: 'Low' }, { value: 'M', label: 'Moderate' }, { value: 'H', label: 'High' }] },
      { abbr: 'U',  label: 'Provider Urgency',             key: 'U',  options: [ND, { value: 'Clear', label: 'Clear' }, { value: 'Green', label: 'Green' }, { value: 'Amber', label: 'Amber' }, { value: 'Red', label: 'Red' }] },
    ],
  },
];

// ── Vector parsers ────────────────────────────────────────────────────────────
function parseVector31(vector: string): Cvss31Metrics {
  const m = { ...DEFAULT_31 };
  for (const part of vector.split('/').slice(1)) {
    const [k, v] = part.split(':');
    if (k in m) (m as any)[k] = v;
  }
  return m;
}

function parseVector40(vector: string): Cvss40Metrics {
  const m = { ...DEFAULT_40 };
  for (const part of vector.split('/').slice(1)) {
    const [k, v] = part.split(':');
    if (k in m) (m as any)[k] = v;
  }
  return m;
}

// ── Main component ────────────────────────────────────────────────────────────
export default function CvssCalculator({ isOpen, onClose, onApply, lockedVersion, initialVector, initialVersion }: CvssCalculatorProps) {
  const detectedVersion: CvssVersion = initialVector?.startsWith('CVSS:4.0') ? '4.0' : '3.1';
  const startVersion: CvssVersion = lockedVersion ?? initialVersion ?? detectedVersion;

  const [activeVersion, setActiveVersion] = useState<CvssVersion>(startVersion);
  const [metrics31, setMetrics31] = useState<Cvss31Metrics>(() =>
    initialVector?.startsWith('CVSS:3.1') ? parseVector31(initialVector) : { ...DEFAULT_31 }
  );
  const [metrics40, setMetrics40] = useState<Cvss40Metrics>(() =>
    initialVector?.startsWith('CVSS:4.0') ? parseVector40(initialVector) : { ...DEFAULT_40 }
  );

  useEffect(() => {
    if (!isOpen) return;
    const v: CvssVersion = lockedVersion ?? (initialVector?.startsWith('CVSS:4.0') ? '4.0' : '3.1');
    setActiveVersion(v);
    setMetrics31(initialVector?.startsWith('CVSS:3.1') ? parseVector31(initialVector) : { ...DEFAULT_31 });
    setMetrics40(initialVector?.startsWith('CVSS:4.0') ? parseVector40(initialVector) : { ...DEFAULT_40 });
  }, [isOpen]); // eslint-disable-line react-hooks/exhaustive-deps

  const result31 = useMemo(() => {
    const vector = buildVector31(metrics31);
    try {
      const cvss = new CVSS31(vector);
      const score = cvss.EnvironmentalScore();
      const severity = ratingToSeverity(CVSS31.Rating(score));
      return { score, vectorString: vector, severity };
    } catch {
      return { score: 0, vectorString: vector, severity: 'INFORMATIONAL' as VulnerabilitySeverity };
    }
  }, [metrics31]);

  const result40 = useMemo(() => {
    const vector = buildVector40(metrics40);
    try {
      const score = new CVSS40(vector).Score();
      const severity = ratingToSeverity(CVSS40.Rating(score));
      return { score, vectorString: vector, severity };
    } catch {
      return { score: 0, vectorString: vector, severity: 'INFORMATIONAL' as VulnerabilitySeverity };
    }
  }, [metrics40]);

  const activeResult = activeVersion === '3.1' ? result31 : result40;

  function update31(key: keyof Cvss31Metrics, value: string) {
    setMetrics31(prev => ({ ...prev, [key]: value }));
  }

  function update40(key: keyof Cvss40Metrics, value: string) {
    setMetrics40(prev => ({ ...prev, [key]: value }));
  }

  const sections = activeVersion === '3.1' ? SECTIONS_31 : SECTIONS_40;

  const footer = (
    <>
      <Button variant="secondary" onClick={onClose} type="button">Cancel</Button>
      <Button
        variant="primary"
        onClick={() => onApply({
          v31: { score: result31.score, vectorString: result31.vectorString },
          v40: { score: result40.score, vectorString: result40.vectorString },
          severity: activeResult.severity,
          activeVersion,
        })}
        type="button"
      >
        Apply
      </Button>
    </>
  );

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="CVSS Calculator"
      size="lg"
      closeOnOverlayClick={false}
      footer={footer}
    >
      {/* Version tabs — hidden when a version is locked */}
      {!lockedVersion && (
        <div className="cvss-tabs">
          <button type="button" className={`cvss-tab-btn${activeVersion === '3.1' ? ' active' : ''}`} onClick={() => setActiveVersion('3.1')}>CVSS 3.1</button>
          <button type="button" className={`cvss-tab-btn${activeVersion === '4.0' ? ' active' : ''}`} onClick={() => setActiveVersion('4.0')}>CVSS 4.0</button>
        </div>
      )}

      {/* Score display */}
      <div className="cvss-score-panel">
        <div className="cvss-score-display">
          <span className="cvss-score-value" style={{ color: SEVERITY_COLORS[activeResult.severity] }}>
            {activeResult.score.toFixed(1)}
          </span>
          <Badge variant={SEVERITY_BADGE_VARIANT[activeResult.severity]}>{activeResult.severity}</Badge>
        </div>
        <div className="cvss-vector-string">{activeResult.vectorString}</div>
      </div>

      {/* Metric sections */}
      {sections.map(section => (
        <div key={section.title} className="cvss-metric-group">
          <div className="cvss-metric-group-title">
            {section.title}
            {section.note && <span className="cvss-metric-group-note"> — {section.note}</span>}
          </div>
          {section.metrics.map(m => {
            const value = activeVersion === '3.1'
              ? metrics31[m.key as keyof Cvss31Metrics]
              : metrics40[m.key as keyof Cvss40Metrics];
            const onChange = activeVersion === '3.1'
              ? (v: string) => update31(m.key as keyof Cvss31Metrics, v)
              : (v: string) => update40(m.key as keyof Cvss40Metrics, v);
            return (
              <MetricRow
                key={m.abbr}
                abbr={m.abbr}
                label={m.label}
                options={m.options}
                value={value}
                onChange={onChange}
              />
            );
          })}
        </div>
      ))}
    </Modal>
  );
}
