import { useCallback, useState } from 'react';
import Page from '../components/Page';
import VulnSummaryPanel, { type VulnSummaryFilters } from '../components/VulnSummaryPanel';
import VulnerabilitiesView from './VulnerabilitiesView';

// Standalone /vulnerabilities route: the summary donuts above the shared vulnerabilities list.
// They re-aggregate as the table's filters change — the list reports its filter set up here and
// the summary endpoint applies the same narrowing, so the numbers always describe the rows below.
// The `vulnerabilities-page` class spaces the row (see Applications.css).
export default function VulnerabilitiesPage() {
  const [filters, setFilters] = useState<VulnSummaryFilters>({});
  const handleFiltersChange = useCallback((next: VulnSummaryFilters) => setFilters(next), []);

  return (
    <Page className="vulnerabilities-page">
      <VulnSummaryPanel {...filters} />
      <VulnerabilitiesView onFiltersChange={handleFiltersChange} />
    </Page>
  );
}
