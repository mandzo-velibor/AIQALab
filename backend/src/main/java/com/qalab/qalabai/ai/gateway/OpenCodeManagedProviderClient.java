package com.qalab.qalabai.ai.gateway;

import com.qalab.qalabai.ai.opencode.OpenCodeAiProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapts the existing OpenCode managed provider (Go → Zen → Gemini → Ollama
 * fallback chain) to the {@link ProviderClient} contract. This is the default
 * AIQALAB managed path — it reuses the fallback logic without duplicating it.
 *
 * <p>One call to this client can cost several upstream HTTP requests, because the
 * delegate cascades. The reported token counts are therefore the <em>sum across every
 * attempt</em> and {@code attempts} says how many there were. Reporting only the
 * winning response would understate the cost of a failing run by exactly the number of
 * rejected responses — and a free tier looks affordable right up until it does not.</p>
 */
public class OpenCodeManagedProviderClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeManagedProviderClient.class);

    private final OpenCodeAiProvider delegate;

    public OpenCodeManagedProviderClient(OpenCodeAiProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public AiProviderType type() {
        return AiProviderType.AIQALAB;
    }

    @Override
    public ProviderCallResult call(ProviderCallRequest request) {
        // Collected per invocation rather than on the delegate, which is a singleton
        // shared across concurrent workflows.
        List<OpenCodeAiProvider.Attempt> attempts = new ArrayList<>();

        String content = delegate.chat(request.getSystemPrompt(), request.getUserPrompt(),
                request.getValidator(), request.getMaxOutputTokens(), attempts::add);

        int input = 0;
        int output = 0;
        for (OpenCodeAiProvider.Attempt attempt : attempts) {
            input += attempt.inputTokens();
            output += attempt.outputTokens();
        }

        // A cascade that answered on the first try still owes the prompt estimate for
        // that one call, even if the observer was somehow not invoked.
        if (attempts.isEmpty()) {
            input = TokenEstimator.estimateInputTokens(request.getSystemPrompt(), request.getUserPrompt());
            output = TokenEstimator.estimateOutputTokens(content);
        }

        int rejected = 0;
        for (OpenCodeAiProvider.Attempt attempt : attempts) {
            if (!attempt.accepted()) {
                rejected++;
            }
        }
        if (rejected > 0) {
            log.warn("OpenCode cascade used {} upstream call(s) before succeeding; {} response(s) "
                            + "were rejected and still cost tokens",
                    attempts.size(), rejected);
        }

        return new ProviderCallResult(content, input, output, true, "AIQALAB-managed", attempts.size());
    }
}
