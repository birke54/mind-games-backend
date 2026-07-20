package com.cortex_clash.mind_games.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * One player's seat in a {@link Match}, and the only view either player gets of the other.
 *
 * <p>{@link #getCorrectCells()} is deliberately the whole of the shared progress signal. The
 * opponent's grid is never sent: most of a half-solved grid is correct, so shipping it would hand
 * over the solution the match is otherwise careful to withhold.
 */
@Entity
@Table(name = "match_players")
public class MatchPlayer {

    /** Cells in a Sudoku grid; a player is finished when every one of them is right. */
    public static final int TOTAL_CELLS = 81;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "match_id", nullable = false, updatable = false)
    private Long matchId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Integer userId;

    /**
     * How many cells this player currently has right, recomputed by the server on every save.
     * Not monotonic — clearing a correct digit moves it back down.
     */
    @Column(name = "correct_cells", nullable = false)
    private int correctCells;

    /** When this player completed their board; {@code null} if they have not (or did not). */
    @Column(name = "finished_at")
    private Instant finishedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "joined_at")
    private Instant joinedAt;

    /** Required by JPA. */
    protected MatchPlayer() {
    }

    /**
     * Seats a player in a match.
     *
     * @param matchId the match being joined
     * @param userId  the player taking the seat
     */
    public MatchPlayer(Long matchId, Integer userId) {
        this.matchId = matchId;
        this.userId = userId;
    }

    /**
     * Records how far along this player is.
     *
     * @param correctCells cells currently matching the solution, 0 to {@link #TOTAL_CELLS}
     */
    public void recordProgress(int correctCells) {
        this.correctCells = correctCells;
    }

    /** Marks this player as having completed their board, if not already stamped. */
    public void markFinished() {
        if (finishedAt == null) {
            this.finishedAt = Instant.now();
        }
        this.correctCells = TOTAL_CELLS;
    }

    public Long getId() {
        return id;
    }

    public Long getMatchId() {
        return matchId;
    }

    public Integer getUserId() {
        return userId;
    }

    public int getCorrectCells() {
        return correctCells;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}
