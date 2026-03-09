package com.insureflow.infrastructure.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the clients table.
 *
 * This class is the bridge between Java objects and database rows.
 * @Entity tells JPA "this class maps to a table".
 * @Table(name="clients") tells it which table.
 *
 * Why is this separate from the domain Client class?
 * Because domain classes should not know about @Column or @GeneratedValue.
 * Those are infrastructure details. If you add a cache layer or switch
 * databases, you change this entity — the domain Client never changes.
 *
 * @GeneratedValue(strategy = GenerationType.UUID) means Postgres generates
 * the UUID automatically using gen_random_uuid() from our Flyway migration.
 *
 * @CreationTimestamp means Hibernate sets this automatically when the row
 * is first inserted. You never set it manually.
 */
@Entity
@Table(name = "clients")
public class ClientJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "email", unique = true, nullable = false)
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "national_id", unique = true)
    private String nationalId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    public ClientJpaEntity() {}

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