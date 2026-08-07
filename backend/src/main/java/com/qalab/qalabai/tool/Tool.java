package com.qalab.qalabai.tool;

public interface Tool {

    String getName();

    Object execute(ToolContext context);
}
