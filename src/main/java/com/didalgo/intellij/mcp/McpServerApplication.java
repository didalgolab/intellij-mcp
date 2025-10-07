package com.didalgo.intellij.mcp;

import com.didalgo.intellij.mcp.SymbolSourceLookupTool.SymbolLookupInput;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jackson.JsonMixinModuleEntries;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@SpringBootConfiguration
@EnableAutoConfiguration
public class McpServerApplication {

    @Bean
    @Primary
    JsonMixinModuleEntries customJsonMixins() {
        // Empty mapping: disables any @JsonMixin registration
        return JsonMixinModuleEntries.create(builder -> {
            // no entries
            // builder.add(TargetClass.class, MixinClass.class); // opt-in if ever needed
        });
    }

    @Bean
    static BeanFactoryPostProcessor removeJsonMixinsBean() {
        return bf -> {
            if (bf instanceof BeanDefinitionRegistry reg &&
                    reg.containsBeanDefinition("jsonMixinModuleEntries")) {
                reg.removeBeanDefinition("jsonMixinModuleEntries");
            }
        };
    }

    @Bean
    public WeatherService weatherService() {
        return new WeatherService();
    }

    @Bean
    public SymbolSourceLookupTool symbolSourceLookupTool() {
        return new SymbolSourceLookupTool();
    }

    @Bean
    public ToolCallbackProvider weatherTools(WeatherService weatherService) {
        return MethodToolCallbackProvider.builder().toolObjects(weatherService).build();
    }

    @Bean
    public ToolCallback getSourceCode(SymbolSourceLookupTool tool) {
        return FunctionToolCallback.builder("ide_get_source_code", tool::resolveSymbol)
                .inputType(SymbolLookupInput.class)
                .description("Resolves classes, members, or resources to source snippets from an IDE project.")
                .build();
    }
}
