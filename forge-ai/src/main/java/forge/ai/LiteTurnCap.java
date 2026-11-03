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
    /** Trip lines per duel; {@link #duelSummary()} always has the totals. */
    private static final int MAX_TRIP_LINES = 3;

    private static final AtomicInteger TRIPS = new AtomicInteger();
    private static final AtomicInteger SHORTENED = new AtomicInteger();

    private LiteTurnCap() {
    }

    static void noteTrip(final Player player, final int turn) {
        if (TRIPS.incrementAndGet() <= MAX_TRIP_LINES) {
            System.out.println("[TFR-AiCap] " + player + " timed out on turn " + turn
                    + ": no Flat Monte Carlo this turn, later priorities " + LIMIT_AFTER_TIMEOUT_MS + " ms");
        }
    }

    static long shortened(final long fullNanos) {
        SHORTENED.incrementAndGet();
        return Math.min(fullNanos, TimeUnit.MILLISECONDS.toNanos(LIMIT_AFTER_TIMEOUT_MS));
    }

    /** The totals since the last call, or null when the cap never tripped. Called once per duel. */
    public static String duelSummary() {
        final int trips = TRIPS.getAndSet(0);
        final int priorities = SHORTENED.getAndSet(0);
        return trips == 0 ? null : "[TFR-AiCap] this duel: " + trips + " turn(s) capped, " + priorities + " priorities shortened";
    }
}
