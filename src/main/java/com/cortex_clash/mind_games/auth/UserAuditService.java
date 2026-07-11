package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.domain.AuditAction;
import com.cortex_clash.mind_games.domain.UserAudit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit writes that must survive a caller's transaction rollback.
 *
 * <p>This lives in its own bean on purpose: a {@code REQUIRES_NEW} method invoked on {@code this}
 * would be bypassed by Spring's transactional proxy (self-invocation) and simply join the caller's
 * transaction — defeating the whole point. Calling it as a separate bean goes through the proxy, so
 * the audit really does commit in an independent transaction.
 */
@Service
public class UserAuditService {

    private final UserAuditRepository userAuditRepository;

    public UserAuditService(UserAuditRepository userAuditRepository) {
        this.userAuditRepository = userAuditRepository;
    }

    /**
     * Records a failed registration in a brand-new transaction, so the row persists even though the
     * caller's transaction (the one whose {@code save} just failed) is about to roll back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedRegistration(String username) {
        userAuditRepository.save(new UserAudit(username, AuditAction.FAILED_REGISTRATION));
    }
}
