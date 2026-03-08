package com.insureflow.domain.model.enums;

/**
 * The category of damage or loss being claimed.
 * RouterAgent reads the claim description and picks one of these.
 *
 * VEHICLE_DAMAGE   → car accidents, collisions, scratches
 * PROPERTY_DAMAGE  → house, building, furniture damage
 * HEALTH           → medical expenses, hospitalization
 * THEFT            → stolen car, burglary
 * NATURAL_DISASTER → flood, earthquake, storm
 * OTHER            → anything that doesn't fit above
 */
public enum ClaimType {
    VEHICLE_DAMAGE,
    PROPERTY_DAMAGE,
    HEALTH,
    THEFT,
    NATURAL_DISASTER,
    OTHER
}