package com.cortex_clash.mind_games.multiplayer;

import com.cortex_clash.mind_games.auth.UserRepository;
import com.cortex_clash.mind_games.domain.Match;
import com.cortex_clash.mind_games.domain.MatchPlayer;
import com.cortex_clash.mind_games.domain.MatchStatus;
import com.cortex_clash.mind_games.domain.User;
import com.cortex_clash.mind_games.generate.BoardDifficulty;
import com.cortex_clash.mind_games.generate.GeneratedBoardRepository;
import com.cortex_clash.mind_games.generate.UserBoardRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Conceding a race. The rule that matters: giving up hands the win to the opponent rather than
 * quietly deleting the match, so "I give up" means something to the other player.
 */
class MatchServiceForfeitTest {

    private static final String LOSER = "alice";
    private static final Integer LOSER_ID = 1;
    private static final Integer OPPONENT_ID = 2;
    private static final Long MATCH_ID = 10L;

    private final MatchRepository matchRepository = mock(MatchRepository.class);
    private final MatchPlayerRepository matchPlayerRepository = mock(MatchPlayerRepository.class);
    private final GeneratedBoardRepository generatedBoardRepository = mock(GeneratedBoardRepository.class);
    private final UserBoardRepository userBoardRepository = mock(UserBoardRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private final MatchService matchService = new MatchService(
            matchRepository, matchPlayerRepository, generatedBoardRepository,
            userBoardRepository, userRepository);

    private Match givenMatch(MatchStatus status) {
        Match match = new Match("7QK4TB", BoardDifficulty.EASY, LOSER_ID);
        if (status != MatchStatus.WAITING) {
            match.start();
        }

        User user = mock(User.class);
        when(user.getId()).thenReturn(LOSER_ID);
        when(userRepository.findByUsername(LOSER)).thenReturn(Optional.of(user));
        when(userRepository.findAllById(any())).thenReturn(List.of());
        when(matchRepository.findLockedById(MATCH_ID)).thenReturn(Optional.of(match));
        when(userBoardRepository.findByMatchIdAndUserId(any(), any())).thenReturn(Optional.empty());
        return match;
    }

    private void givenSeats(MatchPlayer... seats) {
        when(matchPlayerRepository.findByMatchIdOrderByJoinedAtAsc(any())).thenReturn(List.of(seats));
    }

    @Test
    void concedingAStartedRaceHandsTheWinToTheOpponent() {
        Match match = givenMatch(MatchStatus.ACTIVE);
        givenSeats(new MatchPlayer(MATCH_ID, LOSER_ID), new MatchPlayer(MATCH_ID, OPPONENT_ID));

        matchService.forfeit(MATCH_ID, LOSER);

        assertEquals(MatchStatus.FINISHED, match.getStatus());
        assertEquals(OPPONENT_ID, match.getWinnerUserId());
    }

    @Test
    void closingAnUnjoinedLobbyEndsItWithNoWinner() {
        Match match = givenMatch(MatchStatus.WAITING);
        givenSeats(new MatchPlayer(MATCH_ID, LOSER_ID));

        matchService.forfeit(MATCH_ID, LOSER);

        assertEquals(MatchStatus.ABANDONED, match.getStatus());
        assertNull(match.getWinnerUserId(), "there is nobody to award an unjoined lobby to");
    }

    @Test
    void concedingTwiceDoesNotChangeTheResult() {
        Match match = givenMatch(MatchStatus.ACTIVE);
        givenSeats(new MatchPlayer(MATCH_ID, LOSER_ID), new MatchPlayer(MATCH_ID, OPPONENT_ID));

        matchService.forfeit(MATCH_ID, LOSER);
        matchService.forfeit(MATCH_ID, LOSER);

        assertEquals(MatchStatus.FINISHED, match.getStatus());
        assertEquals(OPPONENT_ID, match.getWinnerUserId());
    }

    @Test
    void someoneElsesMatchIsIndistinguishableFromOneThatDoesNotExist() {
        givenMatch(MatchStatus.ACTIVE);
        // Neither seat belongs to the caller.
        givenSeats(new MatchPlayer(MATCH_ID, OPPONENT_ID), new MatchPlayer(MATCH_ID, 3));

        assertThrows(MatchNotFoundException.class, () -> matchService.forfeit(MATCH_ID, LOSER));
    }

    @Test
    void anUnknownMatchIsNotFound() {
        givenMatch(MatchStatus.ACTIVE);
        when(matchRepository.findLockedById(anyLong())).thenReturn(Optional.empty());

        assertThrows(MatchNotFoundException.class, () -> matchService.forfeit(MATCH_ID, LOSER));
    }
}
