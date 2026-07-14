package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.domain.AuditAction;
import com.cortex_clash.mind_games.domain.User;
import com.cortex_clash.mind_games.domain.UserAudit;
import com.cortex_clash.mind_games.domain.UserStatus;
import com.cortex_clash.mind_games.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.security.auth.login.CredentialException;

@Service
public class AuthService {
    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    /** Number of consecutive failed logins before the account is locked. */
    private static final int LOCKOUT_THRESHOLD = 5;

    /**
     * A well-formed BCrypt hash (cost 10, matching {@link BCryptPasswordEncoder}'s default)
     * compared against when the username is unknown. Doing an equal-cost hash on both the
     * found and not-found paths keeps response time constant, preventing timing-based user
     * enumeration.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository userRepository;
    private final UserAuditRepository userAuditRepository;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final UserAuditService userAuditService;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, UserAuditRepository userAuditRepository,
                       JwtService jwtService, RefreshTokenService refreshTokenService,
                       UserAuditService userAuditService) {
        this.userRepository = userRepository;
        this.userAuditRepository = userAuditRepository;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.userAuditService = userAuditService;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    @Transactional
    public User registerUser(String username, String email, String password) {
        try {
            validateRegistration(username, email, password);
        } catch (IllegalArgumentException e) {
            userAuditService.recordFailedRegistration(username);
            throw e;
        }
        User user = new User(username, email, passwordEncoder.encode(password));
        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // A concurrent registration slipped past validateRegistration and collided on a UNIQUE
            // constraint. Record the failure in a SEPARATE transaction so the audit survives this
            // transaction's rollback.
            logger.warn("Registration failed for username={}", user.getUsername(), e);
            userAuditService.recordFailedRegistration(user.getUsername());
            throw e;
        }
        userAuditRepository.save(new UserAudit(user.getUsername(), AuditAction.USER_REGISTERED));
        return user;
    }

    private void validateRegistration(String username, String email, String password) {
        // Cheap in-memory checks first, so malformed input is rejected before any DB round-trip.
        if (username.length() > User.USERNAME_MAX_LENGTH) {
            logger.warn("Username too long: {}", username);
            throw new IllegalArgumentException("Username too long: " + username);
        }
        if (email.length() > User.EMAIL_MAX_LENGTH) {
            logger.warn("Email too long: {}", email);
            throw new IllegalArgumentException("Email too long: " + email);
        }
        // TODO: Validate email is in a valid format.
        // Same policy the reset flow enforces: a password you may register with is a password you
        // may reset to, and vice versa.
        PasswordPolicy.validate(password);

        // DB existence checks last (the UNIQUE constraint is the authoritative guard under races).
        if (userRepository.existsByUsername(username)) {
            logger.warn("Username already taken: {}", username);
            throw new IllegalArgumentException("Username already taken: " + username);
        }
        if (userRepository.existsByEmail(email)) {
            logger.warn("Email already registered: {}", email);
            throw new IllegalArgumentException("Email already registered: " + email);
        }
    }

    // Default rollback rules are intentional: the failed-login writes (attempt count, lockout,
    // audit rows) must COMMIT even though we throw CredentialException afterward — which works
    // only because CredentialException is a *checked* exception. Do not add
    // rollbackFor = Exception.class, and do not make CredentialException a RuntimeException,
    // or lockout tracking will be silently rolled back on every failed login.
    @Transactional
    public TokenPair authenticateUser(String username, String password) throws CredentialException {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            logger.warn("Unknown username: {}", username);
            // Compare against a dummy hash so unknown-user and wrong-password paths
            // take the same time (prevents timing-based user enumeration).
            passwordEncoder.matches(password, DUMMY_HASH);
            userAuditRepository.save(new UserAudit(username, AuditAction.FAILED_LOGIN));
            throw new CredentialException("Invalid username or password");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            logger.warn("Incorrect password for user: {}", username);
            user.setFailedAttempts(user.getFailedAttempts() + 1);
            if (user.getFailedAttempts() >= LOCKOUT_THRESHOLD) {
                logger.warn("Account locked for user: {}", username);
                user.setStatus(UserStatus.LOCKED_OUT);
            }
            userRepository.save(user);
            userAuditRepository.save(new UserAudit(username, AuditAction.FAILED_LOGIN));
            if (user.isLockedOut()) {
                userAuditRepository.save(new UserAudit(username, AuditAction.LOCKOUT));
                // A locked account's existing sessions should not survive.
                refreshTokenService.revokeAllForUser(user.getId());
            }
            throw new CredentialException("Invalid username or password");
        }
        if (user.getFailedAttempts() != 0) {
            user.setFailedAttempts(0);
            userRepository.save(user);
        }
        userAuditRepository.save(new UserAudit(username, AuditAction.SUCCESSFUL_LOGIN));
        return new TokenPair(jwtService.issueToken(user), refreshTokenService.issue(user));
    }
}
