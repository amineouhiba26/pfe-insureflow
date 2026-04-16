package com.insureflow.infrastructure.security;

import com.insureflow.domain.model.Client;
import com.insureflow.domain.port.out.ClientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Syncs existing DB clients to Keycloak on startup.
 * Runs once after Spring Boot is fully started.
 */
@Service
public class KeycloakSyncService {

    private static final Logger log = LoggerFactory.getLogger(KeycloakSyncService.class);

    @Value("${keycloak.auth-server-url}")
    private String keycloakUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.admin.username}")
    private String adminUsername;

    @Value("${keycloak.admin.password}")
    private String adminPassword;

    private final ClientRepository      clientRepository;
    private final KeycloakAdminService  keycloakAdminService;
    private final RestTemplate          restTemplate = new RestTemplate();

    public KeycloakSyncService(ClientRepository clientRepository,
                                KeycloakAdminService keycloakAdminService) {
        this.clientRepository     = clientRepository;
        this.keycloakAdminService = keycloakAdminService;
    }

    @Value("${keycloak.sync.enabled:true}")
    private boolean syncEnabled;

    @EventListener(ApplicationReadyEvent.class)
    public void syncClientsToKeycloak() {
        if (!syncEnabled) {
            log.info("[KEYCLOAK SYNC] Disabled — skipping");
            return;
        }
        log.info("[KEYCLOAK SYNC] Starting...");
        try {
            String adminToken = getAdminToken();
            ensureCinMapperExists(adminToken);

            List<Client> clients = clientRepository.findAll();
            int updated = 0, skipped = 0;

            for (Client client : clients) {
                if (client.getNationalId() == null) {
                    skipped++;
                    continue;
                }

                String keycloakUserId = null;
                // 1. Try by Email
                if (client.getEmail() != null) {
                    keycloakUserId = findUserByEmail(client.getEmail(), adminToken);
                }

                // 2. Try by Exact Generated Username (e.g., ali.al.mansouri)
                if (keycloakUserId == null) {
                    String username = client.getFullName().toLowerCase()
                            .trim()
                            .replace(" ", ".")
                            .replace("'", "");
                    keycloakUserId = findUserByUsername(username, adminToken);
                }

                // 3. Try by Simplified Username (e.g., alialmansouri or ali.almansouri)
                if (keycloakUserId == null) {
                    String simplified = client.getFullName().toLowerCase().replace(" ", "").replace("'", "");
                    // Search for users and filter manually
                    keycloakUserId = findUserBySimplifiedUsername(simplified, adminToken);
                }

                // 4. Try by Full Name
                if (keycloakUserId == null) {
                    keycloakUserId = findUserByFullName(client.getFullName(), adminToken);
                }

                if (keycloakUserId != null) {
                    log.info("[KEYCLOAK SYNC] Found existing user {} for CIN {}", keycloakUserId, client.getNationalId());
                    updateCinAttribute(keycloakUserId, client.getNationalId(), adminToken);
                    resetPassword(keycloakUserId, client.getNationalId(), adminToken);
                    updated++;
                } else {
                    log.info("[KEYCLOAK SYNC] Creating new user for {}", client.getFullName());
                    keycloakAdminService.createUser(
                            client.getFullName(),
                            client.getEmail(),
                            client.getNationalId(),
                            client.getPhone()
                    );
                    updated++;
                }
            }

            log.info("[KEYCLOAK SYNC] Done — updated={} skipped={}",
                    updated, skipped);

        } catch (Exception e) {
            log.error("[KEYCLOAK SYNC] Failed: {}", e.getMessage());
        }
    }

    private String findUserByEmail(String email, String token) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    keycloakUrl + "/admin/realms/" + realm +
                            "/users?email=" + email + "&exact=true",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
            if (response.getBody() != null && !response.getBody().isEmpty()) {
                return (String) ((Map<?, ?>) response.getBody().get(0)).get("id");
            }
        } catch (Exception e) {
            log.debug("[KEYCLOAK SYNC] findByEmail failed: {}", e.getMessage());
        }
        return null;
    }

    private String findUserByUsername(String username, String token) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    keycloakUrl + "/admin/realms/" + realm +
                            "/users?username=" + username + "&exact=true",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
            if (response.getBody() != null && !response.getBody().isEmpty()) {
                return (String) ((Map<?, ?>) response.getBody().get(0)).get("id");
            }
        } catch (Exception e) {
            log.debug("[KEYCLOAK SYNC] findByUsername failed: {}", e.getMessage());
        }
        return null;
    }

    private String findUserBySimplifiedUsername(String simplified, String token) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            // Search all users and filter (in a real app with 10k users this needs caution, but fine for PFE)
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    keycloakUrl + "/admin/realms/" + realm + "/users?max=100",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
            
            if (response.getBody() != null) {
                for (Map<String, Object> user : response.getBody()) {
                    String username = ((String) user.get("username")).toLowerCase().replace(".", "");
                    if (username.equals(simplified)) {
                        return (String) user.get("id");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[KEYCLOAK SYNC] findBySimplifiedUsername failed: {}", e.getMessage());
        }
        return null;
    }

    private String findUserByFullName(String fullName, String token) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            // Keycloak search is fuzzy by default, we'll filter on our side
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    keycloakUrl + "/admin/realms/" + realm + "/users?search=" + fullName,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
            
            if (response.getBody() != null) {
                for (Map<String, Object> user : response.getBody()) {
                    String firstName = (String) user.get("firstName");
                    String lastName  = (String) user.get("lastName");
                    String foundName = ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
                    
                    if (foundName.equalsIgnoreCase(fullName.trim())) {
                        return (String) user.get("id");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[KEYCLOAK SYNC] findByFullName failed: {}", e.getMessage());
        }
        return null;
    }

    private void ensureCinMapperExists(String adminToken) {
        try {
            String cinScopeId = getOrCreateCinClientScope(adminToken);
            if (cinScopeId != null) {
                addDefaultScopeToClient("insureflow-frontend", cinScopeId, adminToken);
            }
        } catch (Exception e) {
            log.warn("[KEYCLOAK SYNC] CIN scope setup failed: {}", e.getMessage());
        }
    }

    private String getOrCreateCinClientScope(String adminToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        // Check if cin client scope already exists
        ResponseEntity<List<Map<String, Object>>> scopesResp = restTemplate.exchange(
                keycloakUrl + "/admin/realms/" + realm + "/client-scopes",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        if (scopesResp.getBody() != null) {
            String existing = scopesResp.getBody().stream()
                    .filter(s -> "cin".equals(s.get("name")))
                    .map(s -> (String) s.get("id"))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                log.info("[KEYCLOAK SYNC] 'cin' client scope already exists (id={})", existing);
                return existing;
            }
        }

        // Create the cin client scope
        Map<String, Object> scopePayload = new java.util.HashMap<>();
        scopePayload.put("name", "cin");
        scopePayload.put("description", "CIN national ID claim");
        scopePayload.put("protocol", "openid-connect");
        scopePayload.put("attributes", Map.of("include.in.token.scope", "false"));

        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.postForEntity(
                keycloakUrl + "/admin/realms/" + realm + "/client-scopes",
                new HttpEntity<>(scopePayload, headers),
                Void.class
        );

        // Fetch the new scope ID
        ResponseEntity<List<Map<String, Object>>> updatedResp = restTemplate.exchange(
                keycloakUrl + "/admin/realms/" + realm + "/client-scopes",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        String cinScopeId = updatedResp.getBody() == null ? null :
                updatedResp.getBody().stream()
                        .filter(s -> "cin".equals(s.get("name")))
                        .map(s -> (String) s.get("id"))
                        .findFirst()
                        .orElse(null);

        if (cinScopeId == null) {
            log.warn("[KEYCLOAK SYNC] Failed to retrieve 'cin' scope after creation");
            return null;
        }

        // Add protocol mapper to the scope
        Map<String, Object> mapper = Map.of(
                "name",           "cin",
                "protocol",       "openid-connect",
                "protocolMapper", "oidc-usermodel-attribute-mapper",
                "config", Map.of(
                        "user.attribute",       "cin",
                        "claim.name",           "cin",
                        "jsonType.label",       "String",
                        "id.token.claim",       "true",
                        "access.token.claim",   "true",
                        "userinfo.token.claim", "true"
                )
        );

        restTemplate.postForEntity(
                keycloakUrl + "/admin/realms/" + realm +
                        "/client-scopes/" + cinScopeId + "/protocol-mappers/models",
                new HttpEntity<>(mapper, headers),
                Void.class
        );

        log.info("[KEYCLOAK SYNC] Created 'cin' client scope with mapper (id={})", cinScopeId);
        return cinScopeId;
    }

    private void addDefaultScopeToClient(String clientId, String scopeId, String adminToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(adminToken);

            ResponseEntity<List<Map<String, Object>>> clientsResp = restTemplate.exchange(
                    keycloakUrl + "/admin/realms/" + realm + "/clients?clientId=" + clientId,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );

            if (clientsResp.getBody() == null || clientsResp.getBody().isEmpty()) {
                log.warn("[KEYCLOAK SYNC] Client '{}' not found in realm", clientId);
                return;
            }

            String clientUuid = (String) clientsResp.getBody().get(0).get("id");

            // Check if already assigned as default
            ResponseEntity<List<Map<String, Object>>> defaultScopes = restTemplate.exchange(
                    keycloakUrl + "/admin/realms/" + realm +
                            "/clients/" + clientUuid + "/default-client-scopes",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );

            boolean alreadyAssigned = defaultScopes.getBody() != null &&
                    defaultScopes.getBody().stream()
                            .anyMatch(s -> scopeId.equals(s.get("id")));

            if (alreadyAssigned) {
                log.info("[KEYCLOAK SYNC] 'cin' scope already default for client '{}'", clientId);
                return;
            }

            restTemplate.exchange(
                    keycloakUrl + "/admin/realms/" + realm +
                            "/clients/" + clientUuid + "/default-client-scopes/" + scopeId,
                    HttpMethod.PUT,
                    new HttpEntity<>(headers),
                    Void.class
            );

            log.info("[KEYCLOAK SYNC] Added 'cin' as default scope to client '{}'", clientId);

        } catch (Exception e) {
            log.warn("[KEYCLOAK SYNC] Failed to assign 'cin' scope to client '{}': {}",
                    clientId, e.getMessage());
        }
    }


    private void updateCinAttribute(String userId, String cin, String token) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            
            // Fetch current user first to avoid wiping other fields during PUT
            ResponseEntity<Map<String, Object>> userResp = restTemplate.exchange(
                    keycloakUrl + "/admin/realms/" + realm + "/users/" + userId,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {}
            );
            
            Map<String, Object> user = userResp.getBody();
            if (user == null) return;

            // Update attributes safely
            @SuppressWarnings("unchecked")
            Map<String, Object> attributes = (Map<String, Object>) user.get("attributes");
            if (attributes == null) {
                attributes = new java.util.HashMap<>();
            } else {
                attributes = new java.util.HashMap<>(attributes);
            }
            attributes.put("cin", List.of(cin));
            
            // Prepare update object with mandatory fields
            Map<String, Object> update = new java.util.HashMap<>(user);
            update.put("attributes", attributes);

            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.exchange(
                    keycloakUrl + "/admin/realms/" + realm + "/users/" + userId,
                    HttpMethod.PUT,
                    new HttpEntity<>(update, headers),
                    Void.class
            );
            log.info("[KEYCLOAK SYNC] CIN attribute set for userId={}", userId);

        } catch (Exception e) {
            log.warn("[KEYCLOAK SYNC] Update CIN failed for userId {}: {}",
                    userId, e.getMessage());
        }
    }

    private void resetPassword(String userId, String newPassword, String token) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> credential = Map.of(
                    "type",      "password",
                    "value",     newPassword,
                    "temporary", false
            );

            restTemplate.exchange(
                    keycloakUrl + "/admin/realms/" + realm +
                            "/users/" + userId + "/reset-password",
                    HttpMethod.PUT,
                    new HttpEntity<>(credential, headers),
                    Void.class
            );

            log.info("[KEYCLOAK SYNC] Password reset for userId={}", userId);

        } catch (Exception e) {
            log.warn("[KEYCLOAK SYNC] Password reset failed for {}: {}",
                    userId, e.getMessage());
        }
    }

    private String getAdminToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id",  "admin-cli");
        form.add("username",   adminUsername);
        form.add("password",   adminPassword);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                keycloakUrl + "/realms/master/protocol/openid-connect/token",
                HttpMethod.POST,
                new HttpEntity<>(form, headers),
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {}
        );

        return (String) response.getBody().get("access_token");
    }
}
