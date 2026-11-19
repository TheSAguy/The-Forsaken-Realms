package forge.ai;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import forge.game.player.Player;

/**
 * TFR round 924: what caps an AI turn on lite settings (phones and tablets). The Android proof saw up to 55 s a turn on a
 * big board with 151 AI timeouts in one duel. Flat Monte Carlo's 2.5 s turn budget meters rollouts only, and 134 of the
 * 165 timeouts were plain heuristic priorities: each priority is its own session with its own 5 s. So the first timeout
 * in a turn switches Monte Carlo off for that turn, and the turn's later priority sessions get {@link #LIMIT_AFTER_TIMEOUT_MS}.
 * Attackers and blockers keep their own full sessions. AiController keeps the turn; this class counts, for the log.
 */
public final class LiteTurnCap {
    /** A later priority's limit in a turn that has already timed out. */
    static final long LIMIT_AFTER_TIMEOUT_MS = 1000;
    /**
     * Round 1044: this many shortened priorities in a row that still ran out of time put the rest of the turn on
     * {@link #LIMIT_AFTER_STREAK_MS}. A timed-out priority passes, so on a board where 1 s is not enough the extra time
     * bought nothing (the 1.29 Android log: 219 of 247 shortened priorities still timed out, about 3.7 minutes of wait).
     * Every {@link #PROBE_EVERY}th priority of the streak still gets {@link #LIMIT_AFTER_TIMEOUT_MS}, so a board that got
     * cheaper (or a play that needs 250-1000 ms) is found again; one priority that finishes in time ends the streak.
     */
    static final int STREAK_TO_ESCALATE = 3;
    /** Round 1044: a priority's limit once {@link #STREAK_TO_ESCALATE} shortened priorities in a row timed out. */
    static final long LIMIT_AFTER_STREAK_MS = 250;
    /** Round 1044: on the streak limit, every this many priorities one runs on the shortened limit again (a probe). */
    static final int PROBE_EVERY = 4;
    /** Trip lines per duel; {@link #duelSummary()} always has the totals. */
    private static final int MAX_TRIP_LINES = 3;

    private static final AtomicInteger TRIPS = new AtomicInteger();
    private static final AtomicInteger SHORTENED = new AtomicInteger();
    /** Round 1044: turns that escalated, and the priorities that ran on the streak limit. */
    private static final AtomicInteger STREAK_TURNS = new AtomicInteger();
    private static final AtomicInteger STREAK_PRIORITIES = new AtomicInteger();
    /** Round 1020: every AI timeout in a real game (desktop and phone), for the [TFR-Duel] line's aiTimeouts=. */
    private static final AtomicInteger TIMEOUTS = new AtomicInteger();

    private LiteTurnCap() {
    }

    /** Round 1020: one AI priority session ran out of time. Rollout games don't count. */
    static void noteTimeout(final boolean simulationCopy) {
        if (!simulationCopy) {
            TIMEOUTS.incrementAndGet();
        }
    }

    /** Round 1020: the real-game AI timeouts since the last call. Called once per duel, for aiTimeouts=. */
    public static int takeDuelTimeouts() {
        return TIMEOUTS.getAndSet(0);
    }

    static void noteTrip(final Player player, final int turn) {
        if (TRIPS.incrementAndGet() <= MAX_TRIP_LINES) {
            System.out.println("[TFR-AiCap] " + player + " timed out on turn " + turn
                    + ": no Flat Monte Carlo this turn, later priorities " + LIMIT_AFTER_TIMEOUT_MS + " ms");
        }
    }

    static long shortened(final long fullNanos) {
        return shortened(fullNanos, 0);
    }

    /**
     * Round 1044: a later priority's limit in a capped turn, given how many shortened priorities in a row just timed
     * out: from {@link #STREAK_TO_ESCALATE} on, {@link #LIMIT_AFTER_STREAK_MS}, except every {@link #PROBE_EVERY}th
     * (250, 250, 250, 1000, 250, ...). Never longer than the game's own timeout.
     */
    static long shortened(final long fullNanos, final int timeoutStreak) {
        SHORTENED.incrementAndGet();
        final int intoStreak = timeoutStreak - STREAK_TO_ESCALATE + 1; // 1 = the first priority on the streak limit
        final boolean escalated = intoStreak >= 1 && intoStreak % PROBE_EVERY != 0;
        if (escalated) {
            STREAK_PRIORITIES.incrementAndGet();
        }
        return Math.min(fullNanos, TimeUnit.MILLISECONDS.toNanos(escalated ? LIMIT_AFTER_STREAK_MS : LIMIT_AFTER_TIMEOUT_MS));
    }

    /** Round 1044: a capped turn's streak just reached {@link #STREAK_TO_ESCALATE}. */
    static void noteStreak(final Player player, final int turn) {
        if (STREAK_TURNS.incrementAndGet() <= MAX_TRIP_LINES) {
            System.out.println("[TFR-AiCap] " + player + " turn " + turn + ": " + STREAK_TO_ESCALATE
                    + " shortened priorities in a row timed out, later priorities " + LIMIT_AFTER_STREAK_MS
                    + " ms (every " + PROBE_EVERY + "th " + LIMIT_AFTER_TIMEOUT_MS + " ms) until one finishes in time");
        }
    }

    /** The totals since the last call, or null when the cap never tripped. Called once per duel. */
    public static String duelSummary() {
        final int trips = TRIPS.getAndSet(0);
        final int priorities = SHORTENED.getAndSet(0);
        final int streakTurns = STREAK_TURNS.getAndSet(0);
        final int streakPriorities = STREAK_PRIORITIES.getAndSet(0);
        if (trips == 0) {
            return null;
        }
        final String line = "[TFR-AiCap] this duel: " + trips + " turn(s) capped, " + priorities + " priorities shortened";
        return streakTurns == 0 ? line
                : line + ", " + streakPriorities + " of them at " + LIMIT_AFTER_STREAK_MS + " ms after " + STREAK_TO_ESCALATE
                        + " timeouts in a row (" + streakTurns + " time(s))";
    }
}
