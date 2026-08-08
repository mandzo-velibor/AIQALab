package com.qalab.qalabai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.agent.locator.LocatorAgent;
import com.qalab.qalabai.cache.AnalysisCache;
import com.qalab.qalabai.dto.analysis.AnalysisResponse;
import com.qalab.qalabai.dto.locator.LocatorDto;
import com.qalab.qalabai.dto.locator.LocatorResponse;
import com.qalab.qalabai.model.LocatorDefinition;
import com.qalab.qalabai.model.LocatorHistory;
import com.qalab.qalabai.repository.LocatorHistoryRepository;
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
    private final LocatorHistoryRepository locatorHistoryRepository;
    private final AnalysisCache analysisCache;
    private final ObjectMapper objectMapper;

    public LocatorService(LocatorAgent locatorAgent,
                          LocatorRepository locatorRepository,
                          LocatorHistoryRepository locatorHistoryRepository,
                          AnalysisCache analysisCache,
                          ObjectMapper objectMapper) {
        this.locatorAgent = locatorAgent;
        this.locatorRepository = locatorRepository;
        this.locatorHistoryRepository = locatorHistoryRepository;
        this.analysisCache = analysisCache;
        this.objectMapper = objectMapper;
    }

    public LocatorResponse generateLocators(String url) {
        return generateLocators(url, null);
    }

    public LocatorResponse generateLocators(String url, Long projectId) {
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

        List<LocatorDefinition> saved = locatorRepository.saveAll(locators);
        log.info("Saved {} locators to database", saved.size());

        List<LocatorDto> dtos = saved.stream()
                .map(this::toDto)
                .toList();

        saveHistory(url, saved, projectId);

        return new LocatorResponse(dtos.size(), dtos);
    }

    private void saveHistory(String url, List<LocatorDefinition> locators, Long projectId) {
        if (projectId == null) {
            return;
        }

        try {
            for (LocatorDefinition locator : locators) {
                LocatorHistory history = new LocatorHistory();
                history.setProjectId(projectId);
                history.setElementName(locator.getElementName());
                history.setLocator(locator.getPreferredLocator());
                history.setStrategy(locator.getStrategy());
                history.setConfidence(locator.getConfidence());
                history.setStatus("ACTIVE");
                locatorHistoryRepository.save(history);
            }
            log.info("Saved {} locator history entries for project {}, URL {}",
                    locators.size(), projectId, url);
        } catch (Exception e) {
            log.warn("Failed to save locator history for project {}, URL {}: {}",
                    projectId, url, e.getMessage());
        }
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
