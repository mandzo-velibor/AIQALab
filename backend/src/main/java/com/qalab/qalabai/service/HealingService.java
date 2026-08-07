package com.qalab.qalabai.service;

import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.agent.healing.SelfHealingAgent;
import com.qalab.qalabai.model.FailureAnalysis;
import com.qalab.qalabai.model.HealingSuggestion;
import com.qalab.qalabai.model.Project;
import com.qalab.qalabai.repository.FailureAnalysisRepository;
import com.qalab.qalabai.repository.HealingSuggestionRepository;
import com.qalab.qalabai.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class HealingService {

    private static final Logger log = LoggerFactory.getLogger(HealingService.class);

    private final SelfHealingAgent selfHealingAgent;
    private final FailureAnalysisRepository failureAnalysisRepository;
    private final HealingSuggestionRepository healingSuggestionRepository;
    private final ProjectRepository projectRepository;

    public HealingService(SelfHealingAgent selfHealingAgent,
                          FailureAnalysisRepository failureAnalysisRepository,
                          HealingSuggestionRepository healingSuggestionRepository,
                          ProjectRepository projectRepository) {
        this.selfHealingAgent = selfHealingAgent;
        this.failureAnalysisRepository = failureAnalysisRepository;
        this.healingSuggestionRepository = healingSuggestionRepository;
        this.projectRepository = projectRepository;
    }

    public HealingSuggestion generateHealingSuggestion(Long executionId) {
        log.info("Generating healing suggestion for execution {}", executionId);

        FailureAnalysis analysis = failureAnalysisRepository.findByExecutionId(executionId)
                .orElseThrow(() -> new RuntimeException("Failure analysis not found for execution: " + executionId));

        Project project = projectRepository.findById(analysis.getProjectId())
                .orElseThrow(() -> new RuntimeException("Project not found: " + analysis.getProjectId()));

        Task task = new Task(UUID.randomUUID().toString(), "GENERATE_HEALING", project.getBaseUrl());
        task.putContext("failureAnalysisId", analysis.getId());
        task.putContext("projectId", project.getId());
        task.putContext("baseUrl", project.getBaseUrl());

        var result = selfHealingAgent.execute(task);

        if (!result.isSuccess()) {
            throw new RuntimeException("Healing generation failed: " + result.getMessage());
        }

        Long suggestionId = (Long) result.getData().get("suggestionId");
        return healingSuggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new RuntimeException("Suggestion not found: " + suggestionId));
    }

    public HealingSuggestion approveSuggestion(Long suggestionId, String approvedBy) {
        log.info("Approving healing suggestion {}", suggestionId);

        HealingSuggestion suggestion = healingSuggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new RuntimeException("Suggestion not found: " + suggestionId));

        suggestion.setStatus("APPROVED");
        suggestion.setApprovedBy(approvedBy);
        suggestion.setApprovedAt(java.time.LocalDateTime.now());

        return healingSuggestionRepository.save(suggestion);
    }

    public HealingSuggestion rejectSuggestion(Long suggestionId, String rejectedBy) {
        log.info("Rejecting healing suggestion {}", suggestionId);

        HealingSuggestion suggestion = healingSuggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new RuntimeException("Suggestion not found: " + suggestionId));

        suggestion.setStatus("REJECTED");
        suggestion.setApprovedBy(rejectedBy);
        suggestion.setApprovedAt(java.time.LocalDateTime.now());

        return healingSuggestionRepository.save(suggestion);
    }
}
