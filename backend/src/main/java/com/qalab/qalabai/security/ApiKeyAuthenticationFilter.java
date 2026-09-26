package com.qalab.qalabai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.api.ApiError;
import com.qalab.qalabai.api.ErrorCode;
import com.qalab.qalabai.api.ErrorResponse;
import com.qalab.qalabai.repository.AccountRepository;
import com.qalab.qalabai.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Resolves a bearer API key to the calling account and places it on the request.
 *
 * <p>Rejections emit the standard {@code {"error": {...}}} body rather than Spring's
 * default HTML or an empty 401, because both the CLI and the UI parse that shape
 * (see {@code frontend/src/lib/http.ts}). Returning a bare 401 would degrade error
 * handling on the client.</p>
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = "qalab.authPrincipal";

    /**
     * Paths reachable without a key. Single source of truth: {@code SecurityConfig}
     * references this list for its permitAll rules, so the two cannot drift.
     *
     * <p>Only a liveness probe and a bootstrap probe that reports whether a key is
     * needed. Everything else, including every AI-spending endpoint, requires one.</p>
     */
    public static final List<String> PUBLIC_PATHS = List.of(
            "/api/v1/account/bootstrap",
            "/actuator/health",
            "/error");

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    /**
     * Dependencies are held as {@link ObjectProvider}, not injected directly, and that
     * is load-bearing rather than stylistic.
     *
     * <p>The servlet container builds the filter chain while starting Tomcat, which
     * happens <em>before</em> the JPA EntityManagerFactory exists. A filter that
     * injects a repository therefore fails the entire context with "Cannot resolve
     * reference to bean 'jpaSharedEM_entityManagerFactory'". An ObjectProvider
     * resolves nothing at construction; the target is looked up per request, by which
     * point the context is fully up. A class-level {@code @Lazy} was tried first and
     * did not reliably defer instantiation here.</p>
     */
    private final ObjectProvider<ApiKeyService> apiKeyService;
    private final ObjectProvider<AccountRepository> accountRepository;
    private final ObjectMapper objectMapper;
    private final boolean requireApiKey;

    public ApiKeyAuthenticationFilter(ObjectProvider<ApiKeyService> apiKeyService,
                                      ObjectProvider<AccountRepository> accountRepository,
                                      ObjectMapper objectMapper,
                                      @Value("${qalab.security.require-api-key:false}") boolean requireApiKey) {
        this.apiKeyService = apiKeyService;
        this.accountRepository = accountRepository;
        this.objectMapper = objectMapper;
        this.requireApiKey = requireApiKey;
    }

    /**
     * Public paths bypass the key check entirely.
     *
     * <p>This is separate from the filter chain's permitAll rules on purpose: the chain
     * decides whether Spring Security's own mechanisms apply, while this filter is what
     * actually rejects. Without it, {@code /api/v1/account/bootstrap} answered 401 and
     * a client could not discover that a key was required in the first place.</p>
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return false;
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        return PUBLIC_PATHS.stream().anyMatch(p -> normalized.equals(p) || normalized.startsWith(p + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Optional<AuthPrincipal> principal = resolve(request);

        if (principal.isPresent()) {
            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal.get());
            chain.doFilter(request, response);
            return;
        }

        // Enforcement is deliberately conditional: requiring a key on a fresh local
        // install with no key yet would make the service unusable out of the box. An
        // operator who never creates a key stays unprotected, which is why startup
        // logs a loud warning. See ADR 0001.
        if (requireApiKey || apiKeyService.getObject().anyKeyExists()) {
            reject(response, request);
            return;
        }

        chain.doFilter(request, response);
    }

    private Optional<AuthPrincipal> resolve(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        String raw = header.substring(BEARER.length()).trim();
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        Optional<Long> accountId = apiKeyService.getObject().resolveAccountId(raw);
        if (accountId.isEmpty()) {
            log.debug("Rejected an API key that matched no active credential");
            return Optional.empty();
        }
        return accountRepository.getObject().findById(accountId.get())
                .map(account -> new AuthPrincipal(account.getId(), account.getPlan().name(), null));
    }

    private void reject(HttpServletResponse response, HttpServletRequest request) throws IOException {
        String message = "Missing or invalid API key. Send 'Authorization: Bearer <key>'. "
                + "Create one with POST /api/v1/account/api-keys, or set "
                + "qalab.security.require-api-key=false to run unprotected.";
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(),
                ErrorResponse.of(new ApiError(ErrorCode.UNAUTHENTICATED, message, null)));
    }

    /** Convenience for controllers and tests. */
    public static AuthPrincipal principalOf(HttpServletRequest request) {
        Object value = request.getAttribute(PRINCIPAL_ATTRIBUTE);
        return value instanceof AuthPrincipal principal ? principal : null;
    }
}
