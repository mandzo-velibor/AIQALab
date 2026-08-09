package com.qalab.qalabai.locator.intelligence.model;

import java.util.Map;

/**
 * Stable, locator-relevant identity of an element captured from the live page.
 * Volatile attributes (class, generated ids) are kept out of the fingerprint
 * so the same element can be recognised across refactorings.
 *
 * @param url            page URL the element was captured from
 * @param tag            element tag, e.g. "button"
 * @param role           computed ARIA role, if any
 * @param accessibleName accessible name (aria-label / text / title)
 * @param text           trimmed text content
 * @param testId         data-testid / data-test / data-cy value
 * @param label          label text when associated with a form control
 * @param placeholder    placeholder attribute
 * @param name           name attribute (form controls)
 * @param id             id attribute
 * @param href           href attribute for links
 * @param className      class attribute (kept for reference, not fingerprinted)
 * @param attributes     full attribute map (kept for reference)
 * @param visible        whether the element was visible at capture time
 * @param enabled        whether the element was enabled at capture time
 */
public record ElementIdentity(
        String url,
        String tag,
        String role,
        String accessibleName,
        String text,
        String testId,
        String label,
        String placeholder,
        String name,
        String id,
        String href,
        String className,
        Map<String, String> attributes,
        boolean visible,
        boolean enabled
) {
}
