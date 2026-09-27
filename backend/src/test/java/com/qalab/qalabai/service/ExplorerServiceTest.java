package com.qalab.qalabai.service;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.ai.gateway.AiGateway;
import com.qalab.qalabai.ai.gateway.AiRequest;
import com.qalab.qalabai.ai.gateway.AiProviderType;
import com.qalab.qalabai.ai.gateway.AiResponse;
import com.qalab.qalabai.cache.AnalysisCache;
import com.qalab.qalabai.dto.analysis.AnalysisResponse;
import com.qalab.qalabai.model.PageAnalysisHistory;
import com.qalab.qalabai.repository.PageAnalysisHistoryRepository;
import com.qalab.qalabai.tool.ToolContext;
import com.qalab.qalabai.tool.browser.BrowserTool;
import com.qalab.qalabai.tool.browser.DomSimplifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code ExplorerService.analyze} is the entry point of the whole product — everything
 * downstream consumes its output — and it had almost no direct coverage. The branches worth
 * testing are the ones that decide whether the page is analysed at all, and whether the
 * result is trustworthy enough to cache: the cache short-circuit, forceRefresh, a browser
 * failure, and the credential rule from B-027.
 */
class ExplorerServiceTest {

    private BrowserTool browserTool;
    private DomSimplifier domSimplifier;
    private AiGateway aiGateway;
    private AnalysisCache cache;
    private PageAnalysisHistoryRepository historyRepository;
    private ExplorerService service;

    private static final String LLM_JSON = """
            {"pageType":"login","summary":"A login form.","confidence":90,
             "forms":[{"name":"login","inputs":["user","pass"]}],
             "buttons":["Sign in"],"navigation":[],"dialogs":[],"tables":[],
             "possibleFlows":[],"riskAreas":[]}
            """;

    @BeforeEach
    void setUp() {
        browserTool = mock(BrowserTool.class);
        domSimplifier = mock(DomSimplifier.class);
        aiGateway = mock(AiGateway.class);
        cache = mock(AnalysisCache.class);
        historyRepository = mock(PageAnalysisHistoryRepository.class);
        when(historyRepository.findByProjectIdAndUrlOrderByVersionDesc(any(), anyString()))
                .thenReturn(java.util.List.of());
        when(domSimplifier.simplify(anyString())).thenAnswer(i -> i.getArgument(0));
        // A real hash, so the cache interactions under test are keyed the way they are in
        // production. A mock returning null here would make every anyString() matcher miss
        // and quietly turn these tests into assertions about nothing.
        when(cache.hashUrl(anyString())).thenReturn("hash-of-url");

        service = new ExplorerService(browserTool, domSimplifier, aiGateway, cache,
                new com.fasterxml.jackson.databind.ObjectMapper(), historyRepository,
                new com.qalab.qalabai.prompt.PromptLibrary());
    }

    private Map<String, Object> browserResult() {
        Map<String, Object> map = new HashMap<>();
        map.put("title", "Login");
        map.put("url", "https://example.test/login");
        map.put("html", "<html><body><form></form></body></html>");
        map.put("accessibilityTree", "Sign in");
        map.put("screenshotPath", "/tmp/shot.png");
        return map;
    }

    private void stubHappyPath() {
        when(browserTool.execute(any(ToolContext.class))).thenReturn(browserResult());
        when(aiGateway.complete(any(), any()))
                .thenReturn(new AiResponse(LLM_JSON, AiProviderType.OPENAI, "m",
                        10, 10, false, java.math.BigDecimal.ZERO, "op-1"));
    }

    @Test
    void analysesThePageAndReturnsTheModelsFindings() {
        stubHappyPath();

        AnalysisResponse analysis = service.analyze("https://example.test/login", false, 1L);

        assertThat(analysis.pageType()).isEqualTo("login");
        assertThat(analysis.confidence()).isEqualTo(90);
        assertThat(analysis.forms()).hasSize(1);
        assertThat(analysis.screenshotPath())
                .as("the path is what gets persisted; the base64 blob would not fit the column")
                .isEqualTo("/tmp/shot.png");
    }

    @Test
    void aCachedAnalysisShortCircuitsTheBrowserAndTheModel() {
        AnalysisResponse cached = new AnalysisResponse("cached", "from cache", 50,
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), "/tmp/old.png");
        when(cache.get(anyString())).thenReturn(cached);

        AnalysisResponse result = service.analyze("https://example.test/login", false, 1L);

        assertThat(result).isSameAs(cached);
        // The whole point of the cache: no browser launch and no provider call. Either one
        // would make a cache hit slower than a miss.
        verify(browserTool, never()).execute(any());
        verify(aiGateway, never()).complete(any(), any());
    }

    @Test
    void forceRefreshBypassesTheCacheAndRepopulatesIt() {
        stubHappyPath();
        when(cache.get(anyString())).thenReturn(null);

        service.analyze("https://example.test/login", true, 1L);

        // Reading the cache is fine; what must not happen is letting a stale entry win over
        // an explicit refresh.
        verify(cache).put(anyString(), any(AnalysisResponse.class), anyString());
        verify(browserTool).execute(any(ToolContext.class));
    }

    @Test
    void aBrowserFailureStopsTheAnalysisBeforeAnyProviderCall() {
        Map<String, Object> failed = new HashMap<>();
        failed.put("error", "net::ERR_CONNECTION_REFUSED");
        when(browserTool.execute(any(ToolContext.class))).thenReturn(failed);

        assertThatThrownBy(() -> service.analyze("https://example.test/down", false, 1L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("ERR_CONNECTION_REFUSED");

        // A dead page must not reach the model: it would spend a call to describe nothing,
        // and the empty answer would be cached as if it were a real finding.
        verify(aiGateway, never()).complete(any(), any());
        verify(cache, never()).put(anyString(), any(AnalysisResponse.class), anyString());
    }

    @Test
    void anUnexpectedToolResultTypeIsRejectedRatherThanCastBlindly() {
        when(browserTool.execute(any(ToolContext.class))).thenReturn("not a map");

        assertThatThrownBy(() -> service.analyze("https://example.test", false, 1L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unexpected result");
    }

    @Test
    void credentialsAreNeverWrittenToTheCache() {
        stubHappyPath();
        when(cache.get(anyString())).thenReturn(null);
        Map<String, Object> postLogin = new HashMap<>();
        postLogin.put("title", "Dashboard");
        postLogin.put("url", "https://example.test/dash");
        postLogin.put("html", "<html>secret area</html>");
        postLogin.put("accessibilityTree", "Welcome");
        postLogin.put("screenshotPath", "/tmp/after.png");
        when(browserTool.login(anyString(), anyString(), anyString())).thenReturn(postLogin);

        service.analyze("https://example.test/login", false, 1L, "alice", "hunter2");

        // B-027: caching the credentials let a later anonymous run read the previous
        // user's password back out and bake it into its own generated tests. The username
        // and password must appear nowhere in what is written to the cache.
        ArgumentCaptor<String> pageContent = ArgumentCaptor.forClass(String.class);
        verify(cache).putPostLoginContent(anyString(), pageContent.capture());
        assertThat(pageContent.getValue())
                .doesNotContain("hunter2")
                .doesNotContain("alice");
    }

    @Test
    void historyIsRecordedWithTheScreenshotPathAndAnIncrementedVersion() {
        stubHappyPath();
        when(cache.get(anyString())).thenReturn(null);
        when(historyRepository.findByProjectIdAndUrlOrderByVersionDesc(anyLong(), anyString()))
                .thenReturn(java.util.List.of());

        service.analyze("https://example.test/login", false, 7L);

        ArgumentCaptor<PageAnalysisHistory> saved =
                ArgumentCaptor.forClass(PageAnalysisHistory.class);
        verify(historyRepository).save(saved.capture());
        assertThat(saved.getValue().getScreenshotReference())
                .as("the reference is a path, so the column cannot overflow")
                .isEqualTo("/tmp/shot.png");
        assertThat(saved.getValue().getVersion()).isEqualTo(1);
    }

    @Test
    void aSecondAnalysisOfTheSameUrlIncrementsTheStoredVersion() {
        stubHappyPath();
        when(cache.get(anyString())).thenReturn(null);
        PageAnalysisHistory previous = new PageAnalysisHistory();
        previous.setVersion(3);
        when(historyRepository.findByProjectIdAndUrlOrderByVersionDesc(anyLong(), anyString()))
                .thenReturn(java.util.List.of(previous));

        service.analyze("https://example.test/login", false, 7L);

        ArgumentCaptor<PageAnalysisHistory> saved =
                ArgumentCaptor.forClass(PageAnalysisHistory.class);
        verify(historyRepository).save(saved.capture());
        assertThat(saved.getValue().getVersion())
                .as("overwriting version 3 in place would lose the history the user can browse")
                .isEqualTo(4);
    }

    @Test
    void aUserInstructionReachesTheModelPrompt() {
        stubHappyPath();
        when(cache.get(anyString())).thenReturn(null);

        service.analyze("https://example.test/login", false, 1L, null, null,
                "focus on the password reset path");

        ArgumentCaptor<AiRequest> sent = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiGateway).complete(sent.capture(), any());
        assertThat(sent.getValue().getUserPrompt())
                .as("the instruction is the user's steering; dropping it silently is a bug "
                        + "that looks like the model ignoring them")
                .contains("focus on the password reset path");
    }
}
