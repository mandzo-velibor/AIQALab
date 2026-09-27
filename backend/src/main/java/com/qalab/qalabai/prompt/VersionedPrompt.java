package com.qalab.qalabai.prompt;

/**
 * A prompt template together with the identity of the exact text that was used.
 *
 * @param name    logical prompt name, matching {@code prompts/<name>.md}
 * @param version content hash of the text, short form
 * @param text    the template body
 */
public record VersionedPrompt(String name, String version, String text) {

    @Override
    public String toString() {
        return name + "@" + version;
    }
}
