# mind-games-backend

The backend for [Cortex Clash](https://cortexclash.org) — a Sudoku service that deals graded
puzzles to signed-in players and keeps their progress. It is a stateless Spring Boot REST API over
MySQL: accounts and JWT auth on one side, a pool of pregenerated, difficulty-graded boards on the
other.

The frontend lives in a separate repository (`mind-games-ui`).

## What it does

**Puzzles.** Boards are never generated on the request path. A scheduled job keeps a pool of solved
puzzles topped up per difficulty: it generates a board, grades it by logically solving it with
human-style techniques (naked singles through X-wing) and summing a weighted cost per technique
applied, then files it as `easy`, `moderate`, or `hard` by score. `GET /api/v1/board` deals one out
of the pool — the pooled row is claimed under a row lock, copied to the player, and deleted in a
single transaction, so no two players ever get the same puzzle. Draining the pool is what trips the
low watermark and triggers the next refill.

**Accounts.** Registration, login, and a short-lived access token (JWT) paired with a rotating
refresh token in an HttpOnly cookie. Failed logins are counted and lock the account at a configured
threshold; every request re-reads the user, so a lockout takes effect immediately rather than
whenever the access token happens to expire. Password reset is an emailed one-time token — the raw
token is never persisted, and the endpoints are written to answer identically whether or not the
address belongs to an account.

## Stack

Java 26 · Spring Boot 4.1 (Web, Security, Data JPA, Validation, Mail, Actuator) · Flyway · MySQL ·
Gradle · Docker → ECR → ECS Fargate.

## Running locally

You need a JDK (the Gradle toolchain wants Java 26) and a MySQL you can point at:

```bash
docker run -d --name mind-games-db -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=secret -e MYSQL_DATABASE=mind_games mysql:8
```

Nothing in `application.yaml` has a default for the database, the JWT secret, or the grading and
generation knobs — the app is meant to fail loudly rather than start half-configured. Export them
first:

```bash
export DB_URL='jdbc:mysql://localhost:3306/mind_games'
export DB_USERNAME=root
export DB_PASSWORD=secret

export JWT_SECRET=$(openssl rand -base64 32)   # HMAC-SHA256 key, Base64, >= 32 bytes
export JWT_EXPIRATION_MINUTES=15
export JWT_REFRESH_EXPIRATION_DAYS=30
export SECURITY_LOCKOUT_THRESHOLD=5
export COOKIE_SECURE=false                     # the browser drops a Secure cookie over plain HTTP

# Grade-score bands (the values the dev deployment runs with): below MIN or above HARD
# the board is discarded, and the three MAX values are the easy/moderate/hard cutoffs.
export BOARD_GRADING_MIN_THRESHOLD=43
export BOARD_GRADING_EASY_MAX_THRESHOLD=52
export BOARD_GRADING_MODERATE_MAX_THRESHOLD=72
export BOARD_GRADING_HARD_MAX_THRESHOLD=92
export BOARD_GENERATION_PREGENERATION_BOARD_COUNT=5
export BOARD_GENERATION_LOW_WATERMARK=2
export BOARD_GENERATION_MAX_ATTEMPTS_PER_BOARD=3

./gradlew bootRun
```

Flyway applies `src/main/resources/db/migration` on startup; Hibernate only validates against the
result (`ddl-auto: validate`), so schema changes go in a new migration, never in an entity alone.
The board pool starts empty and the refill job runs every five minutes by default, so the first
`GET /api/v1/board` may answer 503 until a refill completes.

Password reset defaults to `PASSWORD_RESET_MAILER=log`, which writes the reset link to the log
instead of sending it — convenient locally, and never to be used in a deployed environment, since
that log line is a live account-takeover secret. Deployments set it to `smtp` and point
`MAIL_HOST`/`MAIL_PORT`/`MAIL_USERNAME`/`MAIL_PASSWORD` at SES.

```bash
./gradlew test        # unit tests, plus a Spring context load that needs the env above
./gradlew bootJar     # build/libs/mind-games-0.0.1-SNAPSHOT.jar
```

## API

Everything is under `/api`. Public: `ping`, `register`, `authenticate_user`, `refresh`, `logout`,
and the two password-reset endpoints. Everything else needs `Authorization: Bearer <accessToken>`.

| Endpoint | Purpose |
|---|---|
| `GET /api/ping` | Liveness for humans. Load balancers use `GET /actuator/health/readiness` on the management port (9090), not this. |
| `POST /api/v1/register` | Create an account. 201, or 400 on a weak password, or 409 on a taken username/email. |
| `POST /api/v1/authenticate_user` | Access token in the body, refresh token in an HttpOnly cookie. |
| `POST /api/v1/refresh` | Trade the refresh cookie for a new access token; the refresh token rotates. |
| `POST /api/v1/logout` | Revoke the refresh token and clear the cookie. |
| `POST /api/v1/request_password_reset` | Email a reset link. Always 202, whatever the address. |
| `POST /api/v1/reset_password` | Redeem the token, set a new password, revoke every session. |
| `GET /api/v1/board?difficulty=easy\|moderate\|hard` | Deal a board. 503 if the pool is empty at that tier. |
| `GET /api/v1/boards` | The caller's 5 most recent **unfinished** boards, most recently played first. Completed boards are not listed. |
| `PUT /api/v1/boards/{id}` | Save full state: grid, pencil marks, elapsed time. Completion is terminal (409 after). |
| `POST /api/v1/boards/{id}/hint` | Fill one cell with its solved digit. Body optional: `{"cellIndex": 0-80}`, else the first empty cell. |
| `GET /api/v1/board/stats` | The caller's lifetime totals — boards solved, best time, time played — overall and per difficulty. Every tier appears; one never played reports zeroes. |
| `POST /api/v1/matches` | Open a two-player lobby at a difficulty. Returns a join code; no board is dealt yet. |
| `POST /api/v1/matches/join` | Redeem a join code to take the second seat and start the race. 503 if the pool is empty at that tier. |
| `GET /api/v1/matches/{id}` | Poll a match you are playing in. 404 if it isn't yours — indistinguishable from not existing. |
| `POST /api/v1/matches/{id}/forfeit` | Concede. Hands a started race to the opponent; closes an unjoined lobby with no winner. |

Boards are 81-character row-major strings (`'0'` = empty, read left to right, top to bottom). Saves
send the whole grid, not a delta; the server detects completion by comparing the saved grid to the
solution.

A match deals **one** pooled puzzle and copies it into a board per player, so the ordinary save
endpoints carry the race unchanged — there is no separate progress endpoint. Two consequences the
frontend has to handle: a match board's `solution` comes back `null` until that player completes it
(so conflict marking has to be off and hints must go to `POST /boards/{id}/hint`), and the only
thing you learn about your opponent is a `correctCells` count — never their grid, since a
mostly-correct grid is a mostly-complete solution. Races are scored on the server's
`startedAt`/`finishedAt`, not the client-accumulated `elapsedSeconds`.

Once a match is no longer live, `GET /api/v1/matches/{id}` returns the puzzle's `solution` to
**both** players. Withholding it is about protecting the race, so it is released when the race ends
— otherwise the player who lost is stuck with a board frozen in progress and no way to see the
answer. It hangs off the match rather than the board for exactly that reason: the losing board is
still `in_progress` and will never reveal it on its own.

Passwords must be at least 8 characters and at most 30 *bytes*. Standard alphanumerics and basic
symbols are one UTF-8 byte each, so for those passwords the ceiling is effectively 30 characters;
accented characters and emoji encode to more than one byte and so hit it sooner.

## Layout

```
src/main/java/com/cortex_clash/mind_games/
  api/        controllers, request/response records
  auth/       registration, login, refresh + reset tokens, password policy, cleanup jobs
  security/   JWT issue/verify, the bearer filter, CloudFront origin verification, filter chain
  generate/   Sudoku generation, grading, and the board pool
  multiplayer/ two-player races: lobbies, join codes, scoring, the stale-match sweep
  domain/     JPA entities and enum converters
  mail/       reset-mail delivery (SMTP, or log for local development)
src/main/resources/db/migration/   Flyway migrations
infra/                             ECS task definition, CloudFront setup script
docs/aws-deployment.md             deployment runbook
```

## Deployment

`docs/aws-deployment.md` is the runbook: ECS Fargate behind an internal ALB and an API Gateway HTTP
API, backed by RDS MySQL, with CloudFront in front. Pushing to `master` builds the image and pushes
it to ECR (`.github/workflows/ci.yml`); the task definition in `infra/ecs/` supplies the environment
contract, with `DB_PASSWORD` and `JWT_SECRET` coming from Secrets Manager. A push doesn't restart the
running task, so an EventBridge Scheduler job force-deploys the ECS service nightly at 8 PM Pacific —
the newest `master` image goes live within a day, no manual step (runbook §14).

Two things to know before touching the topology. The service runs as a **single task** on purpose:
the board refill and token cleanup jobs are in-process `@Scheduled` jobs that assume one instance,
and scaling to two would have both refilling the same difficulty and overshooting the pool. And
`ORIGIN_VERIFY_SECRET`, when set, makes the app reject any request that did not arrive through
CloudFront carrying the matching `X-Origin-Verify` header — that is what keeps the origin from being
reachable around the WAF. It is blank by default so local runs work.
