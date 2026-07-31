package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.metrics.TagValue;

public enum RegistrationFailureReason implements TagValue {
    USERNAME_TAKEN("username_taken"),
    EMAIL_TAKEN("email_taken"),
    USERNAME_LENGTH("username_length_constraints_not_met"),
    EMAIL_LENGTH("email_length_constraints_not_met"),
    CONCURRENT_REGISTRATION("concurrent_registration"),
    PASSWORD_POLICY_FAILURE("password_policy_failure");

    private final String reason;

    RegistrationFailureReason(String reason) { this.reason = reason; }

    @Override
    public String value() {
        return this.reason;
    }
}
