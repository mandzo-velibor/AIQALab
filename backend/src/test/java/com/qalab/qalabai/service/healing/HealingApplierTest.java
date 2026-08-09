package com.qalab.qalabai.service.healing;

import com.qalab.qalabai.model.GeneratedTest;
import com.qalab.qalabai.model.HealingSuggestion;
import com.qalab.qalabai.repository.GeneratedTestRepository;
import com.qalab.qalabai.repository.HealingSuggestionRepository;
import com.qalab.qalabai.repository.LocatorHistoryRepository;
import com.qalab.qalabai.service.workspace.TestWorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealingApplierTest {

    private HealingSuggestionRepository suggestionRepository;
    private LocatorHistoryRepository locatorHistoryRepository;
    private GeneratedTestRepository generatedTestRepository;
    private TestWorkspaceService testWorkspaceService;
    private HealingApplier applier;

    @BeforeEach
    void setUp() {
        suggestionRepository = mock(HealingSuggestionRepository.class);
        locatorHistoryRepository = mock(LocatorHistoryRepository.class);
        generatedTestRepository = mock(GeneratedTestRepository.class);
        testWorkspaceService = mock(TestWorkspaceService.class);
        applier = new HealingApplier(suggestionRepository, locatorHistoryRepository,
                generatedTestRepository, testWorkspaceService);
    }

    @Test
    void appliesSuggestionAndRewritesTestSource() {
        HealingSuggestion suggestion = new HealingSuggestion();
        suggestion.setId(1L);
        suggestion.setProjectId(7L);
        suggestion.setElementName("Login Button");
        suggestion.setOldLocator("getByRole('button', { name: 'Log in' })");
        suggestion.setNewLocator("getByRole('button', { name: 'Sign in' })");
        suggestion.setStatus("APPROVED");

        when(suggestionRepository.findById(1L)).thenReturn(Optional.of(suggestion));
        when(locatorHistoryRepository.findByProjectIdAndElementNameAndStatus(7L, "Login Button", "ACTIVE"))
                .thenReturn(Optional.empty());

        GeneratedTest test = new GeneratedTest();
        test.setId(3L);
        test.setProjectId(7L);
        test.setTestCode("await page.getByRole('button', { name: 'Log in' }).click();\n");
        when(generatedTestRepository.findByProjectId(7L)).thenReturn(List.of(test));

        applier.apply(1L, "test");

        assertEquals("await page.getByRole('button', { name: 'Sign in' }).click();\n", test.getTestCode());
        verify(generatedTestRepository).save(test);
        verify(testWorkspaceService).writeTestFiles(7L, List.of(test));
    }

    @Test
    void stripsPagePrefixFromOldLocatorWhenMatchingTestSource() {
        HealingSuggestion suggestion = new HealingSuggestion();
        suggestion.setId(2L);
        suggestion.setProjectId(7L);
        suggestion.setElementName("Login Button");
        suggestion.setOldLocator("page.getByRole('button', { name: 'Log in' })");
        suggestion.setNewLocator("getByRole('button', { name: 'Sign in' })");
        suggestion.setStatus("APPROVED");

        when(suggestionRepository.findById(2L)).thenReturn(Optional.of(suggestion));
        when(locatorHistoryRepository.findByProjectIdAndElementNameAndStatus(7L, "Login Button", "ACTIVE"))
                .thenReturn(Optional.empty());

        GeneratedTest test = new GeneratedTest();
        test.setId(4L);
        test.setProjectId(7L);
        test.setTestCode("await page.getByRole('button', { name: 'Log in' }).click();\n");
        when(generatedTestRepository.findByProjectId(7L)).thenReturn(List.of(test));

        applier.apply(2L, "test");

        assertEquals("await page.getByRole('button', { name: 'Sign in' }).click();\n", test.getTestCode());
        verify(generatedTestRepository).save(test);
        verify(testWorkspaceService).writeTestFiles(7L, List.of(test));
    }

    @Test
    void doesNotRewriteWhenOldLocatorAbsentFromTestSource() {
        HealingSuggestion suggestion = new HealingSuggestion();
        suggestion.setId(3L);
        suggestion.setProjectId(7L);
        suggestion.setElementName("Login Button");
        suggestion.setOldLocator("getByRole('button', { name: 'Log in' })");
        suggestion.setNewLocator("getByRole('button', { name: 'Sign in' })");
        suggestion.setStatus("APPROVED");

        when(suggestionRepository.findById(3L)).thenReturn(Optional.of(suggestion));
        when(locatorHistoryRepository.findByProjectIdAndElementNameAndStatus(7L, "Login Button", "ACTIVE"))
                .thenReturn(Optional.empty());

        GeneratedTest test = new GeneratedTest();
        test.setId(5L);
        test.setProjectId(7L);
        test.setTestCode("await page.getByLabel('Username').fill('user');\n");
        when(generatedTestRepository.findByProjectId(7L)).thenReturn(List.of(test));

        applier.apply(3L, "test");

        assertEquals("await page.getByLabel('Username').fill('user');\n", test.getTestCode());
        verify(generatedTestRepository, never()).save(any(GeneratedTest.class));
        verify(testWorkspaceService, never()).writeTestFiles(any(), any());
        assertEquals("APPLIED", suggestion.getStatus());
    }

    @Test
    void updatesPageObjectCodeAsWell() {
        HealingSuggestion suggestion = new HealingSuggestion();
        suggestion.setId(4L);
        suggestion.setProjectId(7L);
        suggestion.setElementName("Login Button");
        suggestion.setOldLocator("getByRole('button', { name: 'Log in' })");
        suggestion.setNewLocator("getByRole('button', { name: 'Sign in' })");
        suggestion.setStatus("APPROVED");

        when(suggestionRepository.findById(4L)).thenReturn(Optional.of(suggestion));
        when(locatorHistoryRepository.findByProjectIdAndElementNameAndStatus(7L, "Login Button", "ACTIVE"))
                .thenReturn(Optional.empty());

        GeneratedTest test = new GeneratedTest();
        test.setId(6L);
        test.setProjectId(7L);
        test.setTestCode("await page.getByRole('button', { name: 'Log in' }).click();\n");
        test.setPageObjectCode("this.loginButton = page.getByRole('button', { name: 'Log in' });\n");
        when(generatedTestRepository.findByProjectId(7L)).thenReturn(List.of(test));

        applier.apply(4L, "test");

        assertFalse(test.getTestCode().contains("Log in"));
        assertFalse(test.getPageObjectCode().contains("Log in"));
        assertTrue(test.getPageObjectCode().contains("Sign in"));
        verify(generatedTestRepository).save(test);
    }
}
