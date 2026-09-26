package com.qalab.qalabai.ai.gateway;

import com.qalab.qalabai.ai.opencode.OpenCodeAiProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two places declare the managed cascade's model: the OpenCode provider's own
 * {@code opencode.zen.*} settings, and {@code qalab.ai.providers.aiqalab.model}, which
 * only exists so the gateway can resolve a model to report.
 *
 * <p>They drifted. Space Bunny Free became the cascade's primary and the gateway entry
 * was left naming {@code big-pickle} — the fallback — so the gateway reported a model
 * the cascade only reached after two providers had already failed. Nothing failed
 * loudly: a string in a config file is not a compile error, and a test on the provider
 * alone could not see the other half.</p>
 */
class ManagedModelConsistencyTest {

    /** The provider's own default, as {@code @Value} declares it. */
    private String providerZenModel() {
        return valueOf("opencode.zen.model");
    }

    private String gatewayManagedModel() {
        return valueOf("qalab.ai.providers.aiqalab.model");
    }

    /**
     * Reads a value out of the shipped application.yml.
     *
     * <p>Parsed with a real YAML reader rather than a regex: the file is nested, so
     * "opencode.zen.model" never appears as a literal — only the leaf {@code model:} does,
     * several times. A regex would either miss it or match the wrong one, and a test that
     * quietly reads the wrong key is worse than no test.</p>
     *
     * <p>Reading the shipped file rather than hardcoding the values is the point: a test
     * asserting a literal asserts itself and would stay green through the next change of
     * exactly this kind.</p>
     */
    @SuppressWarnings("unchecked")
    private String valueOf(String dottedKey) {
        Map<String, Object> root;
        try (var stream = new ClassPathResource("application.yml").getInputStream()) {
            root = new org.yaml.snakeyaml.Yaml().load(stream);
        } catch (IOException e) {
            throw new IllegalStateException("application.yml is unreadable", e);
        }
        Object current = root;
        for (String segment : dottedKey.split("\\.")) {
            assertTrue(current instanceof Map,
                    dottedKey + ": " + segment + " is not inside a mapping");
            Map<String, Object> map = (Map<String, Object>) current;
            assertTrue(map.containsKey(segment),
                    dottedKey + " is missing from application.yml (no key \"" + segment + "\")");
            current = map.get(segment);
        }
        // Values are "${ENV:default}"; the default is what this test is about.
        String raw = String.valueOf(current);
        Matcher matcher = Pattern.compile("^\\$\\{[^:}]+:(.*)}$").matcher(raw.trim());
        assertTrue(matcher.matches(),
                dottedKey + " should be an overridable \"${ENV:default}\", was: " + raw);
        return matcher.group(1).trim();
    }

    @Test
    void theGatewayReportsTheModelTheCascadeActuallyTriesFirst() {
        assertEquals(providerZenModel(), gatewayManagedModel(),
                "the gateway must report the cascade's primary model, not its fallback. "
                        + "A user reading a usage record should be told which model ran.");
    }

    @Test
    void spaceBunnyFreeIsTheManagedPrimaryAndBigPickleTheFallback() {
        assertEquals("space-bunny-free", providerZenModel());
        assertEquals("big-pickle", valueOf("opencode.zen.fallback-model"),
                "Big Pickle stays in the chain, immediately after Space Bunny Free");
    }

    @Test
    void bothManagedModelsArePricedSoCostIsNotReportedAsUnknown() {
        // An unregistered model returns a null cost, which is indistinguishable from
        // having forgotten to price it.
        ProviderPricingRegistry registry = new ProviderPricingRegistry();
        assertNotNull(registry.lookup(AiProviderType.OPENCODE, providerZenModel()));
        assertNotNull(registry.lookup(AiProviderType.OPENCODE, valueOf("opencode.zen.fallback-model")));
    }

    @Test
    void theProvidersOwnBeanDefaultMatchesTheConfigFileToo() {
        // The @Value default in the bean is what applies when the property is absent, so
        // it must not be left pointing at the old model either.
        OpenCodeAiProvider provider = new OpenCodeAiProvider(new org.springframework.web.client.RestTemplate());
        String beanDefault = readValueDefault(provider, "zenModel");
        assertEquals(providerZenModel(), beanDefault,
                "the @Value fallback and application.yml must agree");
    }

    @Test
    void theCascadeCapStillCoversEveryConfiguredCandidate() {
        // Adding a model to the chain without revisiting the cap would make the tail
        // unreachable in the worst case, which is when it matters.
        int candidates = 5; // Go, Zen primary, Zen fallback, Gemini, Ollama
        int cap = Integer.parseInt(valueOf("opencode.max-provider-calls"));
        assertTrue(cap >= candidates,
                "the cap (" + cap + ") must cover all " + candidates
                        + " configured candidates or the tail becomes unreachable");
    }

    private String readValueDefault(Object bean, String field) {
        try {
            Value annotation = bean.getClass().getDeclaredField(field).getAnnotation(Value.class);
            assertNotNull(annotation, field + " has no @Value");
            String expression = annotation.value();
            // "…:default}" — take the text between the last colon and the closing brace.
            int colon = expression.lastIndexOf(':');
            int close = expression.lastIndexOf('}');
            assertTrue(colon > 0 && close > colon, "no default in " + expression);
            return expression.substring(colon + 1, close);
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }
}
