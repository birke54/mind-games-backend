CREATE TABLE `users` (
    `id` INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `username` VARCHAR(25) NOT NULL UNIQUE,
    `email` VARCHAR(50) NOT NULL UNIQUE,
    `password_hash` VARCHAR(255) NOT NULL,
    `failed_attempts` INT UNSIGNED DEFAULT 0,
    `status` ENUM('active', 'locked_out') DEFAULT 'active',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `user_audit` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `username` VARCHAR(25) NOT NULL,
    `action` ENUM(
        'successful_login',
        'logout',
        'failed_login',
        'lockout'
    ) NOT NULL,
    `timestamp` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_user_audit_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `refresh_tokens` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `user_id` INT UNSIGNED NOT NULL,
    -- All tokens rotated from a single login share a family; reuse of a rotated
    -- token revokes the whole family (theft detection).
    `family_id` VARCHAR(36) NOT NULL,
    -- SHA-256 hex of the raw token. The raw value is never stored.
    `token_hash` VARCHAR(64) NOT NULL UNIQUE,
    `expires_at` TIMESTAMP NOT NULL,
    `revoked_at` TIMESTAMP NULL DEFAULT NULL,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `last_used_at` TIMESTAMP NULL DEFAULT NULL,
    CONSTRAINT `fk_refresh_tokens_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    INDEX `idx_refresh_tokens_family` (`family_id`),
    INDEX `idx_refresh_tokens_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `generated_boards` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `difficulty_rating` ENUM('easy', 'moderate', 'hard') NOT NULL,
    -- The puzzle presented to the player, and its unique completed solution.
    `board_state` VARCHAR(81) NOT NULL,
    `solution_state` VARCHAR(81) NOT NULL,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- The pool monitor counts available boards per difficulty on every refill check.
    INDEX `idx_generated_boards_difficulty` (`difficulty_rating`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- A board a user has claimed and is playing. The row is created when a board is served out of
-- `generated_boards` (that pool row is deleted in the same transaction), so this table owns the
-- puzzle from that point on and is the only place it still exists.
CREATE TABLE `user_boards` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `user_id` INT UNSIGNED NOT NULL,
    `difficulty_rating` ENUM('easy', 'moderate', 'hard') NOT NULL,

    -- Derived by the server on every save: flips to 'completed' when current_state first matches
    -- solution_state. The client never sets it. Completion is terminal — a completed board is
    -- immutable and further saves against it are rejected.
    `status` ENUM('in_progress', 'completed') NOT NULL DEFAULT 'in_progress',
    -- When the board was completed; NULL while in progress. Set by the application, not the DB,
    -- because it marks a state transition rather than a row write.
    `completed_at` TIMESTAMP NULL DEFAULT NULL,

    -- The board as it was dealt. Kept immutable so the UI can tell a given (a clue the player may
    -- not change) from a digit the player entered: a cell is a given iff puzzle_state[i] != '0'.
    `puzzle_state` VARCHAR(81) NOT NULL,
    -- The player's board right now: givens plus whatever they have filled in. Starts as a copy of
    -- puzzle_state. '0' is an empty cell.
    `current_state` VARCHAR(81) NOT NULL,
    -- The completed board. Copied from the pool row; the puzzle's unique solution.
    `solution_state` VARCHAR(81) NOT NULL,

    -- Pencil marks, as a JSON object keyed by row-major cell index ("0".."80"), each value the
    -- array of digits noted in that cell. Cells without notes are simply absent:
    --   {"4": [1,5,9], "17": [2,3], "62": [7]}
    `notes` JSON NOT NULL,

    -- Total time the player has spent on this board, in seconds. Accumulated by the client and
    -- sent up on save; the server does not infer it from the timestamps (a board can sit idle).
    `elapsed_seconds` BIGINT UNSIGNED NOT NULL DEFAULT 0,

    `claimed_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `last_modified_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT `fk_user_boards_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    -- Boards are listed and resumed per user, most-recently-played first.
    INDEX `idx_user_boards_user` (`user_id`, `last_modified_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;