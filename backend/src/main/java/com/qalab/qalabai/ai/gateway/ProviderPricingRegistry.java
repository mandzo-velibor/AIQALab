package com.qalab.qalabai.ai.gateway;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Registry of known per-token pricing. Only providers/models where a real cost
 * is configured are listed; anything unknown returns {@code null} cost.
 */
@Component
public class ProviderPricingRegistry {

    private final Map<String, ProviderPricing> pricing = new HashMap<>();

    public ProviderPricingRegistry() {
        register(AiProviderType.OPENAI, "gpt-4o-mini",
                new BigDecimal("0.00000015"), new BigDecimal("0.00000060"));
        register(AiProviderType.OPENAI, "gpt-4o",
                new BigDecimal("0.00000250"), new BigDecimal("0.00001000"));
        register(AiProviderType.GOOGLE, "gemini-1.5-flash",
                new BigDecimal("0.000000075"), new BigDecimal("0.00000030"));
        register(AiProviderType.OPENCODE, "big-pickle",
                new BigDecimal("0.00000000"), new BigDecimal("0.00000000"));
        register(AiProviderType.AIQALAB, "AIQALAB-managed",
                new BigDecimal("0.00000000"), new BigDecimal("0.00000000"));
        register(AiProviderType.ANTHROPIC, "claude-sonnet-4-5",
                new BigDecimal("0.00000300"), new BigDecimal("0.00001500"));
    }

    private void register(AiProviderType provider, String model,
                          BigDecimal input, BigDecimal output) {
        pricing.put(key(provider, model), new ProviderPricing(provider, model, input, output));
    }

    public ProviderPricing lookup(AiProviderType provider, String model) {
        if (model == null) {
            return null;
        }
        ProviderPricing exact = pricing.get(key(provider, model));
        if (exact != null) {
            return exact;
        }
        String lower = model.toLowerCase();
        for (ProviderPricing p : pricing.values()) {
            if (p.getProvider() == provider && lower.contains(p.getModel())) {
                return p;
            }
        }
        return null;
    }

    /** @return estimated USD cost, or {@code null} when pricing is unknown. */
    public BigDecimal estimateCost(AiProviderType provider, String model, int inputTokens, int outputTokens) {
        ProviderPricing p = lookup(provider, model);
        return p == null ? null : p.estimateCost(inputTokens, outputTokens);
    }

    private String key(AiProviderType provider, String model) {
        return provider.name() + ":" + model;
    }
}
