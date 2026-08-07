package com.qalab.qalabai.dto.analysis;

import java.util.List;

public record AnalysisResponse(
        String pageType,
        String summary,
        int confidence,
        List<DetectedForm> forms,
        List<String> buttons,
        List<DetectedNavigation> navigation,
        List<DetectedDialog> dialogs,
        List<DetectedTable> tables,
        List<DetectedFlow> possibleFlows,
        List<RiskArea> riskAreas,
        String screenshotBase64
) {
    public AnalysisResponse {
        forms = forms != null ? List.copyOf(forms) : List.of();
        buttons = buttons != null ? List.copyOf(buttons) : List.of();
        navigation = navigation != null ? List.copyOf(navigation) : List.of();
        dialogs = dialogs != null ? List.copyOf(dialogs) : List.of();
        tables = tables != null ? List.copyOf(tables) : List.of();
        possibleFlows = possibleFlows != null ? List.copyOf(possibleFlows) : List.of();
        riskAreas = riskAreas != null ? List.copyOf(riskAreas) : List.of();
    }
}
