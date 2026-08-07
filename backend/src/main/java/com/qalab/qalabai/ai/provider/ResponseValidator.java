package com.qalab.qalabai.ai.provider;

@FunctionalInterface
public interface ResponseValidator {

    String validate(String response);
}
