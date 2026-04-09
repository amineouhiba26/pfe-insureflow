package com.insureflow.adapter.in.web;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Controller to verify connectivity with the remote Ollama server via Tailscale.
 * This runs on the Mac M2 Pro but calls the LLM on the Ubuntu RX 4060 Ti server.
 */
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
@Slf4j
public class TestRemoteOllamaController {
    
    private final ChatClient.Builder chatClientBuilder;

    @GetMapping("/remote-llm")
    public String testRemoteLLM() {
        log.info("🚀 Testing remote Ollama on Ubuntu via Tailscale...");
        
        try {
            ChatClient chatClient = chatClientBuilder.build();
            
            String response = chatClient.prompt()
                .user("Réponds en français en une phrase: Es-tu un LLM qui tourne sur un serveur distant Ubuntu avec GPU RTX 4060 Ti via Tailscale?")
                .call()
                .content();
            
            log.info("✅ Remote LLM Response: {}", response);
            return "Remote LLM Response: " + response;
        } catch (Exception e) {
            log.error("❌ Remote LLM Test Failed: {}", e.getMessage());
            return "Error calling remote LLM: " + e.getMessage();
        }
    }
    
    @GetMapping("/ping")
    public String ping() {
        return "Pong! InsureFlow backend is running on Mac M2 Pro";
    }
}
