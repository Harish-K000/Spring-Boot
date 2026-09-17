package com.example.platform.agent;

import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class EngineeringAgentApplication {
    public static void main(String[] args) {
        // A parent shell may export DEBUG for unrelated tools. Keep prompt/tool logging quiet by default.
        if (System.getProperty("debug") == null) System.setProperty("debug", "false");
        SpringApplication.run(EngineeringAgentApplication.class, args);
    }

    @Bean
    ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        // Stop on invalid/repeated calls instead of feeding errors into an unbounded tool loop.
        return DefaultToolExecutionExceptionProcessor.builder().alwaysThrow(true).build();
    }
}
