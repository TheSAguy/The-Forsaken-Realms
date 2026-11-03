package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * TFR round 929: Flat Monte Carlo's attack search on a must-attack board, with an attached Reconfigure card: the rollouts
 * run on constraints that see the copied board, none fails, and the real declaration leaves out the Equipment.
 */
public class MustAttackRolloutTest extends FlatMcTestBase {

    private Card creature(final String name) {
        final Card c = put(name, ai, ZoneType.Battlefield);
        c.setSickness(false);
        return c;
    }

    private Card reconfigured(final Card onto) {
        final Card companion = creature("Inchblade Companion");
        final SpellAbility reconfigure = companion.getFirstSpellAbility();
        reconfigure.setActivatingPlayer(ai);
        companion.attachToEntity(onto, reconfigure);
        return companion;
    }

    @Test(timeOut = 60000)
    public void rolloutsDeclareOnAMustAttackBoard() {
        setUpGame();
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, ai);
        brain().setFlatMonteCarloOverride(true);
        brain().setFlatMonteCarloMaxOverride(true);
        brain().setFlatMonteCarloAttackOverride(true);
        put("Grand Melee", ai, ZoneType.Battlefield);
        final Card bear = creature("Runeclaw Bear");
        final Card grizzly = creature("Grizzly Bears");
        final Card companion = reconfigured(bear);
        put("Hill Giant", opp, ZoneType.Battlefield).setSickness(false);
        game.getAction().checkStateEffects(true);
        assertFalse(companion.isCreature());
        final Combat combat = new Combat(ai);
        game.getPhaseHandler().setCombat(combat);
        assertTrue(brain().flatMonteCarloActive(), "the test needs the search");
        final int rollouts = FlatMonteCarloPicker.getTotalRollouts();
        final int failures = FlatMonteCarloPicker.getTotalFailures();

        game.getPhaseHandler().devDeclareAttackers();

        assertTrue(FlatMonteCarloPicker.getTotalRollouts() > rollouts, "the rollouts ran");
        assertEquals(FlatMonteCarloPicker.getTotalFailures(), failures, "none failed");
        assertTrue(combat.isAttacking(bear), "must attack");
        assertTrue(combat.isAttacking(grizzly), "must attack");
        assertFalse(combat.isAttacking(companion), "an Equipment");
    }
}
