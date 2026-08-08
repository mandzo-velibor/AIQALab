package com.qalab.qalabai.ai.gateway;

import org.springframework.stereotype.Component;

/**
 * Placeholder rate limiter: always allows. The infrastructure is in place so a
 * real implementation can be swapped in without touching the gateway.
 */
@Component
public class NoopRateLimiter implements RateLimiter {

    @Override
    public boolean allow(AiProviderType provider) {
        return true;
    }
}
