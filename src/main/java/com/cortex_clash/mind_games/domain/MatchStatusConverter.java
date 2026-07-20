package com.cortex_clash.mind_games.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter for {@link MatchStatus} enum values.
 *
 * <p>Translates between the enum constants and their database representations (lowercase strings:
 * "waiting" / "active" / "finished" / "abandoned"). The {@code autoApply = true} setting means it
 * is used for all {@code MatchStatus} fields without an explicit {@code @Convert} annotation.
 */
@Converter(autoApply = true)
public class MatchStatusConverter implements AttributeConverter<MatchStatus, String> {

    @Override
    public String convertToDatabaseColumn(MatchStatus status) {
        return status == null ? null : status.dbValue();
    }

    @Override
    public MatchStatus convertToEntityAttribute(String value) {
        return value == null ? null : MatchStatus.fromDbValue(value);
    }
}
