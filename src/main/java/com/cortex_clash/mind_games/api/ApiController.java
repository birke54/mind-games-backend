package com.cortex_clash.mind_games.api;

import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.cortex_clash.mind_games.api.requests.AuthenticateUser;
import com.cortex_clash.mind_games.api.requests.CreateMatch;
import com.cortex_clash.mind_games.api.requests.JoinMatch;
import com.cortex_clash.mind_games.api.requests.RegisterUserRequest;
import com.cortex_clash.mind_games.api.requests.RequestPasswordReset;
import com.cortex_clash.mind_games.api.requests.ResetPassword;
import com.cortex_clash.mind_games.api.requests.RevealHint;
import com.cortex_clash.mind_games.api.requests.SaveBoardProgress;
import com.cortex_clash.mind_games.api.responses.BoardResponse;
import com.cortex_clash.mind_games.api.responses.MatchResponse;
import com.cortex_clash.mind_games.auth.AuthService;
import com.cortex_clash.mind_games.auth.InvalidPasswordException;
import com.cortex_clash.mind_games.auth.InvalidResetTokenException;
import com.cortex_clash.mind_games.auth.PasswordResetService;
import com.cortex_clash.mind_games.auth.RefreshTokenService;
import com.cortex_clash.mind_games.auth.TokenPair;
import com.cortex_clash.mind_games.domain.User;
import com.cortex_clash.mind_games.domain.UserBoard;
import com.cortex_clash.mind_games.generate.BoardCompletedException;
import com.cortex_clash.mind_games.generate.BoardDifficulty;
import com.cortex_clash.mind_games.generate.BoardService;
import com.cortex_clash.mind_games.multiplayer.MatchNotFoundException;
import com.cortex_clash.mind_games.multiplayer.MatchService;
import com.cortex_clash.mind_games.multiplayer.MatchSnapshot;
import com.cortex_clash.mind_games.multiplayer.MatchUnavailableException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.security.auth.login.CredentialException;

/**
 * The application's single REST surface: registration, authentication, token refresh/logout,
 * password reset, and dealing and saving boards.
 *
 * <p>Access tokens are returned in response bodies as JSON; refresh tokens are stored in secure,
 * HttpOnly cookies scoped to {@code /api/v1}.
 */
@RestController
@RequestMapping("/api")
public class ApiController {
    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    /** HttpOnly cookie carrying the refresh token; scoped to the auth endpoints. */
    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String COOKIE_PATH = "/api/v1";

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;
    private final PasswordResetService passwordResetService;
    private final BoardService boardService;
    private final MatchService matchService;

    /** Secure flag on the refresh cookie; disable only for local HTTP development. */
    private final boolean cookieSecure;

    public ApiController(AuthService authService, RefreshTokenService refreshTokenService,
                         PasswordResetService passwordResetService,
                         BoardService boardService,
                         MatchService matchService,
                         @Value("${app.security.cookie-secure:true}") boolean cookieSecure) {
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
        this.passwordResetService = passwordResetService;
        this.boardService = boardService;
        this.matchService = matchService;
        this.cookieSecure = cookieSecure;
    }

    /** A match that does not exist, or that the caller is not playing in. */
    @ExceptionHandler(MatchNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleMatchNotFound(MatchNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Match not found"));
    }

    /** A match that exists but cannot be joined or acted on in its current state. */
    @ExceptionHandler(MatchUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleMatchUnavailable(MatchUnavailableException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    /**
     * Health check endpoint.
     *
     * @return a map containing status "ok"
     */
    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("status", "ok");
    }

    /**
     * Registers a new user account.
     *
     * @param request the registration request containing username, email, and password
     * @return 201 (CREATED) with the created username
     * @throws ResponseStatusException with status 400 (BAD_REQUEST) if the password fails the
     *                                 password policy
     * @throws ResponseStatusException with status 409 (CONFLICT) if username or email already exists
     * @throws ResponseStatusException with status 500 (INTERNAL_SERVER_ERROR) on unexpected errors
     */
    @PostMapping("/v1/register")
    public ResponseEntity<Map<String, Object>> registerUser(@Valid @RequestBody RegisterUserRequest request) {
        User user;
        try {
            user = authService.registerUser(request.username(), request.email(), request.password());
        } catch (InvalidPasswordException e) {
            // Caught ahead of IllegalArgumentException, which it extends: a weak password is a
            // malformed request, not a collision with existing data.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("username", user.getUsername()));
    }

    /**
     * Authenticates a user and issues JWT tokens.
     *
     * <p>On success, returns an access token in the response body and sets a refresh token
     * in an HttpOnly cookie.
     *
     * @param request the authentication request containing username and password
     * @return response with access token in body and refresh token in cookie
     * @throws ResponseStatusException with status 401 (UNAUTHORIZED) if credentials are invalid
     */
    @PostMapping("/v1/authenticate_user")
    public ResponseEntity<Map<String, String>> authenticateUser(@RequestBody AuthenticateUser request) {
        try {
            TokenPair tokens = authService.authenticateUser(request.username(), request.password());
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken()).toString())
                    .body(Map.of("accessToken", tokens.accessToken()));
        } catch (CredentialException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    /**
     * Exchanges the refresh cookie for a new access token, rotating the refresh token.
     *
     * <p>This endpoint implements refresh token rotation: each successful refresh invalidates
     * the old refresh token and issues a new one.
     *
     * @param refreshToken the current refresh token from the cookie
     * @return response with new access token in body and new refresh token in cookie
     * @throws ResponseStatusException with status 401 (UNAUTHORIZED) if refresh token is missing or invalid;
     *                                 the cookie is cleared on invalid tokens
     */
    @PostMapping("/v1/refresh")
    public ResponseEntity<Map<String, String>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing refresh token");
        }
        try {
            TokenPair tokens = refreshTokenService.rotate(refreshToken);
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken()).toString())
                    .body(Map.of("accessToken", tokens.accessToken()));
        } catch (CredentialException e) {
            // Bad/rotated/expired token: clear the cookie and report 401.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, clearingCookie().toString())
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Revokes the current refresh token and clears the cookie.
     *
     * <p>If a valid refresh token is present, it is revoked on the server. The refresh cookie
     * is cleared regardless of whether a token was present.
     *
     * @param refreshToken the refresh token from the cookie, if present
     * @return no content (204) response with cleared cookie
     */
    @PostMapping("/v1/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenService.revoke(refreshToken);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearingCookie().toString())
                .build();
    }

    /**
     * Emails a password-reset link to the address given, if it belongs to an account.
     *
     * <p><strong>Always answers 202, with an empty body.</strong> Not "202 on success" — 202 for a
     * registered address, an unregistered one, a rate-limited one, and a string that is not an
     * email address at all. {@code users.email} is UNIQUE, so an address confirmed to exist is an
     * account confirmed to exist: any response that varies with the input turns this endpoint into
     * a user-enumeration oracle, and it is reachable by anyone on the internet. The service is
     * written to make no distinction and this handler has no branch in it, both on purpose.
     *
     * @param request the address to send a reset link to
     * @return 202 (ACCEPTED), unconditionally
     */
    @PostMapping("/v1/request_password_reset")
    public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody RequestPasswordReset request) {
        passwordResetService.requestReset(request.email());
        return ResponseEntity.accepted().build();
    }

    /**
     * Redeems a reset token and sets a new password. On success every existing session is revoked
     * and any lockout is cleared, so the user can immediately log in with the new password — and
     * only with the new password, on every device.
     *
     * <p>The caller is not signed in as a result. A reset proves control of an inbox, not intent to
     * start a session, and the user has just been logged out everywhere on purpose.
     *
     * <p>Both failures answer 400 but say different things, because the user does something
     * different about each: a bad link means request a new one, a rejected password means pick a
     * better one. The message is returned in the body rather than as a
     * {@link ResponseStatusException} reason because Spring Boot's default
     * ({@code server.error.include-message: never}) strips the reason from the response, which
     * would leave the client with a bare 400 and nothing to render — the same reason
     * {@code /v1/refresh} builds its error body by hand.
     *
     * <p>The three ways a token can be bad — unknown, used, expired — are deliberately <em>not</em>
     * distinguished. "That token was already used" confirms to whoever holds a stolen link that it
     * was real, and the legitimate user's next step is the same in all three cases.
     *
     * @param request the raw token from the emailed link, and the new password
     * @return 204 (NO_CONTENT) on success; 400 (BAD_REQUEST) with an {@code error} message if the
     *         token is unknown, used, or expired, or the new password fails the password policy
     */
    @PostMapping("/v1/reset_password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody ResetPassword request) {
        try {
            passwordResetService.resetPassword(request.token(), request.password());
        } catch (InvalidResetTokenException e) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "This password reset link is invalid or has expired. Please request a new one."));
        } catch (InvalidPasswordException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Deals the player a pregenerated board at the requested difficulty, returning both the
     * starting state and the completed solution.
     *
     * <p>The board leaves the pool and becomes the caller's: it is recorded in {@code user_boards}
     * as in-progress, and the returned {@code id} is what subsequent saves are made against. Each
     * call therefore returns a distinct puzzle. Requires a valid bearer token, like every endpoint
     * not explicitly listed as public in {@code SecurityConfig}.
     *
     * @param difficulty the requested tier: {@code easy}, {@code moderate}, or {@code hard}
     * @param principal  the authenticated user
     * @return the newly claimed board
     * @throws ResponseStatusException with status 400 (BAD_REQUEST) if the difficulty is unrecognized
     * @throws ResponseStatusException with status 503 (SERVICE_UNAVAILABLE) if the pool holds no
     *                                 board at that difficulty; the refill scheduler will replenish it
     */
    @GetMapping("/v1/board")
    public BoardResponse board(@RequestParam("difficulty") String difficulty, Principal principal) {
        BoardDifficulty target = parseDifficulty(difficulty);

        UserBoard board = boardService.claimBoard(principal.getName(), target)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "No " + target.dbValue() + " boards available; try again shortly"));

        return BoardResponse.from(board);
    }

    /**
     * Lists the caller's unfinished boards, most recently played first, so the frontend can offer
     * to resume one. Each carries its full state, including the solution. Completed boards are not
     * listed here — the save that finished one is the last the frontend hears of it.
     *
     * @param principal the authenticated user
     * @return the caller's in-progress boards, newest activity first
     */
    @GetMapping("/v1/boards")
    public List<BoardResponse> boards(Principal principal) {
        return boardService.listBoards(principal.getName()).stream()
                .map(BoardResponse::from)
                .toList();
    }

    /**
     * Saves the player's progress on one of their own boards: the current grid, their pencil marks,
     * and the time on the clock. The full state is sent on every save; the server does not apply
     * deltas. {@code last_modified_at} is bumped by the database on write.
     *
     * <p>When the saved grid first matches the solution the board completes, and the response comes
     * back with {@code status: "completed"} and a {@code completedAt} stamp — that is the frontend's
     * cue to freeze the UI. Completion is terminal, so any save after that is rejected with 409.
     *
     * @param boardId   the board being saved
     * @param request   the board's current state, notes, and elapsed time
     * @param principal the authenticated user
     * @return the saved board
     * @throws ResponseStatusException with status 404 (NOT_FOUND) if the caller has no board with
     *                                 that id — a board owned by another user is indistinguishable
     *                                 from one that does not exist
     * @throws ResponseStatusException with status 409 (CONFLICT) if the board is already completed
     *                                 and therefore immutable
     */
    @PutMapping("/v1/boards/{boardId}")
    public BoardResponse saveBoard(@PathVariable Long boardId,
                                   @Valid @RequestBody SaveBoardProgress request,
                                   Principal principal) {
        UserBoard board;
        try {
            board = boardService.saveProgress(
                            boardId,
                            principal.getName(),
                            request.currentState(),
                            request.notes(),
                            request.elapsedSeconds())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Board not found"));
        } catch (BoardCompletedException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }

        return BoardResponse.from(board);
    }

    /**
     * Reveals one cell of the caller's board, filling it with its solved digit.
     *
     * <p>Needed because a match board's solution never reaches the client, so a hint there cannot be
     * computed locally the way solo play does it. The reveal is an ordinary save: it can complete
     * the board, and it moves the player's standing in a race.
     *
     * @param boardId   the board to reveal a cell on
     * @param request   the selected cell, or a null {@code cellIndex} to take the first empty one
     * @param principal the authenticated user
     * @return the board after the reveal
     * @throws ResponseStatusException with status 404 (NOT_FOUND) if the caller has no board with
     *                                 that id
     * @throws ResponseStatusException with status 409 (CONFLICT) if the board is already completed
     */
    @PostMapping("/v1/boards/{boardId}/hint")
    public BoardResponse hint(@PathVariable Long boardId,
                              @Valid @RequestBody(required = false) RevealHint request,
                              Principal principal) {
        UserBoard board;
        try {
            board = boardService.revealHint(
                            boardId,
                            principal.getName(),
                            request == null ? null : request.cellIndex())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Board not found"));
        } catch (BoardCompletedException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }

        return BoardResponse.from(board);
    }

    /**
     * Concedes a match. Giving up a race that has started hands the win to the opponent; closing a
     * lobby nobody joined simply ends it.
     *
     * @param matchId   the match to concede
     * @param principal the authenticated user
     * @return the match as it now stands
     * @throws ResponseStatusException with status 404 (NOT_FOUND) if there is no such match or the
     *                                 caller is not one of its players
     */
    @PostMapping("/v1/matches/{matchId}/forfeit")
    public MatchResponse forfeit(@PathVariable Long matchId, Principal principal) {
        return MatchResponse.from(matchService.forfeit(matchId, principal.getName()));
    }

    /**
     * Opens a two-player lobby and returns its join code. No board is dealt yet — the pool is only
     * drawn on when someone actually joins.
     *
     * @param request   the difficulty both players will race on
     * @param principal the authenticated user, who becomes the host
     * @return the new match, {@code status: "waiting"}
     * @throws ResponseStatusException with status 400 (BAD_REQUEST) if the difficulty is unrecognized
     */
    @PostMapping("/v1/matches")
    public ResponseEntity<MatchResponse> createMatch(@Valid @RequestBody CreateMatch request,
                                                     Principal principal) {
        MatchSnapshot match = matchService.createMatch(principal.getName(), parseDifficulty(request.difficulty()));
        return ResponseEntity
                .created(URI.create("/api/v1/matches/" + match.match().getId()))
                .body(MatchResponse.from(match));
    }

    /**
     * Takes the second seat in a match and starts the race. Both players are dealt their own board
     * from one pooled puzzle, so the ordinary save endpoints carry the game from here.
     *
     * <p>Redeeming a code for a match the caller is already in is a no-op that returns the match,
     * so a refreshed lobby does not error.
     *
     * @param request   the host's join code
     * @param principal the authenticated user
     * @return the started match, {@code status: "active"} with a {@code boardId} to play
     * @throws ResponseStatusException with status 404 (NOT_FOUND) if no match has that code
     * @throws ResponseStatusException with status 409 (CONFLICT) if the lobby is full or the match
     *                                 is already over
     * @throws ResponseStatusException with status 503 (SERVICE_UNAVAILABLE) if the pool holds no
     *                                 board at the match's difficulty; the match stays waiting, so
     *                                 the join can be retried once the refill lands
     */
    @PostMapping("/v1/matches/join")
    public MatchResponse joinMatch(@Valid @RequestBody JoinMatch request, Principal principal) {
        MatchSnapshot match = matchService.joinMatch(principal.getName(), request.joinCode())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "No boards available to start the match; try again shortly"));
        return MatchResponse.from(match);
    }

    /**
     * Reads a match the caller is playing in. This is the endpoint the frontend polls: while
     * waiting, to see the opponent arrive; while racing, to track their progress and learn who won.
     *
     * @param matchId   the match to read
     * @param principal the authenticated user
     * @return the match as that player may see it
     * @throws ResponseStatusException with status 404 (NOT_FOUND) if there is no such match or the
     *                                 caller is not one of its players — the two are deliberately
     *                                 indistinguishable
     */
    @GetMapping("/v1/matches/{matchId}")
    public MatchResponse match(@PathVariable Long matchId, Principal principal) {
        return MatchResponse.from(matchService.getMatch(matchId, principal.getName()));
    }

    /**
     * Resolves a difficulty string from the wire onto its enum.
     *
     * @param difficulty the raw value
     * @return the matching difficulty
     * @throws ResponseStatusException with status 400 (BAD_REQUEST) if it matches no tier
     */
    private static BoardDifficulty parseDifficulty(String difficulty) {
        try {
            return BoardDifficulty.fromDbValue(difficulty.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "difficulty must be one of: easy, moderate, hard");
        }
    }

    /**
     * Creates a refresh token cookie with the configured TTL.
     *
     * @param token the refresh token value
     * @return configured cookie for setting the refresh token
     */
    private ResponseCookie refreshCookie(String token) {
        return baseCookie(token).maxAge(refreshTokenService.refreshTokenTtl()).build();
    }

    /**
     * Creates a cookie that clears the refresh token.
     *
     * @return configured cookie that will clear the existing refresh token cookie
     */
    private ResponseCookie clearingCookie() {
        return baseCookie("").maxAge(0).build();
    }

    /**
     * Creates a base cookie builder with security settings.
     *
     * <p>The cookie is configured as:
     * <ul>
     *   <li>HttpOnly - not accessible to JavaScript</li>
     *   <li>Secure - transmitted only over HTTPS (configurable via app.security.cookie-secure)</li>
     *   <li>SameSite=Strict - prevents CSRF attacks</li>
     *   <li>Path=/api/v1 - scoped to authentication endpoints</li>
     * </ul>
     *
     * @param value the cookie value
     * @return cookie builder with security settings applied
     */
    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path(COOKIE_PATH);
    }
}
