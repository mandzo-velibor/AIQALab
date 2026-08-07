package com.qalab.qalabai.service.healing;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Compares locators by normalizing them and computing token-based similarity.
 * Used to rank candidate locators and to decide whether a healing suggestion
 * is close enough to the original element to be trustworthy.
 */
@Service
public class LocatorSimilarityService {

    public double similarity(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        String normA = normalize(a);
        String normB = normalize(b);
        if (normA.equals(normB)) {
            return 1.0;
        }
        if (normA.isEmpty() || normB.isEmpty()) {
            return 0.0;
        }

        Set<String> tokensA = tokenize(normA);
        Set<String> tokensB = tokenize(normB);

        Set<String> intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);

        Set<String> union = new HashSet<>(tokensA);
        union.addAll(tokensB);

        double jaccard = union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();

        double levenshteinScore = 1.0 - (double) levenshtein(normA, normB) / Math.max(normA.length(), normB.length());

        return 0.6 * jaccard + 0.4 * levenshteinScore;
    }

    public boolean isSimilar(String a, String b, double threshold) {
        return similarity(a, b) >= threshold;
    }

    private String normalize(String locator) {
        return locator.toLowerCase()
                .replaceAll("[^a-z0-9\\-_]", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private Set<String> tokenize(String normalized) {
        Set<String> tokens = new HashSet<>();
        for (String token : normalized.split(" ")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        Set<String> subtokens = new HashSet<>();
        for (String token : tokens) {
            if (token.contains("-")) {
                subtokens.addAll(Arrays.asList(token.split("-")));
            }
        }
        tokens.addAll(subtokens);
        return tokens;
    }

    private int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(prev[j] + 1, curr[j - 1] + 1),
                        prev[j - 1] + cost
                );
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }
}
