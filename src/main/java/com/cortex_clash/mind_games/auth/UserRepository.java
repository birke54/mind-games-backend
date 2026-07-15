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

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
