package com.cortex_clash.mind_games.multiplayer;

/**
 * Thrown when a match cannot be joined or acted on: the lobby is full, the race is already over, or
 * the caller is trying to join their own game. Distinct from "no such match", which is a 404 —
 * this is a 409, meaning the match exists but is not in a state that allows what was asked.
 */
public class MatchUnavailableException extends RuntimeException {

    public MatchUnavailableException(String message) {
        super(message);
    }
}
