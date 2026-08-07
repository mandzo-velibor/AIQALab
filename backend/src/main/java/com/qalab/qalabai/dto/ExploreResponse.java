package com.qalab.qalabai.dto;

import java.util.Map;

public class ExploreResponse {

    private String title;
    private String url;
    private String screenshotBase64;
    private long buttonCount;
    private long inputCount;
    private long linkCount;
    private long formCount;
    private Map<String, Object> agentResults;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getScreenshotBase64() {
        return screenshotBase64;
    }

    public void setScreenshotBase64(String screenshotBase64) {
        this.screenshotBase64 = screenshotBase64;
    }

    public long getButtonCount() {
        return buttonCount;
    }

    public void setButtonCount(long buttonCount) {
        this.buttonCount = buttonCount;
    }

    public long getInputCount() {
        return inputCount;
    }

    public void setInputCount(long inputCount) {
        this.inputCount = inputCount;
    }

    public long getLinkCount() {
        return linkCount;
    }

    public void setLinkCount(long linkCount) {
        this.linkCount = linkCount;
    }

    public long getFormCount() {
        return formCount;
    }

    public void setFormCount(long formCount) {
        this.formCount = formCount;
    }

    public Map<String, Object> getAgentResults() {
        return agentResults;
    }

    public void setAgentResults(Map<String, Object> agentResults) {
        this.agentResults = agentResults;
    }
}
