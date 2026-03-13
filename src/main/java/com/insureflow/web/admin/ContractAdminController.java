package com.insureflow.web.admin;

import com.insureflow.domain.port.out.VectorStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Admin endpoint for uploading insurance contract PDFs into pgvector.
 *
 * POST /api/v1/admin/contracts/{policyId}/ingest
 *   → uploads a PDF, chunks it, embeds it, stores in pgvector
 *   → from this point, ValidatorAgent can retrieve chunks for this policy
 *
 * GET /api/v1/admin/contracts/{policyId}/search?query=...
 *   → test endpoint: run a semantic search and see what chunks would be returned
 *   → useful for verifying RAG is working before running the full pipeline
 *
 * In a real system, this would be protected by admin authentication.
 * For the demo/PFE this is open.
 */
@RestController
@RequestMapping("/api/v1/admin/contracts")
public class ContractAdminController {

    private static final Logger log = LoggerFactory.getLogger(ContractAdminController.class);

    private final VectorStorePort vectorStorePort;

    public ContractAdminController(VectorStorePort vectorStorePort) {
        this.vectorStorePort = vectorStorePort;
    }

    @PostMapping("/{policyId}/ingest")
    public ResponseEntity<Map<String, String>> ingest(
            @PathVariable String policyId,
            @RequestParam("file") MultipartFile file) {
        try {
            log.info("[ADMIN] Ingesting contract for policyId={} file='{}'",
                    policyId, file.getOriginalFilename());

            vectorStorePort.ingestDocument(
                    policyId,
                    file.getBytes(),
                    file.getOriginalFilename()
            );

            return ResponseEntity.ok(Map.of(
                    "status", "ingested",
                    "policyId", policyId,
                    "fileName", file.getOriginalFilename()
            ));
        } catch (Exception e) {
            log.error("[ADMIN] Ingestion failed for policyId={}: {}", policyId, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{policyId}/search")
    public ResponseEntity<List<String>> search(
            @PathVariable String policyId,
            @RequestParam String query,
            @RequestParam(defaultValue = "3") int topK) {

        List<String> chunks = vectorStorePort.retrieveRelevantChunks(query, policyId, topK);
        return ResponseEntity.ok(chunks);
    }
}