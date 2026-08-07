package com.qalab.qalabai.config;

public interface AgentStatusListener {

    void onAgentStatusChange(String agentName, String status);
}
