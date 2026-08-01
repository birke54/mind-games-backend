package com.cortex_clash.mind_games.metrics;

public enum TagName {
    REASON("reason");

    private final String key;

    TagName(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
