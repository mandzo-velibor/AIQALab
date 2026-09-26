package com.qalab.qalabai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One Playwright test's outcome within a {@link TestExecution}.
 *
 * <p>Exists because a run's status alone ("FAILED") cannot answer the questions the
 * reports need: which tests failed, how long each took, whether it was flaky, and
 * which screenshot and trace belong to <em>that</em> failure. Before this, all of that
 * existed only as text in a truncated stdout blob.</p>
 *
 * <p>Attachment paths are stored as JSON arrays in TEXT columns rather than as a
 * child table: they are only ever read as a complete list per test, never queried
 * across tests, so normalising them would add joins without buying anything.</p>
 */
@Entity
@Table(name = "test_case_result", indexes = {
        @Index(name = "idx_test_case_result_execution", columnList = "execution_id")
})
public class TestCaseResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false, foreignKey = @jakarta.persistence.ForeignKey(
            name = "fk_test_case_result_execution"))
    private TestExecution execution;

    /** Position within the run, so the report can preserve Playwright's own ordering. */
    @Column(name = "ordinal_position", nullable = false)
    private Integer ordinalPosition;

    @Column(name = "spec_file")
    private String specFile;

    @Column(name = "test_title")
    private String testTitle;

    @Column(name = "full_title")
    private String fullTitle;

    @Column(nullable = false)
    private String status;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(nullable = false)
    private Integer retries;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "error_snippet", columnDefinition = "TEXT")
    private String errorSnippet;

    @Column(columnDefinition = "TEXT")
    private String screenshots;

    @Column(columnDefinition = "TEXT")
    private String videos;

    @Column(columnDefinition = "TEXT")
    private String traces;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public TestExecution getExecution() {
        return execution;
    }

    public void setExecution(TestExecution execution) {
        this.execution = execution;
    }

    public Integer getOrdinalPosition() {
        return ordinalPosition;
    }

    public void setOrdinalPosition(Integer ordinalPosition) {
        this.ordinalPosition = ordinalPosition;
    }

    public String getSpecFile() {
        return specFile;
    }

    public void setSpecFile(String specFile) {
        this.specFile = specFile;
    }

    public String getTestTitle() {
        return testTitle;
    }

    public void setTestTitle(String testTitle) {
        this.testTitle = testTitle;
    }

    public String getFullTitle() {
        return fullTitle;
    }

    public void setFullTitle(String fullTitle) {
        this.fullTitle = fullTitle;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Integer getRetries() {
        return retries;
    }

    public void setRetries(Integer retries) {
        this.retries = retries;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getErrorSnippet() {
        return errorSnippet;
    }

    public void setErrorSnippet(String errorSnippet) {
        this.errorSnippet = errorSnippet;
    }

    public String getScreenshots() {
        return screenshots;
    }

    public void setScreenshots(String screenshots) {
        this.screenshots = screenshots;
    }

    public String getVideos() {
        return videos;
    }

    public void setVideos(String videos) {
        this.videos = videos;
    }

    public String getTraces() {
        return traces;
    }

    public void setTraces(String traces) {
        this.traces = traces;
    }
}
