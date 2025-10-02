package com.didalgo.intellij.mcp;

import com.intellij.openapi.diagnostic.Logger;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

import java.util.concurrent.atomic.AtomicInteger;

@SpringBootApplication
public class McpServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(McpServerApplication.class, args);
	}

	@Bean
	public ToolCallbackProvider weatherTools(WeatherService weatherService) {
		return MethodToolCallbackProvider.builder().toolObjects(weatherService).build();
	}

	public record TextInput(String input) {
	}

	@Bean
	public ToolCallback toUpperCase() {
		return FunctionToolCallback.builder("toUpperCase", (TextInput input) -> input.input().toUpperCase())
			.inputType(TextInput.class)
			.description("Put the text to upper case")
			.build();
	}

    @Bean
    public PortsListener portsListener() {
        return new PortsListener(new AtomicInteger(0));
    }

    public static class PortsListener {
        private final AtomicInteger portRef;

        PortsListener(AtomicInteger ref) {
            this.portRef = ref;
        }

        @EventListener
        public void onWebServerReady(WebServerInitializedEvent evt) {
            int p = evt.getWebServer().getPort();
            portRef.set(p);
            Logger.getInstance(McpServerService.class)
                    .info("MCP SSE endpoint is at http://127.0.0.1:" + p + "/sse");
        }
    }
}