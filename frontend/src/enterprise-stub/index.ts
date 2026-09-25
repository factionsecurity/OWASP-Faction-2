/**
 * The open source edition's stand-in for the paid overlay.
 *
 * <p>Vite aliases `@enterprise` here when `FACTION_EDITION=community`, so the open source
 * build resolves the same imports without shipping a line of enterprise source. This file
 * is the whole of what the public repo contains in place of `src/enterprise/`.
 *
 * <p>These render nothing on purpose. Every call site is already wrapped in
 * `<PaidFeature>`, which shows the upgrade panel and never renders its children when the
 * feature is unavailable — so a stub that drew its own placeholder would be a second,
 * inconsistent version of that screen. Returning null keeps one upgrade experience.
 */
import type { AssessmentPrefillActionProps, AssessorTimelineProps, AvailabilityProfileCardProps } from '../types';

const Unavailable = () => null;

export const SsoConfig = Unavailable;
export const BrandingPage = Unavailable;
export const InboundEmailConfigPage = Unavailable;
export const AiTokenUsageCard = Unavailable;
export const McpServerConfigCard = Unavailable;
export const McpLogTab = Unavailable;
export const McpConnectCard = Unavailable;
/** No outside source to pre-fill the scheduling form from, so no button in its action bar. */
export const AssessmentPrefillAction: (props: AssessmentPrefillActionProps) => null = () => null;
/** Engagements' By User view; the call site shows the not-in-this-edition panel instead. */
export const AssessorTimeline: (props: AssessorTimelineProps) => null = () => null;
/** The profile's availability card and the admin page; call sites wrap them in PaidFeature or a feature check. */
export const AvailabilityProfileCard: (props: AvailabilityProfileCardProps) => null = () => null;
export const AvailabilityAdminPage = Unavailable;
