package com.cortex_clash.mind_games.domain;

import com.cortex_clash.mind_games.generate.BoardDifficulty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A race is decided once. Two players can finish within milliseconds of each other, so the
 * transition to a winner has to be one-way regardless of what arrives afterwards.
 */
class MatchTest {

    private Match waitingMatch() {
        return new Match("7QK4TB", BoardDifficulty.MODERATE, 1);
    }

    @Test
    void startsWaitingWithNoWinnerAndNoClock() {
        Match match = waitingMatch();

        assertEquals(MatchStatus.WAITING, match.getStatus());
        assertNull(match.getStartedAt());
        assertNull(match.getWinnerUserId());
        assertTrue(match.isLive());
    }

    @Test
    void startingStampsTheClockTheResultIsMeasuredFrom() {
        Match match = waitingMatch();
        match.start();

        assertEquals(MatchStatus.ACTIVE, match.getStatus());
        assertNotNull(match.getStartedAt());
    }

    @Test
    void cannotStartTwice() {
        Match match = waitingMatch();
        match.start();

        assertThrows(IllegalStateException.class, match::start);
    }

    @Test
    void firstFinisherWinsAndALaterOneCannotOverwriteThem() {
        Match match = waitingMatch();
        match.start();

        assertTrue(match.finishWith(1), "the first completing save decides the race");
        assertEquals(MatchStatus.FINISHED, match.getStatus());
        assertEquals(1, match.getWinnerUserId());
        assertNotNull(match.getFinishedAt());

        assertFalse(match.finishWith(2), "a save landing after the race is over changes nothing");
        assertEquals(1, match.getWinnerUserId());
        assertFalse(match.isLive());
    }

    @Test
    void aMatchThatNeverStartedCannotBeWon() {
        Match match = waitingMatch();

        assertFalse(match.finishWith(1));
        assertEquals(MatchStatus.WAITING, match.getStatus());
        assertNull(match.getWinnerUserId());
    }
}
