package com.cortex_clash.mind_games.generate;

import com.cortex_clash.mind_games.auth.UserRepository;
import com.cortex_clash.mind_games.domain.GeneratedBoard;
import com.cortex_clash.mind_games.domain.User;
import com.cortex_clash.mind_games.domain.UserBoard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deals boards out of the {@code generated_boards} pool and keeps the resulting {@link UserBoard}
 * rows up to date as players make progress.
 *
 * <p>A pooled board is <em>consumed</em> when it is dealt: the row is claimed under a row lock,
 * copied into a {@code UserBoard} for the claiming player, and deleted — all in one transaction,
 * so a board can never be lost between leaving the pool and being persisted, and no two players
 * ever receive the same puzzle. Draining the pool is what eventually trips
 * {@link BoardGenerationService}'s low watermark and triggers a refill, so this service never
 * generates anything itself: if the pool is empty it says so rather than making a player wait on a
 * synchronous solve.
 */
@Service
public class BoardService {
    private static final Logger log = LoggerFactory.getLogger(BoardService.class);

    private final GeneratedBoardRepository generatedBoardRepository;
    private final UserBoardRepository userBoardRepository;
    private final UserRepository userRepository;

    public BoardService(GeneratedBoardRepository generatedBoardRepository,
                        UserBoardRepository userBoardRepository,
                        UserRepository userRepository) {
        this.generatedBoardRepository = generatedBoardRepository;
        this.userBoardRepository = userBoardRepository;
        this.userRepository = userRepository;
    }

    /**
     * Deals one pooled board at the requested difficulty to a user: the pool row is claimed and
     * deleted, and a {@link UserBoard} is created holding the puzzle, its solution, and a copy of
     * the puzzle as the starting progress.
     *
     * @param username   the claiming user, as carried by the bearer token
     * @param difficulty the difficulty tier to deal
     * @return the newly claimed board, or empty if the pool is exhausted at that difficulty (the
     *         refill scheduler will top it back up)
     * @throws IllegalArgumentException if {@code username} does not resolve to a user
     */
    @Transactional
    public Optional<UserBoard> claimBoard(String username, BoardDifficulty difficulty) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Unknown user: " + username));

        Optional<GeneratedBoard> claimed =
                generatedBoardRepository.claimNextByDifficulty(difficulty.dbValue());
        if (claimed.isEmpty()) {
            log.warn("Board pool exhausted for {}; generation is not keeping up with demand", difficulty);
            return Optional.empty();
        }

        GeneratedBoard pooled = claimed.get();
        generatedBoardRepository.delete(pooled);

        UserBoard board = userBoardRepository.save(new UserBoard(
                user.getId(), difficulty, pooled.getBoardState(), pooled.getSolutionState()));
        log.info("Dealt {} board {} (pool board {}) to user {}",
                difficulty, board.getId(), pooled.getId(), username);

        return Optional.of(board);
    }

    /**
     * Saves a player's progress on one of their own boards. The lookup is scoped to the user, so a
     * board belonging to someone else is indistinguishable from one that does not exist.
     *
     * <p>If the saved grid matches the solution the board completes, and that is terminal: the row
     * is frozen and any later save against it is rejected. The board is read under a write lock so
     * two concurrent saves cannot both slip past that check.
     *
     * @param boardId        the board to save
     * @param username       the owning user, as carried by the bearer token
     * @param currentState   the player's board, 81-char row-major
     * @param notes          pencil marks keyed by row-major cell index; cells with none are omitted
     * @param elapsedSeconds total time spent on the board so far
     * @return the saved board, or empty if the user has no board with that id
     * @throws BoardCompletedException if the board is already completed
     */
    @Transactional
    public Optional<UserBoard> saveProgress(Long boardId, String username, String currentState,
                                            Map<String, List<Integer>> notes, long elapsedSeconds) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Unknown user: " + username));

        return userBoardRepository.findLockedByIdAndUserId(boardId, user.getId()).map(board -> {
            if (board.isCompleted()) {
                log.info("Rejected save against completed board {} by user {}", boardId, username);
                throw new BoardCompletedException(boardId);
            }

            board.saveProgress(currentState, notes, elapsedSeconds);
            UserBoard saved = userBoardRepository.save(board);
            if (saved.isCompleted()) {
                log.info("User {} completed {} board {} in {}s",
                        username, saved.getDifficulty(), boardId, saved.getElapsedSeconds());
            }
            return saved;
        });
    }

    /**
     * Lists a user's boards, most recently played first.
     *
     * @param username the user, as carried by the bearer token
     * @return that user's boards, newest activity first
     */
    @Transactional(readOnly = true)
    public List<UserBoard> listBoards(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Unknown user: " + username));
        return userBoardRepository.findByUserIdOrderByLastModifiedAtDesc(user.getId());
    }
}
