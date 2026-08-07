package com.qalab.qalabai.config;

import com.qalab.qalabai.agent.AgentOrchestrator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrchestratorConfig {

    @Bean
    public CommandLineRunner wireOrchestrator(AgentOrchestrator orchestrator, AgentWebSocketHandler wsHandler) {
        return args -> orchestrator.setStatusListener(wsHandler);
    }
}
