package com.qalab.qalabai.healing.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.ai.gateway.AgentExecutionContext;
import com.qalab.qalabai.ai.gateway.AiGateway;
import com.qalab.qalabai.ai.gateway.AiOperation;
import com.qalab.qalabai.ai.gateway.AiRequest;
import com.qalab.qalabai.ai.gateway.AiResponse;
import com.qalab.qalabai.ai.provider.JsonValidators;
import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.healing.model.DomSnapshot;
import com.qalab.qalabai.healing.model.LocatorCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * AI evaluation of locator candidates, always routed through the {@link AiGateway}
 * so token accounting and budget policy apply exactly like every other AI call.
 * The prompt is compact: original locator, failure, bounded page context and the
 * pre-ranked candidate list — nothing else.
 */
@Component
public class HealingAiEvaluator {

    private static final Logger log = LoggerFactory.getLogger(HealingAiEvaluator.class);

    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;

    public HealingAiEvaluator(AiGateway aiGateway, ObjectMapper objectMapper) {
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.systemPrompt = loadPrompt();
    }

    public CandidateEvaluation evaluate(String originalLocator,
                                        String failureReason,
                                        DomSnapshot snapshot,
                                        List<LocatorCandidate> candidates,
                                        ProjectContext projectContext,
                                        String operationId) {
        String userPrompt = buildUserPrompt(originalLocator, failureReason, snapshot, candidates);

        AiRequest request = AiRequest.builder(AiOperation.HEALING_EVALUATION, systemPrompt, userPrompt)
                .validator(JsonValidators.isJsonObject())
                .build();
        AgentExecutionContext ctx = AgentExecutionContext.builder()
                .projectContext(projectContext)
                .operationId(operationId != null ? operationId : "op-healing")
                .build();

        AiResponse response = aiGateway.complete(request, ctx);
        return parse(response.getContent());
    }

    private String buildUserPrompt(String originalLocator, String failureReason,
                                   DomSnapshot snapshot, List<LocatorCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("Original locator:\n").append(originalLocator != null ? originalLocator : "unknown").append("\n\n");
        sb.append("Failure:\n").append(failureReason != null ? failureReason : "n/a").append("\n\n");
        sb.append("Page context (current URL): ")
                .append(snapshot != null && snapshot.currentUrl() != null ? snapshot.currentUrl() : "n/a").append("\n\n");
        sb.append("Relevant DOM:\n")
                .append(snapshot != null && snapshot.relevantHtml() != null ? snapshot.relevantHtml() : "n/a").append("\n\n");
        sb.append("Pre-ranked candidate locators (deterministic validation):\n");
        if (candidates == null || candidates.isEmpty()) {
            sb.append("- none\n");
        } else {
            for (int i = 0; i < candidates.size(); i++) {
                LocatorCandidate c = candidates.get(i);
                sb.append(i + 1).append(". ").append(c.locator())
                        .append("  [strategy=").append(c.strategy())
                        .append(", score=").append(String.format("%.2f", c.score()))
                        .append(", unique=").append(c.unique())
                        .append(", visible=").append(c.visible())
                        .append(", enabled=").append(c.enabled())
                        .append("]\n");
            }
        }
        sb.append("\nRecommend the best replacement locator. Only choose from the candidate list unless the correct replacement is absent.");
        return sb.toString();
    }

    private CandidateEvaluation parse(String aiResponse) throws RuntimeException {
        try {
            String json = extractJson(aiResponse);
            JsonNode root = objectMapper.readTree(json);
            String recommended = root.path("recommendedLocator").asText("");
            double confidence = root.path("confidence").asDouble(0.5);
            confidence = Math.max(0.0, Math.min(1.0, confidence));
            String reason = root.path("reason").asText("");
            boolean safe = root.path("safeToApply").asBoolean(false);
            List<String> risks = new ArrayList<>();
            JsonNode risksNode = root.path("risks");
            if (risksNode.isArray()) {
                risksNode.forEach(r -> risks.add(r.asText()));
            }
            return new CandidateEvaluation(recommended, confidence, reason, safe, risks);
        } catch (Exception e) {
            log.warn("Failed to parse healing evaluation response: {}", e.getMessage());
            return new CandidateEvaluation("", 0.0, "AI evaluation unparseable: " + e.getMessage(), false, List.of());
        }
    }

    private String extractJson(String response) {
        if (response == null) {
            return "{}";
        }
        String trimmed = response.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }

    private String loadPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/healing-evaluator.md");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load healing evaluator prompt: {}", e.getMessage());
            return "";
        }
    }
}
