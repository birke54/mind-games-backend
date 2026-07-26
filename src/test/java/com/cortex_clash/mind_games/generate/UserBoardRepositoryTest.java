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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the derived board-listing query against the real MySQL schema. The filtering, the
 * ordering, and the page cap are all executed by the query Spring Data derives from the method
 * name, so nothing short of a database can tell whether they actually happen — this is also what
 * catches a method name that no longer resolves against the entity.
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

        Page<UserBoard> page = userBoardRepository.findByStatusAndUserIdOrderByLastModifiedAtDesc(
                BoardStatus.IN_PROGRESS, ownerId, Pageable.ofSize(5));

        assertEquals(5, page.getNumberOfElements(), "the page is capped at the requested size");
        assertEquals(7, page.getTotalElements(), "all seven resumable boards match the filter");

        List<Long> returned = page.getContent().stream().map(UserBoard::getId).toList();
        List<Long> newestFiveNewestFirst = inProgress.reversed().subList(0, 5);
        assertEquals(newestFiveNewestFirst, returned,
                "the five most recently played in-progress boards, newest activity first");

        assertTrue(page.getContent().stream().allMatch(b -> b.getStatus() == BoardStatus.IN_PROGRESS),
                "a completed board has nothing to resume and must not be listed");
        assertTrue(page.getContent().stream().allMatch(b -> b.getUserId().equals(ownerId)),
                "the listing is scoped to its own user");
    }

    @Test
    void listsNothingForAUserWhoseBoardsAreAllCompleted() {
        completedBoardLastPlayed(ownerId, BASE);
        entityManager.flush();
        entityManager.clear();

        Page<UserBoard> page = userBoardRepository.findByStatusAndUserIdOrderByLastModifiedAtDesc(
                BoardStatus.IN_PROGRESS, ownerId, Pageable.ofSize(5));

        assertTrue(page.isEmpty());
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
