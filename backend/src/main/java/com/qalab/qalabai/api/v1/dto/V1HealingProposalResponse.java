package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.healing.model.HealingProposal;

import java.time.LocalDateTime;
import java.util.List;

public record V1HealingProposalResponse(
        String proposalId,
        Long projectId,
        String runId,
        String testId,
        String testName,
        String classification,
        String originalLocator,
        String recommendedLocator,
        double confidence,
        String confidenceLabel,
        boolean safeToApply,
        String reason,
        String status,
        String reviewAction,
        LocalDateTime reviewedAt,
        List<V1LocatorCandidateDto> alternatives,
        LocalDateTime createdAt,
        String originalLocatorHealth,
        Double originalLocatorStability,
        String recommendedLocatorHealth,
        Double recommendedLocatorStability,
        String recommendedStabilityLevel,
        Double recommendedSemanticScore,
        Double recommendedQualityScore
) {

    public static V1HealingProposalResponse from(HealingProposal p) {
        return new V1HealingProposalResponse(
                p.getProposalId(), p.getProjectId(), p.getRunId(), p.getTestId(),
                p.getTestName(), p.getClassification(), p.getOriginalLocator(),
                p.getRecommendedLocator(),
                p.getConfidence() != null ? p.getConfidence() : 0.0,
                p.getConfidenceLabel(), Boolean.TRUE.equals(p.getSafeToApply()),
                p.getReason(), p.getStatus(), p.getReviewAction(), p.getReviewedAt(),
                alternativesOf(p), p.getCreatedAt(),
                p.getOriginalLocatorHealth(), p.getOriginalLocatorStability(),
                p.getRecommendedLocatorHealth(), p.getRecommendedLocatorStability(),
                p.getRecommendedStabilityLevel(), p.getRecommendedSemanticScore(),
                p.getRecommendedQualityScore());
    }

    private static List<V1LocatorCandidateDto> alternativesOf(HealingProposal p) {
        if (p.getAlternativesJson() == null || p.getAlternativesJson().isBlank()) {
            return List.of();
        }
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var type = mapper.getTypeFactory()
                    .constructCollectionType(List.class, com.qalab.qalabai.healing.model.LocatorCandidate.class);
            List<com.qalab.qalabai.healing.model.LocatorCandidate> list = mapper.readValue(p.getAlternativesJson(), type);
            return V1LocatorCandidateDto.fromAll(list);
        } catch (Exception e) {
            return List.of();
        }
    }
}
