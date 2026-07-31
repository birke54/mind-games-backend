package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.metrics.TagValue;

public enum PasswordPolicyFailureReason implements TagValue {
    PASSWORD_EMPTY("password_empty"),
    PASSWORD_LENGTH("password_length_constraints_not_met"),
    MATCHES_EXISTING("password_matches_existing_user_password");

    private final String reason;

    PasswordPolicyFailureReason(String reason) { this.reason = reason; }

    @Override
    public String value() {
        return this.reason;
    }
}
