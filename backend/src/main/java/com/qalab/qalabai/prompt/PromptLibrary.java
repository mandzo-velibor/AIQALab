package com.qalab.qalabai.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads prompt templates and gives each one a version.
 *
 * <p>Every call site used to open {@code prompts/<name>.md} itself — eight copies of the
 * same try/catch — and each returned {@code ""} when the file was missing. That last part
 * is the real problem: an empty system prompt does not fail, it produces a confident,
 * useless answer. The prompt is the product here, so a missing one has to stop the run.
 *
 * <p>The version is a content hash rather than a hand-maintained constant. A constant is
 * the obvious design and the wrong one: nobody forgets to read it, people forget to
 * increment it, and a prompt edited without a version bump produces output that is
 * unattributable — the exact situation versioning exists to prevent. A hash changes if and
 * only if the text changes, so it cannot drift.
 */
@Component
public class PromptLibrary {

    private static final Logger log = LoggerFactory.getLogger(PromptLibrary.class);

    /**
     * The prompts that exist. Held explicitly rather than discovered from the classpath so
     * that a typo in a call site fails immediately instead of resolving to a name nobody
     * registered, and so a test can assert no template is left unversioned.
     */
    private static final List<String> KNOWN = List.of(
            "explorer",
            "locator-agent",
            "planner-agent",
            "test-generator",
            "failure-analyst",
            "self-healing-agent",
            "healing-evaluator",
            "bug-report-generator"
    );

    private final Map<String, VersionedPrompt> cache = new ConcurrentHashMap<>();

    public static List<String> knownPromptNames() {
        return KNOWN;
    }

    /**
     * @throws IllegalArgumentException if the name is not registered, or the file is absent
     */
    public VersionedPrompt get(String name) {
        return cache.computeIfAbsent(name, this::load);
    }

    public String text(String name) {
        return get(name).text();
    }

    public String version(String name) {
        return get(name).version();
    }

    private VersionedPrompt load(String name) {
        if (!KNOWN.contains(name)) {
            throw new IllegalArgumentException(
                    "Unknown prompt '" + name + "'. Known prompts: " + KNOWN
                            + ". Register it in PromptLibrary.KNOWN so it is versioned and tested.");
        }
        ClassPathResource resource = new ClassPathResource("prompts/" + name + ".md");
        if (!resource.exists()) {
            // Deliberately fatal. The previous behaviour returned an empty prompt, which
            // costs a provider call and returns a plausible-looking wrong answer.
            throw new IllegalStateException(
                    "Prompt template missing from the classpath: prompts/" + name + ".md");
        }
        try {
            String text = resource.getContentAsString(StandardCharsets.UTF_8);
            String version = shortHash(text);
            log.info("Loaded prompt {}@{} ({} chars)", name, version, text.length());
            return new VersionedPrompt(name, version, text);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read prompts/" + name + ".md", e);
        }
    }

    /** First 12 hex characters of the SHA-256: enough to identify a revision, short enough to log. */
    static String shortHash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
