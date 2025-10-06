package com.didalgo.intellij.mcp;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Map;

@Service(Service.Level.APP)
public final class McpServerService implements Disposable {
    private static final Logger LOG = Logger.getInstance(McpServerService.class);
    private volatile ConfigurableApplicationContext ctx;
    private volatile int port;

    public McpServerService() {
    }

    public void start() {
        if (ctx != null && ctx.isActive())
            return;

        var pluginClassLoader = getClass().getClassLoader();
        var currentThread = Thread.currentThread();
        var lastClassLoader = currentThread.getContextClassLoader();

        try {
            currentThread.setContextClassLoader(pluginClassLoader);

            var resourceLoader = new DefaultResourceLoader(pluginClassLoader);
            ctx = new SpringApplicationBuilder(resourceLoader, McpServerApplication.class)
                    .properties(Map.of("server.port", 59452))
                    .listeners(onWebServerReady())
                    .run();
        } finally {
            currentThread.setContextClassLoader(lastClassLoader);
        }
        LOG.info("MCP server starting...");
        // TODO: display port
    }

    private ApplicationListener<WebServerInitializedEvent> onWebServerReady() {
        return this::onWebServerReady;
    }

    private void onWebServerReady(WebServerInitializedEvent evt) {
        this.port = evt.getWebServer().getPort();
        Logger.getInstance(McpServerService.class)
                .info("MCP SSE endpoint is at http://127.0.0.1:" + port + "/sse");
    }

    @Override
    public void dispose() {
        if (ctx != null) {
            try {
                ctx.close();
            } catch (Exception ignore) { }
            ctx = null;
        }
    }
}
