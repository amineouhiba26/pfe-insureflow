package com.insureflow.domain.port.out;

import com.insureflow.domain.model.Policy;
import java.util.Optional;
import java.util.UUID;

public interface PolicyRepository {
    Policy save(Policy policy);
    Optional<Policy> findById(UUID id);
}