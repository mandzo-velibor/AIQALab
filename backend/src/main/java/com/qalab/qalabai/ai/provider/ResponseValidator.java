package com.qalab.qalabai.ai.provider;

@FunctionalInterface
public interface ResponseValidator {

    boolean isValid(String response);
}
