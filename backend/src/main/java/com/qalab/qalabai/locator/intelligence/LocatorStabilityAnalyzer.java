package com.qalab.qalabai.locator.intelligence;

import com.qalab.qalabai.locator.intelligence.model.StabilityResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic fragility analysis for locators. Detects positional selectors,
 * absolute XPath, generated/dynamic class names, deep hierarchies and
 * generated-looking ids. Every penalty carries an explicit explanation so the
 * result is explainable end to end.
 */
@Component
public class LocatorStabilityAnalyzer {

    private static final Pattern POSITIONAL = Pattern.compile(
            "nth-child|nth-of-type|:eq\\(|first\\(|last\\(|index\\(");
    private static final Pattern ABSOLUTE_XPATH = Pattern.compile(
            "^xpath=.*/html/|^//html/|/div\\[[0-9]+\\]/");
    private static final Pattern GENERATED_CLASS = Pattern.compile(
            "css-[a-z0-9]+|makeStyles-|jss[0-9]+|MuiButton-root|MuiSvgIcon-root|emotion-|sc-[a-z]+");
    private static final Pattern GENERATED_ID = Pattern.compile("#[a-z]+-[0-9]+");
    private static final Pattern CSS_INNER = Pattern.compile("locator\\(['\"]([^'\"]+)['\"]\\)");
    private static final Pattern CSS_COMBINATOR = Pattern.compile("[>+~]");
    private static final Pattern CSS_WHITESPACE = Pattern.compile("\\s+");

    private static final double MAX_PENALTY = 25.0;

    public StabilityResult analyze(String locator) {
        List<String> reasons = new ArrayList<>();
        double penalty = 0.0;

        if (locator == null || locator.isBlank()) {
            reasons.add("Locator is empty.");
            return new StabilityResult(0.0, StabilityResult.Level.LOW, reasons);
        }

        if (POSITIONAL.matcher(locator).find()) {
            penalty += 5.0;
            reasons.add("Depends on element position (nth-child/index); a DOM structure change invalidates it.");
        }
        if (ABSOLUTE_XPATH.matcher(locator).find()) {
            penalty += 6.0;
            reasons.add("Absolute XPath depends on the exact DOM hierarchy and breaks after layout changes.");
        }
        if (GENERATED_CLASS.matcher(locator).find()) {
            penalty += 4.0;
            reasons.add("Contains a generated/dynamic class (CSS-in-JS or MUI) that changes on rebuild.");
        }
        if (GENERATED_ID.matcher(locator).find()) {
            penalty += 2.5;
            reasons.add("ID looks generated (e.g. #input-17) and may change after refactoring.");
        }
        long chain = deepChainCount(locator);
        if (chain > 2) {
            penalty += 2.0;
            reasons.add("Uses a deep DOM chain (" + chain + " combinators); shallow selectors are preferred.");
        }

        double score = Math.max(0.0, MAX_PENALTY - Math.min(penalty, MAX_PENALTY));
        if (reasons.isEmpty()) {
            reasons.add("No fragile patterns detected.");
        }
        return new StabilityResult(round(score), level(score), reasons);
    }

    private long deepChainCount(String locator) {
        Matcher css = CSS_INNER.matcher(locator);
        if (!css.find()) {
            return 0L;
        }
        String inner = css.group(1);
        long explicit = CSS_COMBINATOR.matcher(inner).results().count();
        long descendants = CSS_WHITESPACE.matcher(CSS_COMBINATOR.matcher(inner).replaceAll(" ")).results().count();
        return explicit + descendants;
    }

    private StabilityResult.Level level(double score) {
        if (score >= 18.0) {
            return StabilityResult.Level.HIGH;
        }
        if (score >= 10.0) {
            return StabilityResult.Level.MEDIUM;
        }
        return StabilityResult.Level.LOW;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
