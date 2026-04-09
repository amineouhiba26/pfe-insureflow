package com.insureflow.infrastructure.ai;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
public class LangChain4jConfig {

    @Bean
    @Primary
    public ChatLanguageModel chatLanguageModel() {
        return OllamaChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("llama3.1:8b")
                .temperature(0.1)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    @Bean("visionModel")
    public ChatLanguageModel visionModel() {
        return OllamaChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("llama3.2-vision")
                .temperature(0.1)
                .timeout(Duration.ofSeconds(300))
                .build();
    }
}