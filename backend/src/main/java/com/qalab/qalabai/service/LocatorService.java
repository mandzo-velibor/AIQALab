package com.qalab.qalabai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.agent.locator.LocatorAgent;
import com.qalab.qalabai.cache.AnalysisCache;
import com.qalab.qalabai.dto.analysis.AnalysisResponse;
import com.qalab.qalabai.dto.locator.LocatorDto;
import com.qalab.qalabai.dto.locator.LocatorResponse;
import com.qalab.qalabai.model.LocatorDefinition;
import com.qalab.qalabai.repository.LocatorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class LocatorService {

    private static final Logger log = LoggerFactory.getLogger(LocatorService.class);

    private final LocatorAgent locatorAgent;
    private final LocatorRepository locatorRepository;
    private final AnalysisCache analysisCache;
    private final ObjectMapper objectMapper;

    public LocatorService(LocatorAgent locatorAgent,
                          LocatorRepository locatorRepository,
                          AnalysisCache analysisCache,
                          ObjectMapper objectMapper) {
        this.locatorAgent = locatorAgent;
        this.locatorRepository = locatorRepository;
        this.analysisCache = analysisCache;
        this.objectMapper = objectMapper;
    }

    public LocatorResponse generateLocators(String url) {
        log.info("Generating locators for URL: {}", url);

        AnalysisResponse analysis = analysisCache.getByUrl(url);
        if (analysis == null) {
            throw new RuntimeException("No analysis found for URL: " + url + ". Please analyze the page first.");
        }

        String analysisJson;
        try {
            analysisJson = objectMapper.writeValueAsString(analysis);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize analysis: " + e.getMessage(), e);
        }

        Task task = new Task(UUID.randomUUID().toString(), "GENERATE_LOCATORS", url);
        task.putContext("pageUrl", url);
        task.putContext("pageAnalysisJson", analysisJson);

        var result = locatorAgent.execute(task);

        if (!result.isSuccess()) {
            throw new RuntimeException("Locator generation failed: " + result.getMessage());
        }

        @SuppressWarnings("unchecked")
        List<LocatorDefinition> locators = (List<LocatorDefinition>) result.getData().get("locators");

        List<LocatorDto> dtos = locators.stream()
                .map(this::toDto)
                .toList();

        return new LocatorResponse(dtos.size(), dtos);
    }

    public List<LocatorDto> getLocatorsForUrl(String pageUrl) {
        return locatorRepository.findByPageUrl(pageUrl).stream()
                .map(this::toDto)
                .toList();
    }

    private LocatorDto toDto(LocatorDefinition entity) {
        List<String> fallbacks = entity.getFallbackLocators() != null && !entity.getFallbackLocators().isBlank()
                ? Arrays.asList(entity.getFallbackLocators().split(","))
                : List.of();

        return new LocatorDto(
                entity.getId(),
                entity.getElementName(),
                entity.getElementType(),
                entity.getPreferredLocator(),
                fallbacks,
                entity.getStrategy(),
                entity.getConfidence(),
                entity.getReason()
        );
    }
}
