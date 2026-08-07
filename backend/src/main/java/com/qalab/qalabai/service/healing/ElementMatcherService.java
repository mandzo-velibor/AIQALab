package com.qalab.qalabai.service.healing;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministically searches a live page for candidate elements that could
 * replace a broken locator. Candidates are ranked using LocatorSimilarityService
 * against the original locator text and the element's role/name.
 */
@Service
public class ElementMatcherService {

    private static final Logger log = LoggerFactory.getLogger(ElementMatcherService.class);

    private final LocatorSimilarityService similarityService;

    public ElementMatcherService(LocatorSimilarityService similarityService) {
        this.similarityService = similarityService;
    }

    public record Candidate(String role, String name, String locator, double score) {
    }

    public List<Candidate> findCandidates(String url, String brokenLocator, String elementName) {
        List<Candidate> candidates = new ArrayList<>();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true)
            );
            Page page = browser.newPage();
            page.navigate(url);
            page.waitForLoadState();

            List<String[]> roleNamePairs = collectRoleNamePairs(page);
            for (String[] pair : roleNamePairs) {
                double score = rank(brokenLocator, elementName, pair[0], pair[1]);
                if (score > 0.2) {
                    String locator = toLocator(pair[0], pair[1]);
                    candidates.add(new Candidate(pair[0], pair[1], locator, score));
                }
            }

            candidates.sort((a, b) -> Double.compare(b.score(), a.score()));
            browser.close();
        } catch (Exception e) {
            log.error("ElementMatcherService failed for {}: {}", url, e.getMessage());
        }

        return candidates.stream().limit(10).toList();
    }

    private List<String[]> collectRoleNamePairs(Page page) {
        List<String[]> pairs = new ArrayList<>();
        for (AriaRole role : new AriaRole[]{
                AriaRole.BUTTON, AriaRole.LINK, AriaRole.TEXTBOX,
                AriaRole.HEADING, AriaRole.CHECKBOX, AriaRole.RADIO,
                AriaRole.TAB, AriaRole.MENUITEM, AriaRole.LISTITEM
        }) {
            var locator = page.getByRole(role);
            int count = (int) locator.count();
            for (int i = 0; i < count; i++) {
                try {
                    String name = locator.nth(i).getAttribute("aria-label");
                    if (name == null || name.isBlank()) {
                        name = locator.nth(i).innerText();
                    }
                    if (name == null || name.isBlank()) {
                        name = locator.nth(i).getAttribute("value");
                    }
                    if (name != null && !name.isBlank() && name.length() < 200) {
                        pairs.add(new String[]{role.toString().toLowerCase(), name.trim()});
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return pairs;
    }

    private double rank(String brokenLocator, String elementName, String role, String name) {
        double score = 0.0;
        if (elementName != null && !elementName.isBlank()) {
            score += 0.5 * similarityService.similarity(elementName, name);
        }
        if (brokenLocator != null && !brokenLocator.isBlank()) {
            score += 0.5 * similarityService.similarity(brokenLocator, name);
        }
        return score;
    }

    private String toLocator(String role, String name) {
        return String.format("getByRole(\"%s\",{name:\"%s\"})", role, name);
    }
}
