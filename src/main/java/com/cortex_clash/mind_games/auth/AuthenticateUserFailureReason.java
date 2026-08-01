package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.metrics.TagValue;

public enum AuthenticateUserFailureReason implements TagValue {
    UNKNOWN_USER("unknown_user"),
    LOCKED_OUT_USER("locked_out_user"),
    INVALID_PASSWORD("invalid_password");

    private final String reason;

    AuthenticateUserFailureReason(String reason) { this.reason = reason; }

    @Override
    public String value() {
        return this.reason;
    }
}
