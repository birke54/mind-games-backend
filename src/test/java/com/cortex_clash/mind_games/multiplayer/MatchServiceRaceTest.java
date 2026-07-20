package com.cortex_clash.mind_games.multiplayer;

import com.cortex_clash.mind_games.auth.UserRepository;
import com.cortex_clash.mind_games.domain.Match;
import com.cortex_clash.mind_games.domain.MatchPlayer;
import com.cortex_clash.mind_games.domain.MatchStatus;
import com.cortex_clash.mind_games.domain.UserBoard;
import com.cortex_clash.mind_games.generate.BoardDifficulty;
import com.cortex_clash.mind_games.generate.GeneratedBoardRepository;
import com.cortex_clash.mind_games.generate.UserBoardRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scoring a race from ordinary board saves. The rules that matter: progress is recomputed from the
 * grid the player actually sent rather than reported by the client, and the first completing save
 * decides the match — a second finisher moments later must not overwrite the winner.
 */
class MatchServiceRaceTest {

    private static final Integer WINNER_ID = 1;
    private static final Integer LOSER_ID = 2;
    private static final Long MATCH_ID = 10L;

    private static final String SOLUTION = "123456789".repeat(9);
    private static final String EMPTY = "0".repeat(81);

    private final MatchRepository matchRepository = mock(MatchRepository.class);
    private final MatchPlayerRepository matchPlayerRepository = mock(MatchPlayerRepository.class);
    private final GeneratedBoardRepository generatedBoardRepository = mock(GeneratedBoardRepository.class);
    private final UserBoardRepository userBoardRepository = mock(UserBoardRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private final MatchService matchService = new MatchService(
            matchRepository, matchPlayerRepository, generatedBoardRepository,
            userBoardRepository, userRepository);

    /** A running race whose match row is available under the write lock. */
    private Match givenActiveMatch() {
        Match match = new Match("7QK4TB", BoardDifficulty.EASY, WINNER_ID);
        ReflectionTestUtils.setField(match, "id", MATCH_ID);
        match.start();
        when(matchRepository.findLockedById(MATCH_ID)).thenReturn(Optional.of(match));
        return match;
    }

    /**
     * A board in the match, filled to {@code currentState}.
     *
     * @param userId       whose board it is
     * @param currentState the grid as saved
     */
    private UserBoard givenBoard(Integer userId, String currentState) {
        UserBoard board = new UserBoard(userId, MATCH_ID, BoardDifficulty.EASY, EMPTY, SOLUTION);
        board.saveProgress(currentState, board.getNotes(), 0);
        return board;
    }

    private MatchPlayer givenSeat(Integer userId) {
        MatchPlayer seat = new MatchPlayer(MATCH_ID, userId);
        when(matchPlayerRepository.findByMatchIdAndUserId(MATCH_ID, userId))
                .thenReturn(Optional.of(seat));
        return seat;
    }

    @Test
    void progressIsRecomputedFromTheSavedGrid() {
        givenActiveMatch();
        MatchPlayer seat = givenSeat(WINNER_ID);

        // Nine correct digits and one wrong one: a client-reported figure would count all ten.
        String partial = SOLUTION.substring(0, 9) + "9" + EMPTY.substring(10);
        matchService.recordBoardSaved(givenBoard(WINNER_ID, partial));

        assertEquals(9, seat.getCorrectCells(), "a wrong digit is not progress");
    }

    @Test
    void theFirstCompletingSaveWinsTheMatch() {
        Match match = givenActiveMatch();
        MatchPlayer seat = givenSeat(WINNER_ID);

        matchService.recordBoardSaved(givenBoard(WINNER_ID, SOLUTION));

        assertEquals(MatchStatus.FINISHED, match.getStatus());
        assertEquals(WINNER_ID, match.getWinnerUserId());
        assertEquals(MatchPlayer.TOTAL_CELLS, seat.getCorrectCells());
        assertNotNull(seat.getFinishedAt());
    }

    @Test
    void aSecondFinisherDoesNotStealTheWin() {
        Match match = givenActiveMatch();
        givenSeat(WINNER_ID);
        MatchPlayer loserSeat = givenSeat(LOSER_ID);

        matchService.recordBoardSaved(givenBoard(WINNER_ID, SOLUTION));
        matchService.recordBoardSaved(givenBoard(LOSER_ID, SOLUTION));

        assertEquals(WINNER_ID, match.getWinnerUserId(), "the race is decided by who got here first");
        assertNotNull(loserSeat.getFinishedAt(),
                "the loser still finished their board, they just did not win");
    }

    @Test
    void aSoloBoardNeverTouchesTheMatchTables() {
        UserBoard solo = new UserBoard(WINNER_ID, BoardDifficulty.EASY, EMPTY, SOLUTION);
        solo.saveProgress(SOLUTION, solo.getNotes(), 0);

        matchService.recordBoardSaved(solo);

        verify(matchPlayerRepository, never()).save(any());
        verify(matchRepository, never()).findLockedById(any());
    }

    @Test
    void aBoardWhoseOwnerHasNoSeatIsIgnored() {
        Match match = givenActiveMatch();
        when(matchPlayerRepository.findByMatchIdAndUserId(MATCH_ID, WINNER_ID))
                .thenReturn(Optional.empty());

        matchService.recordBoardSaved(givenBoard(WINNER_ID, SOLUTION));

        assertEquals(MatchStatus.ACTIVE, match.getStatus());
        assertNull(match.getWinnerUserId());
    }
}
