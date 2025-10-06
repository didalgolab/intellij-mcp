package com.didalgo.intellij.mcp;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class StartupHook implements AppLifecycleListener {
    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        ApplicationManager.getApplication().getService(McpServerService.class).start();
    }
}