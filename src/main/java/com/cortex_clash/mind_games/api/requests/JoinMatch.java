package com.cortex_clash.mind_games.api.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Redeems a host's join code to take the second seat in a match.
 *
 * @param joinCode the code the host shared; case-insensitive and trimmed server-side, since it is
 *                 typed in by hand
 */
public record JoinMatch(
        @NotBlank(message = "joinCode is required")
        @Size(max = 8, message = "joinCode must be at most 8 characters")
        String joinCode) {
}
