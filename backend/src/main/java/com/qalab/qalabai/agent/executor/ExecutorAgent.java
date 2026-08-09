package com.qalab.qalabai.agent.executor;

import com.qalab.qalabai.agent.AgentResult;
import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.agent.QaAgent;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.service.workspace.WorkspaceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ExecutorAgent implements QaAgent {

    private static final Logger log = LoggerFactory.getLogger(ExecutorAgent.class);

    private final WorkspaceProvider workspaceProvider;

    public ExecutorAgent(WorkspaceProvider workspaceProvider) {
        this.workspaceProvider = workspaceProvider;
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
        String testType = (String) task.getContextValue("testType");
        String instruction = (String) task.getContextValue("instruction");
        ProjectContext project = (ProjectContext) task.getContextValue("projectContext");

        if (project == null) {
            return AgentResult.failure(getName(), "Missing projectContext in task context");
        }

        if (testFile == null && (runAll == null || !runAll)) {
            return AgentResult.failure(getName(), "Missing testFile or runAll in task context");
        }

        try {
            log.info("Executing Playwright tests via WorkspaceProvider (testType={})", testType);
            Map<String, Object> result = workspaceProvider.execute(project, testFile, Boolean.TRUE.equals(runAll), testType);

            String status = (String) result.get("status");
            Long duration = result.get("duration") instanceof Number n ? n.longValue() : 0L;
            String output = (String) result.get("output");
            String error = (String) result.get("error");

            if ("FAILED".equals(status) || "ERROR".equals(status)) {
                error = error != null ? error : extractError(output);
            }

            AgentResult agentResult = AgentResult.success(getName(), "Execution completed: " + status);
            agentResult.putData("status", status);
            agentResult.putData("duration", duration);
            agentResult.putData("output", output);
            if (testType != null && !testType.isBlank()) {
                agentResult.putData("testTypeApplied", testType);
            }
            if (instruction != null && !instruction.isBlank()) {
                agentResult.putData("instruction", instruction);
            }
            if (error != null) {
                agentResult.putData("error", error);
            }
            return agentResult;

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
