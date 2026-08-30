import { createContext, useContext, useState } from 'react';
import type { ReactNode } from 'react';

export interface Breadcrumb {
  label: string;
  /** When set, the crumb renders as a link; the last crumb is usually plain text */
  to?: string;
}

interface PageTitleContextValue {
  pageTitle: string | null;
  setPageTitle: (title: string | null) => void;
  breadcrumbs: Breadcrumb[] | null;
  setBreadcrumbs: (crumbs: Breadcrumb[] | null) => void;
}

const PageTitleContext = createContext<PageTitleContextValue>({
  pageTitle: null,
  setPageTitle: () => {},
  breadcrumbs: null,
  setBreadcrumbs: () => {},
});

export function PageTitleProvider({ children }: { children: ReactNode }) {
  const [pageTitle, setPageTitle] = useState<string | null>(null);
  const [breadcrumbs, setBreadcrumbs] = useState<Breadcrumb[] | null>(null);
  return (
    <PageTitleContext.Provider value={{ pageTitle, setPageTitle, breadcrumbs, setBreadcrumbs }}>
      {children}
    </PageTitleContext.Provider>
  );
}

export function usePageTitle() {
  return useContext(PageTitleContext);
}
