package com.cortex_clash.mind_games.domain;

import com.cortex_clash.mind_games.generate.BoardDifficulty;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A board a user has claimed and is playing. Created when a board is served out of the
 * {@code generated_boards} pool — that pool row is deleted in the same transaction, so from then
 * on this row is the only copy of the puzzle.
 *
 * <p>The three board strings are all 81-char row-major (see
 * {@code com.cortex_clash.mind_games.generate.Board}): {@code puzzleState} is the immutable deal,
 * {@code currentState} is the player's progress, and {@code solutionState} is the completed board.
 */
@Entity
@Table(name = "user_boards")
public class UserBoard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Owning user. Deleting the user cascades this row away (FK ON DELETE CASCADE). */
    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Convert(converter = BoardDifficultyConverter.class)
    @Column(name = "difficulty_rating", nullable = false)
    private BoardDifficulty difficulty;

    /**
     * Whether the board is still being played or is finished. Derived by the server on every save
     * from {@code currentState} against {@code solutionState} — never taken from the client, which
     * holds the solution and could otherwise simply declare a board completed.
     *
     * <p>Completion is terminal: once this reaches {@link BoardStatus#COMPLETED} the board is
     * immutable and {@link #saveProgress} refuses to touch it.
     */
    @Convert(converter = BoardStatusConverter.class)
    @Column(name = "status", nullable = false)
    private BoardStatus status = BoardStatus.IN_PROGRESS;

    /** When the board was completed, or {@code null} while it is still in progress. */
    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * The board as dealt, never modified. A cell is a given — a clue the player may not change —
     * exactly when {@code puzzleState.charAt(i) != '0'}.
     */
    @Column(name = "puzzle_state", nullable = false, length = 81)
    private String puzzleState;

    /** The player's board right now: the givens plus whatever they have entered. */
    @Column(name = "current_state", nullable = false, length = 81)
    private String currentState;

    /** The puzzle's unique completed solution. */
    @Column(name = "solution_state", nullable = false, length = 81)
    private String solutionState;

    /**
     * Pencil marks, keyed by row-major cell index as a string ({@code "0"} through {@code "80"}),
     * each value the digits noted in that cell. Cells with no notes are absent from the map, so a
     * fresh board is an empty map rather than 81 empty lists.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notes", nullable = false)
    private Map<String, List<Integer>> notes = new LinkedHashMap<>();

    /**
     * Total seconds the player has spent on this board. Accumulated by the client and sent up on
     * save — it cannot be derived from the timestamps, since a board can sit idle for days.
     */
    @Column(name = "elapsed_seconds", nullable = false)
    private long elapsedSeconds;

    /**
     * When the board was dealt out of the pool. Set by the database (DEFAULT CURRENT_TIMESTAMP);
     * {@link Generated} makes Hibernate read the value back after the insert, so a freshly claimed
     * board carries a real timestamp instead of {@code null}.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "claimed_at")
    private Instant claimedAt;

    /**
     * When the board was last saved. Set by the database (ON UPDATE CURRENT_TIMESTAMP) and read
     * back after both insert and update, so a saved board reflects the write that just happened.
     */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "last_modified_at")
    private Instant lastModifiedAt;

    /** Required by JPA. */
    protected UserBoard() {
    }

    /**
     * Deals a freshly claimed board to a user: progress starts as an exact copy of the puzzle,
     * with no notes and no time on the clock.
     *
     * @param userId        the claiming user
     * @param difficulty    the board's difficulty tier
     * @param puzzleState   the starting state, 81-char row-major
     * @param solutionState the completed state, 81-char row-major
     */
    public UserBoard(Integer userId, BoardDifficulty difficulty, String puzzleState, String solutionState) {
        this.userId = userId;
        this.difficulty = difficulty;
        this.puzzleState = puzzleState;
        this.currentState = puzzleState;
        this.solutionState = solutionState;
    }

    /**
     * Records the player's progress. If the grid now matches the solution the board completes:
     * {@code status} flips to {@link BoardStatus#COMPLETED} and {@code completedAt} is stamped.
     * {@code lastModifiedAt} is bumped by the database on update.
     *
     * <p>This is the board's terminal state. A completed board is immutable, so calling this again
     * is a programming error — callers must check {@link #isCompleted()} first and turn it into a
     * suitable response rather than letting this throw.
     *
     * @param currentState   the player's board, 81-char row-major
     * @param notes          pencil marks keyed by row-major cell index; cells with none are omitted
     * @param elapsedSeconds total time spent on the board so far
     * @throws IllegalStateException if the board is already completed
     */
    public void saveProgress(String currentState, Map<String, List<Integer>> notes, long elapsedSeconds) {
        if (isCompleted()) {
            throw new IllegalStateException("Board " + id + " is completed and cannot be modified");
        }
        this.currentState = currentState;
        this.notes = notes == null ? new LinkedHashMap<>() : notes;
        this.elapsedSeconds = elapsedSeconds;
        if (solutionState.equals(currentState)) {
            this.status = BoardStatus.COMPLETED;
            this.completedAt = Instant.now();
        }
    }

    /** Whether the board is finished — and therefore frozen. */
    public boolean isCompleted() {
        return status == BoardStatus.COMPLETED;
    }

    public Long getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public BoardDifficulty getDifficulty() {
        return difficulty;
    }

    public BoardStatus getStatus() {
        return status;
    }

    public String getPuzzleState() {
        return puzzleState;
    }

    public String getCurrentState() {
        return currentState;
    }

    public String getSolutionState() {
        return solutionState;
    }

    public Map<String, List<Integer>> getNotes() {
        return notes;
    }

    public long getElapsedSeconds() {
        return elapsedSeconds;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public Instant getLastModifiedAt() {
        return lastModifiedAt;
    }
}
