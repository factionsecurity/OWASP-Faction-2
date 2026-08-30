import type { PeerReview } from '../types';

/** The identity fields every reviewer display reads; narrowed so list rows work too. */
type ReviewedBy = Pick<PeerReview, 'reviewerNames' | 'reviewedByName' | 'reviewedByUserId'>;

/**
 * Everyone who worked a review, in first-contribution order.
 *
 * <p>A review can be worked by several people at once, so `reviewedByUserId` — whoever claimed it
 * — is only the first of them. Reviews saved before the list existed have just the claimer, and
 * fall back to them rather than showing as having no reviewer at all.
 */
export function peerReviewerNames(review: ReviewedBy): string[] {
  if (review.reviewerNames?.length) return review.reviewerNames;
  if (review.reviewedByName) return [review.reviewedByName];
  if (review.reviewedByUserId) return [review.reviewedByUserId];
  return [];
}

/** Those names as prose: "Amy", "Amy and Ben", "Amy, Ben and Cal". */
export function peerReviewerLabel(review: ReviewedBy, fallback = 'Unknown'): string {
  const names = peerReviewerNames(review);
  if (names.length === 0) return fallback;
  if (names.length === 1) return names[0];
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
}
