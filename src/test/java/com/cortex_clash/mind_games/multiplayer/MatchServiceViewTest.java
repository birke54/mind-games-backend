package com.cortex_clash.mind_games.multiplayer;

import com.cortex_clash.mind_games.auth.UserRepository;
import com.cortex_clash.mind_games.domain.Match;
import com.cortex_clash.mind_games.domain.MatchPlayer;
import com.cortex_clash.mind_games.domain.MatchStatus;
import com.cortex_clash.mind_games.domain.User;
import com.cortex_clash.mind_games.domain.UserBoard;
import com.cortex_clash.mind_games.generate.BoardDifficulty;
import com.cortex_clash.mind_games.generate.GeneratedBoardRepository;
import com.cortex_clash.mind_games.generate.UserBoardRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a player is allowed to see of a match they are in.
 *
 * <p>The rule under test is the solution's release: withheld for as long as the race could still be
 * won with it, then handed to <em>both</em> players once the match is decided — including the one
 * who lost, whose own board stays frozen in progress and would otherwise never show the answer.
 */
class MatchServiceViewTest {

    private static final String VIEWER = "alice";
    private static final Integer VIEWER_ID = 1;
    private static final Integer OPPONENT_ID = 2;
    private static final Long MATCH_ID = 10L;
    private static final Long BOARD_ID = 99L;

    private static final String PUZZLE = "0".repeat(81);
    private static final String SOLUTION = "123456789".repeat(9);

    private final MatchRepository matchRepository = mock(MatchRepository.class);
    private final MatchPlayerRepository matchPlayerRepository = mock(MatchPlayerRepository.class);
    private final GeneratedBoardRepository generatedBoardRepository = mock(GeneratedBoardRepository.class);
    private final UserBoardRepository userBoardRepository = mock(UserBoardRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private final MatchService matchService = new MatchService(
            matchRepository, matchPlayerRepository, generatedBoardRepository,
            userBoardRepository, userRepository);

    /** A started race the viewer is playing in, with their board dealt. */
    private Match givenActiveMatch() {
        Match match = new Match("7QK4TB", BoardDifficulty.EASY, VIEWER_ID);
        ReflectionTestUtils.setField(match, "id", MATCH_ID);
        match.start();

        User viewer = mock(User.class);
        when(viewer.getId()).thenReturn(VIEWER_ID);
        when(userRepository.findByUsername(VIEWER)).thenReturn(Optional.of(viewer));
        when(userRepository.findAllById(any())).thenReturn(List.of());

        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(match));
        when(matchRepository.findLockedById(MATCH_ID)).thenReturn(Optional.of(match));
        when(matchPlayerRepository.findByMatchIdAndUserId(MATCH_ID, VIEWER_ID))
                .thenReturn(Optional.of(new MatchPlayer(MATCH_ID, VIEWER_ID)));
        when(matchPlayerRepository.findByMatchIdOrderByJoinedAtAsc(MATCH_ID)).thenReturn(
                List.of(new MatchPlayer(MATCH_ID, VIEWER_ID), new MatchPlayer(MATCH_ID, OPPONENT_ID)));

        UserBoard board = new UserBoard(VIEWER_ID, MATCH_ID, BoardDifficulty.EASY, PUZZLE, SOLUTION);
        ReflectionTestUtils.setField(board, "id", BOARD_ID);
        when(userBoardRepository.findByMatchIdAndUserId(MATCH_ID, VIEWER_ID))
                .thenReturn(Optional.of(board));
        return match;
    }

    @Test
    void aLiveRaceWithholdsTheSolution() {
        givenActiveMatch();

        MatchSnapshot snapshot = matchService.getMatch(MATCH_ID, VIEWER);

        assertNull(snapshot.viewerSolution(), "it could still be used to win the race");
        assertEquals(BOARD_ID, snapshot.viewerBoardId());
    }

    @Test
    void aWaitingLobbyWithholdsTheSolution() {
        Match match = givenActiveMatch();
        ReflectionTestUtils.setField(match, "status", MatchStatus.WAITING);

        assertNull(matchService.getMatch(MATCH_ID, VIEWER).viewerSolution());
    }

    @Test
    void theWinnerSeesTheSolutionOnceTheMatchIsDecided() {
        Match match = givenActiveMatch();
        match.finishWith(VIEWER_ID);

        assertEquals(SOLUTION, matchService.getMatch(MATCH_ID, VIEWER).viewerSolution());
    }

    @Test
    void theLoserAlsoSeesTheSolution() {
        Match match = givenActiveMatch();
        // The opponent got there first, so the viewer's own board is frozen mid-puzzle.
        match.finishWith(OPPONENT_ID);

        assertEquals(SOLUTION, matchService.getMatch(MATCH_ID, VIEWER).viewerSolution(),
                "losing a race should not mean never seeing the answer");
    }

    @Test
    void anAbandonedMatchReleasesTheSolutionToo() {
        Match match = givenActiveMatch();
        match.abandon();

        assertEquals(SOLUTION, matchService.getMatch(MATCH_ID, VIEWER).viewerSolution(),
                "there is no race left to protect");
    }

    @Test
    void concedingReleasesTheSolutionInTheSameResponse() {
        givenActiveMatch();

        // The forfeit response is what the client renders; it should not need a second poll.
        assertEquals(SOLUTION, matchService.forfeit(MATCH_ID, VIEWER).viewerSolution());
    }

    @Test
    void aMatchYouAreNotInStaysUnreadable() {
        givenActiveMatch();
        when(matchPlayerRepository.findByMatchIdAndUserId(MATCH_ID, VIEWER_ID))
                .thenReturn(Optional.empty());

        assertThrows(MatchNotFoundException.class, () -> matchService.getMatch(MATCH_ID, VIEWER));
    }
}
