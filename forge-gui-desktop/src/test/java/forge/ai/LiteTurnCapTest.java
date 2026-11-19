package forge.ai;

import java.util.concurrent.TimeUnit;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import forge.ai.simulation.FlatMcTestBase;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;

/**
 * TFR round 924: on lite settings the first AI timeout caps that turn; the next turn is whole; a desktop never changes.
 * Round 1044: shortened priorities that keep timing out escalate to the streak limit until one finishes in time.
 */
public class LiteTurnCapTest extends FlatMcTestBase {
    private static final long FULL = TimeUnit.SECONDS.toNanos(5);
    private static final long SHORT = TimeUnit.MILLISECONDS.toNanos(LiteTurnCap.LIMIT_AFTER_TIMEOUT_MS);
    private static final long STREAK = TimeUnit.MILLISECONDS.toNanos(LiteTurnCap.LIMIT_AFTER_STREAK_MS);

    private void setUpLite() {
        setUpGame(); // turn 5
        game.AI_FLAT_MONTE_CARLO_LITE = true;
        LiteTurnCap.duelSummary();
    }

    @Test
    public void aTimeoutCapsThatTurnOnly() {
        setUpLite();
        assertEquals(brain().prioritySessionNanos(), FULL);
        assertFalse(brain().isFlatMonteCarloTripped());

        brain().noteLiteTimeout();
        assertTrue(brain().isFlatMonteCarloTripped(), "no Monte Carlo for the rest of the turn");
        assertEquals(brain().prioritySessionNanos(), SHORT);
        assertEquals(brain().prioritySessionNanos(), SHORT);

        setTurn(6);
        assertFalse(brain().isFlatMonteCarloTripped(), "back the next turn");
        assertEquals(brain().prioritySessionNanos(), FULL);
    }

    @Test
    public void aSecondTimeoutInATurnCountsOnce() {
        setUpLite();
        brain().noteLiteTimeout();
        brain().noteLiteTimeout();
        brain().prioritySessionNanos();
        assertEquals(LiteTurnCap.duelSummary(), "[TFR-AiCap] this duel: 1 turn(s) capped, 1 priorities shortened");
        assertNull(LiteTurnCap.duelSummary(), "the totals are per duel");
    }

    @Test
    public void aNewTurnCanTripAgain() {
        setUpLite();
        brain().noteLiteTimeout();
        setTurn(6);
        brain().noteLiteTimeout();
        assertEquals(brain().prioritySessionNanos(), SHORT);
        assertNotNull(LiteTurnCap.duelSummary());
    }

    @Test
    public void aDesktopIsUntouched() {
        setUpGame();
        LiteTurnCap.duelSummary();
        brain().noteLiteTimeout();
        assertFalse(brain().isFlatMonteCarloTripped());
        assertEquals(brain().prioritySessionNanos(), FULL);
        assertNull(LiteTurnCap.duelSummary());
    }

    @Test
    public void aRealTimeoutTripsTheCap() {
        setUpLite();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        for (int i = 0; i < 3; i++) {
            addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
            addCard("Mountain", ai);
        }
        game.getAction().checkStateEffects(true);
        game.AI_TIMEOUT = 0; // the evaluation has no time at all

        brain().chooseSpellAbilityToPlay();

        game.AI_TIMEOUT = 5;
        assertTrue(brain().isFlatMonteCarloTripped());
        assertEquals(brain().prioritySessionNanos(), SHORT);
    }

    /** Round 1044: one shortened priority, as chooseSpellAbilityToPlay brackets it; returns the limit it ran on. */
    private long priority(final boolean timesOut) {
        final long limit = brain().prioritySessionNanos();
        assertTrue(brain().notePriorityStart(), "a capped turn's priority");
        if (timesOut) {
            brain().noteLiteTimeout();
        }
        brain().notePriorityEnd();
        return limit;
    }

    @Test
    public void threeTimeoutsInARowEscalate() {
        setUpLite();
        brain().noteLiteTimeout(); // the trip, on a full-length priority
        for (int i = 0; i < LiteTurnCap.STREAK_TO_ESCALATE; i++) {
            assertEquals(priority(true), SHORT, "priority " + i + " of the streak");
        }
        assertEquals(priority(true), STREAK, "the streak escalates");
        assertEquals(priority(true), STREAK, "and stays while priorities keep timing out");
        assertEquals(LiteTurnCap.duelSummary(), "[TFR-AiCap] this duel: 1 turn(s) capped, 5 priorities shortened, 2 of them at "
                + LiteTurnCap.LIMIT_AFTER_STREAK_MS + " ms after " + LiteTurnCap.STREAK_TO_ESCALATE + " timeouts in a row (1 time(s))");
    }

    @Test
    public void everyFourthStreakPriorityProbesAtTheShortenedLimit() {
        setUpLite();
        brain().noteLiteTimeout();
        for (int i = 0; i < LiteTurnCap.STREAK_TO_ESCALATE; i++) {
            priority(true);
        }
        for (int i = 1; i < LiteTurnCap.PROBE_EVERY; i++) {
            assertEquals(priority(true), STREAK, "streak priority " + i);
        }
        assertEquals(priority(true), SHORT, "the probe");
        assertEquals(priority(true), STREAK, "a probe that times out keeps the streak");
        priority(true);
        priority(true);
        assertEquals(priority(false), SHORT, "the next probe, which finishes in time");
        assertEquals(priority(true), SHORT, "the streak is over");
    }

    @Test
    public void aPriorityThatFinishesEndsTheStreak() {
        setUpLite();
        brain().noteLiteTimeout();
        for (int i = 0; i < LiteTurnCap.STREAK_TO_ESCALATE; i++) {
            priority(true);
        }
        assertEquals(priority(false), STREAK, "finished in time on the streak limit");
        assertEquals(priority(true), SHORT, "back to the shortened limit");
        assertEquals(priority(true), SHORT);
    }

    @Test
    public void anInterruptedStreakDoesNotEscalate() {
        setUpLite();
        brain().noteLiteTimeout();
        priority(true);
        priority(true);
        priority(false);
        priority(true);
        priority(true);
        assertEquals(brain().prioritySessionNanos(), SHORT, "two, a finish, then two: never three in a row");
        assertEquals(LiteTurnCap.duelSummary(), "[TFR-AiCap] this duel: 1 turn(s) capped, 6 priorities shortened");
    }

    @Test
    public void theNextTurnStartsWhole() {
        setUpLite();
        brain().noteLiteTimeout();
        for (int i = 0; i < LiteTurnCap.STREAK_TO_ESCALATE; i++) {
            priority(true);
        }
        setTurn(6);
        assertEquals(brain().prioritySessionNanos(), FULL);
        brain().noteLiteTimeout();
        assertEquals(brain().prioritySessionNanos(), SHORT, "a new trip starts a new streak");
    }

    @Test
    public void aDesktopNeverEscalates() {
        setUpGame();
        LiteTurnCap.duelSummary();
        for (int i = 0; i < 6; i++) {
            brain().prioritySessionNanos();
            assertFalse(brain().notePriorityStart(), "no capped turn on a desktop");
            brain().noteLiteTimeout();
            brain().notePriorityEnd();
        }
        assertEquals(brain().prioritySessionNanos(), FULL);
        assertNull(LiteTurnCap.duelSummary());
    }

    @Test
    public void realTimeoutsEscalate() {
        setUpLite();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        for (int i = 0; i < 3; i++) {
            addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
            addCard("Mountain", ai);
        }
        game.getAction().checkStateEffects(true);
        game.AI_TIMEOUT = 0; // every priority runs out of time
        for (int i = 0; i <= LiteTurnCap.STREAK_TO_ESCALATE; i++) {
            brain().chooseSpellAbilityToPlay(); // the trip, then the streak
        }
        game.AI_TIMEOUT = 5;
        assertEquals(brain().prioritySessionNanos(), STREAK);
    }
}
