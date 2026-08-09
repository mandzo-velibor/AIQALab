package com.qalab.qalabai.service.healing;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailedLocatorExtractorTest {

    @Test
    void extractsLocatorFromCallLog() {
        String error = """
                Error: locator.click: Test timeout of 10000ms exceeded.
                Call log:
                  - waiting for getByRole('button', { name: 'Log in' })
                """;
        Optional<String> locator = FailedLocatorExtractor.extract(error, "");
        assertTrue(locator.isPresent());
        assertEquals("getByRole('button', { name: 'Log in' })", locator.get());
    }

    @Test
    void extractsLocatorFromSourceLineWithoutPagePrefix() {
        String error = "    > 12 |   await page.getByRole('button', { name: 'Log in' }).click();";
        Optional<String> locator = FailedLocatorExtractor.extract(error, null);
        assertTrue(locator.isPresent());
        assertEquals("getByRole('button', { name: 'Log in' })", locator.get());
    }

    @Test
    void extractsLocatorFromConsoleLogs() {
        String logs = "Running 1 test\n  waiting for getByLabel('Password')";
        Optional<String> locator = FailedLocatorExtractor.extract(null, logs);
        assertTrue(locator.isPresent());
        assertEquals("getByLabel('Password')", locator.get());
    }

    @Test
    void extractsCssLocator() {
        Optional<String> locator = FailedLocatorExtractor.extract("waiting for page.locator('#login').click()", "");
        assertTrue(locator.isPresent());
        assertEquals("locator('#login')", locator.get());
    }

    @Test
    void returnsEmptyWhenNoLocatorPresent() {
        Optional<String> locator = FailedLocatorExtractor.extract("Server returned 500", "all green");
        assertTrue(locator.isEmpty());
    }

    @Test
    void returnsEmptyForNullInputs() {
        assertTrue(FailedLocatorExtractor.extract(null, null).isEmpty());
    }
}
