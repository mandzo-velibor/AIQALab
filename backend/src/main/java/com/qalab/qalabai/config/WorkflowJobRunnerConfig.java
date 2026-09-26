package com.qalab.qalabai.config;

import com.qalab.qalabai.service.WorkflowJobRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sizing for the workflow job pool.
 *
 * <p>Each worker runs a headless browser and a test suite, so the pool is small by
 * default. A generous queue absorbs a burst without letting the node accept work it
 * can never finish. Both are configurable because the right values depend entirely
 * on the host: a 1-vCPU instance and a 16-core box want very different numbers.</p>
 */
@Configuration
public class WorkflowJobRunnerConfig {

    @Bean
    public WorkflowJobRunner workflowJobRunner(
            @Value("${qalab.workflow.workers:2}") int workers,
            @Value("${qalab.workflow.queue-capacity:20}") int queueCapacity) {
        return new WorkflowJobRunner(Math.max(1, workers), Math.max(1, queueCapacity));
    }
}
