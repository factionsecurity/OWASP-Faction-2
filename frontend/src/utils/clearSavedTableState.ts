/**
 * Earlier builds remembered each table's filters, search, sort and paging in localStorage, one
 * entry per user and table under this prefix. Tables now start fresh on every visit, so remove
 * whatever an earlier build left behind rather than leave it sitting in the browser.
 */
const PREFIX = 'faction.tableState.';

export function clearSavedTableState(): void {
  try {
    const keys: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key && key.startsWith(PREFIX)) keys.push(key);
    }
    keys.forEach(key => localStorage.removeItem(key));
  } catch {
    // Storage blocked: there is nothing we could have saved there either.
  }
}
