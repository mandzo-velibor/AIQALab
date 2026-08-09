package com.qalab.qalabai.dto.locator;

import java.util.List;

public record LocatorResponse(
        int generated,
        List<LocatorDto> locators,
        String instruction,
        List<String> strategiesUsed
) {}
