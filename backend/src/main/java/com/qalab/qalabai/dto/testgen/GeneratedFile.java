package com.qalab.qalabai.dto.testgen;

/**
 * A single generated source file returned to the client. The Core returns
 * source content; it does not write files into its own repository.
 */
public record GeneratedFile(String path, String content) {
}
