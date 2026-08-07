package com.qalab.qalabai.agent.explorer;

import com.qalab.qalabai.agent.AgentResult;
import com.qalab.qalabai.agent.QaAgent;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.tool.ToolContext;
import com.qalab.qalabai.tool.browser.BrowserTool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ExplorerAgent implements QaAgent {

    private final BrowserTool browserTool;

    public ExplorerAgent(BrowserTool browserTool) {
        this.browserTool = browserTool;
    }

    @Override
    public String getName() {
        return "Explorer";
    }

    @Override
    public AgentResult execute(Task task) {
        String url = task.getUrl();

        if (url == null || url.isBlank()) {
            return AgentResult.failure(getName(), "URL is required");
        }

        ToolContext context = new ToolContext().put("url", url);
        Object rawResult = browserTool.execute(context);

        if (rawResult instanceof Map<?, ?> map) {
            if (map.containsKey("error")) {
                return AgentResult.failure(getName(), "Browser error: " + map.get("error"));
            }

            AgentResult result = AgentResult.success(getName(), "Exploration complete");
            result.putData("title", map.get("title"));
            result.putData("url", map.get("url"));
            result.putData("screenshotPath", map.get("screenshotPath"));
            result.putData("screenshotBase64", map.get("screenshotBase64"));
            result.putData("buttonCount", map.get("buttonCount"));
            result.putData("inputCount", map.get("inputCount"));
            result.putData("linkCount", map.get("linkCount"));
            result.putData("formCount", map.get("formCount"));

            task.putContext("pageUrl", url);
            return result;
        }

        return AgentResult.failure(getName(), "Unexpected result from BrowserTool");
    }
}
