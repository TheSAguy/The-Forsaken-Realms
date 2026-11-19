package forge.ai.ability;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.Random;

import org.apache.commons.lang3.tuple.Pair;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.ComputerUtil;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * TFR round 1044 (logs-1118 P3-39): DamageDealAi.getDamagingSAToChain rolls its chain chance first and looks for danger
 * only when the roll says no. In danger it must still always chain, as when danger set the chance to 100 before the
 * roll. The dice are fixed here: 99 fails any chance below 100, 0 passes any chance above 0.
 */
public class DamageChainInDangerTest extends AITest {
    private Player ai;

    @AfterMethod
    public void freshDice() {
        MyRandom.setRandom(new Random());
    }

    /**
     * Dice whose nextInt(100) is {@code answer}. MyRandom wraps them in its counting Random, which draws every value
     * through the delegate's nextInt() as next(bits) = nextInt() >>> (32 - bits); nextInt(100) takes next(31) % 100.
     */
    private static Random dice(final int answer) {
        return new Random() {
            private static final long serialVersionUID = 1L;

            @Override
            public int nextInt() {
                return answer << 1;
            }
        };
    }

    @Test
    public void theDiceAreFixed() {
        MyRandom.setRandom(dice(99));
        assertTrue(MyRandom.getRandom().nextInt(100) == 99 && !MyRandom.percentTrue(90) && MyRandom.percentTrue(100));
        MyRandom.setRandom(dice(0));
        assertTrue(MyRandom.getRandom().nextInt(100) == 0 && MyRandom.percentTrue(1) && !MyRandom.percentTrue(0));
    }

    /** The AI holds two Lightning Bolts and two Mountains; in danger, it is at 3 against two Hill Giants. */
    private SpellAbility boltWithASecondInHand(final boolean danger) {
        final Game game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        for (final Player p : game.getPlayers()) {
            fillLibrary(p, 20);
        }
        addCards("Mountain", 2, ai);
        final Card bolt = addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        if (danger) {
            addCards("Hill Giant", 2, opp);
            ai.setLife(3, null);
        }
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        final SpellAbility sa = bolt.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    @Test
    public void inDangerAFailedRollStillChains() {
        final SpellAbility sa = boltWithASecondInHand(true);
        assertTrue(ComputerUtil.aiLifeInDanger(ai, true, 0), "the board puts the AI in serious danger");
        MyRandom.setRandom(dice(99));
        final Pair<SpellAbility, Integer> chain = DamageDealAi.getDamagingSAToChain(ai, sa, "3");
        assertNotNull(chain, "in danger the AI chains whatever the roll");
        assertTrue(chain.getValue() == 3, "the second Bolt's 3 damage");
    }

    @Test
    public void outOfDangerAFailedRollDoesNotChain() {
        final SpellAbility sa = boltWithASecondInHand(false);
        MyRandom.setRandom(dice(99));
        assertNull(DamageDealAi.getDamagingSAToChain(ai, sa, "3"));
    }

    @Test
    public void outOfDangerAPassedRollChains() {
        final SpellAbility sa = boltWithASecondInHand(false);
        MyRandom.setRandom(dice(0));
        assertNotNull(DamageDealAi.getDamagingSAToChain(ai, sa, "3"));
    }
}
