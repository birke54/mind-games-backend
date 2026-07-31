package com.cortex_clash.mind_games.auth;

import com.cortex_clash.mind_games.metrics.TagValue;

public enum RefreshTokenFailureReason implements TagValue {
    INVALID_USER("invalid_user"),
    INVALID_TOKEN("invalid_token"),
    EXPIRED_TOKEN("expired_token"),
    REVOKED_TOKEN("revoked_token");

    private final String reason;

    RefreshTokenFailureReason(String reason) { this.reason = reason; }

    @Override
    public String value() {
        return this.reason;
    }
}
