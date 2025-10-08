package com.didalgo.intellij.mcp;

import com.didalgo.intellij.mcp.SymbolSourceLookupTool.SymbolLookupInput;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
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
    public SymbolSourceLookupTool symbolSourceLookupTool() {
        return new SymbolSourceLookupTool();
    }

    @Bean
    public ToolCallback getSourceCode(SymbolSourceLookupTool tool) {
        return FunctionToolCallback.builder("IDE_SearchSourceCode", tool::resolveSymbol)
                .inputType(SymbolLookupInput.class)
                .description("Fetches source code snippets for classes or resources located on the IDE project's" +
                        " classpath which aren't directly present in your codebase, such as dependency classes," +
                        " JDK classes, or classpath resources. Use this tool to quickly view their source code," +
                        " original or decompiled.")
                .build();
    }
}
