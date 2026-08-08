package com.qalab.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Minimal Java SDK for the QALabAI Core API.
 *
 * <p>Methods mirror the REST endpoints: {@link #generate(String)},
 * {@link #plan(String)}, {@link #explore(String)}, {@link #executeAll()},
 * {@link #execute(String)}, {@link #reports()}, {@link #report(long)},
 * {@link #intent(String)} and budget policy access. Every method returns the
 * raw API payload as a Jackson {@link JsonNode} plus the HTTP status.</p>
 *
 * <p>Configure the target via the constructor. All requests are synchronous
 * and time out after 2 minutes (long workflows may take a while).</p>
 *
 * <pre>{@code
 * QalabClient qalab = new QalabClient("http://localhost:8080")
 *         .projectId("the-internet-tests")
 *         .baseUrl("https://the-internet.herokuapp.com/login")
 *         .workspacePath("/tmp/qa");
 * JsonNode tests = qalab.generate("https://the-internet.herokuapp.com/login");
 * }</pre>
 */
public final class QalabClient {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final String apiUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private String projectId = "qalab-sdk";
    private String name;
    private String baseUrl;
    private String framework = "PLAYWRIGHT";
    private String language = "TYPESCRIPT";
    private String workspacePath;
    private Long databaseId;

    public QalabClient(String apiUrl) {
        this(apiUrl, HttpClient.newHttpClient(), new ObjectMapper());
    }

    public QalabClient(String apiUrl, HttpClient http, ObjectMapper mapper) {
        this.apiUrl = apiUrl.replaceAll("/+$", "");
        this.http = http;
        this.mapper = mapper;
    }

    // --- fluent configuration -------------------------------------------------

    public QalabClient projectId(String projectId) {
        this.projectId = projectId;
        return this;
    }

    public QalabClient name(String name) {
        this.name = name;
        return this;
    }

    public QalabClient baseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        return this;
    }

    public QalabClient workspacePath(String workspacePath) {
        this.workspacePath = workspacePath;
        return this;
    }

    public QalabClient databaseId(Long databaseId) {
        this.databaseId = databaseId;
        return this;
    }

    // --- operations -----------------------------------------------------------

    /** Detects the user intent for a natural-language prompt (no side effects). */
    public ApiResult detectIntent(String prompt) {
        return post("/api/v1/intent", body().put("prompt", prompt).toString());
    }

    /** Detects the intent and dispatches to the matching workflow operation. */
    public ApiResult intent(String prompt) {
        return post("/api/v1/intent/run", body().put("prompt", prompt).put("url", baseUrl).toString());
    }

    /** Generates Playwright tests for a page. */
    public ApiResult generate(String url) {
        return post("/api/v1/tests", body().put("url", url).toString());
    }

    /** Generates a test plan for a page. */
    public ApiResult plan(String url) {
        return post("/api/v1/test-plan", body().put("url", url).toString());
    }

    /** Explores and analyzes a page. */
    public ApiResult explore(String url) {
        return post("/api/v1/explore", body().put("url", url).toString());
    }

    /** Runs every generated test in the configured project/workspace. */
    public ApiResult executeAll() {
        return post("/api/v1/run", body().put("runAll", true).toString());
    }

    /** Runs a single test by its database id. */
    public ApiResult execute(String testId) {
        return post("/api/v1/run", body().put("testId", testId).toString());
    }

    /** Lists all execution reports. */
    public ApiResult reports() {
        return get("/api/v1/reports");
    }

    /** Fetches a single execution report. */
    public ApiResult report(long executionId) {
        return get("/api/v1/reports/" + executionId);
    }

    /** Shows the current budget enforcement policy and usage. */
    public ApiResult budgetPolicy() {
        return get("/api/v1/account/budget-policy");
    }

    /** Updates the budget enforcement policy: HARD, SOFT or NONE. */
    public ApiResult updateBudgetPolicy(String policy) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("policy", policy);
        return patch("/api/v1/account/budget-policy", payload.toString());
    }

    /**
     * Runs the self-healing pipeline on a failed execution and, when the
     * failure is locator-related, produces a human-reviewable proposal.
     */
    public ApiResult analyzeHealing(long executionId) {
        return post("/api/v1/healing/propose", body().put("executionId", executionId).toString());
    }

    /** Fetches a single healing proposal by id. */
    public ApiResult healingProposal(String proposalId) {
        return get("/api/v1/healing/" + proposalId);
    }

    /** Lists proposals produced for a run (executionId). */
    public ApiResult proposalsByRun(String runId) {
        return get("/api/v1/healing?runId=" + runId);
    }

    /** Lists the healing proposal history of a project. */
    public ApiResult healingHistory(long projectId) {
        return get("/api/v1/projects/" + projectId + "/healing");
    }

    /** Marks a proposal as accepted (approved for later application). */
    public ApiResult acceptProposal(String proposalId) {
        return post("/api/v1/healing/" + proposalId + "/accept", "{}");
    }

    /** Marks a proposal as rejected. */
    public ApiResult rejectProposal(String proposalId) {
        return post("/api/v1/healing/" + proposalId + "/reject", "{}");
    }

    /** The operation id of the last response, when present. */
    public Optional<String> operationId(ApiResult result) {
        return Optional.ofNullable(result.operationId());
    }

    // --- internals ------------------------------------------------------------

    private ObjectNode body() {
        ObjectNode project = mapper.createObjectNode();
        project.put("projectId", projectId);
        if (name != null) project.put("name", name);
        if (baseUrl != null) project.put("baseUrl", baseUrl);
        project.put("framework", framework);
        project.put("language", language);
        if (workspacePath != null) project.put("workspacePath", workspacePath);
        if (databaseId != null) project.put("databaseId", databaseId);

        ObjectNode body = mapper.createObjectNode();
        body.set("project", project);
        return body;
    }

    private ApiResult get(String path) {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).GET().timeout(TIMEOUT).build();
        return send(request);
    }

    private ApiResult post(String path, String json) {
        return send(request(path, "POST", json));
    }

    private ApiResult patch(String path, String json) {
        return send(request(path, "PATCH", json));
    }

    private HttpRequest request(String path, String method, String json) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .timeout(TIMEOUT);
        return method.equals("POST")
                ? builder.POST(HttpRequest.BodyPublishers.ofString(json)).build()
                : builder.method("PATCH", HttpRequest.BodyPublishers.ofString(json)).build();
    }

    private URI uri(String path) {
        return URI.create(apiUrl + path);
    }

    private ApiResult send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body() != null ? response.body() : "";
            String operationId = null;
            try {
                JsonNode node = mapper.readTree(body);
                JsonNode op = node.get("operationId");
                if (op != null && op.isTextual()) {
                    operationId = op.asText();
                }
            } catch (Exception ignore) {
                // non-JSON response
            }
            return new ApiResult(response.statusCode(), body, operationId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QalabSdkException("Interrupted while calling " + request.uri(), e);
        } catch (Exception e) {
            throw new QalabSdkException("Call to " + request.uri() + " failed: " + e.getMessage(), e);
        }
    }
}
