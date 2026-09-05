import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { terminologyApi } from '../api';
import type { TerminologyConfig } from '../types';

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
  /** Re-reads after an administrator changes the wording, so the change is visible immediately. */
  refresh: () => Promise<void>;
}

const TerminologyContext = createContext<TerminologyContextValue>({
  ...DEFAULTS,
  organizationLower: 'organization',
  organizationsLower: 'organizations',
  subOrganizationLower: 'sub-organization',
  subOrganizationsLower: 'sub-organizations',
  organizationArticle: 'an',
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
