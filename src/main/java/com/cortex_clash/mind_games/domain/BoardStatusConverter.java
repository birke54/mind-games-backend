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

    @Override
    public String convertToDatabaseColumn(BoardStatus status) {
        return status == null ? null : status.dbValue();
    }

    @Override
    public BoardStatus convertToEntityAttribute(String value) {
        return value == null ? null : BoardStatus.fromDbValue(value);
    }
}
