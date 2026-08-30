import type { ReactNode } from 'react';
import DiamondIcon from './DiamondIcon';
import { useEdition } from '../context/EditionContext';
import type { FeatureKey, QuotaKey } from '../types';
import './PaidFeature.css';

/**
 * Marks something this build does not include.
 *
 * <p>The wording is deliberately flat. This is an OWASP project, and a marker that reads
 * as an advertisement inside one is the thing to avoid — the mark exists so a feature's
 * absence is explained rather than mysterious, not to sell anything. Somebody who cannot
 * find SSO should learn it is not in this edition, not file a bug.
 *
 * <p>Rendered inline beside nav items, buttons and headings; never the whole message,
 * always a marker on something still visible.
 */
export function PaidBadge({ label = 'Not in this edition', pill = false }: { label?: string; pill?: boolean }) {
  return (
    <span className={`paid-badge${pill ? ' paid-badge--pill' : ''}`} title={label}>
      <DiamondIcon className="paid-badge__diamond" />
      {label}
    </span>
  );
}

/**
 * A link to wherever this deployment says more about editions, if it says anything.
 *
 * <p>Renders nothing when no URL is configured, which is the shipped default: the open
 * source build carries no outbound commercial link at all. An operator who wants one sets
 * {@code FACTION_UPGRADE_URL}.
 */
export function UpgradeLink({ children = 'Learn more' }: { children?: ReactNode }) {
  const { upgradeUrl } = useEdition();

  if (!upgradeUrl) return null;

  return (
    <a className="paid-lock__cta" href={upgradeUrl} target="_blank" rel="noopener noreferrer">
      {children}
    </a>
  );
}

interface PaidFeatureProps {
  feature: FeatureKey;
  /** What the feature is called, for the panel heading. */
  title: string;
  /** One sentence on what it actually does. Describe it; do not pitch it. */
  description: string;
  children: ReactNode;
}

/**
 * Renders `children` when the build includes the feature, and an explanation when it
 * does not.
 *
 * <p>Deliberately a *locked* view rather than a hidden one. Hiding the feature would
 * leave someone hunting for something that was never there; showing it greyed out with a
 * plain description tells them what it is and that this build does not have it, which is
 * all they need.
 */
export function PaidFeature({ feature, title, description, children }: PaidFeatureProps) {
  const { hasFeature } = useEdition();

  if (hasFeature(feature)) {
    return <>{children}</>;
  }

  return (
    <div className="paid-lock">
      <DiamondIcon className="paid-lock__diamond" />
      <h2 className="paid-lock__title">{title}</h2>
      <p className="paid-lock__body">{description}</p>
      <p className="paid-lock__note">Not included in this edition.</p>
      <UpgradeLink />
    </div>
  );
}

/**
 * "3 of 4 users" next to a capped create action.
 *
 * <p>Renders nothing when the quota is unlimited, so it can be dropped into a page
 * unconditionally and simply disappears where no cap applies. At the cap it states the
 * number and stops — the count is the whole message.
 */
export function QuotaNotice({ quota, noun }: { quota: QuotaKey; noun: string }) {
  const { limitOf, usageOf, atLimit } = useEdition();
  const limit = limitOf(quota);

  if (limit === null) return null;

  return (
    <span className={`quota-notice${atLimit(quota) ? ' quota-notice--at-limit' : ''}`}>
      <span className="quota-notice__count">{usageOf(quota)} of {limit}</span>
      {noun}
    </span>
  );
}
