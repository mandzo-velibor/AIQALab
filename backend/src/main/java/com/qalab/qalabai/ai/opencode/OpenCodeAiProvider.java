package com.qalab.qalabai.ai.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qalab.qalabai.ai.gateway.CircuitBreaker;
import com.qalab.qalabai.ai.gateway.ProviderCascadeExhaustedException;
import com.qalab.qalabai.ai.gateway.ProviderResilience;
import com.qalab.qalabai.ai.gateway.TokenEstimator;
import com.qalab.qalabai.ai.provider.ResponseValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Primary
public class OpenCodeAiProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeAiProvider.class);

    private static final String GO_API_URL = "https://opencode.ai/zen/go/v1/messages";
    private static final String ZEN_API_URL = "https://opencode.ai/zen/v1/chat/completions";
    private static final String CHAT_PATH = "/chat/completions";

    /**
     * Hard ceiling on upstream calls for one logical operation, across the whole
     * cascade.
     *
     * <p>This used to be {@code MAX_ATTEMPTS=3} <em>per attempt of a 5-provider
     * cascade</em>, so one operation could issue 15 upstream calls, each asking for
     * {@code max_tokens: 12000}. On a free or rate-limited tier that is both the
     * latency and the "why is it so slow" problem, and none of the rejected responses
     * were visible to the token budget.</p>
     *
     * <p>The default of 4 means: try the first four candidates in order, once each.
     * It is deliberately smaller than the candidate count — a cascade that can always
     * reach its last resort is not a cascade, it is a serial retry storm.</p>
     */
    @Value("${opencode.max-provider-calls:4}")
    private int maxProviderCalls;

    /**
     * How many times a single provider may be tried before it is abandoned. Stops a
     * lone flaky provider from consuming the entire budget on its own.
     */
    @Value("${opencode.max-attempts-per-provider:2}")
    private int maxAttemptsPerProvider;

    /**
     * Fallback ceiling when a caller does not state what the operation needs. The
     * gateway supplies {@link com.qalab.qalabai.ai.gateway.AiOperation#budgetOutputTokens()}
     * in practice; this only guards direct callers of this bean.
     */
    private static final int DEFAULT_MAX_TOKENS = 4000;
    private static final long BASE_DELAY_MS = 1000;
    private static final long MAX_DELAY_MS = 8000;

    @Value("${opencode.go.api-key:}")
    private String goApiKey;

    @Value("${opencode.zen.api-key:}")
    private String zenApiKey;

    @Value("${opencode.go.model:qwen3.7-plus}")
    private String goModel;

    @Value("${opencode.zen.model:space-bunny-free}")
    private String zenModel;

    @Value("${opencode.zen.fallback-model:big-pickle}")
    private String zenFallbackModel;

    @Value("${ollama.api-key:}")
    private String ollamaApiKey;

    @Value("${ollama.base-url:https://ollama.com/v1}")
    private String ollamaBaseUrl;

    @Value("${ollama.model:gpt-oss:20b}")
    private String ollamaModel;

    @Value("${gemini.api-key:}")
    private String geminiApiKey;

    @Value("${gemini.base-url:https://generativelanguage.googleapis.com/v1beta/openai}")
    private String geminiBaseUrl;

    @Value("${gemini.model:gemini-1.5-flash}")
    private String geminiModel;

    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;

    /**
     * Per-model breaker. Optional so a direct construction (tests, tooling) still works;
     * when absent the cascade simply has no breaker and behaves as before.
     */
    private ProviderResilience resilience;

    public OpenCodeAiProvider(RestTemplate aiRestTemplate) {
        this.restTemplate = aiRestTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setResilience(ProviderResilience resilience) {
        this.resilience = resilience;
    }

    @PostConstruct
    public void init() {
        if (goApiKey != null && !goApiKey.isBlank()) {
            log.info("OpenCode Go configured as PRIMARY with model: {}", goModel);
        } else {
            log.warn("OpenCode Go API key not configured.");
        }

        if (zenApiKey != null && !zenApiKey.isBlank()) {
            log.info("OpenCode Zen configured as FALLBACK with model: {} then fallback model: {}",
                    zenModel, zenFallbackModel);
        } else {
            log.warn("OpenCode Zen API key not configured.");
        }

        if (ollamaApiKey != null && !ollamaApiKey.isBlank()) {
            log.info("Ollama configured as FALLBACK with model: {} at {}", ollamaModel, ollamaBaseUrl);
        } else {
            log.warn("Ollama API key not configured.");
        }

        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            log.info("Gemini configured as FALLBACK with model: {} at {}", geminiModel, geminiBaseUrl);
        } else {
            log.warn("Gemini API key not configured.");
        }
    }

    public String getName() {
        return "OpenCode";
    }

    public String chat(String prompt) {
        return chat(null, prompt);
    }

    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, null);
    }

    public String chat(String systemPrompt, String userPrompt, ResponseValidator validator) {
        return chat(systemPrompt, userPrompt, validator, null);
    }

    /**
     * Runs the provider cascade under a hard budget of upstream calls.
     *
     * @param maxOutputTokens ceiling for a single response, or null to use the
     *                        default. Passed per operation so a locator suggestion
     *                        does not request a test suite's worth of tokens.
     */
    public String chat(String systemPrompt, String userPrompt, ResponseValidator validator,
                       Integer maxOutputTokens) {
        return chat(systemPrompt, userPrompt, validator, maxOutputTokens, null);
    }

    /**
     * @param observer notified once per upstream call, successful or not. Passed in
     *                 rather than accumulated on this bean: it is a singleton shared
     *                 by concurrent workflows, so per-request state here would be a
     *                 data race.
     */
    public String chat(String systemPrompt, String userPrompt, ResponseValidator validator,
                       Integer maxOutputTokens, AttemptObserver observer) {
        if ((goApiKey == null || goApiKey.isBlank())
                && (zenApiKey == null || zenApiKey.isBlank())
                && (ollamaApiKey == null || ollamaApiKey.isBlank())
                && (geminiApiKey == null || geminiApiKey.isBlank())) {
            throw new IllegalStateException("No AI provider API key configured. Set OPENCODE_GO_API_KEY, OPENCODE_ZEN_API_KEY, OLLAMA_API_KEY or GEMINI_API_KEY.");
        }

        log.info("AI request sent. Prompt size: system={} chars, user={} chars",
                systemPrompt == null ? 0 : systemPrompt.length(),
                userPrompt == null ? 0 : userPrompt.length());

        int budget = Math.max(1, maxProviderCalls);
        int perProvider = Math.max(1, maxAttemptsPerProvider);
        List<String> failures = new ArrayList<>();
        Map<String, Integer> failuresPerProvider = new LinkedHashMap<>();
        boolean goExhausted = false;
        int calls = 0;
        int attemptNo = 0;

        // A flat loop over the candidates, not a retry loop wrapped around the
        // cascade. The candidates themselves are the retries: a transient failure on
        // one provider is answered by moving to the next, and a provider that keeps
        // failing is capped at `perProvider` tries so it cannot eat the whole budget.
        while (calls < budget) {
            boolean progressed = false;

            for (Candidate candidate : candidates()) {
                if (calls >= budget) {
                    break;
                }
                if (candidate.requiresGo() && goExhausted) {
                    continue;
                }
                // A model whose breaker is open is skipped without any network I/O. This
                // is the case the gateway-level breaker cannot see: it only observes the
                // aggregate outcome of the whole cascade, so a dead model in the middle
                // would keep costing a timeout on every call.
                if (resilience != null) {
                    CircuitBreaker.Refusal refusal = resilience.checkCircuit(candidate.label());
                    if (refusal != null) {
                        log.warn("Skipping {}: {}", candidate.label(), refusal.guidance());
                        failures.add(candidate.label() + ": circuit open, skipped");
                        continue;
                    }
                }
                if (failuresPerProvider.getOrDefault(candidate.label(), 0) >= perProvider) {
                    log.debug("Skipping {}: already failed {} time(s)", candidate.label(), perProvider);
                    continue;
                }

                calls++;
                attemptNo++;
                try {
                    log.info("Attempt {}/{} — {}", attemptNo, budget, candidate.label());
                    String raw = candidate.invoker().invoke(systemPrompt, userPrompt, tokens(maxOutputTokens));
                    String rejectReason = accepts(raw, validator);
                    if (observer != null) {
                        // A rejected response still cost tokens and still cost
                        // wall-clock, so it is reported like any other attempt.
                        observer.onAttempt(new Attempt(candidate.label(), calls,
                                TokenEstimator.estimateInputTokens(systemPrompt, userPrompt),
                                TokenEstimator.estimateOutputTokens(raw),
                                rejectReason == null, rejectReason));
                    }
                    if (rejectReason == null) {
                        if (resilience != null) {
                            resilience.recordSuccess(candidate.label());
                        }
                        log.info("Cascade succeeded on {} after {} upstream call(s)", candidate.label(), calls);
                        return raw;
                    }
                    if (resilience != null) {
                        // A rejected response is still a working provider, so it does not
                        // open the breaker — but the count of consecutive failures must
                        // reset, or one bad response would count towards the threshold.
                        resilience.recordSuccess(candidate.label());
                    }
                    failures.add(candidate.label() + ": rejected, " + rejectReason);
                    failuresPerProvider.merge(candidate.label(), 1, Integer::sum);
                    log.warn("{} response rejected ({} chars): {}", candidate.label(), raw.length(), rejectReason);
                    progressed = true;
                } catch (UsageLimitException e) {
                    // A usage limit is terminal for that provider, not a transient blip:
                    // retrying it, or falling back to another model on the same exhausted
                    // key, would only burn quota that is already spent.
                    if (observer != null) {
                        observer.onAttempt(new Attempt(candidate.label(), calls, 0, 0, false, "usage limit"));
                    }
                    // Only Go's remaining candidates are short-circuited here. Marking a
                    // Zen or Gemini limit as "Go is out" would skip a Go model that still
                    // has quota, which is the opposite of what the flag is for.
                    boolean isGo = "go".equals(e.family());
                    if (isGo) {
                        goExhausted = true;
                    }
                    failures.add(candidate.label() + ": usage limit reached");
                    failuresPerProvider.merge(candidate.label(), 1, Integer::sum);
                    log.warn("{} usage limit reached: {}. {}",
                            e.endpoint(), e.getMessage(),
                            isGo ? "Go is out for this request." : "Skipping to the next provider.");
                    progressed = true;
                } catch (Exception e) {
                    if (observer != null) {
                        observer.onAttempt(new Attempt(candidate.label(), calls, 0, 0, false,
                                e.getClass().getSimpleName() + ": " + e.getMessage()));
                    }
                    if (resilience != null) {
                        resilience.recordFailure(candidate.label());
                    }
                    failures.add(candidate.label() + ": " + e.getMessage());
                    failuresPerProvider.merge(candidate.label(), 1, Integer::sum);
                    log.warn("{} failed: {}", candidate.label(), e.getMessage());
                    progressed = true;
                }
            }

            // Every remaining candidate is either exhausted or out of budget. Retrying
            // the pass would change nothing, so stop rather than spin.
            if (!progressed) {
                break;
            }
        }

        String reason = failures.isEmpty()
                ? "No provider was configured or every provider returned an empty/invalid response."
                : "Provider errors after " + calls + " upstream call(s): " + String.join(" | ", failures);
        log.error("No AI provider returned a usable response. {}", reason);
        throw new ProviderCascadeExhaustedException(reason, calls);
    }

    /** One upstream call's outcome, for cost attribution. */
    public record Attempt(String provider, int sequence, int inputTokens, int outputTokens,
                          boolean accepted, String detail) {
        public int totalTokens() {
            return inputTokens + outputTokens;
        }
    }

    @FunctionalInterface
    public interface AttemptObserver {
        void onAttempt(Attempt attempt);
    }

    /**
     * One candidate in the cascade: a label for logs and failure messages, whether it
     * needs the Go credential, and the actual call.
     */
    private record Candidate(String label, boolean requiresGo, Call invoker) {
        interface Call {
            String invoke(String systemPrompt, String userPrompt, int maxTokens) throws Exception;
        }
    }

    /**
     * Candidates in preference order. Go first because it is the managed default,
     * then the two Zen models, then Gemini, then a local Ollama.
     */
    private List<Candidate> candidates() {
        List<Candidate> list = new ArrayList<>();
        if (goApiKey != null && !goApiKey.isBlank()) {
            list.add(new Candidate("Go(" + goModel + ")", true,
                    call(goEndpoint())));
        }
        if (zenApiKey != null && !zenApiKey.isBlank()) {
            list.add(new Candidate("Zen(" + zenModel + ")", false,
                    call(zenEndpoint(zenModel))));
            list.add(new Candidate("Zen(" + zenFallbackModel + ")", false,
                    call(zenEndpoint(zenFallbackModel))));
        }
        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            list.add(new Candidate("Gemini(" + geminiModel + ")", false,
                    call(new ManagedEndpoint(
                            "Gemini(" + geminiModel + ")", "gemini", chatUrl(geminiBaseUrl), geminiModel,
                            bearer(geminiApiKey), OPENAI_REPLY))));
        }
        if (ollamaApiKey != null && !ollamaApiKey.isBlank()) {
            list.add(new Candidate("Ollama(" + ollamaModel + ")", false,
                    call(new ManagedEndpoint(
                            "Ollama(" + ollamaModel + ")", "ollama", chatUrl(ollamaBaseUrl), ollamaModel,
                            bearer(ollamaApiKey), OPENAI_REPLY))));
        }
        return list;
    }

    private ManagedEndpoint goEndpoint() {
        String key = goApiKey;
        return new ManagedEndpoint("Go(" + goModel + ")", "go", GO_API_URL, goModel,
                headers -> {
                    headers.set("x-api-key", key);
                    headers.set("anthropic-version", "2023-06-01");
                },
                ANTHROPIC_REPLY);
    }

    private ManagedEndpoint zenEndpoint(String model) {
        return new ManagedEndpoint("Zen(" + model + ")", "zen", ZEN_API_URL, model,
                bearer(zenApiKey), OPENAI_REPLY);
    }

    private static AuthStrategy bearer(String key) {
        return headers -> headers.setBearerAuth(key);
    }

    /**
     * Appends the chat path to a configurable base URL. A trailing slash is trimmed rather
     * than concatenated, so a base URL configured with one does not produce "//chat/…",
     * which several gateways reject with a 404 that looks like a bad model.
     */
    private static String chatUrl(String baseUrl) {
        String base = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        return base + CHAT_PATH;
    }

    private Candidate.Call call(ManagedEndpoint endpoint) {
        return (sys, usr, max) -> callChatApi(endpoint, sys, usr, max);
    }

    private int tokens(Integer maxOutputTokens) {
        return maxOutputTokens != null && maxOutputTokens > 0 ? maxOutputTokens : DEFAULT_MAX_TOKENS;
    }

    private String accepts(String result, ResponseValidator validator) {
        if (result == null || result.isBlank()) {
            return "empty response";
        }
        if (validator == null) {
            return null;
        }
        return validator.validate(result);
    }

    /**
     * The one HTTP call every managed provider goes through.
     *
     * <p>Go, Zen, Gemini and Ollama were four near-identical ~50-line methods differing
     * only in URL, model, auth header and where the reply text sits in the JSON. Copying
     * that shape four times is how the request body ended up subtly inconsistent between
     * providers — the sort of drift that only shows up as one provider rejecting a request
     * the others accept. What genuinely differs is now data: an {@link AuthStrategy} and a
     * {@link ResponseExtractor} per endpoint.
     */
    String callChatApi(ManagedEndpoint endpoint, String systemPrompt,
                               String userPrompt, int maxTokens) throws Exception {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", endpoint.model());
        requestBody.put("max_tokens", maxTokens);

        ArrayNode messages = objectMapper.createArrayNode();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode systemMessage = objectMapper.createObjectNode();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            messages.add(systemMessage);
        }
        ObjectNode userMessage = objectMapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", userPrompt);
        messages.add(userMessage);
        requestBody.set("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        endpoint.auth().apply(headers);

        HttpEntity<String> entity =
                new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    endpoint.url(), HttpMethod.POST, entity, String.class);
            String content = endpoint.extractor().extract(objectMapper.readTree(response.getBody()));
            log.info("{} response received, length: {} chars", endpoint.label(), content.length());
            return content;
        } catch (HttpStatusCodeException e) {
            // Usage limits are translated per endpoint so the caller can skip to the next
            // provider for the right reason: a spent quota is not a fault to retry against
            // the same key, and reporting it as one burns the whole cascade.
            if (isUsageLimit(e)) {
                throw new UsageLimitException(endpoint.label(), endpoint.family(),
                        e.getResponseBodyAsString());
            }
            throw e;
        }
    }

    interface AuthStrategy {
        void apply(HttpHeaders headers);
    }

    interface ResponseExtractor {
        String extract(JsonNode responseJson);
    }

    record ManagedEndpoint(String label, String family, String url, String model,
                                   AuthStrategy auth, ResponseExtractor extractor) {
    }

    static final ResponseExtractor ANTHROPIC_REPLY = OpenCodeAiProvider::extractAnthropicContent;
    static final ResponseExtractor OPENAI_REPLY = OpenCodeAiProvider::extractOpenAiContent;

    // Static because they are pure JSON readers with no instance state, which is what
    // lets the endpoint table be built as static constants.
    static String extractAnthropicContent(JsonNode responseJson) {
        StringBuilder content = new StringBuilder();
        JsonNode contentArray = responseJson.path("content");
        if (contentArray.isArray()) {
            for (JsonNode item : contentArray) {
                if ("text".equals(item.path("type").asText())) {
                    content.append(item.path("text").asText());
                }
            }
        }
        return content.toString();
    }

    static String extractOpenAiContent(JsonNode responseJson) {
        JsonNode choices = responseJson.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return "";
        }

        JsonNode message = choices.get(0).path("message");
        JsonNode content = message.path("content");

        if (content.isTextual()) {
            return content.asText();
        }

        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText(""))) {
                    sb.append(part.path("text").asText(""));
                }
            }
            return sb.toString();
        }

        return "";
    }

    private boolean isUsageLimit(HttpStatusCodeException e) {
        if (e.getStatusCode().value() != 429) {
            return false;
        }
        String body = e.getResponseBodyAsString();
        String lower = body == null ? "" : body.toLowerCase();
        return lower.contains("usage");
    }

    private long backoff(int attempt) {
        return Math.min(MAX_DELAY_MS, BASE_DELAY_MS * (1L << (attempt - 1)));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Carries the endpoint that reported the limit. The cascade skips to the next provider,
     * and a message naming only "usage limit" leaves an operator unable to tell which quota
     * was spent without reading the log around the failure.
     */
    static class UsageLimitException extends RuntimeException {
        private final String endpoint;
        private final String family;

        UsageLimitException(String endpoint, String family, String detail) {
            super(endpoint + " reported a usage limit: " + detail);
            this.endpoint = endpoint;
            this.family = family;
        }

        String endpoint() {
            return endpoint;
        }

        String family() {
            return family;
        }
    }
}
