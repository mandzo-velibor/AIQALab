package com.qalab.qalabai.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CORS origin list used to be hardcoded to {@code http://localhost:3000}, so
 * every frontend API call was blocked by the browser the moment the app was
 * deployed anywhere else — including the Oracle Cloud host this project now runs
 * on. These tests pin the parsing and the registered mapping.
 */
class WebConfigCorsTest {

    private static final String DEV_DEFAULT = "http://localhost:3000";

    private WebConfig configWith(String originsProperty) {
        WebConfig config = new WebConfig();
        try {
            Field f = WebConfig.class.getDeclaredField("allowedOrigins");
            f.setAccessible(true);
            f.set(config, originsProperty);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return config;
    }

    private List<String> registeredOrigins(WebConfig config) {
        CorsRegistry registry = new CorsRegistry();
        config.addCorsMappings(registry);
        return originsViaReflection(registry);
    }

    @SuppressWarnings("unchecked")
    private static List<String> originsViaReflection(CorsRegistry registry) {
        // CorsRegistry#getCorsConfigurations is protected, so read it reflectively
        // rather than duplicating the registration logic in the test.
        try {
            java.lang.reflect.Method m = CorsRegistry.class.getDeclaredMethod("getCorsConfigurations");
            m.setAccessible(true);
            var configs = (java.util.Map<String, CorsConfiguration>) m.invoke(registry);
            assertEquals(1, configs.size(), "expected exactly one CORS mapping");
            return List.copyOf(configs.values().iterator().next().getAllowedOrigins());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void defaultsToLocalhostForDevelopment() {
        assertEquals(List.of(DEV_DEFAULT), WebConfig.parseOrigins(DEV_DEFAULT));
    }

    @Test
    void acceptsACommaSeparatedListOfOrigins() {
        assertEquals(
                List.of("https://qa.example.com", "https://qa.internal.example.com"),
                WebConfig.parseOrigins("https://qa.example.com,https://qa.internal.example.com"));
    }

    @Test
    void trimsWhitespaceAroundConfiguredOrigins() {
        assertEquals(
                List.of("https://a.example.com", "https://b.example.com"),
                WebConfig.parseOrigins("  https://a.example.com ,  https://b.example.com  "));
    }

    @Test
    void dropsBlankEntries() {
        assertEquals(
                List.of("https://a.example.com", "https://b.example.com"),
                WebConfig.parseOrigins("https://a.example.com,,   ,https://b.example.com"));
    }

    @Test
    void blankConfigurationYieldsNoOrigins() {
        assertTrue(WebConfig.parseOrigins(null).isEmpty());
        assertTrue(WebConfig.parseOrigins("   ").isEmpty());
    }

    @Test
    void registersTheConfiguredOriginsOnTheApiMapping() {
        List<String> origins = registeredOrigins(
                configWith("https://qa.example.com,https://qa.internal.example.com"));

        assertEquals(List.of("https://qa.example.com", "https://qa.internal.example.com"), origins);
    }

    @Test
    void dropsAWildcardOriginBecauseCredentialsAreEnabled() {
        // allowCredentials(true) plus "*" is rejected by browsers; registering it would
        // surface as an opaque console failure, so it is refused with a warning instead.
        assertEquals(List.of("https://a.example.com"),
                WebConfig.parseOrigins("*,https://a.example.com"));
        assertTrue(WebConfig.parseOrigins("*").isEmpty());
    }

    @Test
    void neverRegistersAWildcardOrigin() {
        for (String configured : List.of(DEV_DEFAULT, "https://a.example.com", "*")) {
            assertFalse(registeredOrigins(configWith(configured)).contains("*"),
                    "wildcard origin must not be registered for: " + configured);
        }
    }
}
