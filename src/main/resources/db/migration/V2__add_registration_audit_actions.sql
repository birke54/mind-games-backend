ALTER TABLE `user_audit` MODIFY COLUMN `action` ENUM(
    'user_registered',
    'failed_registration',
    'successful_login',
    'logout',
    'failed_login',
    'lockout'
) NOT NULL;
