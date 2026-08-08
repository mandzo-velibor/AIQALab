package com.qalab.qalabai.intent;

import java.util.List;

/**
 * The outcome of intent detection: a resolved {@link Intent}, the ordered steps
 * needed to fulfil it, and the natural-language terms that triggered it.
 */
public record IntentResult(
        Intent intent,
        List<String> steps,
        List<String> matchedKeywords,
        String prompt
) {
}
