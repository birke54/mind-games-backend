package com.cortex_clash.mind_games.domain;

import com.cortex_clash.mind_games.generate.BoardDifficulty;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A pregenerated Sudoku board held in the pool, ready to be handed to a player. The board is
 * stored as an 81-char row-major string (see {@code com.cortex_clash.mind_games.generate.Board}).
 */
@Entity
@Table(name = "generated_boards")
public class GeneratedBoard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Convert(converter = BoardDifficultyConverter.class)
    @Column(name = "difficulty_rating", nullable = false)
    private BoardDifficulty difficulty;

    @Column(name = "board_state", nullable = false, length = 81)
    private String boardState;

    @Column(name = "solution_state", nullable = false, length = 81)
    private String solutionState;

    /** Managed by the database (DEFAULT CURRENT_TIMESTAMP). */
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected GeneratedBoard() {
        // Required by JPA.
    }

    public GeneratedBoard(BoardDifficulty difficulty, String boardState, String solutionState) {
        this.difficulty = difficulty;
        this.boardState = boardState;
        this.solutionState = solutionState;
    }

    public Long getId() {
        return id;
    }

    public BoardDifficulty getDifficulty() {
        return difficulty;
    }

    public String getBoardState() {
        return boardState;
    }

    public String getSolutionState() {
        return solutionState;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
