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

    @EventListener(ApplicationReadyEvent.class)
    public void syncClientsToKeycloak() {
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
                if (client.getEmail() != null) {
                    keycloakUserId = findUserByEmail(client.getEmail(), adminToken);
                }

                if (keycloakUserId == null) {
                    String username = client.getFullName().toLowerCase()
                            .trim()
                            .replace(" ", ".")
                            .replace("'", "");
                    keycloakUserId = findUserByUsername(username, adminToken);
                }

                if (keycloakUserId != null) {
                    updateCinAttribute(keycloakUserId, client.getNationalId(), adminToken);
                    resetPassword(keycloakUserId, client.getNationalId(), adminToken);
                    updated++;
                } else {
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

    private void ensureCinMapperExists(String adminToken) {
        String[] clients = {"insureflow-frontend", "insureflow-backend"};

        for (String clientId : clients) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth(adminToken);

                ResponseEntity<List<Map<String, Object>>> clientsResponse = restTemplate.exchange(
                        keycloakUrl + "/admin/realms/" + realm +
                                "/clients?clientId=" + clientId,
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {}
                );

                if (clientsResponse.getBody() == null ||
                        clientsResponse.getBody().isEmpty()) continue;

                String clientUuid = (String) clientsResponse.getBody().get(0).get("id");

                ResponseEntity<List<Map<String, Object>>> mappersResponse = restTemplate.exchange(
                        keycloakUrl + "/admin/realms/" + realm +
                                "/clients/" + clientUuid +
                                "/protocol-mappers/models",
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {}
                );

                boolean cinMapperExists = mappersResponse.getBody() != null &&
                        mappersResponse.getBody().stream()
                                .anyMatch(m -> "cin".equals(m.get("name")));

                if (cinMapperExists) {
                    log.debug("[KEYCLOAK SYNC] CIN mapper already exists for {}",
                            clientId);
                    continue;
                }

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

                headers.setContentType(MediaType.APPLICATION_JSON);
                restTemplate.postForEntity(
                        keycloakUrl + "/admin/realms/" + realm +
                                "/clients/" + clientUuid +
                                "/protocol-mappers/models",
                        new HttpEntity<>(mapper, headers),
                        Void.class
                );

                log.debug("[KEYCLOAK SYNC] CIN mapper created for client: {}",
                        clientId);

            } catch (Exception e) {
                log.warn("[KEYCLOAK SYNC] Mapper creation failed for {}: {}",
                        clientId, e.getMessage());
            }
        }
    }


    private void updateCinAttribute(String userId, String cin, String token) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> update = Map.of(
                    "attributes", Map.of("cin", List.of(cin))
            );

            restTemplate.exchange(
                    keycloakUrl + "/admin/realms/" + realm + "/users/" + userId,
                    HttpMethod.PUT,
                    new HttpEntity<>(update, headers),
                    Void.class
            );
            log.debug("[KEYCLOAK SYNC] CIN updated for userId={}", userId);

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

            log.debug("[KEYCLOAK SYNC] Password reset for userId={}", userId);

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
