package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.healing.model.LocatorCandidate;

import java.util.List;

public record V1LocatorCandidateDto(
        String locator,
        String strategy,
        double score,
        boolean unique,
        boolean visible,
        boolean enabled,
        int matchedElementCount,
        String reason
) {

    public static V1LocatorCandidateDto from(LocatorCandidate c) {
        return new V1LocatorCandidateDto(
                c.locator(), c.strategy().name(), c.score(), c.unique(),
                c.visible(), c.enabled(), c.matchedElementCount(), c.reason());
    }

    public static List<V1LocatorCandidateDto> fromAll(List<LocatorCandidate> candidates) {
        return candidates.stream().map(V1LocatorCandidateDto::from).toList();
    }
}
