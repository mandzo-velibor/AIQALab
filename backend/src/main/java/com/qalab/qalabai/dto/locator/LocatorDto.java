package com.qalab.qalabai.dto.locator;

import java.util.List;

public record LocatorDto(
        Long id,
        String elementName,
        String elementType,
        String preferredLocator,
        List<String> fallbackLocators,
        String strategy,
        Integer confidence,
        String reason
) {}
