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

    /**
     * Converts the {@link BoardDifficulty} enum value to its database column representation.
     *
     * @param difficulty the enum value to convert, may be null
     * @return the lowercase database string value, or null if the input is null
     */
    @Override
    public String convertToDatabaseColumn(BoardDifficulty difficulty) {
        return difficulty == null ? null : difficulty.dbValue();
    }

    /**
     * Converts the database column value to its corresponding {@link BoardDifficulty} enum constant.
     *
     * @param value the database string value to convert, may be null
     * @return the matching BoardDifficulty enum constant, or null if the input is null
     * @throws IllegalArgumentException if the value doesn't match any known difficulty level
     */
    @Override
    public BoardDifficulty convertToEntityAttribute(String value) {
        return value == null ? null : BoardDifficulty.fromDbValue(value);
    }
}
