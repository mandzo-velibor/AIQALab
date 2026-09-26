package com.qalab.qalabai.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Why was that run slow?" is unanswerable when the interesting lines do not say which
 * run. These tests pin the two things that make a trace work — the id reaches the MDC, and
 * it does not outlive the request.
 */
class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, FilterChain chain)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void aRequestGetsAnOperationIdOnTheResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");

        MockHttpServletResponse response = run(request, (req, res) -> {
        });

        String id = response.getHeader(RequestCorrelationFilter.OPERATION_ID_HEADER);
        assertNotNull(id, "the caller must be able to quote the id in a bug report");
        assertTrue(id.startsWith("op-"), id);
    }

    @Test
    void theIdIsOnEveryLogLineForTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        String[] seen = new String[1];

        run(request, (req, res) ->
                seen[0] = MDC.get(RequestCorrelationFilter.MDC_OPERATION_ID));

        assertNotNull(seen[0], "the MDC must carry the id while the chain runs, or every "
                + "log line inside the request is untraceable");
    }

    @Test
    void methodAndPathAreAlsoOnTheMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/intent/run");
        String[] method = new String[1];
        String[] path = new String[1];

        run(request, (req, res) -> {
            method[0] = MDC.get(RequestCorrelationFilter.MDC_METHOD);
            path[0] = MDC.get(RequestCorrelationFilter.MDC_PATH);
        });

        assertEquals("POST", method[0]);
        assertEquals("/api/v1/intent/run", path[0]);
    }

    @Test
    void aClientSuppliedIdIsHonouredSoATraceSurvivesAProxy() throws Exception {
        // The id a client was given must be the id that appears in the log, or a trace
        // breaks at the first hop that does not understand it.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        request.addHeader(RequestCorrelationFilter.OPERATION_ID_HEADER, "op-abc-123");

        MockHttpServletResponse response = run(request, (req, res) -> {
        });

        assertEquals("op-abc-123", response.getHeader(RequestCorrelationFilter.OPERATION_ID_HEADER));
    }

    @Test
    void aHostileSuppliedIdIsReplacedRatherThanEchoed() throws Exception {
        // It lands in a response header and a log field, so an unbounded value is both a
        // log-flooding vector and a header-injection risk.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        request.addHeader(RequestCorrelationFilter.OPERATION_ID_HEADER,
                "op-injected\r\nX-Evil: yes");

        MockHttpServletResponse response = run(request, (req, res) -> {
        });

        String id = response.getHeader(RequestCorrelationFilter.OPERATION_ID_HEADER);
        assertTrue(id.startsWith("op-"), "an unusable id must be replaced: " + id);
        assertFalse(id.contains("\r"), "and must not carry a line break");
    }

    @Test
    void anOverlongIdIsReplaced() {
        assertFalse(RequestCorrelationFilter.isAcceptable("x".repeat(200)));
        assertFalse(RequestCorrelationFilter.isAcceptable(""));
        assertFalse(RequestCorrelationFilter.isAcceptable("   "));
        assertTrue(RequestCorrelationFilter.isAcceptable("op-abc.123_XYZ"));
    }

    @Test
    void theMdcIsClearedAfterTheRequest() throws Exception {
        // Leaking an id between requests on a pooled thread is the classic servlet-filter
        // bug. It shows up much later as unrelated requests inheriting an id, which is
        // worse than having none.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");

        run(request, (req, res) -> {
        });

        assertTrue(MDC.get(RequestCorrelationFilter.MDC_OPERATION_ID) == null,
                "the operation id must not outlive the request");
        assertTrue(MDC.get(RequestCorrelationFilter.MDC_METHOD) == null);
        assertTrue(MDC.get(RequestCorrelationFilter.MDC_PATH) == null);
    }

    @Test
    void theMdcIsClearedEvenWhenTheRequestThrows() throws Exception {
        // A filter that only cleans up on the happy path leaks the id for the whole life
        // of that thread.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                    assertNotNull(MDC.get(RequestCorrelationFilter.MDC_OPERATION_ID));
                    throw new RuntimeException("boom");
                }));

        assertTrue(MDC.get(RequestCorrelationFilter.MDC_OPERATION_ID) == null,
                "a failed request must not leave its id behind");
    }

    @Test
    void twoRequestsGetDifferentIds() throws Exception {
        MockHttpServletResponse first = run(new MockHttpServletRequest("GET", "/a"), (req, res) -> {
        });
        MockHttpServletResponse second = run(new MockHttpServletRequest("GET", "/a"), (req, res) -> {
        });

        assertFalse(first.getHeader(RequestCorrelationFilter.OPERATION_ID_HEADER)
                        .equals(second.getHeader(RequestCorrelationFilter.OPERATION_ID_HEADER)),
                "concurrent requests must not share an id, or a trace mixes them");
    }
}
