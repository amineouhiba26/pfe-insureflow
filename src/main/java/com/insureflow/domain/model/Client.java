package com.insureflow.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a policyholder — the person who bought insurance and filed the claim.
 *
 * This is a pure domain class. No @Entity, no @Column, no Spring annotations.
 * The JPA entity (ClientJpaEntity) lives in infrastructure and mirrors this
 * but they are kept separate on purpose.
 *
 * Why? Because your business logic should not care HOW data is stored.
 * It just works with Client objects. If you switch from Postgres to MongoDB
 * tomorrow, this class never changes.
 */
public class Client {

    private UUID id;
    private String fullName;
    private String email;
    private String phone;
    private String nationalId;
    private Instant createdAt;

    // Default constructor — needed for mapping from JPA entity
    public Client() {}

    // Full constructor — used when creating a new client
    public Client(UUID id, String fullName, String email,
                  String phone, String nationalId) {
        this.id = id;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.nationalId = nationalId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getNationalId() { return nationalId; }
    public void setNationalId(String nationalId) { this.nationalId = nationalId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}