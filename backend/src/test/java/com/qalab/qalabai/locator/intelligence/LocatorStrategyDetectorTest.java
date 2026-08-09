package com.qalab.qalabai.locator.intelligence;

import com.qalab.qalabai.healing.model.LocatorStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocatorStrategyDetectorTest {

    private final LocatorStrategyDetector detector = new LocatorStrategyDetector();

    @Test
    void detectsTestId() {
        assertEquals(LocatorStrategy.TEST_ID,
                detector.detect("getByTestId('login-button')"));
        assertEquals(LocatorStrategy.TEST_ID,
                detector.detect("page.locator('[data-testid=\"login\"]')"));
    }

    @Test
    void detectsRoleAndName() {
        assertEquals(LocatorStrategy.ROLE,
                detector.detect("getByRole('button', { name: 'Log in' })"));
        assertEquals(LocatorStrategy.LABEL,
                detector.detect("getByLabel('Email address')"));
        assertEquals(LocatorStrategy.PLACEHOLDER,
                detector.detect("getByPlaceholder('Enter email')"));
        assertEquals(LocatorStrategy.TEXT,
                detector.detect("getByText('Create account')"));
    }

    @Test
    void detectsNameIdCssXpath() {
        assertEquals(LocatorStrategy.NAME, detector.detect("locator('[name=\"email\"]')"));
        assertEquals(LocatorStrategy.ID, detector.detect("locator('#submit-btn')"));
        assertEquals(LocatorStrategy.CSS, detector.detect("locator('form .btn-primary')"));
        assertEquals(LocatorStrategy.XPATH, detector.detect("xpath=//button[@id='login']"));
        assertEquals(LocatorStrategy.XPATH, detector.detect("//div[1]/button[2]"));
    }

    @Test
    void blankLocatorFallsBackToCss() {
        assertEquals(LocatorStrategy.CSS, detector.detect(""));
        assertEquals(LocatorStrategy.CSS, detector.detect(null));
    }
}
