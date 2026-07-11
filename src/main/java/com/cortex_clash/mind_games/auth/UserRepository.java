package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for {@link User} entity operations.
 *
 * <p>Provides data access methods for user authentication and registration processes,
 * including username and email uniqueness checks.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Integer> {

    /**
     * Finds a user by their username.
     *
     * @param username the username to search for
     * @return an {@link Optional} containing the user if found, or empty if not found
     */
    Optional<User> findByUsername(String username);

    /**
     * Checks whether a user with the given username exists.
     *
     * @param username the username to check
     * @return {@code true} if a user with the username exists, {@code false} otherwise
     */
    boolean existsByUsername(String username);

    /**
     * Checks whether a user with the given email exists.
     *
     * @param email the email to check
     * @return {@code true} if a user with the email exists, {@code false} otherwise
     */
    boolean existsByEmail(String email);
}
