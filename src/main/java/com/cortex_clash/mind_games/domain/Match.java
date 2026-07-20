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
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * A two-player race on one puzzle. The match itself holds no board: when the second player joins,
 * a single board is claimed from the pool and copied into a {@link UserBoard} for each player, so
 * the ordinary save and completion machinery carries the game and this row only tracks who is
 * racing and how it ended.
 *
 * <p>The race is scored on {@link #getStartedAt()} and {@link #getFinishedAt()}, both stamped
 * server-side. {@code UserBoard.elapsedSeconds} is accumulated by the client and cannot be trusted
 * to settle a race.
 */
@Entity
@Table(name = "matches")
public class Match {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Shared out-of-band and typed in by the joiner. Unique across all matches, live or not. */
    @Column(name = "join_code", nullable = false, length = 8, updatable = false)
    private String joinCode;

    @Convert(converter = BoardDifficultyConverter.class)
    @Column(name = "difficulty_rating", nullable = false)
    private BoardDifficulty difficulty;

    @Convert(converter = MatchStatusConverter.class)
    @Column(name = "status", nullable = false)
    private MatchStatus status = MatchStatus.WAITING;

    /** The player who created the lobby. */
    @Column(name = "host_user_id", nullable = false, updatable = false)
    private Integer hostUserId;

    /** The first player whose board completed; {@code null} until the race is decided. */
    @Column(name = "winner_user_id")
    private Integer winnerUserId;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at")
    private Instant createdAt;

    /** When the second player joined and the boards were dealt; {@code null} while waiting. */
    @Column(name = "started_at")
    private Instant startedAt;

    /** When the race was decided; {@code null} until then. */
    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Required by JPA. */
    protected Match() {
    }

    /**
     * Opens a lobby. No board is dealt yet — the pool is only touched once someone actually joins,
     * so a lobby nobody joins costs no puzzle.
     *
     * @param joinCode   the code the second player will use to find this match
     * @param difficulty the tier both players will race on
     * @param hostUserId the player creating the lobby
     */
    public Match(String joinCode, BoardDifficulty difficulty, Integer hostUserId) {
        this.joinCode = joinCode;
        this.difficulty = difficulty;
        this.hostUserId = hostUserId;
    }

    /**
     * Starts the race, stamping the clock the result will be measured against.
     *
     * @throws IllegalStateException if the match is not waiting for a second player
     */
    public void start() {
        if (status != MatchStatus.WAITING) {
            throw new IllegalStateException("Match " + id + " is " + status.dbValue() + ", not waiting");
        }
        this.status = MatchStatus.ACTIVE;
        this.startedAt = Instant.now();
    }

    /**
     * Declares a winner. Only the first call takes effect: the race is decided by whichever
     * completing save reaches the server first, and a second player finishing moments later must
     * not overwrite that. Callers hold a write lock on the row, so the check is not a race itself.
     *
     * @param userId the player whose board just completed
     * @return {@code true} if this call decided the match, {@code false} if it was already over
     */
    public boolean finishWith(Integer userId) {
        if (status != MatchStatus.ACTIVE) {
            return false;
        }
        this.status = MatchStatus.FINISHED;
        this.winnerUserId = userId;
        this.finishedAt = Instant.now();
        return true;
    }

    /** Ends the match without a winner — expired in the lobby, or given up on. */
    public void abandon() {
        this.status = MatchStatus.ABANDONED;
        this.finishedAt = Instant.now();
    }

    /** Whether the race is still to be decided. */
    public boolean isLive() {
        return status == MatchStatus.WAITING || status == MatchStatus.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public String getJoinCode() {
        return joinCode;
    }

    public BoardDifficulty getDifficulty() {
        return difficulty;
    }

    public MatchStatus getStatus() {
        return status;
    }

    public Integer getHostUserId() {
        return hostUserId;
    }

    public Integer getWinnerUserId() {
        return winnerUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
