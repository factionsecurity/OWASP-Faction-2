import { test, expect } from '@playwright/test';
import { loginAsSuperAdmin, TEST_CONFIG } from './helpers';

/**
 * A mention inside an assessment note links to
 * `/assessments/<id>?section=notebook&node=<nodeId>`. AssessmentDetail only ever read
 * `?vuln=`, so the page opened on its default tab and the linked note was never shown —
 * the link was correct, the landing was not.
 *
 * Reads an existing note from the API rather than creating one, because creating a note
 * through the UI means creating an assessment first, and the behaviour under test is
 * purely about landing on the right tab with the right note open.
 */
test.describe('Notebook mention deep link', () => {
  test('opens the assessment on the notebook tab with the linked note selected', async ({ page }) => {
    await loginAsSuperAdmin(page);

    // Find any assessment whose application has a notebook tree.
    const target = await page.evaluate(async () => {
      const token = localStorage.getItem('token');
      const headers = { Authorization: `Bearer ${token}` };

      const list = await fetch('/api/v1/assessments?page=0&size=25', { headers }).then(r => r.json());
      for (const assessment of list?.data ?? []) {
        if (!assessment.applicationId) continue;
        const tree = await fetch(`/api/v1/applications/${assessment.applicationId}/notebook`, { headers })
          .then(r => (r.ok ? r.json() : null))
          .catch(() => null);
        const root = tree?.data?.[0];
        if (root?.id) {
          return { assessmentId: assessment.id, nodeId: root.id, title: root.title as string };
        }
      }
      return null;
    });

    test.skip(!target, 'No assessment with a notebook node available in this environment');

    await page.goto(
      `/assessments/${target!.assessmentId}?section=notebook&node=${target!.nodeId}`,
    );

    // The notebook tab must be the active one, not the default Assessment Info tab.
    await expect(page.locator('.inner-nav-item.active')).toHaveText(/Notebook/, {
      timeout: TEST_CONFIG.timeout.medium,
    });

    // …and the linked note must be the one open in the editor pane, which is what
    // "view in Faction" is supposed to deliver.
    await expect(page.locator('.nb-title-input')).toHaveValue(target!.title, {
      timeout: TEST_CONFIG.timeout.medium,
    });
  });
});
