package com.didalgo.intellij.mcp;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

public class StartMcpServerActivity implements StartupActivity.DumbAware {
    @Override
    public void runActivity(@NotNull Project project) {
        // Start the server (per-project). It will be stopped when the project disposes.
        project.getService(McpServerService.class).start();
    }
}
