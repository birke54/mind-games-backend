package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.domain.User;
import com.cortex_clash.mind_games.domain.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrative / lifecycle updates to the {@code users} table.
 *
 * <p>Currently covers the mutable operational columns {@code status} and
 * {@code failed_attempts}. The database maintains {@code updated_at} automatically
 * (ON UPDATE CURRENT_TIMESTAMP), so callers do not touch it.
 */
@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Sets the account status (e.g. ACTIVE, LOCKED_OUT). */
    @Transactional
    public User updateStatus(String username, UserStatus status) {
        User user = require(username);
        user.setStatus(status);
        return userRepository.save(user);
    }

    /** Sets the raw failed-attempt counter. */
    @Transactional
    public User updateFailedAttempts(String username, int failedAttempts) {
        User user = require(username);
        user.setFailedAttempts(failedAttempts);
        return userRepository.save(user);
    }

    /** Convenience: lock an account. */
    @Transactional
    public User lockUser(String username) {
        return updateStatus(username, UserStatus.LOCKED_OUT);
    }

    /** Convenience: reactivate an account and clear its failed-attempt counter. */
    @Transactional
    public User unlockUser(String username) {
        User user = require(username);
        user.setStatus(UserStatus.ACTIVE);
        user.setFailedAttempts(0);
        return userRepository.save(user);
    }

    /** Convenience: clear the failed-attempt counter without changing status. */
    @Transactional
    public User resetFailedAttempts(String username) {
        return updateFailedAttempts(username, 0);
    }

    private User require(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Unknown user: " + username));
    }
}
