import { ReactNode } from 'react';
import './Page.css';

type PageVariant = 'default' | 'narrow' | 'flush';

interface PageProps {
  children: ReactNode;
  /**
   * Layout width:
   *  - 'default' (full width, the standard for tables & content)
   *  - 'narrow'  (constrained, centered column for forms & settings)
   *  - 'flush'   (edge-to-edge; cancels content-area padding)
   */
  variant?: PageVariant;
  /** Stretch to the full height of the content area (split / scroll-managed layouts). */
  fill?: boolean;
  /** Extra class names to merge onto the page wrapper. */
  className?: string;
}

/**
 * Standard page wrapper for everything rendered inside the dashboard
 * content area. Provides a single, reusable layout contract so new
 * features get consistent full-width sizing without bespoke CSS.
 */
export default function Page({ children, variant = 'default', fill = false, className }: PageProps) {
  const classes = [
    'page',
    variant === 'narrow' && 'page--narrow',
    variant === 'flush' && 'page--flush',
    fill && 'page--fill',
    className,
  ]
    .filter(Boolean)
    .join(' ');

  return <div className={classes}>{children}</div>;
}
