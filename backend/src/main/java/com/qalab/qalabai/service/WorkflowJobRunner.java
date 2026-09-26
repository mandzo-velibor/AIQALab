package com.qalab.qalabai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Runs long operations off the request thread.
 *
 * <p>The full QA workflow takes minutes and involves a headless browser, npm
 * subprocesses and several LLM calls. Serving it on the request thread means every
 * concurrent run pins a Tomcat thread for its whole duration, and any reverse proxy
 * or load balancer in front drops the connection at its own read timeout — which is
 * exactly the {@code AsyncRequestNotUsableException: Connection reset by peer} seen
 * in the Oracle Cloud logs, while the backend kept working and the user saw a
 * failure.</p>
 *
 * <p>The POST therefore returns immediately with an operation id and the client polls.
 * The pool is deliberately <b>bounded</b>: an unbounded queue would accept work the
 * node can never finish, turning a slow instance into an out-of-memory one. When the
 * queue is full the caller is rejected with a clear, retryable error rather than
 * being silently parked.</p>
 *
 * <p>State is in memory and per process. That is correct for the single-node
 * deployment this is; a multi-node deployment would need shared storage, and that
 * limitation is recorded rather than hidden. A terminal result is retained for a
 * bounded period so a slow client can still collect it.</p>
 */
@Service
public class WorkflowJobRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowJobRunner.class);

    /** Terminal results are kept this long so a late or retried poll still succeeds. */
    private static final Duration RESULT_RETENTION = Duration.ofHours(2);

    private final ThreadPoolExecutor executor;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public record Job(String operationId, String status, LocalDateTime submittedAt,
                      LocalDateTime startedAt, LocalDateTime finishedAt,
                      Object result, String error) {

        public boolean isTerminal() {
            return "COMPLETED".equals(status) || "FAILED".equals(status);
        }
    }

    /**
     * @param poolSize    concurrent operations; each holds a browser and a test run
     * @param queueCapacity how many submissions may wait; beyond this the caller is rejected
     */
    public WorkflowJobRunner(int poolSize, int queueCapacity) {
        this.executor = new ThreadPoolExecutor(
                poolSize, poolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "qalab-workflow-" + r.hashCode());
                    t.setDaemon(true);
                    return t;
                },
                // Do not run silently alongside other work forever: a job that has not
                // started in 10 minutes is dropped so its caller gets a clean failure.
                new ThreadPoolExecutor.AbortPolicy());
        log.info("Workflow job runner: workers={} queueCapacity={} retention={}",
                poolSize, queueCapacity, RESULT_RETENTION);
    }

    /**
     * Queues work and returns its job id.
     *
     * @throws QueueFullException when the backlog is saturated
     */
    public String submit(String operationId, Runnable work) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId is required to track the job");
        }
        Job submitted = new Job(operationId, "QUEUED", LocalDateTime.now(),
                null, null, null, null);
        jobs.put(operationId, submitted);

        try {
            executor.execute(() -> run(operationId, work));
        } catch (RuntimeException e) {
            // Never leave a caller polling a job that will never run.
            jobs.put(operationId, new Job(operationId, "FAILED", submitted.submittedAt(),
                    null, LocalDateTime.now(), null,
                    "The server is at capacity (queue of " + executor.getQueue().size()
                            + " waiting, " + executor.getActiveCount() + " running). Retry shortly."));
            log.warn("Rejected workflow {} — executor saturated", operationId);
            throw new QueueFullException(submitted.submittedAt(), executor.getQueue().size(),
                    executor.getActiveCount());
        }
        return operationId;
    }

    private void run(String operationId, Runnable work) {
        jobs.put(operationId, new Job(operationId, "RUNNING", submittedAt(operationId),
                LocalDateTime.now(), null, null, null));
        try {
            work.run();
            Object result = resultOf(operationId);
            jobs.put(operationId, new Job(operationId, "COMPLETED", submittedAt(operationId),
                    startedAt(operationId), LocalDateTime.now(), result, null));
            log.info("Workflow job {} completed", operationId);
        } catch (Exception e) {
            log.warn("Workflow job {} failed: {}", operationId, e.getMessage(), e);
            jobs.put(operationId, new Job(operationId, "FAILED", submittedAt(operationId),
                    startedAt(operationId), LocalDateTime.now(), null,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /** Publishes the workflow result so the job record can hand it back on the poll. */
    public void complete(String operationId, Object result) {
        Job existing = jobs.get(operationId);
        if (existing == null) {
            return;
        }
        jobs.put(operationId, new Job(operationId, existing.status(), existing.submittedAt(),
                existing.startedAt(), LocalDateTime.now(), result, existing.error()));
    }

    public Job get(String operationId) {
        evictExpired();
        return jobs.get(operationId);
    }

    public int queueDepth() {
        return executor.getQueue().size();
    }

    public int activeCount() {
        return executor.getActiveCount();
    }

    private LocalDateTime submittedAt(String operationId) {
        Job job = jobs.get(operationId);
        return job == null ? LocalDateTime.now() : job.submittedAt();
    }

    private LocalDateTime startedAt(String operationId) {
        Job job = jobs.get(operationId);
        return job == null ? LocalDateTime.now() : job.startedAt();
    }

    private Object resultOf(String operationId) {
        Job job = jobs.get(operationId);
        return job == null ? null : job.result();
    }

    private void evictExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minus(RESULT_RETENTION);
        jobs.entrySet().removeIf(e -> e.getValue().finishedAt() != null
                && e.getValue().finishedAt().isBefore(cutoff));
    }

    /** Thrown when the backlog is saturated; the caller should surface a retryable error. */
    public static class QueueFullException extends RuntimeException {
        private final int queueDepth;
        private final int active;

        public QueueFullException(LocalDateTime submittedAt, int queueDepth, int active) {
            super("Workflow queue is full (" + queueDepth + " waiting, " + active + " running). Retry shortly.");
            this.queueDepth = queueDepth;
            this.active = active;
        }

        public int queueDepth() {
            return queueDepth;
        }

        public int active() {
            return active;
        }
    }
}
