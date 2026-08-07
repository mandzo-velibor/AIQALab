package com.qalab.qalabai.agent;

public interface QaAgent {

    String getName();

    AgentResult execute(Task task);
}
