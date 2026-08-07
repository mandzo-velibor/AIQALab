package com.qalab.qalabai.agent;

import com.qalab.qalabai.config.AgentStatusListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AgentOrchestrator {

    private final List<QaAgent> agents = new ArrayList<>();
    private AgentStatusListener statusListener;

    public AgentOrchestrator(List<QaAgent> agents) {
        this.agents.addAll(agents);
    }

    public void setStatusListener(AgentStatusListener listener) {
        this.statusListener = listener;
    }

    public List<AgentResult> executeAll(Task task) {
        List<AgentResult> results = new ArrayList<>();

        for (QaAgent agent : agents) {
            notifyStatus(agent.getName(), "RUNNING");

            AgentResult result = agent.execute(task);
            results.add(result);

            notifyStatus(agent.getName(), result.isSuccess() ? "COMPLETED" : "FAILED");

            task.putContext(agent.getName() + "_result", result);
        }

        return results;
    }

    public AgentResult executeSingle(String agentName, Task task) {
        for (QaAgent agent : agents) {
            if (agent.getName().equalsIgnoreCase(agentName)) {
                notifyStatus(agent.getName(), "RUNNING");

                AgentResult result = agent.execute(task);

                notifyStatus(agent.getName(), result.isSuccess() ? "COMPLETED" : "FAILED");
                return result;
            }
        }
        return AgentResult.failure("Orchestrator", "Agent not found: " + agentName);
    }

    private void notifyStatus(String agentName, String status) {
        if (statusListener != null) {
            statusListener.onAgentStatusChange(agentName, status);
        }
    }
}
