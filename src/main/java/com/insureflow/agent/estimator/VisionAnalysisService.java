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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Analyse visuelle des photos de sinistres via llama3.2-vision (Ollama local).
 */
@Service
public class VisionAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(VisionAnalysisService.class);

    private static final String PROMPT = """
        Tu es un expert en évaluation de dommages pour une compagnie d'assurance.
        
        RÈGLE ABSOLUE : Réponds UNIQUEMENT avec un objet JSON valide.
        Commence directement par { et termine par }. Aucun texte, aucun markdown.
        
        Niveaux de sévérité :
        MINOR      → cosmétique, fonctionnel malgré le dommage
        MODERATE   → réparation nécessaire
        SEVERE     → inutilisable, remplacement nécessaire
        TOTAL_LOSS → destruction totale, irréparable
        
        Noms des éléments OBLIGATOIREMENT en FRANÇAIS.
        
        Format JSON OBLIGATOIRE :
        {
          "damagedElements": [
            {"element": "pare-choc avant", "severity": "SEVERE"},
            {"element": "capot", "severity": "MODERATE"}
          ],
          "overallSeverity": "SEVERE",
          "confidence": 0.92,
          "reasoning": "Description en français des dommages observés sur les photos."
        }
        """;

    private final ChatLanguageModel visionModel;

    public VisionAnalysisService(@Qualifier("visionModel") ChatLanguageModel visionModel) {
        this.visionModel = visionModel;
    }

    /**
     * Analyse les photos et retourne un JSON de dommages.
     * Retourne null si aucune photo n'est chargeable (fallback texte dans EstimatorAgentService).
     */
    public String analyse(List<String> photoUrls, String claimType) {
        if (photoUrls == null || photoUrls.isEmpty()) return null;

        log.info("[VISION] Analyse de {} photo(s) — claimType={}", photoUrls.size(), claimType);

        try {
            List<dev.langchain4j.data.message.Content> contents = new ArrayList<>();
            contents.add(TextContent.from(
                    PROMPT + "\n\nType de sinistre : " + claimType));

            int loaded = 0;
            for (String url : photoUrls) {
                byte[] bytes = downloadImage(url);
                if (bytes != null && bytes.length > 0) {
                    contents.add(ImageContent.from(
                            Base64.getEncoder().encodeToString(bytes),
                            detectMimeType(url)));
                    loaded++;
                }
            }

            if (loaded == 0) {
                log.warn("[VISION] Aucune photo accessible — fallback texte");
                return null;
            }

            Response<AiMessage> response = visionModel.generate(UserMessage.from(contents));
            log.info("[VISION] Analyse terminée ({} photo(s))", loaded);
            return response.content().text();

        } catch (Exception e) {
            log.error("[VISION] Echec: {}", e.getMessage());
            return null;
        }
    }

    private byte[] downloadImage(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) java.net.URI.create(urlStr).toURL().openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "InsureFlow/1.0");
            try (InputStream is = conn.getInputStream()) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            log.warn("[VISION] Photo inaccessible: {}", urlStr);
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
