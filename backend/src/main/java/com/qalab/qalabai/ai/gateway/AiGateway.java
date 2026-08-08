package com.qalab.qalabai.ai.gateway;

import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.api.ErrorCode;
import com.qalab.qalabai.config.AiGatewayProperties;
import com.qalab.qalabai.model.Account;
import com.qalab.qalabai.service.AccountService;
import com.qalab.qalabai.service.TokenBudgetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single entry point for all AI calls. Agents and services never talk to a
 * provider directly — everything flows through {@link #complete}.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Resolve provider config (request > workflow context > defaults).</li>
 *   <li>Resolve credentials: MANAGED (server keys), BYOK (CredentialStore) or LOCAL.</li>
 *   <li>Pre-flight budget check: MANAGED + hard stop &rarr; {@code AI_BUDGET_EXCEEDED}, no provider call.</li>
 *   <li>Rate limiting (placeholder).</li>
 *   <li>Execute the call with retries (not for invalid credentials / budget / invalid request).</li>
 *   <li>Record usage for every mode; only MANAGED consumes the allowance.</li>
 *   <li>Estimate cost when pricing is known, else null.</li>
 * </ul>
 */
@Service
public class AiGateway {

    private static final Logger log = LoggerFactory.getLogger(AiGateway.class);

    private final AiGatewayProperties properties;
    private final ManagedCredentials managedCredentials;
    private final CredentialStore credentialStore;
    private final AccountService accountService;
    private final TokenBudgetService budgetService;
    private final UsageService usageService;
    private final RateLimiter rateLimiter;
    private final ProviderPricingRegistry pricingRegistry;
    private final Map<AiProviderType, ProviderClient> clients;

    public AiGateway(AiGatewayProperties properties,
                     ManagedCredentials managedCredentials,
                     CredentialStore credentialStore,
                     AccountService accountService,
                     TokenBudgetService budgetService,
                     UsageService usageService,
                     RateLimiter rateLimiter,
                     ProviderPricingRegistry pricingRegistry,
                     List<ProviderClient> providerClients) {
        this.properties = properties;
        this.managedCredentials = managedCredentials;
        this.credentialStore = credentialStore;
        this.accountService = accountService;
        this.budgetService = budgetService;
        this.usageService = usageService;
        this.rateLimiter = rateLimiter;
        this.pricingRegistry = pricingRegistry;
        this.clients = providerClients.stream()
                .collect(Collectors.toMap(ProviderClient::type, Function.identity()));
    }

    /**
     * Executes an AI call. May throw {@link ApiException} with:
     * {@code AI_BUDGET_EXCEEDED}, {@code AI_PROVIDER_NOT_CONFIGURED},
     * {@code AI_CREDENTIAL_INVALID}, {@code AI_RATE_LIMITED},
     * {@code AI_PROVIDER_UNAVAILABLE} or {@code AI_OPERATION_NOT_ALLOWED}.
     */
    public AiResponse complete(AiRequest request, AgentExecutionContext context) {
        String operationId = context != null && context.getOperationId() != null
                ? context.getOperationId() : "op-" + UUID.randomUUID();

        AiProviderConfig config = resolveConfig(request, context);
        AiProviderType provider = config.getProvider();
        AiCredentialMode mode = config.getCredentialMode();

        Account account = accountService.defaultAccount();
        TokenBudget budget = budgetService.currentBudget();

        // Propagate the (shared, never-reset) budget into the workflow context.
        if (context != null) {
            context.setTokenBudget(budget);
        }

        // HARD STOP: no provider call when the managed allowance is exhausted
        // and the account uses the HARD policy.
        if (mode == AiCredentialMode.MANAGED && budget.isHardStop()) {
            log.warn("AI budget exceeded for account {}. used={}/limit={} policy={}",
                    account.getId(), budget.getUsed(), budget.getLimit(), budget.getPolicy());
            throw ApiException.aiBudgetExceeded(
                    "Monthly AI budget exhausted. Used " + budget.getUsed()
                            + " of " + budget.getLimit() + " tokens. "
                            + "Connect your own provider (BYOK) or wait for reset.",
                    operationId);
        }

        // SOFT STOP: allow the call but flag the workflow context so orchestrators
        // can degrade gracefully instead of failing.
        if (mode == AiCredentialMode.MANAGED && budget.isSoftExceeded()) {
            log.warn("AI budget soft-exceeded for account {}. used={}/limit={} policy={}",
                    account.getId(), budget.getUsed(), budget.getLimit(), budget.getPolicy());
            if (context != null) {
                context.setBudgetSoftExceeded(true);
            }
        }

        if (!rateLimiter.allow(provider)) {
            throw ApiException.aiRateLimited("AI rate limit reached for provider " + provider, operationId);
        }

        ProviderClient client = resolveClient(provider);
        if (client == null) {
            throw ApiException.aiProviderNotConfigured(
                    "AI provider " + provider + " is not supported.", operationId);
        }

        String apiKey = resolveApiKey(provider, mode, operationId);
        String model = resolveModel(config, provider);
        String baseUrl = resolveBaseUrl(provider, mode);

        ProviderCallResult result = executeWithRetry(
                new ProviderCallRequest(request.getSystemPrompt(), request.getUserPrompt(),
                        model, apiKey, baseUrl, request.getMaxOutputTokens(), request.getValidator()),
                client, mode, operationId);

        int input = result.getInputTokens();
        int output = result.getOutputTokens();
        boolean estimated = result.isEstimated();
        if (estimated) {
            input = TokenEstimator.estimateInputTokens(request.getSystemPrompt(), request.getUserPrompt());
            output = TokenEstimator.estimateOutputTokens(result.getContent());
        }

        java.math.BigDecimal cost = pricingRegistry.estimateCost(provider, model, input, output);

        Long projectId = context != null && context.getProjectContext() != null
                ? context.getProjectContext().getDatabaseId() : null;
        usageService.recordUsage(account.getId(), projectId, operationId, request.getOperation(),
                provider, result.getModelUsed() != null ? result.getModelUsed() : model,
                mode, input, output, estimated, cost);

        log.info("AI call done: op={} provider={} mode={} tokens={} estimated={} cost={}",
                request.getOperation(), provider, mode, input + output, estimated, cost);

        return new AiResponse(result.getContent(), provider,
                result.getModelUsed() != null ? result.getModelUsed() : model,
                input, output, estimated, cost, operationId);
    }

    private AiProviderConfig resolveConfig(AiRequest request, AgentExecutionContext context) {
        if (context != null && context.getProviderConfig() != null) {
            return context.getProviderConfig();
        }
        if (request.getProvider() != null || request.getModel() != null) {
            return new AiProviderConfig(
                    request.getProvider() != null ? request.getProvider() : properties.getDefaultProvider(),
                    request.getModel(),
                    request.getCredentialMode() != null ? request.getCredentialMode() : properties.getDefaultCredentialMode());
        }
        return new AiProviderConfig(properties.getDefaultProvider(), null, properties.getDefaultCredentialMode());
    }

    private ProviderClient resolveClient(AiProviderType provider) {
        return clients.get(provider);
    }

    private String resolveApiKey(AiProviderType provider, AiCredentialMode mode, String operationId) {
        switch (mode) {
            case MANAGED:
                return managedCredentials.keyFor(provider).orElse(null);
            case BYOK:
                return credentialStore.get(provider)
                        .orElseThrow(() -> ApiException.aiCredentialInvalid(
                                "No credentials configured for provider " + provider
                                        + ". Connect them in Settings → AI Providers first.",
                                operationId));
            case LOCAL:
            default:
                return null;
        }
    }

    private String resolveModel(AiProviderConfig config, AiProviderType provider) {
        if (config.getModel() != null && !config.getModel().isBlank()) {
            return config.getModel();
        }
        AiGatewayProperties.ProviderEndpoint endpoint = properties.endpoint(provider);
        if (endpoint != null && endpoint.getModel() != null && !endpoint.getModel().isBlank()) {
            return endpoint.getModel();
        }
        return null;
    }

    private String resolveBaseUrl(AiProviderType provider, AiCredentialMode mode) {
        if (mode == AiCredentialMode.LOCAL) {
            return "http://localhost:11434/v1";
        }
        AiGatewayProperties.ProviderEndpoint endpoint = properties.endpoint(provider);
        if (endpoint != null && endpoint.getBaseUrl() != null && !endpoint.getBaseUrl().isBlank()) {
            return endpoint.getBaseUrl();
        }
        return null;
    }

    private ProviderCallResult executeWithRetry(ProviderCallRequest callRequest,
                                                ProviderClient client,
                                                AiCredentialMode mode,
                                                String operationId) {
        int maxRetries = properties.getMaxRetries();
        for (int attempt = 0; ; attempt++) {
            try {
                return client.call(callRequest);
            } catch (OpenAiCompatProviderClient.ProviderHttpException e) {
                classifyHttpError(e, mode, operationId);
                if (attempt >= maxRetries) {
                    throw ApiException.aiProviderUnavailable(
                            "AI provider " + client.type() + " unavailable: " + e.getMessage(), operationId);
                }
                sleep(properties.getRetryBackoffMs() * (attempt + 1));
            } catch (Exception e) {
                if (attempt >= maxRetries) {
                    throw ApiException.aiProviderUnavailable(
                            "AI provider " + client.type() + " failed: " + e.getMessage(), operationId, e);
                }
                log.warn("AI provider {} attempt {}/{} failed: {}", client.type(), attempt + 1, maxRetries + 1, e.getMessage());
                sleep(properties.getRetryBackoffMs() * (attempt + 1));
            }
        }
    }

    private void classifyHttpError(OpenAiCompatProviderClient.ProviderHttpException e,
                                   AiCredentialMode mode, String operationId) {
        int status = e.getStatusCode();
        if (status == 401 || status == 403) {
            if (mode == AiCredentialMode.BYOK) {
                throw ApiException.aiCredentialInvalid(
                        "Provider rejected the credential (HTTP " + status + "). "
                                + "Check your API key in Settings → AI Providers.", operationId);
            }
            throw ApiException.aiCredentialInvalid(
                    "Provider rejected the managed credential (HTTP " + status + ").", operationId);
        }
        if (status == 429) {
            throw ApiException.aiRateLimited("AI provider rate limit reached (HTTP 429).", operationId);
        }
        if (status == 400 || status == 404 || status == 422) {
            throw ApiException.invalidRequest("AI provider rejected the request: " + e.getMessage());
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Exposed for the pre-flight usage endpoints. */
    public TokenBudget currentBudget() {
        return budgetService.currentBudget();
    }

    public List<ProviderStatus> providerStatus() {
        return List.of(
                new ProviderStatus(AiProviderType.AIQALAB, managedCredentials.isManaged(AiProviderType.AIQALAB)),
                new ProviderStatus(AiProviderType.OPENAI, managedCredentials.isManaged(AiProviderType.OPENAI) || credentialStore.has(AiProviderType.OPENAI)),
                new ProviderStatus(AiProviderType.ANTHROPIC, managedCredentials.isManaged(AiProviderType.ANTHROPIC) || credentialStore.has(AiProviderType.ANTHROPIC)),
                new ProviderStatus(AiProviderType.GOOGLE, managedCredentials.isManaged(AiProviderType.GOOGLE) || credentialStore.has(AiProviderType.GOOGLE)),
                new ProviderStatus(AiProviderType.OPENCODE, managedCredentials.isManaged(AiProviderType.OPENCODE)),
                new ProviderStatus(AiProviderType.OLLAMA, true));
    }

    /** Public provider status (no credentials). */
    public static class ProviderStatus {
        private final AiProviderType provider;
        private final boolean configured;

        public ProviderStatus(AiProviderType provider, boolean configured) {
            this.provider = provider;
            this.configured = configured;
        }

        public AiProviderType getProvider() {
            return provider;
        }

        public boolean isConfigured() {
            return configured;
        }
    }

    public Map<String, Object> usageBreakdown() {
        return usageService.breakdownByOperation(accountService.defaultAccount().getId());
    }

    public static String code() {
        return ErrorCode.AI_PROVIDER_UNAVAILABLE;
    }
}
