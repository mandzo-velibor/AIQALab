package com.qalab.qalabai.config;

import com.qalab.qalabai.ai.gateway.*;
import com.qalab.qalabai.ai.opencode.OpenCodeAiProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Registers the internal provider clients used by the AiGateway. Each client
 * is keyed by its {@link AiProviderType}.
 */
@Configuration
@EnableConfigurationProperties(AiGatewayProperties.class)
public class AiGatewayConfig {

    @Bean
    public OpenCodeManagedProviderClient openCodeManagedProviderClient(OpenCodeAiProvider openCodeAiProvider) {
        return new OpenCodeManagedProviderClient(openCodeAiProvider);
    }

    @Bean
    public OpenAiCompatProviderClient openAiCompatProviderClient() {
        return new OpenAiCompatProviderClient(AiProviderType.OPENAI);
    }

    @Bean
    public OpenAiCompatProviderClient googleCompatProviderClient() {
        return new OpenAiCompatProviderClient(AiProviderType.GOOGLE);
    }

    @Bean
    public OpenAiCompatProviderClient ollamaCompatProviderClient() {
        return new OpenAiCompatProviderClient(AiProviderType.OLLAMA);
    }

    @Bean
    public AnthropicCompatProviderClient anthropicCompatProviderClient() {
        return new AnthropicCompatProviderClient();
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
