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

    @Override
    public String convertToDatabaseColumn(AuditAction action) {
        return action == null ? null : action.dbValue();
    }

    @Override
    public AuditAction convertToEntityAttribute(String value) {
        return value == null ? null : AuditAction.fromDbValue(value);
    }
}
