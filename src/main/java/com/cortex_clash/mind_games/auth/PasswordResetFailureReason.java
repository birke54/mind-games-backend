package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.metrics.TagValue;

public enum PasswordResetFailureReason implements TagValue {
    UNKNOWN_USER("unknown_user"),
    INVALID_TOKEN("invalid_token"),
    USED_TOKEN("used_token"),
    EXPIRED_TOKEN("expired_token"),
    RESET_ATTEMPTS_EXCEEDED("reset_attempts_exceeded"),
    PASSWORD_POLICY_FAILURE("password_policy_failure");

    private final String reason;

    PasswordResetFailureReason(String reason) { this.reason = reason; }

    @Override
    public String value() {
        return this.reason;
    }
}
