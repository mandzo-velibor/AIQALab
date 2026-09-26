package com.qalab.qalabai.config;

import com.qalab.qalabai.security.ApiKeyAuthenticationFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Deny-by-default security for the API.
 *
 * <p>Authentication itself is handled by {@link ApiKeyAuthenticationFilter}, which
 * enforces conditionally (see ADR 0001): a fresh install with no key issued yet stays
 * usable, and protection switches on as soon as a key exists or
 * {@code qalab.security.require-api-key=true}.</p>
 *
 * <p>This configuration exists to make everything <em>else</em> explicit — no form
 * login, no default generated password, no sessions, no CSRF token (bearer tokens are
 * not sent ambiently, so CSRF does not apply).</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Endpoints reachable without a key. Sourced from the filter so the chain and the
     * filter — which are two separate mechanisms and have already drifted once — cannot
     * disagree about what is public.
     */
    private static final String[] PUBLIC_ENDPOINTS =
            ApiKeyAuthenticationFilter.PUBLIC_PATHS.toArray(new String[0]);

    @Bean
    @ConditionalOnProperty(name = "spring.security.enabled", havingValue = "true", matchIfMissing = true)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                                      ApiKeyAuthenticationFilter apiKeyFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        // The agent event stream is a browser WebSocket with no
                        // Authorization header available to the browser API, so it is
                        // authenticated at connect time by origin rather than by
                        // bearer token. Tracked as a follow-up in ADR 0001.
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/api/**").permitAll()   // enforced by the filter above
                        .anyRequest().denyAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
