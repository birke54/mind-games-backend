package com.cortex_clash.mind_games.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter for {@link UserStatus} enum values.
 *
 * <p>Translates between the enum constants and their database representations (lowercase strings:
 * "active" / "locked_out" / "pending_confirm"). The {@code autoApply = true} setting means it is
 * used for all {@code UserStatus} fields without an explicit {@code @Convert} annotation.
 */
@Converter(autoApply = true)
public class UserStatusConverter implements AttributeConverter<UserStatus, String> {

    @Override
    public String convertToDatabaseColumn(UserStatus status) {
        return status == null ? null : status.dbValue();
    }

    @Override
    public UserStatus convertToEntityAttribute(String value) {
        return value == null ? null : UserStatus.fromDbValue(value);
    }
}
