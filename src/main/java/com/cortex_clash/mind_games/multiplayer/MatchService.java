package com.cortex_clash.mind_games.multiplayer;

import com.cortex_clash.mind_games.auth.UserRepository;
import com.cortex_clash.mind_games.domain.GeneratedBoard;
import com.cortex_clash.mind_games.domain.Match;
import com.cortex_clash.mind_games.domain.MatchPlayer;
import com.cortex_clash.mind_games.domain.MatchStatus;
import com.cortex_clash.mind_games.domain.User;
import com.cortex_clash.mind_games.domain.UserBoard;
import com.cortex_clash.mind_games.generate.BoardDifficulty;
import com.cortex_clash.mind_games.generate.GeneratedBoardRepository;
import com.cortex_clash.mind_games.generate.UserBoardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Runs two-player races: opening a lobby, seating the second player, and scoring the result.
 *
 * <p>A match owns no puzzle of its own. When the second player joins, <em>one</em> board is claimed
 * from the pool and copied into a {@link UserBoard} for each player, which is what lets the whole
 * existing single-player path — autosave, server-derived completion, the terminal freeze — carry a
 * match unchanged. This service only coordinates around it.
 *
 * <p>Two orderings matter and both are enforced with row locks rather than trusted to timing:
 * seating the second player ({@link #joinMatch}), and deciding who won ({@link #recordBoardSaved}).
 */
@Service
public class MatchService {
    private static final Logger log = LoggerFactory.getLogger(MatchService.class);

    /** Seats per match. Two, and the reason a lobby has to be closed under a lock. */
    private static final int MATCH_SIZE = 2;

    /** No O/0 or I/1 — join codes get read aloud and typed by hand. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;

    /**
     * Attempts to find an unused join code before giving up. Collisions are vanishingly unlikely at
     * 32^6 codes, so exhausting this many tries means something is wrong rather than unlucky.
     */
    private static final int CODE_ATTEMPTS = 5;

    private final MatchRepository matchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final GeneratedBoardRepository generatedBoardRepository;
    private final UserBoardRepository userBoardRepository;
    private final UserRepository userRepository;
    private final SecureRandom random = new SecureRandom();

    public MatchService(MatchRepository matchRepository,
                        MatchPlayerRepository matchPlayerRepository,
                        GeneratedBoardRepository generatedBoardRepository,
                        UserBoardRepository userBoardRepository,
                        UserRepository userRepository) {
        this.matchRepository = matchRepository;
        this.matchPlayerRepository = matchPlayerRepository;
        this.generatedBoardRepository = generatedBoardRepository;
        this.userBoardRepository = userBoardRepository;
        this.userRepository = userRepository;
    }

    /**
     * Opens a lobby and seats the host. No board is dealt and the pool is untouched: a lobby nobody
     * joins should not consume a puzzle.
     *
     * @param username   the host, as carried by the bearer token
     * @param difficulty the tier both players will race on
     * @return the new match, waiting on its join code
     */
    @Transactional
    public MatchSnapshot createMatch(String username, BoardDifficulty difficulty) {
        User host = requireUser(username);

        Match match = matchRepository.save(new Match(generateJoinCode(), difficulty, host.getId()));
        matchPlayerRepository.save(new MatchPlayer(match.getId(), host.getId()));

        log.info("User {} opened {} match {} (code {})",
                username, difficulty, match.getId(), match.getJoinCode());
        return snapshot(match, host.getId());
    }

    /**
     * Seats the second player and starts the race, dealing both boards from a single pooled puzzle.
     *
     * <p>The match row is write-locked for the whole transaction, so two players redeeming the same
     * code concurrently cannot both pass the "still waiting" check and produce a three-player match
     * with a board dealt to nobody.
     *
     * @param username the joining player, as carried by the bearer token
     * @param joinCode the code the host shared; case-insensitive
     * @return the started match, or empty if the pool holds no board at the match's difficulty —
     *         the match is left waiting so the join can simply be retried
     * @throws MatchUnavailableException if the lobby is full, already over, or the caller's own
     */
    @Transactional
    public Optional<MatchSnapshot> joinMatch(String username, String joinCode) {
        User joiner = requireUser(username);

        Match match = matchRepository.findLockedByJoinCode(normalizeCode(joinCode))
                .orElseThrow(() -> new MatchNotFoundException(joinCode));

        // Rejoining your own in-flight match is a refresh, not an error.
        Optional<MatchPlayer> existingSeat =
                matchPlayerRepository.findByMatchIdAndUserId(match.getId(), joiner.getId());
        if (existingSeat.isPresent()) {
            return Optional.of(snapshot(match, joiner.getId()));
        }

        if (!match.isLive()) {
            throw new MatchUnavailableException("That match is already over");
        }
        if (matchPlayerRepository.countByMatchId(match.getId()) >= MATCH_SIZE) {
            throw new MatchUnavailableException("That match is full");
        }

        Optional<GeneratedBoard> claimed =
                generatedBoardRepository.claimNextByDifficulty(match.getDifficulty().dbValue());
        if (claimed.isEmpty()) {
            log.warn("Cannot start match {}: board pool exhausted for {}",
                    match.getId(), match.getDifficulty());
            return Optional.empty();
        }

        GeneratedBoard pooled = claimed.get();
        generatedBoardRepository.delete(pooled);

        matchPlayerRepository.save(new MatchPlayer(match.getId(), joiner.getId()));
        // Both players race the same puzzle on their own board, so ordinary saves apply unchanged.
        for (MatchPlayer player : matchPlayerRepository.findByMatchIdOrderByJoinedAtAsc(match.getId())) {
            userBoardRepository.save(new UserBoard(player.getUserId(), match.getId(),
                    match.getDifficulty(), pooled.getBoardState(), pooled.getSolutionState()));
        }

        match.start();
        matchRepository.save(match);

        log.info("User {} joined match {}; race started on pool board {}",
                username, match.getId(), pooled.getId());
        return Optional.of(snapshot(match, joiner.getId()));
    }

    /**
     * Reads a match for one of its participants — the endpoint the frontend polls.
     *
     * @param matchId  the match
     * @param username the requesting player
     * @return the match as that player may see it
     * @throws MatchNotFoundException if there is no such match, or the caller is not in it — a
     *                                match you are not playing is indistinguishable from one that
     *                                does not exist
     */
    @Transactional(readOnly = true)
    public MatchSnapshot getMatch(Long matchId, String username) {
        User viewer = requireUser(username);
        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new MatchNotFoundException(String.valueOf(matchId)));

        if (matchPlayerRepository.findByMatchIdAndUserId(matchId, viewer.getId()).isEmpty()) {
            throw new MatchNotFoundException(String.valueOf(matchId));
        }
        return snapshot(match, viewer.getId());
    }

    /**
     * Concedes a match. A host abandoning a lobby nobody joined simply closes it; conceding a race
     * that has started hands the win to the opponent, which is what makes "give up" mean something
     * to the other player rather than just vanishing.
     *
     * <p>Idempotent: conceding an already-finished match returns it unchanged rather than erroring,
     * so a double-tapped button or a retried request is harmless.
     *
     * @param matchId  the match to concede
     * @param username the conceding player
     * @return the match as it now stands
     * @throws MatchNotFoundException if there is no such match, or the caller is not in it
     */
    @Transactional
    public MatchSnapshot forfeit(Long matchId, String username) {
        User user = requireUser(username);
        Match match = matchRepository.findLockedById(matchId)
                .orElseThrow(() -> new MatchNotFoundException(String.valueOf(matchId)));

        List<MatchPlayer> seats = matchPlayerRepository.findByMatchIdOrderByJoinedAtAsc(matchId);
        if (seats.stream().noneMatch(seat -> seat.getUserId().equals(user.getId()))) {
            throw new MatchNotFoundException(String.valueOf(matchId));
        }
        if (!match.isLive()) {
            return snapshot(match, user.getId());
        }

        seats.stream()
                .filter(seat -> !seat.getUserId().equals(user.getId()))
                .findFirst()
                .ifPresentOrElse(
                        opponent -> match.finishWith(opponent.getUserId()),
                        // Nobody ever joined, so there is no one to award it to.
                        match::abandon);
        matchRepository.save(match);

        log.info("User {} conceded match {} ({})", username, matchId, match.getStatus().dbValue());
        return snapshot(match, user.getId());
    }

    /**
     * Closes out matches that will never finish on their own: lobbies nobody joined, and races
     * whose players walked away. Both simply stop being live — no winner is invented.
     *
     * @param lobbyCutoff  matches still waiting and created before this are abandoned
     * @param activeCutoff matches still running and started before this are abandoned
     * @return how many matches were closed
     */
    @Transactional
    public int abandonStaleMatches(Instant lobbyCutoff, Instant activeCutoff) {
        List<Match> stale = new ArrayList<>(
                matchRepository.findByStatusAndCreatedAtBefore(MatchStatus.WAITING, lobbyCutoff));
        stale.addAll(matchRepository.findByStatusAndStartedAtBefore(MatchStatus.ACTIVE, activeCutoff));

        stale.forEach(match -> {
            match.abandon();
            matchRepository.save(match);
        });
        return stale.size();
    }

    /**
     * Folds a board save into its match: refreshes the saver's progress and, if that save completed
     * the board, decides the race.
     *
     * <p>Called on every save of a match board, from the ordinary save path — there is no separate
     * "report progress" endpoint, because progress is just a projection of the board the player
     * already sent. A no-op for solo boards.
     *
     * <p>The winner is whichever completing save reaches the server first. The match row is
     * write-locked across the read-decide-write so a near-simultaneous second finisher cannot
     * overwrite the result; {@link Match#finishWith} makes the decision one-way.
     *
     * @param board the board as it stands after the save
     */
    @Transactional
    public void recordBoardSaved(UserBoard board) {
        if (!board.isMatchBoard()) {
            return;
        }

        MatchPlayer seat = matchPlayerRepository
                .findByMatchIdAndUserId(board.getMatchId(), board.getUserId())
                .orElse(null);
        if (seat == null) {
            log.warn("Board {} claims match {} but its owner has no seat there",
                    board.getId(), board.getMatchId());
            return;
        }

        if (!board.isCompleted()) {
            seat.recordProgress(board.countCorrectCells());
            matchPlayerRepository.save(seat);
            return;
        }

        seat.markFinished();
        matchPlayerRepository.save(seat);

        matchRepository.findLockedById(board.getMatchId()).ifPresent(match -> {
            if (match.finishWith(board.getUserId())) {
                matchRepository.save(match);
                log.info("Match {} won by user {} in {}s",
                        match.getId(), board.getUserId(),
                        match.getFinishedAt().getEpochSecond() - match.getStartedAt().getEpochSecond());
            }
        });
    }

    /**
     * Builds the participant's-eye view of a match, resolving usernames for display.
     *
     * @param match    the match
     * @param viewerId the player the snapshot is for
     * @return the snapshot
     */
    private MatchSnapshot snapshot(Match match, Integer viewerId) {
        List<MatchPlayer> seats = matchPlayerRepository.findByMatchIdOrderByJoinedAtAsc(match.getId());

        Map<Integer, String> usernames = new HashMap<>();
        userRepository.findAllById(seats.stream().map(MatchPlayer::getUserId).toList())
                .forEach(user -> usernames.put(user.getId(), user.getUsername()));

        List<MatchSnapshot.PlayerProgress> players = seats.stream()
                .map(seat -> new MatchSnapshot.PlayerProgress(
                        seat.getUserId(),
                        usernames.getOrDefault(seat.getUserId(), "(unknown)"),
                        seat.getCorrectCells(),
                        seat.getFinishedAt()))
                .toList();

        UserBoard viewerBoard = userBoardRepository
                .findByMatchIdAndUserId(match.getId(), viewerId)
                .orElse(null);

        // A live match board withholds its solution (see UserBoard#revealsSolution), which on its
        // own would mean the player who lost never gets to see the answer — their board stays in
        // progress forever. Once the match is decided there is no race left to win with it, so
        // this is where it is finally released, to both players alike.
        String viewerSolution = viewerBoard != null && !match.isLive()
                ? viewerBoard.getSolutionState()
                : null;

        return new MatchSnapshot(match, players,
                viewerBoard == null ? null : viewerBoard.getId(), viewerSolution);
    }

    /**
     * Generates a join code that is not already taken.
     *
     * @return an unused code
     * @throws IllegalStateException if no free code turned up in {@link #CODE_ATTEMPTS} tries
     */
    private String generateJoinCode() {
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            StringBuilder code = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
            String candidate = code.toString();
            if (!matchRepository.existsByJoinCode(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a free join code");
    }

    private static String normalizeCode(String joinCode) {
        return joinCode == null ? "" : joinCode.trim().toUpperCase(Locale.ROOT);
    }

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Unknown user: " + username));
    }
}
