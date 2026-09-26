package com.qalab.qalabai.service;

import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.api.v1.dto.ProjectInfo;
import com.qalab.qalabai.api.v1.dto.V1FullWorkflowRequest;
import com.qalab.qalabai.api.v1.dto.V1WorkflowResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The full-test workflow used to run on the request thread for its whole duration
 * (minutes: a headless browser, npm and several LLM calls). Every concurrent run
 * pinned a Tomcat thread, and any reverse proxy dropped the connection at its own
 * read timeout — the {@code Connection reset by peer} in the cloud logs, where the
 * backend kept working while the user was told it had failed.
 *
 * <p>The workflow service is stubbed so the asynchrony contract can be asserted
 * deterministically and quickly. The workflow's own behaviour is covered by
 * {@code QaWorkflowServiceTest}; what matters here is the HTTP contract: submit
 * returns at once, polling observes the transition, and a saturated queue is
 * rejected rather than silently parked.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "qalab.security.require-api-key=true",
                "qalab.workflow.workers=2",
                "qalab.workflow.queue-capacity=20",
                "qalab.auto-install-playwright=false",
                "qalab.auto-start-frontend=false",
                "qalab.auto-open-browser=false"
        })
@ActiveProfiles("test")
class WorkflowAsyncHttpIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ApiKeyService apiKeyService;

    @Autowired
    OperationProgressStore progressStore;

    @MockBean
    QaWorkflowService qaWorkflowService;

    /**
     * Blocks the worker so queue and concurrency behaviour can be observed.
     * Settable per test, because a CountDownLatch cannot be reset once counted down.
     */
    private final AtomicReference<CountDownLatch> release = new AtomicReference<>(new CountDownLatch(1));

    private HttpHeaders auth() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKeyService.issue(null, "async-test").rawKey());
        return headers;
    }

    private V1FullWorkflowRequest request(String opId) {
        return new V1FullWorkflowRequest(
                ProjectInfo.of("async-test", "http://localhost:1/", "PLAYWRIGHT_TYPESCRIPT", "TypeScript"),
                "http://localhost:1/", null, null, null, opId, null);
    }

    private V1WorkflowResponse cannedResponse(String opId) {
        Map<String, Object> steps = new LinkedHashMap<>();
        steps.put("explore", Map.of("status", "COMPLETED", "pageType", "Login"));
        steps.put("generatedTests", Map.of("status", "COMPLETED", "count", 1));
        return new V1WorkflowResponse(opId, OperationStatus.COMPLETED, "async-test",
                "http://localhost:1/", steps, LocalDateTime.now());
    }

    @Test
    void submittingReturnsImmediatelyWithAnOperationIdAndPollUrl() {
        when(qaWorkflowService.runFullTest(any())).thenReturn(cannedResponse("op-fast"));

        Instant start = Instant.now();
        ResponseEntity<Map> ack = rest.exchange("/api/v1/workflows/full-test", HttpMethod.POST,
                new HttpEntity<>(request("op-fast"), auth()), Map.class);
        Duration elapsed = Duration.between(start, Instant.now());

        assertEquals(HttpStatus.ACCEPTED, ack.getStatusCode(),
                "the workflow must be acknowledged asynchronously, was: " + ack.getBody());
        assertEquals("op-fast", ack.getBody().get("operationId"));
        assertEquals("QUEUED", ack.getBody().get("status"));
        assertEquals("/api/v1/workflows/op-fast", ack.getBody().get("statusUrl"),
                "the client must be told where to poll");
        assertTrue(elapsed.toMillis() < 3_000,
                "the POST must return promptly, took " + elapsed.toMillis() + "ms");
    }

    @Test
    void anUnauthenticatedSubmissionIsRejected() {
        // TestRestTemplate retries on 401, which hides the status, so use a plain
        // RestTemplate pointed at the running port.
        RestTemplate plain = new RestTemplate();
        String base = "http://localhost:" + port();

        org.springframework.web.client.HttpClientErrorException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.client.HttpClientErrorException.class,
                        () -> plain.postForEntity(base + "/api/v1/workflows/full-test",
                                request("op-unauth"), String.class));

        // The status is what matters here. That the rejection also uses the shared
        // {"error":{...}} envelope is asserted in ApiKeyHttpIntegrationTest, where the
        // body is reliably readable; repeating it here would test the client library
        // rather than the async contract.
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    private int port() {
        // TestRestTemplate is bound to the random port the context started on.
        String url = rest.getRestTemplate().getUriTemplateHandler().expand("/").toString();
        return Integer.parseInt(url.substring(url.lastIndexOf(':') + 1).replaceAll("/.*", ""));
    }

    @Test
    void aRunningWorkflowPollsAsAcceptedThenReturnsTheFullResult() throws Exception {
        String opId = "op-poll-to-completion";
        when(qaWorkflowService.runFullTest(any())).thenAnswer(inv -> {
            release.get().await(20, TimeUnit.SECONDS);
            return cannedResponse(opId);
        });

        ResponseEntity<Map> ack = rest.exchange("/api/v1/workflows/full-test", HttpMethod.POST,
                new HttpEntity<>(request(opId), auth()), Map.class);
        assertEquals(HttpStatus.ACCEPTED, ack.getStatusCode());

        // While the worker is blocked the operation must be observable as in-flight.
        boolean sawInFlight = false;
        for (int i = 0; i < 40 && !sawInFlight; i++) {
            ResponseEntity<Map> poll = rest.exchange("/api/v1/workflows/" + opId, HttpMethod.GET,
                    new HttpEntity<>(auth()), Map.class);
            if (poll.getStatusCode() == HttpStatus.ACCEPTED) {
                sawInFlight = true;
                assertTrue(List.of("QUEUED", "RUNNING").contains(String.valueOf(poll.getBody().get("status"))),
                        "unexpected in-flight status: " + poll.getBody());
                assertNotNull(poll.getBody().get("operationId"));
            } else {
                Thread.sleep(50);
            }
        }
        assertTrue(sawInFlight, "a client must be able to observe a non-terminal state while it runs");

        release.get().countDown();

        // And then reach the full result.
        V1WorkflowResponse result = null;
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<Map> poll = rest.exchange("/api/v1/workflows/" + opId, HttpMethod.GET,
                    new HttpEntity<>(auth()), Map.class);
            if (poll.getStatusCode() == HttpStatus.OK) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = poll.getBody();
                result = new V1WorkflowResponse((String) body.get("operationId"),
                        OperationStatus.valueOf((String) body.get("status")),
                        (String) body.get("projectId"), (String) body.get("url"),
                        (Map<String, Object>) body.get("steps"),
                        LocalDateTime.parse((String) body.get("createdAt")));
                break;
            }
            Thread.sleep(100);
        }

        assertNotNull(result, "the workflow should have reached a terminal state");
        assertEquals(OperationStatus.COMPLETED, result.status());
        assertNotNull(result.steps(), "the terminal response must carry the full step map");
        assertTrue(result.steps().containsKey("explore"), String.valueOf(result.steps()));
    }

    @Test
    void anUnknownOperationIsNotFound() {
        ResponseEntity<Map> res = rest.exchange("/api/v1/workflows/op-never-existed", HttpMethod.GET,
                new HttpEntity<>(auth()), Map.class);

        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        assertTrue(String.valueOf(res.getBody()).contains("OPERATION_NOT_FOUND"), String.valueOf(res.getBody()));
    }

    @Test
    void aFailedWorkflowStillReturnsATerminalResultRatherThanHanging() throws Exception {
        String opId = "op-workflow-throws";
        when(qaWorkflowService.runFullTest(any())).thenThrow(new RuntimeException("locator generation exploded"));

        rest.exchange("/api/v1/workflows/full-test", HttpMethod.POST,
                new HttpEntity<>(request(opId), auth()), Map.class);

        HttpStatusCode status = null;
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<Map> poll = rest.exchange("/api/v1/workflows/" + opId, HttpMethod.GET,
                    new HttpEntity<>(auth()), Map.class);
            if (poll.getStatusCode() != HttpStatus.ACCEPTED) {
                status = poll.getStatusCode();
                assertTrue(String.valueOf(poll.getBody()).contains("locator generation exploded"),
                        "the failure reason must reach the client: " + poll.getBody());
                break;
            }
            Thread.sleep(100);
        }
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, status,
                "a workflow that throws must terminate, not poll forever");
    }

    @Test
    void manyConcurrentSubmissionsAreAllAcknowledgedWithoutBlocking() throws Exception {
        // The regression guard for the original problem: on the old code each of these
        // held a request thread for the whole workflow, so this test could not even be
        // written.
        int n = 12;
        when(qaWorkflowService.runFullTest(any())).thenAnswer(inv -> {
            release.get().await(20, TimeUnit.SECONDS);
            return cannedResponse("concurrent");
        });

        CountDownLatch go = new CountDownLatch(1);
        List<Long> latencies = new CopyOnWriteArrayList<>();
        List<HttpStatusCode> statuses = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    Instant s = Instant.now();
                    ResponseEntity<Map> r = rest.exchange("/api/v1/workflows/full-test", HttpMethod.POST,
                            new HttpEntity<>(request("op-concurrent-" + idx), auth()), Map.class);
                    latencies.add(Duration.between(s, Instant.now()).toMillis());
                    statuses.add(r.getStatusCode());
                } catch (Exception e) {
                    statuses.add(HttpStatus.INTERNAL_SERVER_ERROR);
                }
            });
            t.start();
            threads.add(t);
        }
        go.countDown();
        for (Thread t : threads) {
            t.join(TimeUnit.SECONDS.toMillis(30));
        }
        release.get().countDown();

        assertEquals(n, statuses.size());
        long accepted = statuses.stream()
                .filter(s -> s.value() == HttpStatus.ACCEPTED.value()).count();
        assertEquals(n, accepted,
                "all submissions should be acknowledged; a 429 means the queue is too small "
                        + "for this host. statuses: " + statuses.stream().distinct().toList());
        long slowest = latencies.stream().mapToLong(Long::longValue).max().orElse(0);
        assertTrue(slowest < 5_000,
                "no submission should block on the workflow; slowest was " + slowest + "ms");
    }

    @Test
    void theProgressEndpointIsUnchangedForClientsAlreadyWatchingIt() {
        // The workflow service is stubbed here, so no progress is recorded by a real
        // run. Seed the store directly: what this test is about is that the
        // pre-existing /progress contract still answers, since a client may already be
        // polling it.
        String opId = "op-progress-still-works";
        progressStore.update(opId, "RUNNING", "RUNNING_TESTS", "running tests...");

        ResponseEntity<Map> prog = rest.exchange("/api/v1/workflows/" + opId + "/progress",
                HttpMethod.GET, new HttpEntity<>(auth()), Map.class);

        assertEquals(HttpStatus.OK, prog.getStatusCode());
        assertEquals("running tests...", prog.getBody().get("message"));
        assertEquals(opId, prog.getBody().get("operationId"));
    }

    @Test
    void theProgressEndpointReportsNotFoundForAnUnknownOperation() {
        ResponseEntity<Map> prog = rest.exchange("/api/v1/workflows/op-never-ran/progress",
                HttpMethod.GET, new HttpEntity<>(auth()), Map.class);

        assertEquals(HttpStatus.NOT_FOUND, prog.getStatusCode());
    }

    @Test
    void aSaturatedQueueIsRejectedWithARetryableErrorRatherThanSilentlyParked() {
        // 2 workers, so blocks 1 and 2 occupy them; 3 and 4 fill the queue; the 5th is rejected.
        AtomicInteger calls = new AtomicInteger();
        when(qaWorkflowService.runFullTest(any())).thenAnswer(inv -> {
            calls.incrementAndGet();
            release.get().await(20, TimeUnit.SECONDS);
            return cannedResponse("saturated");
        });

        // 2 workers + 20 queued = 22 that can be accepted. Submit well past that so the
        // rejection path is actually reached; a loop stopping at exactly 22 would never
        // trip it.
        int attempts = 30;
        List<HttpStatusCode> statuses = new ArrayList<>();
        List<Map> bodies = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            ResponseEntity<Map> r = rest.exchange("/api/v1/workflows/full-test", HttpMethod.POST,
                    new HttpEntity<>(request("op-saturate-" + i), auth()), Map.class);
            statuses.add(r.getStatusCode());
            bodies.add(r.getBody());
        }
        release.get().countDown();

        long rejected = statuses.stream()
                .filter(s -> s.value() == HttpStatus.TOO_MANY_REQUESTS.value()).count();
        assertTrue(rejected > 0,
                "a saturated queue must reject rather than grow without bound; statuses: "
                        + statuses.stream().map(HttpStatusCode::value).distinct().toList());

        long accepted = statuses.stream()
                .filter(s -> s.value() == HttpStatus.ACCEPTED.value()).count();
        assertTrue(accepted <= 22,
                "accepted count must be bounded by workers+queue, was " + accepted);

        // A rejection must tell the caller what to do and give it a status to poll.
        for (Map body : bodies) {
            if ("REJECTED".equals(body.get("status"))) {
                assertNotNull(body.get("message"));
                assertTrue(String.valueOf(body.get("message")).toLowerCase().contains("retry"),
                        "a rejection must be actionable: " + body.get("message"));
            }
        }
    }
}
