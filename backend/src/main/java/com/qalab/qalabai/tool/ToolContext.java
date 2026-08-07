package com.qalab.qalabai.tool;

import java.util.HashMap;
import java.util.Map;

public class ToolContext {

    private final Map<String, Object> params = new HashMap<>();

    public ToolContext() {
    }

    public ToolContext put(String key, Object value) {
        params.put(key, value);
        return this;
    }

    public Object get(String key) {
        return params.get(key);
    }

    public String getString(String key) {
        Object val = params.get(key);
        return val != null ? val.toString() : null;
    }

    public Map<String, Object> getParams() {
        return params;
    }
}
