package com.qalab.qalabai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    /**
     * Comma-separated list of browser origins allowed to call the API.
     *
     * <p>This used to be hardcoded to {@code http://localhost:3000}, which meant every
     * frontend call was blocked by the browser as soon as the app was deployed
     * anywhere else. Defaults to localhost so development is unchanged.</p>
     *
     * <p>Note: credentials are enabled, so a wildcard origin is never valid here.
     * Configure explicit origins instead.</p>
     */
    @Value("${qalab.cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = parseOrigins(allowedOrigins);
        log.info("CORS allowed origins: {}", origins);

        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    /**
     * Splits and trims the configured origins, dropping blank entries.
     *
     * <p>A wildcard is dropped with a warning: credentials are enabled on this
     * mapping, and browsers reject {@code Access-Control-Allow-Origin: *} together
     * with {@code Allow-Credentials: true}. Silently registering it would surface
     * as an opaque failure in the browser console, so it is refused here with a
     * message naming the fix.</p>
     */
    static List<String> parseOrigins(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> origins = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (origins.contains("*")) {
            log.warn("Ignoring wildcard CORS origin '*'. Credentials are enabled on /api/**, "
                    + "so browsers reject a wildcard. List the explicit origins instead, e.g. "
                    + "qalab.cors.allowed-origins=https://qa.example.com");
            return origins.stream().filter(s -> !"*".equals(s)).toList();
        }
        return origins;
    }
}
