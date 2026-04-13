package com.insureflow.infrastructure.ai;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
public class LangChain4jConfig {

    @Value("${ollama.base-url}")
    private String baseUrl;

    @Value("${ollama.chat-model}")
    private String chatModel;

    @Value("${ollama.chat-timeout}")
    private Duration chatTimeout;

    @Value("${ollama.vision-model}")
    private String visionModelName;

    @Value("${ollama.timeout}")
    private Duration visionTimeout;

    @Bean
    @Primary
    public ChatLanguageModel chatLanguageModel() {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(chatModel)
                .temperature(0.1)
                .timeout(chatTimeout)
                .build();
    }

    @Bean("visionModel")
    public ChatLanguageModel visionModel() {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(visionModelName)
                .temperature(0.1)
                .timeout(visionTimeout)
                .build();
    }
}