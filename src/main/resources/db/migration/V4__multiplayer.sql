-- Two-player races. A match deals ONE pooled board and copies it into a `user_boards` row per
-- player, so every existing save/completion/freeze path applies unchanged and this schema only has
-- to coordinate: who is racing, how far along they are, and who finished first.
CREATE TABLE `matches` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,

    -- Shared out-of-band ("play me: 7QK4TB") and typed in by the joiner. Short and case-folded to
    -- uppercase on the way in, drawn from an alphabet with no O/0/I/1 so it survives being read
    -- aloud. Unique across all time, not just live matches, so a stale code can never resolve to
    -- someone else's game.
    `join_code` VARCHAR(8) NOT NULL UNIQUE,
    `difficulty_rating` ENUM('easy', 'moderate', 'hard') NOT NULL,

    -- waiting  — created, one player, no board dealt yet (the pool is not touched until someone
    --            joins, so an abandoned lobby costs no puzzle).
    -- active   — both players in, boards dealt, race running.
    -- finished — someone completed their board.
    -- abandoned— expired in `waiting`, or gave up.
    `status` ENUM('waiting', 'active', 'finished', 'abandoned') NOT NULL DEFAULT 'waiting',

    `host_user_id` INT UNSIGNED NOT NULL,
    -- The first player whose board completed. NULL until then.
    `winner_user_id` INT UNSIGNED NULL DEFAULT NULL,

    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Stamped by the application when the second player joins, and the only clock the race is
    -- scored on: `user_boards.elapsed_seconds` is accumulated client-side and therefore spoofable,
    -- so match duration is always `finished_at - started_at` measured on the server.
    `started_at` TIMESTAMP NULL DEFAULT NULL,
    `finished_at` TIMESTAMP NULL DEFAULT NULL,

    CONSTRAINT `fk_matches_host` FOREIGN KEY (`host_user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_matches_winner` FOREIGN KEY (`winner_user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL,
    -- The sweeper scans for lobbies that have sat in `waiting` too long.
    INDEX `idx_matches_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `match_players` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `match_id` BIGINT UNSIGNED NOT NULL,
    `user_id` INT UNSIGNED NOT NULL,

    -- How many cells this player has right, recomputed server-side on every save. This is the only
    -- progress figure the opponent is ever shown: sending the actual grid would hand over most of
    -- the solution, and a client-reported number would be both spoofable and misleading (it would
    -- count wrong digits as progress).
    `correct_cells` INT UNSIGNED NOT NULL DEFAULT 0,
    `finished_at` TIMESTAMP NULL DEFAULT NULL,
    `joined_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT `fk_match_players_match` FOREIGN KEY (`match_id`) REFERENCES `matches` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_match_players_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    -- One seat per player per match; this is also what makes joining idempotent.
    UNIQUE KEY `uk_match_players_match_user` (`match_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Which match a board was dealt for, or NULL for an ordinary solo board. Carried on the board
-- itself rather than looked up through `match_players` because the board-serving path needs it on
-- every read: a match board withholds `solution_state` from the client, and that decision has to be
-- cheap enough to make while streaming the resume list. ON DELETE SET NULL — deleting a match must
-- not take the players' boards with it; they simply revert to solo boards.
ALTER TABLE `user_boards`
    ADD COLUMN `match_id` BIGINT UNSIGNED NULL DEFAULT NULL AFTER `user_id`,
    ADD CONSTRAINT `fk_user_boards_match` FOREIGN KEY (`match_id`) REFERENCES `matches` (`id`) ON DELETE SET NULL;
