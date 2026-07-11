package com.cortex_clash.mind_games.auth;

/** A freshly issued access token (JWT) plus the raw refresh token to hand back to the client. */
public record TokenPair(String accessToken, String refreshToken) {}
