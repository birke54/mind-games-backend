package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.domain.UserAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link UserAudit} entities.
 *
 * <p>Provides CRUD operations and query methods for user audit records. Used primarily by
 * {@link UserAuditService} to persist audit events in independent transactions.
 *
 * @see UserAudit
 * @see UserAuditService
 */
@Repository
public interface UserAuditRepository extends JpaRepository<UserAudit, Integer> {
}
