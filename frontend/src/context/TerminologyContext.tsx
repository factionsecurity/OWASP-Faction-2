import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { terminologyApi } from '../api';
import { SEVERITY_LABELS, VULNERABILITY_SEVERITIES } from '../utils/vulnSeverity';
import type { TerminologyConfig, VulnerabilitySeverity } from '../types';

/**
 * The product's own wording, used until the installation says otherwise. Also what renders during
 * the first paint before the request lands — a screen that flashes "Organizations" and settles on
 * "Value Streams" is better than one that flashes an empty heading.
 */
const DEFAULTS: TerminologyConfig = {
  organizationSingular: 'Organization',
  organizationPlural: 'Organizations',
  subOrganizationSingular: 'Sub-organization',
  subOrganizationPlural: 'Sub-organizations',
  severityCritical: SEVERITY_LABELS.CRITICAL,
  severityHigh: SEVERITY_LABELS.HIGH,
  severityMedium: SEVERITY_LABELS.MEDIUM,
  severityLow: SEVERITY_LABELS.LOW,
  severityInformational: SEVERITY_LABELS.INFORMATIONAL,
};

/** Severity enum value -> the config key holding its label. */
const SEVERITY_KEYS: Record<VulnerabilitySeverity, keyof TerminologyConfig> = {
  CRITICAL: 'severityCritical',
  HIGH: 'severityHigh',
  MEDIUM: 'severityMedium',
  LOW: 'severityLow',
  INFORMATIONAL: 'severityInformational',
};

interface TerminologyContextValue extends TerminologyConfig {
  /** Lower-cased singular, for mid-sentence use ("no such value stream"). */
  organizationLower: string;
  subOrganizationLower: string;
  /** Lower-cased plural. */
  organizationsLower: string;
  subOrganizationsLower: string;
  /**
   * "a" or "an" for the singular. "an organization" but "a value stream" — a renamed label that
   * leaves the wrong article behind reads as a bug in the sentence around it.
   */
  organizationArticle: string;
  /**
   * This installation's word for a severity.
   *
   * <p>Tolerant of anything on purpose. It is handed API enum values ("CRITICAL"), already-title-
   * cased labels, and — via SeverityBadge, which also renders likelihood and impact — free text
   * like "3" or "Very High". Anything it does not recognise as one of the five severities comes
   * back untouched, so a rename can never eat a value it was not meant to touch.
   */
  severityLabel: (severity?: string | null) => string;
  /**
   * {value,label} pairs for severity pickers and filters, most severe first. The value is always
   * the enum, so a rename never changes what a filter sends or what a form submits — replaces the
   * static SEVERITY_OPTIONS, which cannot see the configured wording.
   */
  severityOptions: { value: VulnerabilitySeverity; label: string }[];
  /** Re-reads after an administrator changes the wording, so the change is visible immediately. */
  refresh: () => Promise<void>;
}

/** The five severities keyed by their uppercase enum name, for the lookup in `severityLabel`. */
const isSeverity = (value: string): value is VulnerabilitySeverity =>
  value in SEVERITY_KEYS;

const TerminologyContext = createContext<TerminologyContextValue>({
  ...DEFAULTS,
  organizationLower: 'organization',
  organizationsLower: 'organizations',
  subOrganizationLower: 'sub-organization',
  subOrganizationsLower: 'sub-organizations',
  organizationArticle: 'an',
  severityLabel: (severity) => String(severity ?? ''),
  severityOptions: VULNERABILITY_SEVERITIES.map((value) => ({
    value, label: SEVERITY_LABELS[value],
  })),
  refresh: async () => {},
});

export function TerminologyProvider({ children }: { children: ReactNode }) {
  const [config, setConfig] = useState<TerminologyConfig>(DEFAULTS);

  const load = async () => {
    try {
      const res = await terminologyApi.getConfig();
      if (res.data) setConfig(res.data);
    } catch {
      // Falls back to the product's own words. Wording is not worth failing a page load over.
    }
  };

  useEffect(() => { load(); }, []);

  const value = useMemo<TerminologyContextValue>(() => ({
    ...config,
    organizationLower: config.organizationSingular.toLowerCase(),
    organizationsLower: config.organizationPlural.toLowerCase(),
    subOrganizationLower: config.subOrganizationSingular.toLowerCase(),
    subOrganizationsLower: config.subOrganizationPlural.toLowerCase(),
    // Good enough for the handful of sentences that need it: the vowel test is wrong for a
    // "hour"/"university" style label, which is not a plausible name for a business unit.
    organizationArticle: /^[aeiou]/i.test(config.organizationSingular.trim()) ? 'an' : 'a',
    severityLabel: (severity) => {
      const raw = String(severity ?? '').trim();
      if (!raw) return '';
      const key = raw.toUpperCase();
      return isSeverity(key) ? config[SEVERITY_KEYS[key]] : raw;
    },
    severityOptions: VULNERABILITY_SEVERITIES.map((value) => ({
      value, label: config[SEVERITY_KEYS[value]],
    })),
    refresh: load,
  }), [config]);

  return (
    <TerminologyContext.Provider value={value}>{children}</TerminologyContext.Provider>
  );
}

/**
 * What this installation calls organizations.
 *
 * <p>Use it for anything a person reads. Field names, route paths and API payloads keep saying
 * "organization" — renaming those would turn a wording preference into a data migration.
 */
export const useTerminology = () => useContext(TerminologyContext);
