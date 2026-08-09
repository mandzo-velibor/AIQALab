package com.qalab.qalabai.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserInstructionsTest {

    @Test
    void normalizeReturnsNullForNullBlankAndWhitespaceOnly() {
        assertNull(UserInstructions.normalize(null));
        assertNull(UserInstructions.normalize(""));
        assertNull(UserInstructions.normalize("   "));
        assertNull(UserInstructions.normalize("\n\t "));
    }

    @Test
    void normalizeTrimsAndCollapsesInnerWhitespace() {
        assertEquals("focus on negative tests", UserInstructions.normalize("  focus   on\nnegative  tests  "));
    }

    @Test
    void normalizeKeepsNormalTextUntouched() {
        assertEquals("only UI tests", UserInstructions.normalize("only UI tests"));
    }

    @Test
    void isPresentTrueOnlyForNonBlank() {
        assertFalse(UserInstructions.isPresent(null));
        assertFalse(UserInstructions.isPresent("  "));
        assertTrue(UserInstructions.isPresent("negate login"));
    }
}
