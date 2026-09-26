package com.qalab.qalabai.config;

import com.qalab.qalabai.ai.gateway.*;
import com.qalab.qalabai.ai.opencode.OpenCodeAiProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Registers the internal provider clients used by the AiGateway. Each client
 * is keyed by its {@link AiProviderType}.
 */
@Configuration
@EnableConfigurationProperties(AiGatewayProperties.class)
public class AiGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayConfig.class);

    /**
     * Shared HTTP client for every outbound AI call.
     *
     * <p>Both timeouts are deliberately finite. The default {@code RestTemplate}
     * uses a {@code SimpleClientHttpRequestFactory} with connect/read timeouts of
     * {@code 0}, which means <em>infinite</em>: a provider that accepts the
     * connection and then stalls would never raise, so
     * {@link AiGateway#executeWithRetry} would never see an exception and could
     * never retry or fail over. The read timeout is generous because long
     * generations legitimately take minutes, but it must not be unbounded.
     */
    @Bean
    public RestTemplate aiRestTemplate(
            @Value("${qalab.ai.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${qalab.ai.read-timeout-ms:180000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        log.info("AI RestTemplate configured: connectTimeout={}ms readTimeout={}ms",
                connectTimeoutMs, readTimeoutMs);
        return new RestTemplate(factory);
    }

    @Bean
    public OpenCodeManagedProviderClient openCodeManagedProviderClient(OpenCodeAiProvider openCodeAiProvider) {
        return new OpenCodeManagedProviderClient(openCodeAiProvider);
    }

    @Bean
    public OpenAiCompatProviderClient openAiCompatProviderClient(RestTemplate aiRestTemplate) {
        return new OpenAiCompatProviderClient(AiProviderType.OPENAI, aiRestTemplate);
    }

    @Bean
    public OpenAiCompatProviderClient googleCompatProviderClient(RestTemplate aiRestTemplate) {
        return new OpenAiCompatProviderClient(AiProviderType.GOOGLE, aiRestTemplate);
    }

    @Bean
    public OpenAiCompatProviderClient ollamaCompatProviderClient(RestTemplate aiRestTemplate) {
        return new OpenAiCompatProviderClient(AiProviderType.OLLAMA, aiRestTemplate);
    }

    @Bean
    public AnthropicCompatProviderClient anthropicCompatProviderClient(RestTemplate aiRestTemplate) {
        return new AnthropicCompatProviderClient(aiRestTemplate);
    }

    @Bean
    public List<ProviderClient> providerClients(OpenCodeManagedProviderClient managed,
                                                @Qualifier("openAiCompatProviderClient") OpenAiCompatProviderClient openAi,
                                                @Qualifier("googleCompatProviderClient") OpenAiCompatProviderClient google,
                                                @Qualifier("ollamaCompatProviderClient") OpenAiCompatProviderClient ollama,
                                                AnthropicCompatProviderClient anthropic) {
        return List.of(managed, openAi, google, ollama, anthropic);
    }
}
