/**
 * The upgrade mark: a brilliant-cut diamond, drawn top-down.
 *
 * <p>Authored here rather than pulled from an icon set on purpose. This mark ships in an
 * Apache-2.0 repository, and the usual free icon licences require attribution and do not
 * allow sublicensing — a dependency this project should not take on for a shape it can
 * draw in twenty lines.
 *
 * <p>Inherits `currentColor`, so it picks up the accent from whatever it sits in and
 * works in both themes without a second asset. Sized in `em` so it scales with the text
 * beside it instead of needing a size prop at every call site.
 */
export default function DiamondIcon({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      width="1em"
      height="1em"
      className={className}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {/* Crown and pavilion: the table across the top, girdle at the shoulders,
          culet at the point. */}
      <path d="M5.5 3.5h13l4 6-10.5 11L1.5 9.5l4-6Z" />
      {/* Facet lines. Kept to the three that read at 14px — the full brilliant cut
          turns to mud at badge size. */}
      <path d="M1.5 9.5h21M8.5 9.5 12 20.5 15.5 9.5M8.5 9.5l-3-6M15.5 9.5l3-6" />
    </svg>
  );
}
