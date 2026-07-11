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

    /**
     * Converts the {@link UserStatus} enum value to its database column representation.
     *
     * @param status the enum value to convert, may be null
     * @return the lowercase database string value, or null if the input is null
     */
    @Override
    public String convertToDatabaseColumn(UserStatus status) {
        return status == null ? null : status.dbValue();
    }

    /**
     * Converts the database column value to its corresponding {@link UserStatus} enum constant.
     *
     * @param value the database string value to convert, may be null
     * @return the matching UserStatus enum constant, or null if the input is null
     * @throws IllegalArgumentException if the value doesn't match any known status
     */
    @Override
    public UserStatus convertToEntityAttribute(String value) {
        return value == null ? null : UserStatus.fromDbValue(value);
    }
}
