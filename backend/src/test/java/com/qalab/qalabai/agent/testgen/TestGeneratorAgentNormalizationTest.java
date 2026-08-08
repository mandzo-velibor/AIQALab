package com.qalab.qalabai.agent.testgen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.agent.AgentResult;
import com.qalab.qalabai.model.GeneratedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that double-encoded line breaks in AI-generated code (literal "\n"
 * inside a single-line string) are decoded back to real newlines, while code
 * that already contains real line breaks is left untouched.
 */
class TestGeneratorAgentNormalizationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String normalize(String code) throws Exception {
        Method m = TestGeneratorAgent.class.getDeclaredMethod("normalizeCode", String.class);
        m.setAccessible(true);
        return (String) m.invoke(new TestGeneratorAgent(null, objectMapper), code);
    }

    @Test
    void decodesDoubleEncodedNewlines() throws Exception {
        String encoded = "import { test } from '@playwright/test';\\nconst page = await browser.newPage();\\n";
        String result = normalize(encoded);
        assertEquals("import { test } from '@playwright/test';\nconst page = await browser.newPage();\n", result);
        assertTrue(result.contains("\n"));
        assertTrue(!result.contains("\\n"));
    }

    @Test
    void leavesRealNewlinesUntouched() throws Exception {
        String code = "import { test } from '@playwright/test';\nconst x = 1;\n";
        assertEquals(code, normalize(code));
    }

    @Test
    void leavesEmptyAndNullUntouched() throws Exception {
        assertEquals("", normalize(""));
        assertEquals(null, normalize(null));
    }

    @Test
    void parseResponseProducesValidSource() throws Exception {
        String aiResponse = "```json\n"
                + "{\"tests\":[{\"scenarioName\":\"Login with valid credentials\","
                + "\"testCode\":\"import { test, expect } from '@playwright/test';\\\\nconst p = 1;\\\\n\","
                + "\"pageObjectCode\":\"export class LoginPage {}\"}]}\n"
                + "```";

        Method m = TestGeneratorAgent.class.getDeclaredMethod("parseResponse", String.class, String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<GeneratedTest> tests = (List<GeneratedTest>) m.invoke(
                new TestGeneratorAgent(null, objectMapper), aiResponse, "https://example.com");

        assertEquals(1, tests.size());
        assertEquals("import { test, expect } from '@playwright/test';\nconst p = 1;\n", tests.get(0).getTestCode());
        assertEquals("export class LoginPage {}", tests.get(0).getPageObjectCode());
        assertEquals("login-with-valid-credentials.spec.ts", tests.get(0).getTestFileName());
    }
}
