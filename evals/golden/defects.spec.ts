import { test, expect } from '@playwright/test';
import * as path from 'path';

/**
 * The golden suite: one oracle test per planted defect.
 *
 * <p>Each test asserts the <em>correct</em> behaviour, so it passes against a fixture's
 * `correct.html` and fails against its `app.html`. That asymmetry is the whole scoring
 * mechanism: a suite that fails on the defective variant <em>and</em> passes on the
 * correct one has caught that defect, unambiguously, because there is one defect per
 * fixture.</p>
 *
 * <p>Defects are a mix of accessibility, error handling, security and validation, so
 * recall here means the generated tests cover the categories users actually complain
 * about rather than only the easy structural ones.</p>
 *
 * <p>Run by the eval harness, not by the product: these fixtures exist to measure the
 * tool, so they are not part of the workspace's own suite.</p>
 */

const FIXTURES = path.resolve(__dirname, '..', 'fixtures');

function fixture(defectId: string, variant: 'app' | 'correct') {
  return 'file://' + path.join(FIXTURES, defectId, variant + '.html');
}

test.describe('missing-label', () => {
  test('the password field has an accessible name', async ({ page }) => {
    await page.goto(fixture('01-missing-label', 'app'));
    const password = page.locator('#password');
    await expect(password).toBeVisible();
    // getByLabel finds nothing, because the control is unlabelled.
    await expect(page.getByLabel('Password')).toBeVisible();
  });
});

test.describe('wrong-role', () => {
  test('the payment method is a keyboard-operable radio', async ({ page }) => {
    await page.goto(fixture('02-wrong-role', 'app'));
    await expect(page.getByRole('radio', { name: 'Card' })).toBeVisible();
    await expect(page.getByRole('radio', { name: 'Invoice' })).toBeVisible();
  });

  test('the payment method can be chosen with the keyboard', async ({ page }) => {
    await page.goto(fixture('02-wrong-role', 'app'));
    const invoice = page.getByRole('radio', { name: 'Invoice' });
    await invoice.focus();
    await page.keyboard.press('Space');
    await expect(invoice).toBeChecked();
  });
});

test.describe('no-error-state', () => {
  test('submitting an empty login form reports a visible error', async ({ page }) => {
    await page.goto(fixture('03-no-error-state', 'app'));
    await page.getByRole('button', { name: 'Sign in' }).click();
    // A defect: nothing is shown, so this times out rather than failing fast.
    await expect(page.getByRole('alert')).toContainText(/username|password/i);
  });
});

test.describe('xss-reflection', () => {
  test('a script payload in the search box is not executed', async ({ page }) => {
    await page.goto(fixture('04-xss-reflection', 'app'));
    // If the payload runs, it sets this flag. A non-defective page never will.
    await page.evaluate(() => { (window as any).__xss = false; });
    await page.locator('#q').fill('<img src=x onerror="window.__xss = true">');
    await page.waitForTimeout(200);
    const executed = await page.evaluate(() => (window as any).__xss === true);
    expect(executed, 'the injected script executed: reflected XSS').toBe(false);
  });

  test('the search box shows the query literally rather than consuming it as markup', async ({ page }) => {
    await page.goto(fixture('04-xss-reflection', 'app'));
    await page.locator('#q').fill('<b>bold</b>');
    // The discriminator is the *text*, not the presence of a <b> element: the correct
    // page also builds a <b> (safely, via createElement). What differs is whether the
    // typed markup is preserved as characters or swallowed as tags.
    await expect(page.locator('#results')).toContainText('<b>bold</b>');
  });
});

test.describe('cross-field-validation', () => {
  test('a mismatched password confirmation is rejected', async ({ page }) => {
    await page.goto(fixture('05-cross-field-validation', 'app'));
    await page.locator('#email').fill('someone@example.com');
    await page.locator('#password').fill('correct-horse');
    await page.locator('#confirm').fill('correct-hors');
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(page.getByRole('alert')).toContainText(/do not match/i);
  });
});
