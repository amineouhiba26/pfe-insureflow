package com.insureflow.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Creates users in Keycloak when admin creates a client in InsureFlow.
 * Uses Keycloak Admin REST API with admin credentials.
 */
@Service
public class KeycloakAdminService {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminService.class);

    @Value("${keycloak.auth-server-url}")
    private String keycloakUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.admin.username}")
    private String adminUsername;

    @Value("${keycloak.admin.password}")
    private String adminPassword;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Creates a user in Keycloak and assigns CLIENT role.
     * Default password is the CIN — client must change it on first login.
     */
    public String createUser(String fullName, String email,
                              String cin, String phone) {
        try {
            String adminToken = getAdminToken();

            // Split fullName into first + last
            String[] parts     = fullName.trim().split(" ", 2);
            String   firstName = parts[0];
            String   lastName  = parts.length > 1 ? parts[1] : "";
            String   username  = fullName.toLowerCase()
                                         .replace(" ", ".")
                                         .replace("'", "");

            // Build user payload
            Map<String, Object> user = Map.of(
                "username",   username,
                "email",      email != null ? email : username + "@insureflow.com",
                "firstName",  firstName,
                "lastName",   lastName,
                "enabled",    true,
                "attributes", Map.of("cin", List.of(cin)),
                "credentials", List.of(Map.of(
                    "type",      "password",
                    "value",     cin,          // default password = CIN
                    "temporary", false
                ))
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(adminToken);

            ResponseEntity<Void> response = restTemplate.postForEntity(
                keycloakUrl + "/admin/realms/" + realm + "/users",
                new HttpEntity<>(user, headers),
                Void.class
            );

            if (response.getStatusCode() == HttpStatus.CREATED) {
                // Get the created user's ID from Location header
                String location = response.getHeaders()
                        .getLocation().toString();
                String keycloakUserId = location.substring(
                        location.lastIndexOf('/') + 1);

                // Assign CLIENT role
                assignClientRole(keycloakUserId, adminToken);

                log.info("[KEYCLOAK] User created: {} (id={})", username, keycloakUserId);
                return keycloakUserId;
            }

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("[KEYCLOAK] Failed to create user: {} — body: {}",
                    e.getMessage(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("[KEYCLOAK] Failed to create user: {}", e.getMessage());
        }
        return null;
    }

    private void assignClientRole(String userId, String adminToken) {
        try {
            // Get CLIENT role ID
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(adminToken);

            ResponseEntity<List<Map<String, Object>>> rolesResponse = restTemplate.exchange(
                keycloakUrl + "/admin/realms/" + realm + "/roles",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );

            Map<String, Object> clientRole = rolesResponse.getBody()
                .stream()
                .filter(r -> "CLIENT".equals(r.get("name")))
                .findFirst()
                .orElse(null);

            if (clientRole == null) {
                log.warn("[KEYCLOAK] CLIENT role not found");
                return;
            }

            // Assign role to user
            restTemplate.postForEntity(
                keycloakUrl + "/admin/realms/" + realm +
                        "/users/" + userId + "/role-mappings/realm",
                new HttpEntity<>(List.of(clientRole), headers),
                Void.class
            );

            log.info("[KEYCLOAK] CLIENT role assigned to userId={}", userId);

        } catch (Exception e) {
            log.error("[KEYCLOAK] Failed to assign CLIENT role: {}", e.getMessage());
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
