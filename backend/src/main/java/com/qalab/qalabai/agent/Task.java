package com.qalab.qalabai.agent;

import java.util.HashMap;
import java.util.Map;

public class Task {

    private String id;
    private String type;
    private String url;
    private Map<String, Object> context = new HashMap<>();

    public Task() {
    }

    public Task(String id, String type, String url) {
        this.id = id;
        this.type = type;
        this.url = url;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public void setContext(Map<String, Object> context) {
        this.context = context;
    }

    public void putContext(String key, Object value) {
        this.context.put(key, value);
    }

    public Object getContextValue(String key) {
        return this.context.get(key);
    }
}
