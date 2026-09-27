package com.qalab.qalabai.prompt;

import com.qalab.qalabai.prompt.PromptLibrary;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The prompt is the product, and before B-035 it had no version, no fixture and no
 * offline check. These tests are the safety net: they fail the build when a template
 * changes without an eval, when a template is added without being registered, or when a
 * registered name has no file.
 */
class PromptLibraryTest {

    private static final String MANIFEST = "prompts/manifest.properties";

    private final PromptLibrary library = new PromptLibrary();

    private static Map<String, String> manifest() throws IOException {
        Properties props = new Properties();
        try (InputStream in = PromptLibraryTest.class.getClassLoader()
                .getResourceAsStream(MANIFEST)) {
            assertThat(in).as("the manifest must exist at " + MANIFEST).isNotNull();
            props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Map<String, String> entries = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            entries.put(name, props.getProperty(name));
        }
        return entries;
    }

    @Test
    void everyRegisteredPromptExistsAndLoads() {
        for (String name : PromptLibrary.knownPromptNames()) {
            VersionedPrompt prompt = library.get(name);
            assertThat(prompt.text())
                    .as("prompt %s must not be empty — an empty system prompt yields a "
                            + "confident wrong answer rather than an error", name)
                    .isNotBlank();
        }
    }

    @Test
    void anUnknownPromptNameFailsImmediatelyWithTheListOfValidOnes() {
        // A typo must not resolve to a name nobody registered; that is how a prompt
        // silently stops being the one you thought you were running.
        assertThatThrownBy(() -> library.get("test-generator "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown prompt")
                .hasMessageContaining("test-generator");
    }

    @Test
    void theVersionIsStableForUnchangedTextAndChangesWithIt() {
        String first = library.version("test-generator");
        assertThat(library.version("test-generator"))
                .as("the same text must always yield the same version, or logs become noise")
                .isEqualTo(first);

        String mutated = PromptLibrary.shortHash(first + "!");
        assertThat(mutated)
                .as("a one-character change must move the version, or an edit is unattributable")
                .isNotEqualTo(first);
    }

    @Test
    void everyRegisteredPromptIsPinnedInTheManifest() throws IOException {
        Set<String> pinned = manifest().keySet();
        Set<String> registered = new TreeSet<>(PromptLibrary.knownPromptNames());

        assertThat(registered)
                .as("a prompt added without a manifest entry has no eval behind it")
                .isEqualTo(new TreeSet<>(pinned));
    }

    @Test
    void everyManifestEntryCarriesAnEvalCitation() throws IOException {
        // A hash on its own is a list of numbers nobody reads. Requiring a non-empty
        // citation is what stops the manifest decaying back into decoration.
        for (Map.Entry<String, String> entry : manifest().entrySet()) {
            String[] parts = entry.getValue().split(",", 2);
            assertThat(parts)
                    .as("manifest entry for %s must be <hash>,<eval citation>", entry.getKey())
                    .hasSize(2);
            assertThat(parts[0])
                    .as("manifest entry for %s must carry a content hash", entry.getKey())
                    .matches("[0-9a-f]{12}");
            assertThat(parts[1].trim())
                    .as("manifest entry for %s must cite the eval that justified this text",
                            entry.getKey())
                    .isNotBlank();
        }
    }

    @Test
    void noPromptHasChangedWithoutAnEvalRun() throws IOException {
        // The enforcement behind the acceptance criterion. A drift here means someone
        // edited a template and did not re-run the harness.
        List<String> drifted = new ArrayList<>();
        for (Map.Entry<String, String> entry : manifest().entrySet()) {
            String expectedHash = entry.getValue().split(",", 2)[0].trim();
            String actualHash = library.version(entry.getKey());
            if (!expectedHash.equals(actualHash)) {
                drifted.add(entry.getKey() + ": manifest says " + expectedHash
                        + ", file hashes to " + actualHash);
            }
        }

        assertThat(drifted)
                .as("""
                        A prompt template changed without an eval run.

                        The prompt is the product, so an unmeasured edit is a silent
                        behaviour change. Do this instead:
                          1. run  mvn test -Dtest=DefectRecallHarnessTest
                          2. shasum -a 256 src/main/resources/prompts/<name>.md | cut -c1-12
                          3. update prompts/manifest.properties with the new hash and the
                             score you measured, and say what changed in the citation

                        If the change was deliberate and the score is unchanged, say so in
                        the citation — that is a real result, not a formality.
                        """)
                .isEmpty();
    }

    @Test
    void promptsOnTheClasspathAreAllRegistered() throws IOException {
        // A new .md dropped into prompts/ without touching PromptLibrary.KNOWN would load
        // nowhere, and nothing else would complain.
        Set<String> registered = new TreeSet<>(PromptLibrary.knownPromptNames());
        Set<String> unregistered = new TreeSet<>(
                classpathPromptFiles().stream()
                        .filter(f -> !registered.contains(f))
                        .collect(Collectors.toSet()));
        assertThat(unregistered)
                .as("these templates exist but are not registered in PromptLibrary.KNOWN, "
                        + "so they are unversioned and untested")
                .isEmpty();
    }

    private static List<String> classpathPromptFiles() throws IOException {
        java.nio.file.Path dir = java.nio.file.Path.of("src/main/resources/prompts");
        if (!java.nio.file.Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = java.nio.file.Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".md"))
                    .map(n -> n.substring(0, n.length() - 3))
                    .toList();
        }
    }
}
