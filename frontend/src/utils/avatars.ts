/**
 * Shared avatar resolution.
 *
 * Every user's default avatar is a DiceBear identicon seeded by their EMAIL
 * ADDRESS, so it is identical everywhere a user appears (top bar, profile
 * page, vulnerability and application discussions). Users who upload a profile
 * image get that instead.
 *
 * Comments store the author's username while other consumers hold the user id,
 * so the backend avatar map is keyed by both and carries each user's seed
 * (email) plus their uploaded image id. Until the map loads — or for unknown
 * authors — the passed identifier itself seeds the identicon.
 */
import { profileApi } from '../api';

export interface AvatarInfo {
  seed: string;
  profileImageId?: string | null;
}

export function dicebearUrl(seed: string): string {
  return `https://api.dicebear.com/9.x/identicon/svg?seed=${encodeURIComponent(seed)}&scale=80`;
}

export function profileImageUrl(imageId: string): string {
  return `/api/v1/profile-images/${imageId}`;
}

let avatarMap: Record<string, AvatarInfo> | null = null;
let avatarMapPromise: Promise<Record<string, AvatarInfo>> | null = null;
const subscribers = new Set<() => void>();

/** Fetch (once per session) the avatar map keyed by user id and username. */
export function loadProfileImageMap(): Promise<Record<string, AvatarInfo>> {
  if (avatarMap) return Promise.resolve(avatarMap);
  if (!avatarMapPromise) {
    avatarMapPromise = profileApi
      .avatarMap()
      .then((res) => {
        avatarMap = res.data ?? {};
        subscribers.forEach((fn) => fn());
        return avatarMap;
      })
      .catch(() => {
        avatarMapPromise = null;
        return {};
      });
  }
  return avatarMapPromise;
}

/** Re-fetch the map (after the current user uploads/removes their image). */
export function refreshProfileImageMap(): Promise<Record<string, AvatarInfo>> {
  avatarMap = null;
  avatarMapPromise = null;
  return loadProfileImageMap();
}

/** Subscribe to map loads/refreshes; returns an unsubscribe function. */
export function subscribeProfileImages(fn: () => void): () => void {
  subscribers.add(fn);
  return () => subscribers.delete(fn);
}

/**
 * Best URL for a user's avatar, by user id or username: their uploaded image
 * when they have one, else an identicon seeded by their email (from the map)
 * or, failing that, by the identifier itself.
 */
export function avatarUrl(userKey: string, fallbackSeed?: string): string {
  const info = userKey ? avatarMap?.[userKey] : null;
  if (info?.profileImageId) return profileImageUrl(info.profileImageId);
  return dicebearUrl(info?.seed || userKey || fallbackSeed || 'unknown');
}
