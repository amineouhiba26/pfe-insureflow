package com.insureflow.web.admin;

import com.insureflow.infrastructure.persistence.repository.ClaimJpaRepository;
import com.insureflow.infrastructure.persistence.repository.ClientJpaRepository;
import com.insureflow.infrastructure.persistence.repository.HumanReviewJpaRepository;
import com.insureflow.infrastructure.persistence.repository.PolicyJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin endpoints for resetting test data.
 * NEVER expose these in production.
 *
 * DELETE /api/v1/admin/reset/claims
 *   → deletes all claims and human review tasks only
 *   → keeps clients, policies, and contracts in pgvector intact
 *   → use this when you want to re-submit claims without recreating clients
 *
 * DELETE /api/v1/admin/reset/all
 *   → deletes everything in this order:
 *      1. human_review_tasks (references claims)
 *      2. claims             (references policies + clients)
 *      3. vector_store       (pgvector contract chunks)
 *      4. policies           (references clients)
 *      5. clients            (root table)
 *   → order matters because of foreign key constraints
 *   → after this you start completely fresh
 */
@RestController
@RequestMapping("/api/v1/admin/reset")
public class ResetController {

    private static final Logger log = LoggerFactory.getLogger(ResetController.class);

    private final ClaimJpaRepository       claimRepo;
    private final HumanReviewJpaRepository reviewRepo;
    private final PolicyJpaRepository      policyRepo;
    private final ClientJpaRepository      clientRepo;
    private final JdbcTemplate             jdbc;

    public ResetController(ClaimJpaRepository claimRepo,
                           HumanReviewJpaRepository reviewRepo,
                           PolicyJpaRepository policyRepo,
                           ClientJpaRepository clientRepo,
                           JdbcTemplate jdbc) {
        this.claimRepo  = claimRepo;
        this.reviewRepo = reviewRepo;
        this.policyRepo = policyRepo;
        this.clientRepo = clientRepo;
        this.jdbc       = jdbc;
    }

    /**
     * Deletes only claims and human review tasks.
     * Clients, policies, and ingested contracts stay intact.
     * Use this between test runs when you want to re-submit claims.
     */
    @DeleteMapping("/claims")
    public ResponseEntity<Map<String, Object>> resetClaims() {
        long reviews = reviewRepo.count();
        long claims  = claimRepo.count();

        reviewRepo.deleteAll();
        claimRepo.deleteAll();

        log.info("[RESET] Deleted {} human review tasks and {} claims", reviews, claims);

        return ResponseEntity.ok(Map.of(
                "deleted", Map.of(
                        "humanReviewTasks", reviews,
                        "claims",           claims
                ),
                "kept", "clients, policies, vector_store"
        ));
    }

    /**
     * Deletes absolutely everything — clients, policies, claims,
     * human review tasks, and all pgvector contract chunks.
     * After this the DB is in the same state as right after Sprint 0.
     */
    @DeleteMapping("/all")
    public ResponseEntity<Map<String, Object>> resetAll() {
        long reviews  = reviewRepo.count();
        long claims   = claimRepo.count();
        long policies = policyRepo.count();
        long clients  = clientRepo.count();

        // Must delete in FK order
        reviewRepo.deleteAll();
        claimRepo.deleteAll();

        // Delete pgvector contract chunks
        jdbc.execute("DELETE FROM vector_store");

        policyRepo.deleteAll();
        clientRepo.deleteAll();

        log.info("[RESET] Full reset — deleted {} reviews, {} claims, {} policies, {} clients, vector_store cleared",
                reviews, claims, policies, clients);

        return ResponseEntity.ok(Map.of(
                "deleted", Map.of(
                        "humanReviewTasks", reviews,
                        "claims",           claims,
                        "policies",         policies,
                        "clients",          clients,
                        "vectorStore",      "cleared"
                ),
                "status", "full reset complete — start fresh"
        ));
    }
}
