package com.cortex_clash.mind_games.domain;

import com.cortex_clash.mind_games.generate.BoardDifficulty;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter for {@link BoardDifficulty} enum values.
 *
 * <p>This converter automatically translates between the enum constants and their database
 * representations (lowercase strings: "easy", "moderate", "hard"). The {@code autoApply = true}
 * setting means it will be used for all {@code BoardDifficulty} fields without explicit
 * {@code @Convert} annotations.
 */
@Converter(autoApply = true)
public class BoardDifficultyConverter implements AttributeConverter<BoardDifficulty, String> {

    @Override
    public String convertToDatabaseColumn(BoardDifficulty difficulty) {
        return difficulty == null ? null : difficulty.dbValue();
    }

    @Override
    public BoardDifficulty convertToEntityAttribute(String value) {
        return value == null ? null : BoardDifficulty.fromDbValue(value);
    }
}
