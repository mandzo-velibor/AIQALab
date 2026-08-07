package com.qalab.qalabai.agent;

import java.util.HashMap;
import java.util.Map;

public class AgentResult {

    private String agentName;
    private boolean success;
    private String message;
    private Map<String, Object> data = new HashMap<>();

    public AgentResult() {
    }

    public AgentResult(String agentName, boolean success, String message) {
        this.agentName = agentName;
        this.success = success;
        this.message = message;
    }

    public static AgentResult success(String agentName, String message) {
        return new AgentResult(agentName, true, message);
    }

    public static AgentResult failure(String agentName, String message) {
        return new AgentResult(agentName, false, message);
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    public void putData(String key, Object value) {
        this.data.put(key, value);
    }
}
