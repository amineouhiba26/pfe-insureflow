package com.insureflow.infrastructure.security;

import com.insureflow.domain.model.Client;
import com.insureflow.domain.port.out.ClientRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Component
public class JwtUtils {

    private final ClientRepository clientRepository;

    public JwtUtils(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    public UUID extractClientId(Jwt jwt) {
        String cin = jwt.getClaim("cin");
        if (cin != null && !cin.isBlank()) {
            return clientRepository.findByNationalId(cin)
                    .map(Client::getId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Client non trouvé pour CIN: " + cin));
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "CIN absent du token");
    }
}
