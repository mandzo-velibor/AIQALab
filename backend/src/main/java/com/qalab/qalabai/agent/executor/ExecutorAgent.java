package com.qalab.qalabai.agent.executor;

import com.qalab.qalabai.agent.AgentResult;
import com.qalab.qalabai.agent.QaAgent;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.model.TestExecution;
import com.qalab.qalabai.repository.TestExecutionRepository;
import com.qalab.qalabai.tool.ToolContext;
import com.qalab.qalabai.tool.playwright.PlaywrightTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ExecutorAgent implements QaAgent {

    private static final Logger log = LoggerFactory.getLogger(ExecutorAgent.class);

    private final PlaywrightTool playwrightTool;
    private final TestExecutionRepository executionRepository;

    public ExecutorAgent(PlaywrightTool playwrightTool,
                         TestExecutionRepository executionRepository) {
        this.playwrightTool = playwrightTool;
        this.executionRepository = executionRepository;
    }

    @Override
    public String getName() {
        return "Executor";
    }

    @Override
    public AgentResult execute(Task task) {
        log.info("Executor Agent started");

        String testFile = (String) task.getContextValue("testFile");
        Boolean runAll = (Boolean) task.getContextValue("runAll");

        if (testFile == null && (runAll == null || !runAll)) {
            return AgentResult.failure(getName(), "Missing testFile or runAll in task context");
        }

        try {
            log.info("Executing Playwright tests");

            ToolContext context = new ToolContext();
            if (testFile != null) {
                context.put("testFile", testFile);
            }
            if (runAll != null) {
                context.put("runAll", runAll);
            }

            Object rawResult = playwrightTool.execute(context);

            if (rawResult instanceof Map<?, ?> map) {
                String status = (String) map.get("status");
                Long duration = map.get("duration") instanceof Number n ? n.longValue() : 0L;
                String output = (String) map.get("output");
                String error = (String) map.get("error");

                TestExecution execution = new TestExecution();
                execution.setTestFile(testFile != null ? testFile : "all");
                execution.setStatus(status);
                execution.setDuration(duration);
                execution.setConsoleLogs(output);

                if ("FAILED".equals(status) || "ERROR".equals(status)) {
                    execution.setErrorMessage(error != null ? error : extractError(output));
                }

                TestExecution saved = executionRepository.save(execution);
                log.info("Execution saved with id: {}, status: {}", saved.getId(), status);

                AgentResult result = AgentResult.success(getName(), "Execution completed: " + status);
                result.putData("executionId", saved.getId());
                result.putData("status", status);
                result.putData("duration", duration);
                result.putData("output", output);
                return result;
            }

            return AgentResult.failure(getName(), "Unexpected result from PlaywrightTool");

        } catch (Exception e) {
            log.error("Executor Agent failed: {}", e.getMessage(), e);
            return AgentResult.failure(getName(), "Failed: " + e.getMessage());
        }
    }

    private String extractError(String output) {
        if (output == null) return "Unknown error";
        String[] lines = output.split("\n");
        for (String line : lines) {
            if (line.contains("Error:") || line.contains("failed")) {
                return line.length() > 500 ? line.substring(0, 500) : line;
            }
        }
        return output.length() > 500 ? output.substring(0, 500) : output;
    }
}
