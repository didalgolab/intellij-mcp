package com.didalgo.intellij.mcp;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service(Service.Level.PROJECT)
public final class McpServerService implements Disposable {
    private static final Logger LOG = Logger.getInstance(McpServerService.class);
    private final Project project;
    private final AtomicInteger port = new AtomicInteger(-1);
    private volatile ConfigurableApplicationContext ctx;

    public McpServerService(@NotNull Project project) {
        this.project = project;
    }

    public synchronized void start() {
        if (ctx != null && ctx.isActive())
            return;

        ctx = new SpringApplicationBuilder(McpServerApplication.class)
                .properties(Map.of("server.port", 0))
                .run();

        LOG.info("MCP server starting...");

        ApplicationManager.getApplication().invokeLater(() ->
                project.getMessageBus().connect(this));
    }

    public int getPort() {
        return port.get();
    }

    @Override
    public synchronized void dispose() {
        if (ctx != null) {
            try {
                ctx.close();
            } catch (Exception ignore) {
            }
            ctx = null;
        }
    }
}
