import { useEffect, useState } from 'react';
import {
  avatarUrl,
  dicebearUrl,
  loadProfileImageMap,
  subscribeProfileImages,
} from '../utils/avatars';

interface UserAvatarProps {
  userId: string;
  name?: string;
  size?: number;
  className?: string;
}

/**
 * A user's avatar image: their uploaded profile image when they have one,
 * otherwise the DiceBear identicon used across all discussion areas.
 */
export default function UserAvatar({ userId, name, size = 38, className }: UserAvatarProps) {
  const [, bump] = useState(0);

  useEffect(() => {
    loadProfileImageMap();
    return subscribeProfileImages(() => bump((n) => n + 1));
  }, []);

  const label = name || userId;
  return (
    <img
      src={avatarUrl(userId, name)}
      alt={label}
      title={label}
      className={className}
      style={{ width: size, height: size, borderRadius: '50%', objectFit: 'cover', display: 'block' }}
      onError={(e) => {
        // Broken uploaded image → fall back to the seeded identicon
        const img = e.currentTarget;
        const fallback = dicebearUrl(userId || name || 'unknown');
        if (!img.src.startsWith('https://api.dicebear.com/')) img.src = fallback;
      }}
    />
  );
}
