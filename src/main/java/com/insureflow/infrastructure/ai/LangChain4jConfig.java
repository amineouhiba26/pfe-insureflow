package com.insureflow.infrastructure.ai;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * Explicitly creates the ChatLanguageModel bean for LangChain4j.
 *
 * Why do we need this?
 * When both Spring AI and LangChain4j are on the classpath, they both
 * try to auto-configure a bean called "ollamaChatModel". Even with
 * allow-bean-definition-overriding=true, LangChain4j's @AiService
 * factory looks specifically for a ChatLanguageModel bean of its own
 * type (dev.langchain4j.model.chat.ChatLanguageModel).
 *
 * By declaring it explicitly with @Primary, we guarantee LangChain4j
 * finds exactly the bean it needs, and Spring AI uses its own separate
 * EmbeddingModel bean for pgvector — no conflict.
 */
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
}