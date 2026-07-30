package com.cortex_clash.mind_games.generate;

import com.cortex_clash.mind_games.TestcontainersConfiguration;
import com.cortex_clash.mind_games.domain.BoardStatus;
import com.cortex_clash.mind_games.domain.User;
import com.cortex_clash.mind_games.domain.UserBoard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the board-listing query and the stats aggregate against the real MySQL schema. The
 * filtering, the ordering, and the cap of five are all executed by the query Spring Data derives
 * from the method name, so nothing short of a database can tell whether they actually happen —
 * this is also what catches a method name that no longer resolves against the entity.
 *
 * <p>The aggregate needs a database for a second reason: {@link DifficultyStats} is an interface
 * projection, and Spring binds its getters to the query's {@code AS} aliases when a row comes back,
 * not when the context starts. A renamed alias still compiles and still boots — only executing the
 * query catches it, so every getter is asserted here.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class UserBoardRepositoryTest {

    private static final String PUZZLE = "0".repeat(81);
    private static final String SOLUTION = "1".repeat(81);

    /** Distinct enough to order by: the column is a second-resolution MySQL TIMESTAMP. */
    private static final Instant BASE = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    private UserBoardRepository userBoardRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Integer ownerId;
    private Integer otherPlayerId;

    @BeforeEach
    void createUsers() {
        ownerId = persistUser("owner");
        otherPlayerId = persistUser("other");
    }

    @Test
    void listsOnlyTheUsersOwnInProgressBoardsNewestFirstCappedAtThePageSize() {
        // Seven to resume, aged a minute apart, oldest first.
        List<Long> inProgress = List.of(
                boardLastPlayed(ownerId, BASE.minus(7, ChronoUnit.MINUTES)),
                boardLastPlayed(ownerId, BASE.minus(6, ChronoUnit.MINUTES)),
                boardLastPlayed(ownerId, BASE.minus(5, ChronoUnit.MINUTES)),
                boardLastPlayed(ownerId, BASE.minus(4, ChronoUnit.MINUTES)),
                boardLastPlayed(ownerId, BASE.minus(3, ChronoUnit.MINUTES)),
                boardLastPlayed(ownerId, BASE.minus(2, ChronoUnit.MINUTES)),
                boardLastPlayed(ownerId, BASE.minus(1, ChronoUnit.MINUTES)));

        // Finished more recently than anything above, so an unfiltered query would rank them top.
        completedBoardLastPlayed(ownerId, BASE);
        completedBoardLastPlayed(ownerId, BASE.plusSeconds(30));

        // And someone else's board, newer still.
        boardLastPlayed(otherPlayerId, BASE.plusSeconds(60));

        entityManager.flush();
        entityManager.clear();

        List<UserBoard> listed = userBoardRepository.findFirst5ByStatusAndUserIdOrderByLastModifiedAtDesc(
                BoardStatus.IN_PROGRESS, ownerId);

        assertEquals(5, listed.size(), "the listing is capped at five even though seven match");

        List<Long> returned = listed.stream().map(UserBoard::getId).toList();
        List<Long> newestFiveNewestFirst = inProgress.reversed().subList(0, 5);
        assertEquals(newestFiveNewestFirst, returned,
                "the five most recently played in-progress boards, newest activity first");

        assertTrue(listed.stream().allMatch(b -> b.getStatus() == BoardStatus.IN_PROGRESS),
                "a completed board has nothing to resume and must not be listed");
        assertTrue(listed.stream().allMatch(b -> b.getUserId().equals(ownerId)),
                "the listing is scoped to its own user");
    }

    @Test
    void listsNothingForAUserWhoseBoardsAreAllCompleted() {
        completedBoardLastPlayed(ownerId, BASE);
        entityManager.flush();
        entityManager.clear();

        assertTrue(userBoardRepository.findFirst5ByStatusAndUserIdOrderByLastModifiedAtDesc(
                BoardStatus.IN_PROGRESS, ownerId).isEmpty());
    }

    @Test
    void aggregatesEveryTotalTheStatsPathReadsForATier() {
        completedBoard(ownerId, BoardDifficulty.EASY, 300);
        completedBoard(ownerId, BoardDifficulty.EASY, 120);
        inProgressBoard(ownerId, BoardDifficulty.EASY, 60);
        entityManager.flush();
        entityManager.clear();

        DifficultyStats easy = aggregateFor(ownerId).get(BoardDifficulty.EASY);

        assertNotNull(easy, "a tier the player has been dealt is reported");
        assertEquals(BoardDifficulty.EASY, easy.getDifficulty(),
                "the tier comes back as the enum, not the raw database string");
        assertEquals(2, easy.getBoardsSolved());
        assertEquals(1, easy.getBoardsInProgress());
        assertEquals(Long.valueOf(120), easy.getBestTime(),
                "the fastest completion in the tier, not the most recent one");
        assertEquals(420, easy.getSolvedPlayTime(),
                "the solved boards only — this is what a mean solve time is computed from");
        assertEquals(480, easy.getTotalPlayTime(),
                "time on the unfinished board counts too, so this is more than the solved boards");
    }

    @Test
    void reportsNoBestTimeForATierWithNothingFinishedYet() {
        inProgressBoard(ownerId, BoardDifficulty.MODERATE, 90);
        entityManager.flush();
        entityManager.clear();

        DifficultyStats moderate = aggregateFor(ownerId).get(BoardDifficulty.MODERATE);

        assertNotNull(moderate, "a tier started but never finished still reports");
        assertEquals(0, moderate.getBoardsSolved());
        assertEquals(1, moderate.getBoardsInProgress());
        assertNull(moderate.getBestTime(),
                "MIN over no completed board is null, not zero — the service folds the overall best "
                        + "around exactly this, and a zero here would win it outright");
        assertEquals(0, moderate.getSolvedPlayTime(), "no solved board means no solve time");
        assertEquals(90, moderate.getTotalPlayTime(),
                "but the time on the unfinished one is still time played");
    }

    @Test
    void omitsTiersThePlayerHasNotBeenDealt() {
        completedBoard(ownerId, BoardDifficulty.EASY, 300);
        // The only hard board in the table belongs to someone else.
        completedBoard(otherPlayerId, BoardDifficulty.HARD, 10);
        entityManager.flush();
        entityManager.clear();

        assertEquals(Set.of(BoardDifficulty.EASY), aggregateFor(ownerId).keySet(),
                "an unplayed tier is an absent row here — the service is what turns it into the "
                        + "zero row the API reports — and another player's boards are not this "
                        + "player's stats");
    }

    @Test
    void aggregatesNothingForAPlayerWithNoBoards() {
        assertTrue(userBoardRepository.aggregateByDifficulty(
                        ownerId, BoardStatus.COMPLETED, BoardStatus.IN_PROGRESS).isEmpty(),
                "no rows at all is what the service turns into a zero row for every tier");
    }

    private Map<BoardDifficulty, DifficultyStats> aggregateFor(Integer userId) {
        return userBoardRepository
                .aggregateByDifficulty(userId, BoardStatus.COMPLETED, BoardStatus.IN_PROGRESS)
                .stream()
                .collect(Collectors.toMap(DifficultyStats::getDifficulty, Function.identity()));
    }

    private Integer persistUser(String username) {
        return entityManager.persistFlushFind(
                new User(username, username + "@example.test", "hash")).getId();
    }

    private Long boardLastPlayed(Integer userId, Instant lastModifiedAt) {
        UserBoard board = entityManager.persistFlushFind(
                new UserBoard(userId, BoardDifficulty.EASY, PUZZLE, SOLUTION));
        return stampLastModified(board.getId(), lastModifiedAt);
    }

    private Long completedBoardLastPlayed(Integer userId, Instant lastModifiedAt) {
        UserBoard board = new UserBoard(userId, BoardDifficulty.EASY, PUZZLE, SOLUTION);
        board.saveProgress(SOLUTION, Map.of(), 300);
        return stampLastModified(entityManager.persistFlushFind(board).getId(), lastModifiedAt);
    }

    private void completedBoard(Integer userId, BoardDifficulty difficulty, long elapsedSeconds) {
        UserBoard board = new UserBoard(userId, difficulty, PUZZLE, SOLUTION);
        board.saveProgress(SOLUTION, Map.of(), elapsedSeconds);
        entityManager.persist(board);
    }

    private void inProgressBoard(Integer userId, BoardDifficulty difficulty, long elapsedSeconds) {
        UserBoard board = new UserBoard(userId, difficulty, PUZZLE, SOLUTION);
        board.saveProgress(PUZZLE, Map.of(), elapsedSeconds);
        entityManager.persist(board);
    }

    /**
     * {@code last_modified_at} is written by the database (ON UPDATE CURRENT_TIMESTAMP), so the
     * only way to give rows a known ordering is to set it behind Hibernate's back. Naming the
     * column explicitly also suppresses the ON UPDATE trigger for this statement.
     */
    private Long stampLastModified(Long boardId, Instant lastModifiedAt) {
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE user_boards SET last_modified_at = :ts WHERE id = :id")
                .setParameter("ts", Timestamp.from(lastModifiedAt))
                .setParameter("id", boardId)
                .executeUpdate();
        return boardId;
    }
}
