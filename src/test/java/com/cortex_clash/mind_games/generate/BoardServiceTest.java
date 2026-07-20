package com.cortex_clash.mind_games.generate;

import com.cortex_clash.mind_games.auth.UserRepository;
import com.cortex_clash.mind_games.domain.BoardStatus;
import com.cortex_clash.mind_games.domain.GeneratedBoard;
import com.cortex_clash.mind_games.domain.User;
import com.cortex_clash.mind_games.domain.UserBoard;
import com.cortex_clash.mind_games.multiplayer.MatchService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BoardServiceTest {

    private static final String USERNAME = "player";
    private static final String PUZZLE = "0".repeat(81);
    private static final String SOLUTION = "1".repeat(81);

    private final GeneratedBoardRepository generatedBoardRepository = mock(GeneratedBoardRepository.class);
    private final UserBoardRepository userBoardRepository = mock(UserBoardRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final MatchService matchService = mock(MatchService.class);

    private final BoardService boardService =
            new BoardService(generatedBoardRepository, userBoardRepository, userRepository, matchService);

    private final User user = mock(User.class);

    private void givenUserExists() {
        when(user.getId()).thenReturn(7);
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));
    }

    @Test
    void claimingRemovesTheBoardFromThePoolAndRecordsItAgainstTheUser() {
        givenUserExists();
        GeneratedBoard pooled = new GeneratedBoard(BoardDifficulty.MODERATE, PUZZLE, SOLUTION);
        when(generatedBoardRepository.claimNextByDifficulty("moderate")).thenReturn(Optional.of(pooled));
        when(userBoardRepository.save(any(UserBoard.class))).thenAnswer(i -> i.getArgument(0));

        Optional<UserBoard> claimed = boardService.claimBoard(USERNAME, BoardDifficulty.MODERATE);

        assertTrue(claimed.isPresent());
        verify(generatedBoardRepository).delete(pooled);

        ArgumentCaptor<UserBoard> saved = ArgumentCaptor.forClass(UserBoard.class);
        verify(userBoardRepository).save(saved.capture());
        UserBoard board = saved.getValue();
        assertEquals(7, board.getUserId());
        assertEquals(BoardDifficulty.MODERATE, board.getDifficulty());
        assertEquals(PUZZLE, board.getPuzzleState());
        assertEquals(SOLUTION, board.getSolutionState());
        assertEquals(PUZZLE, board.getCurrentState(), "a fresh board starts as a copy of the puzzle");
        assertTrue(board.getNotes().isEmpty(), "a fresh board has no notes");
        assertEquals(0, board.getElapsedSeconds());
        assertEquals(BoardStatus.IN_PROGRESS, board.getStatus(), "a fresh board starts in progress");
    }

    @Test
    void claimingAnExhaustedPoolDealsNothingAndDeletesNothing() {
        givenUserExists();
        when(generatedBoardRepository.claimNextByDifficulty("hard")).thenReturn(Optional.empty());

        assertTrue(boardService.claimBoard(USERNAME, BoardDifficulty.HARD).isEmpty());

        verify(generatedBoardRepository, never()).delete(any());
        verify(userBoardRepository, never()).save(any(UserBoard.class));
    }

    @Test
    void savingProgressStoresTheGridNotesAndElapsedTime() {
        givenUserExists();
        UserBoard existing = new UserBoard(7, BoardDifficulty.EASY, PUZZLE, SOLUTION);
        when(userBoardRepository.findLockedByIdAndUserId(3L, 7)).thenReturn(Optional.of(existing));
        when(userBoardRepository.save(any(UserBoard.class))).thenAnswer(i -> i.getArgument(0));

        Map<String, List<Integer>> notes = Map.of("4", List.of(1, 5, 9));
        String partial = "1".repeat(80) + "0"; // one cell short of the solution
        Optional<UserBoard> saved = boardService.saveProgress(3L, USERNAME, partial, notes, 142);

        assertTrue(saved.isPresent());
        assertEquals(partial, saved.get().getCurrentState());
        assertEquals(notes, saved.get().getNotes());
        assertEquals(142, saved.get().getElapsedSeconds());
        assertEquals(BoardStatus.IN_PROGRESS, saved.get().getStatus(),
                "an unfinished grid stays in progress");
        assertNull(saved.get().getCompletedAt(), "an unfinished board has no completion stamp");
    }

    @Test
    void savingAGridThatMatchesTheSolutionCompletesTheBoard() {
        givenUserExists();
        UserBoard existing = new UserBoard(7, BoardDifficulty.EASY, PUZZLE, SOLUTION);
        when(userBoardRepository.findLockedByIdAndUserId(3L, 7)).thenReturn(Optional.of(existing));
        when(userBoardRepository.save(any(UserBoard.class))).thenAnswer(i -> i.getArgument(0));

        Optional<UserBoard> saved = boardService.saveProgress(3L, USERNAME, SOLUTION, Map.of(), 300);

        assertTrue(saved.isPresent());
        assertEquals(BoardStatus.COMPLETED, saved.get().getStatus());
        assertTrue(saved.get().isCompleted());
        assertNotNull(saved.get().getCompletedAt(),
                "the completing save stamps completedAt, which is the frontend's cue to freeze");
    }

    @Test
    void savingACompletedBoardIsRejectedAndLeavesItUntouched() {
        givenUserExists();
        UserBoard completed = new UserBoard(7, BoardDifficulty.EASY, PUZZLE, SOLUTION);
        completed.saveProgress(SOLUTION, Map.of(), 300);
        when(userBoardRepository.findLockedByIdAndUserId(3L, 7)).thenReturn(Optional.of(completed));

        String tampered = "2" + "1".repeat(80);
        assertThrows(BoardCompletedException.class,
                () -> boardService.saveProgress(3L, USERNAME, tampered, Map.of(), 999));

        verify(userBoardRepository, never()).save(any(UserBoard.class));
        assertEquals(SOLUTION, completed.getCurrentState(), "a completed board is immutable");
        assertEquals(300, completed.getElapsedSeconds());
        assertEquals(BoardStatus.COMPLETED, completed.getStatus());
    }

    @Test
    void theEntityItselfRefusesToBeModifiedOnceCompleted() {
        UserBoard completed = new UserBoard(7, BoardDifficulty.EASY, PUZZLE, SOLUTION);
        completed.saveProgress(SOLUTION, Map.of(), 300);

        assertThrows(IllegalStateException.class,
                () -> completed.saveProgress(PUZZLE, Map.of(), 400));
    }

    @Test
    void savingABoardTheUserDoesNotOwnFindsNothing() {
        givenUserExists();
        when(userBoardRepository.findLockedByIdAndUserId(99L, 7)).thenReturn(Optional.empty());

        assertTrue(boardService.saveProgress(99L, USERNAME, PUZZLE, Map.of(), 0).isEmpty());
        verify(userBoardRepository, never()).save(any(UserBoard.class));
    }
}
