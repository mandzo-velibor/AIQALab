package com.qalab.qalabai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.agent.AgentResult;
import com.qalab.qalabai.agent.locator.LocatorAgent;
import com.qalab.qalabai.cache.AnalysisCache;
import com.qalab.qalabai.dto.analysis.AnalysisResponse;
import com.qalab.qalabai.dto.locator.LocatorResponse;
import com.qalab.qalabai.model.LocatorDefinition;
import com.qalab.qalabai.model.LocatorHistory;
import com.qalab.qalabai.repository.LocatorHistoryRepository;
import com.qalab.qalabai.repository.LocatorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locators are what make generated tests survive a UI change, so two things matter here
 * beyond "it returns something": they must be persisted, and the history must record what
 * was chosen — the history is the evidence the healing agent later reasons over.
 */
class LocatorServiceTest {

    private LocatorAgent locatorAgent;
    private LocatorRepository locatorRepository;
    private LocatorHistoryRepository locatorHistoryRepository;
    private AnalysisCache analysisCache;
    private LocatorService service;

    @BeforeEach
    void setUp() {
        locatorAgent = mock(LocatorAgent.class);
        locatorRepository = mock(LocatorRepository.class);
        locatorHistoryRepository = mock(LocatorHistoryRepository.class);
        analysisCache = mock(AnalysisCache.class);
        service = new LocatorService(locatorAgent, locatorRepository,
                locatorHistoryRepository, analysisCache, new ObjectMapper());
    }

    private static AnalysisResponse analysedPage() {
        return new AnalysisResponse("login", "A login form.", 80,
                List.of(), List.of("Sign in"), List.of(), List.of(), List.of(),
                List.of(), List.of(), "/tmp/shot.png");
    }

    private static LocatorDefinition locator(String element, String preferred, String strategy) {
        LocatorDefinition d = new LocatorDefinition();
        d.setElementName(element);
        d.setPreferredLocator(preferred);
        d.setStrategy(strategy);
        d.setConfidence(88);
        d.setReason("label is stable across renders");
        return d;
    }

    private void stubAgent(List<LocatorDefinition> locators) {
        AgentResult result = AgentResult.success("Locator", "done");
        result.getData().put("locators", locators);
        when(locatorAgent.execute(any())).thenReturn(result);
        when(locatorRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void refusesToGenerateAgainstAPageThatWasNeverAnalysed() {
        when(analysisCache.getByUrl(anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.generateLocators("https://example.test", 1L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No analysis found");

        verify(locatorAgent, never()).execute(any());
        verify(locatorRepository, never()).saveAll(any());
    }

    @Test
    void persistsTheGeneratedLocatorsAndReportsWhatWasUsed() {
        when(analysisCache.getByUrl(anyString())).thenReturn(analysedPage());
        stubAgent(List.of(
                locator("email", "getByLabel('Email')", "getByLabel"),
                locator("password", "getByLabel('Password')", "getByLabel"),
                locator("submit", "getByRole('button', {name:'Sign in'})", "getByRole")));

        LocatorResponse response = service.generateLocators("https://example.test", 1L);

        verify(locatorRepository).saveAll(any());
        assertThat(response.generated()).isEqualTo(3);
        assertThat(response.strategiesUsed())
                .as("the UI shows which strategies were used, so they must be distinct "
                        + "and sorted rather than one entry per locator")
                .containsExactly("getByLabel", "getByRole");
    }

    @Test
    void recordsLocatorHistorySoHealingHasEvidenceToWorkFrom() {
        when(analysisCache.getByUrl(anyString())).thenReturn(analysedPage());
        stubAgent(List.of(locator("email", "getByLabel('Email')", "getByLabel")));

        service.generateLocators("https://example.test", 42L);

        ArgumentCaptor<LocatorHistory> saved = ArgumentCaptor.forClass(LocatorHistory.class);
        verify(locatorHistoryRepository).save(saved.capture());
        assertThat(saved.getValue().getElementName()).isEqualTo("email");
        assertThat(saved.getValue().getLocator()).isEqualTo("getByLabel('Email')");
        assertThat(saved.getValue().getProjectId()).isEqualTo(42L);
    }

    @Test
    void noProjectMeansNoHistoryRatherThanRowsWithANullProject() {
        when(analysisCache.getByUrl(anyString())).thenReturn(analysedPage());
        stubAgent(List.of(locator("email", "getByLabel('Email')", "getByLabel")));

        service.generateLocators("https://example.test", null);

        // History rows scoped to no project are unattributable and would later be offered
        // as evidence for an unrelated project.
        verify(locatorHistoryRepository, never()).save(any());
    }

    @Test
    void anAgentFailureIsReportedAndNothingIsPersisted() {
        when(analysisCache.getByUrl(anyString())).thenReturn(analysedPage());
        when(locatorAgent.execute(any()))
                .thenReturn(AgentResult.failure("Locator", "provider returned no JSON"));

        assertThatThrownBy(() -> service.generateLocators("https://example.test", 1L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("provider returned no JSON");

        verify(locatorRepository, never()).saveAll(any());
    }

    @Test
    void anEmptyResultIsAnEmptyResponseNotAFabricatedLocator() {
        when(analysisCache.getByUrl(anyString())).thenReturn(analysedPage());
        stubAgent(List.of());

        LocatorResponse response = service.generateLocators("https://example.test", 1L);

        assertThat(response.generated())
                .as("inventing a locator would put a selector in a test that does not exist")
                .isZero();
        assertThat(response.strategiesUsed()).isEmpty();
    }
}
