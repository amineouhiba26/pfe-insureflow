package com.insureflow.agent.estimator;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Analyse visuelle via llama3.2-vision local (Ollama).
 * Remplace temporairement GeminiVisionService en attendant
 * une solution cloud stable.
 */
@Service
public class GeminiVisionService {

    private static final Logger log = LoggerFactory.getLogger(GeminiVisionService.class);

    private static final String PROMPT = """
    Tu es un agent d'évaluation de dommages pour une compagnie d'assurance.
    
    RÈGLE ABSOLUE : Tu dois répondre UNIQUEMENT avec un objet JSON valide.
    AUCUN texte avant. AUCUN texte après. AUCUN markdown. AUCUNE explication.
    COMMENCE DIRECTEMENT par { et TERMINE par }
    
    Niveaux de sévérité :
    - MINOR      : égratignures légères, dommages cosmétiques
    - MODERATE   : dommages fonctionnels, réparation nécessaire
    - SEVERE     : dommages structurels importants
    - TOTAL_LOSS : destruction totale, irréparable
    
    Noms des éléments en anglais OBLIGATOIREMENT :
    front bumper, rear bumper, hood, trunk, door, windshield, rear window,
    side mirror, headlight, taillight, wheel, roof, engine, chassis,
    roof, wall, floor, window, electrical system, furniture, appliances
    
    Format JSON OBLIGATOIRE — commence par { immédiatement :
    {
      "damagedElements": [
        {"element": "front bumper", "severity": "SEVERE"},
        {"element": "hood", "severity": "MODERATE"}
      ],
      "overallSeverity": "SEVERE",
      "confidence": 0.92,
      "reasoning": "Description en français des dommages"
    }
    """;

    private final ChatLanguageModel visionModel;

    public GeminiVisionService(@Qualifier("visionModel") ChatLanguageModel visionModel) {
        this.visionModel = visionModel;
    }

    public String analysePhotos(List<String> photoUrls, String claimType) {
        if (photoUrls == null || photoUrls.isEmpty()) {
            log.info("[VISION] Aucune photo — fallback sur analyse textuelle");
            return null;
        }

        log.info("[VISION] Analyse de {} photo(s) avec llama3.2-vision", photoUrls.size());

        try {
            List<dev.langchain4j.data.message.Content> contents = new ArrayList<>();

            contents.add(TextContent.from(
                    PROMPT + "\n\nType de sinistre : " + claimType +
                            "\n\nAnalyse les photos et identifie tous les dommages visibles."
            ));

            int loaded = 0;
            for (String url : photoUrls) {
                try {
                    byte[] bytes = downloadImage(url);
                    if (bytes != null && bytes.length > 0) {
                        String base64   = Base64.getEncoder().encodeToString(bytes);
                        String mimeType = detectMimeType(url);
                        contents.add(ImageContent.from(base64, mimeType));
                        loaded++;
                        log.debug("[VISION] Photo chargée {} KB", bytes.length / 1024);
                    }
                } catch (Exception e) {
                    log.warn("[VISION] Photo ignorée {}: {}", url, e.getMessage());
                }
            }

            if (loaded == 0) {
                log.warn("[VISION] Aucune photo chargée — fallback");
                return null;
            }

            log.info("[VISION] Envoi de {} photo(s) à llama3.2-vision", loaded);
            Response<AiMessage> response = visionModel.generate(UserMessage.from(contents));
            String result = response.content().text();

            log.info("[VISION] Analyse terminée pour {} photo(s)", loaded);
            log.debug("[VISION] Réponse: {}", result);

            return result;

        } catch (Exception e) {
            log.error("[VISION] Analyse échouée: {}", e.getMessage());
            return null;
        }
    }

    private byte[] downloadImage(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "InsureFlow/1.0");
            try (InputStream is = conn.getInputStream()) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            log.warn("[VISION] Download échoué {}: {}", urlStr, e.getMessage());
            return null;
        }
    }

    private String detectMimeType(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".png"))  return "image/png";
        if (lower.contains(".webp")) return "image/webp";
        if (lower.contains(".gif"))  return "image/gif";
        return "image/jpeg";
    }
}