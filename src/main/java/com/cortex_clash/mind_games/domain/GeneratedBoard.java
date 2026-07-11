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

    /**
     * The unique identifier for this generated board.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * The difficulty rating of this Sudoku board.
     */
    @Convert(converter = BoardDifficultyConverter.class)
    @Column(name = "difficulty_rating", nullable = false)
    private BoardDifficulty difficulty;

    /**
     * The initial puzzle state as an 81-character string in row-major order.
     * Empty cells are represented by zeros.
     */
    @Column(name = "board_state", nullable = false, length = 81)
    private String boardState;

    /**
     * The complete solution to this puzzle as an 81-character string in row-major order.
     */
    @Column(name = "solution_state", nullable = false, length = 81)
    private String solutionState;

    /**
     * The timestamp when this board was generated and stored in the database.
     * Managed by the database (DEFAULT CURRENT_TIMESTAMP).
     */
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * Protected no-argument constructor required by JPA.
     */
    protected GeneratedBoard() {
        // Required by JPA.
    }

    /**
     * Constructs a new GeneratedBoard with the specified difficulty, puzzle state, and solution.
     *
     * @param difficulty    the difficulty rating of the board
     * @param boardState    the initial puzzle state as an 81-character string
     * @param solutionState the complete solution as an 81-character string
     */
    public GeneratedBoard(BoardDifficulty difficulty, String boardState, String solutionState) {
        this.difficulty = difficulty;
        this.boardState = boardState;
        this.solutionState = solutionState;
    }

    /**
     * Returns the unique identifier of this generated board.
     *
     * @return the board ID
     */
    public Long getId() {
        return id;
    }

    /**
     * Returns the difficulty rating of this board.
     *
     * @return the difficulty rating
     */
    public BoardDifficulty getDifficulty() {
        return difficulty;
    }

    /**
     * Returns the initial puzzle state as an 81-character string in row-major order.
     *
     * @return the board state
     */
    public String getBoardState() {
        return boardState;
    }

    /**
     * Returns the complete solution to this puzzle as an 81-character string in row-major order.
     *
     * @return the solution state
     */
    public String getSolutionState() {
        return solutionState;
    }

    /**
     * Returns the timestamp when this board was generated and stored.
     *
     * @return the creation timestamp
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
