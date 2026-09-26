package com.qalab.qalabai.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts an {@code operationId} on every log line for the duration of a request.
 *
 * <p>"Why was that run slow?" is unanswerable when the interesting lines do not say
 * <em>which</em> run. The id goes three places: the MDC, so every logger in the call
 * picks it up without being changed; the {@code X-Operation-Id} response header, so a
 * caller can quote it in a bug report; and — when the client supplies one — the request is
 * honoured, which is what makes a trace survive a proxy hop or a retry.</p>
 *
 * <p>The MDC entry is removed in a finally block. Leaking it is a classic servlet-filter
 * bug and shows up much later as unrelated requests inheriting an id, which is worse than
 * having no id at all.</p>
 */
@Component
@Order(1)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String OPERATION_ID_HEADER = "X-Operation-Id";
    public static final String MDC_OPERATION_ID = "operationId";
    public static final String MDC_METHOD = "httpMethod";
    public static final String MDC_PATH = "httpPath";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String operationId = resolve(request);
        long startedNanos = System.nanoTime();

        MDC.put(MDC_OPERATION_ID, operationId);
        MDC.put(MDC_METHOD, request.getMethod());
        MDC.put(MDC_PATH, request.getRequestURI());
        response.setHeader(OPERATION_ID_HEADER, operationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Logged while the MDC is still populated, then cleared: a cleanup line with
            // no id on it is the most confusing line in a log.
            org.slf4j.LoggerFactory.getLogger(RequestCorrelationFilter.class).info(
                    "Request completed: {} {} -> {} in {} ms",
                    request.getMethod(), request.getRequestURI(), response.getStatus(),
                    (System.nanoTime() - startedNanos) / 1_000_000);
            MDC.remove(MDC_OPERATION_ID);
            MDC.remove(MDC_METHOD);
            MDC.remove(MDC_PATH);
        }
    }

    /**
     * A client-supplied id is trusted after a length check and a character check.
     *
     * <p>It goes into a response header and a log field, so an unbounded value is both a
     * log-flooding vector and a header-injection risk. Only the characters a caller would
     * legitimately use are accepted.</p>
     */
    static String resolve(HttpServletRequest request) {
        String supplied = request.getHeader(OPERATION_ID_HEADER);
        if (supplied != null && isAcceptable(supplied)) {
            return supplied;
        }
        return "op-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    static boolean isAcceptable(String value) {
        if (value.isBlank() || value.length() > 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
