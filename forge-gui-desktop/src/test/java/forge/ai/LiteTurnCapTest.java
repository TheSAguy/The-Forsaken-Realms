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

/** TFR round 924: on lite settings the first AI timeout caps that turn; the next turn is whole; a desktop never changes. */
public class LiteTurnCapTest extends FlatMcTestBase {
    private static final long FULL = TimeUnit.SECONDS.toNanos(5);
    private static final long SHORT = TimeUnit.MILLISECONDS.toNanos(LiteTurnCap.LIMIT_AFTER_TIMEOUT_MS);

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
}
