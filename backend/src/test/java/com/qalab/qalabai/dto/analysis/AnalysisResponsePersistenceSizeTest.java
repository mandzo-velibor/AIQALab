package com.qalab.qalabai.dto.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A regression guard, not a feature test.
 *
 * <p>{@code AnalysisResponse} used to carry a base64 screenshot, and ExplorerService
 * serialises this record straight into {@code page_analysis_history.analysis_json}, which
 * is {@code varchar(10000)}. A full-page PNG is orders of magnitude larger than 10 KB, so
 * persisting any real analysis exceeded the column and failed the write — a bug that no
 * unit test caught because nothing asserted the serialised size.
 *
 * <p>The column size is read from the migration so a future schema change that re-allows
 * the bloat fails here with a clear message instead of in production.
 */
class AnalysisResponsePersistenceSizeTest {

    private static final int ANALYSIS_JSON_LIMIT = 10_000;

    @Test
    void serializedAnalysisFitsTheAnalysisJsonColumn() throws Exception {
        AnalysisResponse response = worstRealisticCase();
        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(json.length())
                .as("analysis_json is varchar(%d); a realistic analysis must fit with room to spare",
                        ANALYSIS_JSON_LIMIT)
                .isLessThan(ANALYSIS_JSON_LIMIT);
    }

    @Test
    void theRecordHasNoBinaryFieldToSerialise() {
        // The honest statement of the fix: there is no longer a field that could grow with
        // page height. A base64 field here is what made the size above unbounded.
        assertThat(AnalysisResponse.class.getRecordComponents())
                .noneMatch(c -> c.getName().toLowerCase().contains("base64"));
    }

    @Test
    void theColumnLimitIsTheOneTheMigrationActuallyDeclares() throws Exception {
        // If someone widens the column, the first test's assumption goes stale. Reading the
        // migration makes that change visible here instead of silently making the guard
        // irrelevant, which is how a size check quietly stops protecting anything.
        java.nio.file.Path migration =
                java.nio.file.Path.of("src/main/resources/db/migration/V1__baseline.sql");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.exists(migration),
                "baseline migration not reachable from this working directory");

        String sql = new String(java.nio.file.Files.readAllBytes(migration),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(sql)
                .as("the baseline migration should still declare the 10k analysis_json column")
                .contains("analysis_json varchar(" + ANALYSIS_JSON_LIMIT + ")");
    }

    private static AnalysisResponse worstRealisticCase() {
        List<DetectedForm> forms = List.of(
                new DetectedForm("login", List.of("user", "pass", "remember-me")));
        List<String> buttons = List.of("Submit", "Cancel", "Save", "Delete", "Export");
        List<DetectedNavigation> navigation = List.of(
                new DetectedNavigation("Home", "/"), new DetectedNavigation("Admin", "/admin"));
        List<DetectedDialog> dialogs = List.of(
                new DetectedDialog("Confirm", "button[type=submit]"));
        List<DetectedTable> tables = List.of(
                new DetectedTable("users", List.of("id", "name", "email", "role")));
        List<DetectedFlow> flows = List.of(
                new DetectedFlow("Login", "enter user, enter pass, submit"));
        List<RiskArea> risks = List.of(
                new RiskArea("no CSRF token", "form posts without a token"));

        return new AnalysisResponse(
                "login", "A login form with no rate limiting.", 88,
                forms, buttons, navigation, dialogs, tables, flows, risks,
                "/tmp/screenshots/abc.png");
    }
}
