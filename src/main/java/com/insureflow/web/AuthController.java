package com.insureflow.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Auth info endpoint — returns Keycloak config to the frontend.
 * Login/logout/registration is handled entirely by Keycloak.
 * Spring Boot only verifies tokens — never handles credentials.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Value("${keycloak.auth-server-url}")
    private String keycloakUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.frontend-client-id}")
    private String clientId;

    @GetMapping("/config")
    public ResponseEntity<?> getKeycloakConfig() {
        return ResponseEntity.ok(Map.of(
                "url",      keycloakUrl,
                "realm",    realm,
                "clientId", clientId
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(Map.of(
                "sub",          jwt.getSubject(),
                "cin",          jwt.getClaim("cin") != null ? jwt.getClaim("cin") : "NOT FOUND",
                "username",     jwt.getClaim("preferred_username"),
                "realm_access", jwt.getClaim("realm_access") != null ? jwt.getClaim("realm_access") : "NOT FOUND",
                "roles",        jwt.getClaim("roles") != null ? jwt.getClaim("roles") : "NOT FOUND"
        ));
    }
}