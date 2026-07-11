package com.cortex_clash.mind_games.api;

import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.cortex_clash.mind_games.api.requests.AuthenticateUser;
import com.cortex_clash.mind_games.api.requests.RegisterUserRequest;
import com.cortex_clash.mind_games.api.requests.SaveBoardProgress;
import com.cortex_clash.mind_games.api.responses.BoardResponse;
import com.cortex_clash.mind_games.auth.AuthService;
import com.cortex_clash.mind_games.auth.RefreshTokenService;
import com.cortex_clash.mind_games.auth.TokenPair;
import com.cortex_clash.mind_games.domain.User;
import com.cortex_clash.mind_games.domain.UserBoard;
import com.cortex_clash.mind_games.generate.BoardCompletedException;
import com.cortex_clash.mind_games.generate.BoardDifficulty;
import com.cortex_clash.mind_games.generate.BoardService;
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
 * REST API controller providing authentication and user management endpoints.
 *
 * <p>This controller currently handles:
 * <ul>
 *   <li>User registration</li>
 *   <li>User authentication with JWT tokens</li>
 *   <li>Token refresh using HttpOnly cookies</li>
 *   <li>User logout and token revocation</li>
 *   <li>Serving a pregenerated board to the player</li>
 * </ul>
 *
 * <p>Refresh tokens are stored in secure, HttpOnly cookies scoped to {@code /api/v1}.
 * Access tokens are returned in response bodies as JSON.
 *
 * <p>Additional endpoints will be added as development progresses.</p>
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
    private final BoardService boardService;

    /** Secure flag on the refresh cookie; disable only for local HTTP development. */
    private final boolean cookieSecure;

    public ApiController(AuthService authService, RefreshTokenService refreshTokenService,
                         BoardService boardService,
                         @Value("${app.security.cookie-secure:true}") boolean cookieSecure) {
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
        this.boardService = boardService;
        this.cookieSecure = cookieSecure;
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
     * @return response containing the created username
     * @throws ResponseStatusException with status 409 (CONFLICT) if username or email already exists
     * @throws ResponseStatusException with status 500 (INTERNAL_SERVER_ERROR) on unexpected errors
     */
    @PostMapping("/v1/register")
    public ResponseEntity<Map<String, Object>> registerUser(@RequestBody RegisterUserRequest request) {
        User user;
        try {
            user = authService.registerUser(request.username(), request.email(), request.password());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
        return ResponseEntity.ok()
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
        BoardDifficulty target;
        try {
            target = BoardDifficulty.fromDbValue(difficulty.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "difficulty must be one of: easy, moderate, hard");
        }

        UserBoard board = boardService.claimBoard(principal.getName(), target)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "No " + target.dbValue() + " boards available; try again shortly"));

        return BoardResponse.from(board);
    }

    /**
     * Lists the caller's boards, most recently played first, so the frontend can offer to resume
     * one. Each carries its full state, including the solution.
     *
     * @param principal the authenticated user
     * @return the caller's boards, newest activity first
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
