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

    /**
     * The match this board was dealt for, or {@code null} for an ordinary solo board.
     *
     * <p>Load-bearing for more than bookkeeping: a board in a live match does not send its solution
     * to the client (see {@link #revealsSolution()}). Solo play ships the solution so validation
     * and hints can run locally, which is harmless when the only person you can cheat is yourself —
     * in a race it would let either player win instantly from a value already in their browser.
     */
    @Column(name = "match_id", updatable = false)
    private Long matchId;

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
        this(userId, null, difficulty, puzzleState, solutionState);
    }

    /**
     * Deals a board that belongs to a match. Identical to a solo board except that it withholds its
     * solution from the client while the race is undecided.
     *
     * @param userId        the claiming user
     * @param matchId       the match this board was dealt for, or {@code null} for solo play
     * @param difficulty    the board's difficulty tier
     * @param puzzleState   the starting state, 81-char row-major
     * @param solutionState the completed state, 81-char row-major
     */
    public UserBoard(Integer userId, Long matchId, BoardDifficulty difficulty,
                     String puzzleState, String solutionState) {
        this.userId = userId;
        this.matchId = matchId;
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

    /**
     * Whether a hint could fill this cell: it is not a given, and the player has not already put
     * something there.
     */
    private boolean isHintable(int index) {
        return puzzleState.charAt(index) == '0' && currentState.charAt(index) == '0';
    }

    /**
     * Picks the cell a hint should fill. Honours the player's selection when it can, and otherwise
     * falls back to the first empty cell — a hint that quietly did nothing because the wrong cell
     * was selected is worse than one that lands somewhere else.
     *
     * @param preferred the cell the player had selected, or {@code null} if none
     * @return the cell to reveal, or {@code -1} if the grid has no empty cell left
     */
    public int chooseHintCell(Integer preferred) {
        if (preferred != null && preferred >= 0 && preferred < currentState.length()
                && isHintable(preferred)) {
            return preferred;
        }
        for (int i = 0; i < currentState.length(); i++) {
            if (isHintable(i)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Fills one cell with its solved digit. Routed through {@link #saveProgress} so a hint that
     * happens to fill the last cell completes the board on exactly the same terms as a player
     * typing it would.
     *
     * @param index the cell to reveal
     * @throws IllegalStateException if the board is already completed
     */
    public void revealCell(int index) {
        StringBuilder revealed = new StringBuilder(currentState);
        revealed.setCharAt(index, solutionState.charAt(index));
        saveProgress(revealed.toString(), notes, elapsedSeconds);
    }

    /** Whether the board is finished — and therefore frozen. */
    public boolean isCompleted() {
        return status == BoardStatus.COMPLETED;
    }

    /** Whether this board is being played as one half of a match. */
    public boolean isMatchBoard() {
        return matchId != null;
    }

    /**
     * Whether the solution may be sent to the client. Solo boards always may — local validation and
     * hints depend on it. A match board may not, until its own player has completed it and has
     * nothing left to gain from it.
     *
     * <p>A player who <em>loses</em> a race would therefore never see the solution from here, since
     * their board stays in progress. Deciding otherwise needs the match's status, which this row
     * does not carry — so that reveal belongs to the match endpoint rather than here. Keep this
     * method about the board alone.
     */
    public boolean revealsSolution() {
        return !isMatchBoard() || isCompleted();
    }

    /**
     * Counts how many cells currently match the solution — the opponent-visible progress figure.
     * Empty cells never count, so a fresh board scores only its givens.
     *
     * @return cells matching the solution, 0 to 81
     */
    public int countCorrectCells() {
        int correct = 0;
        for (int i = 0; i < currentState.length(); i++) {
            char digit = currentState.charAt(i);
            if (digit != '0' && digit == solutionState.charAt(i)) {
                correct++;
            }
        }
        return correct;
    }

    public Long getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public Long getMatchId() {
        return matchId;
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
