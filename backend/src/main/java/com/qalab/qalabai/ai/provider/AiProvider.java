package com.qalab.qalabai.ai.provider;

public interface AiProvider {

    String getName();

    String chat(String prompt);

    String chat(String systemPrompt, String userPrompt);
}
