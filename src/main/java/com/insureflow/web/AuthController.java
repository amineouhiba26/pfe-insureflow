package com.insureflow.web;

import com.insureflow.domain.port.out.ClientRepository;
import com.insureflow.infrastructure.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Authentication endpoints.
 *
 * Login with fullName + cin — no password needed for MVP.
 * The BSM specifically requested name + CIN as authentication method.
 *
 * In production this would use hashed passwords or OTP via SMS.
 * For the PFE demo, name + CIN is sufficient.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final ClientRepository clientRepository;
    private final JwtService       jwtService;

    public AuthController(ClientRepository clientRepository,
                          JwtService jwtService) {
        this.clientRepository = clientRepository;
        this.jwtService       = jwtService;
    }

    /**
     * Client login.
     * POST /api/v1/auth/login
     * Body: { "fullName": "Ali Al Mansouri", "cin": "05739884" }
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        log.info("[AUTH] Login attempt for cin={}", request.cin());

        var clientOpt = clientRepository.findByNationalId(request.cin());

        if (clientOpt.isEmpty()) {
            log.warn("[AUTH] CIN not found: {}", request.cin());
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Client non trouvé"));
        }

        var client = clientOpt.get();

        // Verify name matches (case insensitive)
        if (!client.getFullName().equalsIgnoreCase(request.fullName().trim())) {
            log.warn("[AUTH] Name mismatch for cin={}", request.cin());
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Nom incorrect"));
        }

        String token = jwtService.generateToken(
                client.getId(),
                client.getFullName(),
                client.getNationalId(),
                "CLIENT"
        );

        log.info("[AUTH] Login successful for clientId={}", client.getId());

        return ResponseEntity.ok(Map.of(
                "token",    token,
                "clientId", client.getId().toString(),
                "fullName", client.getFullName(),
                "role",     "CLIENT"
        ));
    }

    /**
     * Admin login — hardcoded for demo.
     * In production this would use a separate admin table.
     * POST /api/v1/auth/admin/login
     */
    @PostMapping("/admin/login")
    public ResponseEntity<?> adminLogin(@RequestBody AdminLoginRequest request) {
        if (!"admin".equals(request.username()) || !"insureflow2026".equals(request.password())) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Identifiants incorrects"));
        }

        // Generate admin token with a fixed UUID for demo
        String token = jwtService.generateToken(
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Admin InsureFlow",
                "ADMIN",
                "ADMIN"
        );

        return ResponseEntity.ok(Map.of(
                "token",    token,
                "fullName", "Admin InsureFlow",
                "role",     "ADMIN"
        ));
    }

    public record LoginRequest(String fullName, String cin) {}
    public record AdminLoginRequest(String username, String password) {}
}