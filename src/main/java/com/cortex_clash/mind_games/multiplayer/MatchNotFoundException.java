package com.cortex_clash.mind_games.multiplayer;

/**
 * Thrown when a match cannot be resolved — no such join code or id, or the caller is not one of its
 * players. Those two cases are deliberately the same exception: a match you are not playing must be
 * indistinguishable from one that does not exist, or match ids become enumerable.
 */
public class MatchNotFoundException extends RuntimeException {

    public MatchNotFoundException(String reference) {
        super("No match found for " + reference);
    }
}
