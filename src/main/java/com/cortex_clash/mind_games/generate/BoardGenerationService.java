package com.cortex_clash.mind_games.generate;

import com.cortex_clash.mind_games.domain.GeneratedBoard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps the {@code generated_boards} pool topped up. On a fixed schedule it counts the available
 * boards for each {@link BoardDifficulty}; when a difficulty's count has dropped below
 * {@code low-watermark}, it generates enough new boards to bring that difficulty back up to
 * {@code pregeneration-board-count}.
 *
 * <p>This is the "Option A" in-process scheduled top-up. It assumes a single application instance:
 * with more than one instance running, several would try to refill the same difficulty at once and
 * overshoot the target. Multi-instance safety (a distributed lock or a claimed work queue) would be
 * a follow-up.
 */
@Service
public class BoardGenerationService {
    private static final Logger log = LoggerFactory.getLogger(BoardGenerationService.class);

    private final GeneratedBoardRepository generatedBoardRepository;

    private final int minThreshold;
    private final int easyThreshold;
    private final int moderateThreshold;
    private final int hardThreshold;

    private final int targetCount;
    private final int lowWatermark;
    private final int maxAttemptsPerBoard;

    /**
     * @param generatedBoardRepository repository used to count and persist pooled boards
     * @param minThreshold             lower grade-score bound for a usable board
     * @param easyThreshold            maximum grade score classified as {@link BoardDifficulty#EASY}
     * @param moderateThreshold        maximum grade score classified as {@link BoardDifficulty#MODERATE}
     * @param hardThreshold            maximum grade score classified as {@link BoardDifficulty#HARD}
     * @param targetCount              per-difficulty pool size a refill tops up to
     * @param lowWatermark             refill is triggered once a difficulty's count drops below this
     * @param maxAttemptsPerBoard      generation retries before giving up on a single board
     */
    public BoardGenerationService(
            GeneratedBoardRepository generatedBoardRepository,
            @Value("${app.board_grading.min_threshold}") int minThreshold,
            @Value("${app.board_grading.easy_threshold}") int easyThreshold,
            @Value("${app.board_grading.moderate_threshold}") int moderateThreshold,
            @Value("${app.board_grading.hard_threshold}") int hardThreshold,
            @Value("${app.board_generation.pregeneration-board-count}") int targetCount,
            @Value("${app.board_generation.low-watermark}") int lowWatermark,
            @Value("${app.board_generation.max-attempts-per-board}") int maxAttemptsPerBoard) {
        this.generatedBoardRepository = generatedBoardRepository;
        this.minThreshold = minThreshold;
        this.easyThreshold = easyThreshold;
        this.moderateThreshold = moderateThreshold;
        this.hardThreshold = hardThreshold;
        this.targetCount = targetCount;
        this.lowWatermark = lowWatermark;
        this.maxAttemptsPerBoard = maxAttemptsPerBoard;
    }

    /**
     * Scheduled entry point: checks every {@link BoardDifficulty} and refills any that have fallen
     * below the low watermark. Runs on the cron defined by {@code app.board_generation.refill-cron}.
     */
    @Scheduled(cron = "${app.board_generation.refill-cron}")
    public void refillPool() {
        for (BoardDifficulty difficulty : BoardDifficulty.values()) {
            refillDifficulty(difficulty);
        }
    }

    /**
     * Refills a single difficulty back up to {@code targetCount}, but only if its available count
     * has dropped below {@code lowWatermark}. Stops early if generation repeatedly fails, leaving
     * the remainder for the next scheduled run.
     *
     * @param difficulty the difficulty tier to top up
     */
    private void refillDifficulty(BoardDifficulty difficulty) {
        long available = generatedBoardRepository.countByDifficulty(difficulty);
        if (available >= lowWatermark) {
            return;
        }

        int deficit = targetCount - (int) available;
        log.info("Board pool for {} at {} (< watermark {}); generating {} to reach target {}",
                difficulty, available, lowWatermark, deficit, targetCount);

        int generated = 0;
        for (int i = 0; i < deficit; i++) {
            GeneratedPuzzle puzzle = generateOne(difficulty);
            if (puzzle == null) {
                // Generation kept failing; stop this cycle and try again next tick.
                break;
            }
            generatedBoardRepository.save(
                    new GeneratedBoard(difficulty, puzzle.puzzle(), puzzle.solution()));
            generated++;
        }

        if (generated < deficit) {
            log.warn("Only generated {}/{} {} board(s) this cycle", generated, deficit, difficulty);
        } else {
            log.info("Refilled {} {} board(s)", generated, difficulty);
        }
    }

    /**
     * Generates a single board of the target difficulty, retrying up to
     * {@code max-attempts-per-board} times. Generation can legitimately fail when a candidate
     * grid has no cell-removal permutation that lands in the target band.
     *
     * @return the generated puzzle and its solution, or {@code null} if every attempt failed.
     */
    private GeneratedPuzzle generateOne(BoardDifficulty difficulty) {
        for (int attempt = 1; attempt <= maxAttemptsPerBoard; attempt++) {
            try {
                return new GenerateBoard(difficulty, minThreshold, easyThreshold, moderateThreshold, hardThreshold)
                        .generate();
            } catch (RuntimeException e) {
                log.debug("Attempt {}/{} to generate a {} board failed: {}",
                        attempt, maxAttemptsPerBoard, difficulty, e.getMessage());
            }
        }
        log.warn("Failed to generate a {} board after {} attempts", difficulty, maxAttemptsPerBoard);
        return null;
    }
}
