package com.cortex_clash.mind_games.multiplayer;

import com.cortex_clash.mind_games.auth.UserRepository;
import com.cortex_clash.mind_games.domain.GeneratedBoard;
import com.cortex_clash.mind_games.domain.Match;
import com.cortex_clash.mind_games.domain.MatchPlayer;
import com.cortex_clash.mind_games.domain.MatchStatus;
import com.cortex_clash.mind_games.domain.User;
import com.cortex_clash.mind_games.domain.UserBoard;
import com.cortex_clash.mind_games.generate.BoardDifficulty;
import com.cortex_clash.mind_games.generate.GeneratedBoardRepository;
import com.cortex_clash.mind_games.generate.UserBoardRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Seating the second player. The rules that matter: one pooled puzzle becomes a board for each
 * player, an exhausted pool leaves the lobby waiting rather than half-starting it, and redeeming
 * your own code again is a refresh rather than an error.
 */
class MatchServiceJoinTest {

    private static final String JOINER = "bob";
    private static final Integer JOINER_ID = 2;
    private static final Integer HOST_ID = 1;
    private static final Long MATCH_ID = 10L;
    private static final String CODE = "7QK4TB";

    private static final String PUZZLE = "1".repeat(81);
    private static final String SOLUTION = "2".repeat(81);

    private final MatchRepository matchRepository = mock(MatchRepository.class);
    private final MatchPlayerRepository matchPlayerRepository = mock(MatchPlayerRepository.class);
    private final GeneratedBoardRepository generatedBoardRepository = mock(GeneratedBoardRepository.class);
    private final UserBoardRepository userBoardRepository = mock(UserBoardRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private final MatchService matchService = new MatchService(
            matchRepository, matchPlayerRepository, generatedBoardRepository,
            userBoardRepository, userRepository);

    /** A lobby with only the host seated, waiting on a second player. */
    private Match givenWaitingLobby() {
        Match match = new Match(CODE, BoardDifficulty.EASY, HOST_ID);
        // Normally assigned on insert; the dealt boards are tied to it, so it has to be set here.
        ReflectionTestUtils.setField(match, "id", MATCH_ID);

        User joiner = mock(User.class);
        when(joiner.getId()).thenReturn(JOINER_ID);
        when(userRepository.findByUsername(JOINER)).thenReturn(Optional.of(joiner));
        when(userRepository.findAllById(any())).thenReturn(List.of());

        when(matchRepository.findLockedByJoinCode(CODE)).thenReturn(Optional.of(match));
        when(matchPlayerRepository.findByMatchIdAndUserId(any(), any())).thenReturn(Optional.empty());
        when(matchPlayerRepository.countByMatchId(any())).thenReturn(1L);
        when(userBoardRepository.findByMatchIdAndUserId(any(), any())).thenReturn(Optional.empty());

        // Once the joiner is seated, both seats are visible to the board-dealing loop.
        when(matchPlayerRepository.findByMatchIdOrderByJoinedAtAsc(any())).thenReturn(
                List.of(new MatchPlayer(MATCH_ID, HOST_ID), new MatchPlayer(MATCH_ID, JOINER_ID)));
        return match;
    }

    private void givenPooledBoard() {
        GeneratedBoard pooled = mock(GeneratedBoard.class);
        when(pooled.getBoardState()).thenReturn(PUZZLE);
        when(pooled.getSolutionState()).thenReturn(SOLUTION);
        when(generatedBoardRepository.claimNextByDifficulty(any())).thenReturn(Optional.of(pooled));
    }

    @Test
    void joiningStartsTheRace() {
        Match match = givenWaitingLobby();
        givenPooledBoard();

        matchService.joinMatch(JOINER, CODE);

        assertEquals(MatchStatus.ACTIVE, match.getStatus());
        assertNotNull(match.getStartedAt(), "the race is scored from a server-side clock");
    }

    @Test
    void bothPlayersGetTheirOwnCopyOfOnePuzzle() {
        givenWaitingLobby();
        givenPooledBoard();

        matchService.joinMatch(JOINER, CODE);

        ArgumentCaptor<UserBoard> dealt = ArgumentCaptor.forClass(UserBoard.class);
        verify(userBoardRepository, times(2)).save(dealt.capture());

        List<UserBoard> boards = new ArrayList<>(dealt.getAllValues());
        assertEquals(List.of(HOST_ID, JOINER_ID), boards.stream().map(UserBoard::getUserId).toList());
        boards.forEach(board -> {
            assertEquals(PUZZLE, board.getPuzzleState(), "both race the same puzzle");
            assertEquals(MATCH_ID, board.getMatchId(), "a dealt board is tied to its match");
            assertTrue(board.isMatchBoard());
        });
    }

    @Test
    void aDealtBoardWithholdsItsSolution() {
        givenWaitingLobby();
        givenPooledBoard();

        matchService.joinMatch(JOINER, CODE);

        ArgumentCaptor<UserBoard> dealt = ArgumentCaptor.forClass(UserBoard.class);
        verify(userBoardRepository, times(2)).save(dealt.capture());
        dealt.getAllValues().forEach(board ->
                assertFalse(board.revealsSolution(),
                        "a live match board must not ship the solution to the browser"));
    }

    @Test
    void anExhaustedPoolLeavesTheLobbyWaiting() {
        Match match = givenWaitingLobby();
        when(generatedBoardRepository.claimNextByDifficulty(any())).thenReturn(Optional.empty());

        Optional<MatchSnapshot> joined = matchService.joinMatch(JOINER, CODE);

        assertTrue(joined.isEmpty(), "the caller gets a 503 rather than a half-started match");
        assertEquals(MatchStatus.WAITING, match.getStatus(), "so the join can simply be retried");
        verify(userBoardRepository, never()).save(any());
        verify(matchPlayerRepository, never()).save(any());
    }

    @Test
    void rejoiningYourOwnMatchIsARefreshNotAnError() {
        Match match = givenWaitingLobby();
        when(matchPlayerRepository.findByMatchIdAndUserId(any(), any()))
                .thenReturn(Optional.of(new MatchPlayer(MATCH_ID, JOINER_ID)));

        Optional<MatchSnapshot> rejoined = matchService.joinMatch(JOINER, CODE);

        assertTrue(rejoined.isPresent());
        assertEquals(MatchStatus.WAITING, match.getStatus(), "a refresh must not re-deal boards");
        verify(generatedBoardRepository, never()).claimNextByDifficulty(any());
    }

    @Test
    void aFullLobbyCannotBeJoined() {
        givenWaitingLobby();
        when(matchPlayerRepository.countByMatchId(any())).thenReturn(2L);

        assertThrows(MatchUnavailableException.class, () -> matchService.joinMatch(JOINER, CODE));
        verify(generatedBoardRepository, never()).claimNextByDifficulty(any());
    }

    @Test
    void aFinishedMatchCannotBeJoined() {
        Match match = givenWaitingLobby();
        match.start();
        match.finishWith(HOST_ID);

        assertThrows(MatchUnavailableException.class, () -> matchService.joinMatch(JOINER, CODE));
    }

    @Test
    void codesAreMatchedCaseInsensitivelyAndTrimmed() {
        givenWaitingLobby();
        givenPooledBoard();

        // Join codes are read aloud and typed by hand, so the wire value is normalized first.
        matchService.joinMatch(JOINER, "  7qk4tb  ");

        verify(matchRepository).findLockedByJoinCode(CODE);
    }

    @Test
    void anUnknownCodeIsNotFound() {
        givenWaitingLobby();
        when(matchRepository.findLockedByJoinCode(any())).thenReturn(Optional.empty());

        assertThrows(MatchNotFoundException.class, () -> matchService.joinMatch(JOINER, "ZZZZZZ"));
    }
}
