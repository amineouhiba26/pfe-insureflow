package com.insureflow.estimator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SimilarClaimsService {

    private static final Logger log = LoggerFactory.getLogger(SimilarClaimsService.class);

    @PersistenceContext
    private EntityManager em;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    private static final String EMBED_MODEL = "nomic-embed-text";

    public record SimilarClaim(
            String id,
            String clientName,
            String description,
            String decision,
            BigDecimal estimatedAmount,
            String rejectionReason,
            double similarity
    ) {}

    /**
     * Finds the top-N past claims most similar to the given description
     * using pgvector cosine similarity.
     *
     * Returns an empty list (never throws) so callers are never blocked.
     */
    public List<SimilarClaim> findSimilar(String description, String claimType, int limit) {
        try {
            float[] embedding = generateEmbedding(description);
            if (embedding == null || embedding.length == 0) {
                log.warn("[ESTIMATOR] Similarity search skipped: embedding generation failed");
                return List.of();
            }

            String vectorLiteral = toVectorLiteral(embedding);

            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery("""
                    SELECT id::text, client_name, description, decision,
                           estimated_amount, rejection_reason,
                           1 - (embedding <=> CAST(:vec AS vector)) AS similarity
                    FROM past_claims
                    WHERE claim_type = :claimType
                      AND embedding IS NOT NULL
                    ORDER BY embedding <=> CAST(:vec AS vector)
                    LIMIT :lim
                    """)
                    .setParameter("vec", vectorLiteral)
                    .setParameter("claimType", claimType)
                    .setParameter("lim", limit)
                    .getResultList();

            List<SimilarClaim> result = new ArrayList<>();
            for (Object[] row : rows) {
                result.add(new SimilarClaim(
                        (String)  row[0],
                        (String)  row[1],
                        (String)  row[2],
                        (String)  row[3],
                        row[4] != null ? new BigDecimal(row[4].toString()) : BigDecimal.ZERO,
                        (String)  row[5],
                        row[6] != null ? ((Number) row[6]).doubleValue() : 0.0
                ));
            }

            log.info("[ESTIMATOR] Similar claims found: {} result(s) for claimType={}", result.size(), claimType);
            return result;

        } catch (Exception e) {
            log.warn("[ESTIMATOR] Similarity search failed (non-blocking): {}", e.getMessage());
            return List.of();
        }
    }

    // ── Ollama embedding call ─────────────────────────────────────────────────

    float[] generateEmbedding(String text) {
        try {
            String url = ollamaBaseUrl + "/api/embeddings";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = Map.of("model", EMBED_MODEL, "prompt", text);
            HttpEntity<Map<String, String>> req = new HttpEntity<>(body, headers);

            ResponseEntity<String> resp = restTemplate.postForEntity(url, req, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) return null;

            JsonNode root = mapper.readTree(resp.getBody());
            JsonNode embNode = root.path("embedding");
            if (!embNode.isArray()) return null;

            float[] vec = new float[embNode.size()];
            for (int i = 0; i < embNode.size(); i++) {
                vec[i] = (float) embNode.get(i).asDouble();
            }
            return vec;

        } catch (Exception e) {
            log.warn("[ESTIMATOR] Ollama embedding call failed: {}", e.getMessage());
            return null;
        }
    }

    private String toVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
