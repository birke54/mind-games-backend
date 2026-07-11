package com.cortex_clash.mind_games.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter for {@link BoardStatus} enum values.
 *
 * <p>Translates between the enum constants and their database representations (lowercase strings:
 * "in_progress" / "completed"). The {@code autoApply = true} setting means it is used for all
 * {@code BoardStatus} fields without an explicit {@code @Convert} annotation.
 */
@Converter(autoApply = true)
public class BoardStatusConverter implements AttributeConverter<BoardStatus, String> {

    /**
     * Converts the {@link BoardStatus} enum value to its database column representation.
     *
     * @param status the enum value to convert, may be null
     * @return the lowercase database string value, or null if the input is null
     */
    @Override
    public String convertToDatabaseColumn(BoardStatus status) {
        return status == null ? null : status.dbValue();
    }

    /**
     * Converts the database column value to its corresponding {@link BoardStatus} enum constant.
     *
     * @param value the database string value to convert, may be null
     * @return the matching BoardStatus enum constant, or null if the input is null
     * @throws IllegalArgumentException if the value doesn't match any known status
     */
    @Override
    public BoardStatus convertToEntityAttribute(String value) {
        return value == null ? null : BoardStatus.fromDbValue(value);
    }
}
