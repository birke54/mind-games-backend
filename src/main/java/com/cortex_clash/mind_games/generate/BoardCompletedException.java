package com.cortex_clash.mind_games.generate;

/**
 * Thrown when a save is attempted against a board that is already completed. Completion is
 * terminal: a finished board is frozen, and the client is expected to have stopped sending saves
 * the moment it saw {@code status: "completed"} come back.
 */
public class BoardCompletedException extends RuntimeException {

    public BoardCompletedException(Long boardId) {
        super("Board " + boardId + " is completed and cannot be modified");
    }
}
