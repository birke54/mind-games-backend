CREATE TABLE `password_reset_tokens` (
    `id`         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `user_id`    INT UNSIGNED NOT NULL,
    -- SHA-256 hex of the raw token. The raw value is never stored; it exists only in the email.
    `token_hash` VARCHAR(64) NOT NULL UNIQUE,
    `expires_at` TIMESTAMP NOT NULL,
    -- Single use. Set on redemption, and on reissue to kill any outstanding token for the user.
    -- A token with this set is dead.
    `used_at`    TIMESTAMP NULL DEFAULT NULL,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `fk_password_reset_tokens_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    INDEX `idx_password_reset_tokens_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `user_audit` MODIFY COLUMN `action` ENUM(
    'user_registered',
    'failed_registration',
    'successful_login',
    'logout',
    'failed_login',
    'lockout',
    'password_reset_requested',
    'password_reset_completed'
) NOT NULL;
