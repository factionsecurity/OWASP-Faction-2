import { forwardRef } from 'react';
import type { SVGProps } from 'react';

interface GliderIconProps extends SVGProps<SVGSVGElement> {
  size?: number | string;
}

/**
 * The hacker emblem — Conway's Game of Life glider — drawn to match lucide's
 * conventions (24×24 viewBox, currentColor stroke, width 2, rounded joins) so it
 * drops in anywhere a lucide icon is used.
 *
 *   . X .
 *   . . X
 *   X X X
 */
const GliderIcon = forwardRef<SVGSVGElement, GliderIconProps>(
  ({ size = 24, ...props }, ref) => (
    <svg
      ref={ref}
      xmlns="http://www.w3.org/2000/svg"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      {...props}
    >
      <rect x="10" y="3" width="4" height="4" rx="1" />
      <rect x="17" y="10" width="4" height="4" rx="1" />
      <rect x="3" y="17" width="4" height="4" rx="1" />
      <rect x="10" y="17" width="4" height="4" rx="1" />
      <rect x="17" y="17" width="4" height="4" rx="1" />
    </svg>
  )
);

GliderIcon.displayName = 'GliderIcon';

export default GliderIcon;
