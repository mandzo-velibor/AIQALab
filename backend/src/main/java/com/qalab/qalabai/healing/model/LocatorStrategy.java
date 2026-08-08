package com.qalab.qalabai.healing.model;

/**
 * Strategy used to build a locator candidate, ordered from most to least
 * stable/fragile. Test-id based locators are preferred; XPath is a last resort.
 */
public enum LocatorStrategy {
    TEST_ID,
    ROLE,
    LABEL,
    PLACEHOLDER,
    TEXT,
    CSS,
    XPATH
}
