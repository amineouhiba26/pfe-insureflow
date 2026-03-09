package com.insureflow.infrastructure.persistence.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.List;

/**
 * Converts between List<String> in Java and a comma-separated TEXT in Postgres.
 *
 * Why: JPA doesn't support Postgres TEXT[] arrays natively.
 * This converter serializes ["photo1.jpg","photo2.jpg"] → "photo1.jpg,photo2.jpg"
 * and back when reading from DB.
 *
 * @Converter(autoApply = false) means you must explicitly put
 * @Convert(converter = StringListConverter.class) on the field.
 */
@Converter(autoApply = false)
public class StringListConverter implements AttributeConverter<List<String>, String> {

    @Override
    public String convertToDatabaseColumn(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        return String.join(",", list);
    }

    @Override
    public List<String> convertToEntityAttribute(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.asList(value.split(","));
    }
}