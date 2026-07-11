package com.cortex_clash.mind_games.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter for {@link AuditAction} enum.
 *
 * <p>Persists {@link AuditAction} enum constants as their lowercase database string representations
 * (e.g., "successful_login", "failed_login", "user_registered"). This converter is automatically
 * applied to all {@link AuditAction} attributes in JPA entities.
 */
@Converter(autoApply = true)
public class AuditActionConverter implements AttributeConverter<AuditAction, String> {

    /**
     * Converts an {@link AuditAction} enum constant to its database string representation.
     *
     * @param action the audit action to convert, may be null
     * @return the database string value, or null if the input is null
     */
    @Override
    public String convertToDatabaseColumn(AuditAction action) {
        return action == null ? null : action.dbValue();
    }

    /**
     * Converts a database string value to the corresponding {@link AuditAction} enum constant.
     *
     * @param value the database string value to convert, may be null
     * @return the matching AuditAction constant, or null if the input is null
     * @throws IllegalArgumentException if the value does not match any known audit action
     */
    @Override
    public AuditAction convertToEntityAttribute(String value) {
        return value == null ? null : AuditAction.fromDbValue(value);
    }
}
