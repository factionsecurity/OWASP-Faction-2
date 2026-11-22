/**
 * The `?search=` a list page was opened with — how global search's "See all" hands its text to
 * the full list. Read once, as the page's starting search; the page owns it from then on.
 */
export function initialSearchFromUrl(): string {
  try {
    return new URLSearchParams(window.location.search).get('search') ?? '';
  } catch {
    return '';
  }
}
