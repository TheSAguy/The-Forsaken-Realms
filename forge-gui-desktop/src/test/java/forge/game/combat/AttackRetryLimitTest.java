package forge.game.combat;

import forge.LobbyPlayer;
import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.util.AITimeoutTracker;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * TFR round 929: an AI whose attack declaration can never pass does not loop forever. Juggernaut must attack, so a
 * declaration with no attackers is invalid; before the round each of these tests ran until its timeout.
 */
public class AttackRetryLimitTest extends AITest {

    /** Declares nothing, however often it is asked. */
    private static class NeverAttacks extends PlayerControllerAi {
        int asked;

        NeverAttacks(final Game game, final Player player, final LobbyPlayer lobbyPlayer) {
            super(game, player, lobbyPlayer);
        }

        @Override
        public void declareAttackers(final Player attacker, final Combat combat) {
            asked++;
        }
    }

    /** The same, but a person: after {@code failures} tries it declares properly. */
    private static class HumanRetries extends NeverAttacks {
        private final Card card;
        private final Player defender;
        private final int failures;

        HumanRetries(final Game game, final Player player, final LobbyPlayer lobbyPlayer, final Card card0, final Player defender0, final int failures0) {
            super(game, player, lobbyPlayer);
            card = card0;
            defender = defender0;
            failures = failures0;
        }

        @Override
        public boolean isAI() {
            return false;
        }

        @Override
        public void declareAttackers(final Player attacker, final Combat combat) {
            if (++asked > failures) {
                combat.addAttacker(card, defender);
            }
        }
    }

    private Player attacker;
    private Player defender;
    private Card juggernaut;

    private Game setUpMustAttack() {
        final Game game = initAndCreateGame();
        attacker = game.getPlayers().get(1);
        defender = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, attacker);
        juggernaut = addCard("Juggernaut", attacker);
        juggernaut.setSickness(false);
        game.getAction().checkStateEffects(true);
        game.getPhaseHandler().setCombat(new Combat(attacker));
        return game;
    }

    @Test(timeOut = 30000)
    public void anAiAttackThatCanNeverPassIsSkipped() {
        final Game game = setUpMustAttack();
        final NeverAttacks ai = new NeverAttacks(game, attacker, attacker.getLobbyPlayer());
        attacker.dangerouslySetController(ai);

        game.getPhaseHandler().devDeclareAttackers();

        AssertJUnit.assertEquals(AttackRetryLimit.MAX_AI_TRIES, ai.asked);
        AssertJUnit.assertTrue(game.getPhaseHandler().getCombat().getAttackers().isEmpty());
        AssertJUnit.assertFalse("nothing is left tapped", juggernaut.isTapped());
    }

    @Test(timeOut = 30000)
    public void aRolloutStopsAtItsDeadlineOnTheFirstRetry() {
        final Game game = setUpMustAttack();
        final NeverAttacks ai = new NeverAttacks(game, attacker, attacker.getLobbyPlayer());
        attacker.dangerouslySetController(ai);

        final Object limit = AITimeoutTracker.arm(System.nanoTime() - 1, false);
        try {
            game.getPhaseHandler().devDeclareAttackers();
            AssertJUnit.fail("a passed deadline ends the declaration");
        } catch (final AITimeoutTracker.Expired expected) {
            AssertJUnit.assertEquals(1, ai.asked);
        } finally {
            AITimeoutTracker.disarm(limit);
        }
    }

    @Test(timeOut = 30000)
    public void aHumanMayRetryAsOftenAsTheyLike() {
        final Game game = setUpMustAttack();
        final int failures = AttackRetryLimit.MAX_AI_TRIES * 2;
        final HumanRetries human = new HumanRetries(game, attacker, attacker.getLobbyPlayer(), juggernaut, defender, failures);
        attacker.dangerouslySetController(human);

        game.getPhaseHandler().devDeclareAttackers();

        AssertJUnit.assertEquals(failures + 1, human.asked);
        AssertJUnit.assertTrue(game.getPhaseHandler().getCombat().isAttacking(juggernaut));
    }

    @Test(timeOut = 30000)
    public void anAiThatDeclaresProperlyIsAskedOnce() {
        final Game game = setUpMustAttack();

        game.getPhaseHandler().devDeclareAttackers();

        AssertJUnit.assertTrue(game.getPhaseHandler().getCombat().isAttacking(juggernaut));
        AssertJUnit.assertTrue(juggernaut.isTapped());
    }

    /** The state that froze a Xira duel: in a copied game the constraints still count an attached Reconfigure card as a creature. */
    @Test(timeOut = 30000)
    public void aReconfiguredEquipmentCannotFreezeACopiedGame() {
        final Game game = initAndCreateGame();
        attacker = game.getPlayers().get(1);
        defender = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, attacker);
        addCard("Grand Melee", attacker);
        final Card bear = addCard("Runeclaw Bear", attacker);
        bear.setSickness(false);
        final Card companion = addCard("Inchblade Companion", attacker);
        companion.setSickness(false);
        final forge.game.spellability.SpellAbility reconfigure = companion.getFirstSpellAbility();
        reconfigure.setActivatingPlayer(attacker);
        companion.attachToEntity(bear, reconfigure);
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertFalse("attached, it is only an Equipment", companion.isCreature());
        final Combat real = new Combat(attacker);
        game.getPhaseHandler().setCombat(real);
        AssertJUnit.assertFalse(real.getAttackConstraints().getLegalAttackers().getLeft().containsKey(companion));

        final Game copy = new forge.ai.simulation.GameCopier(game).makeCopy();
        final Combat copied = copy.getPhaseHandler().getCombat();
        copy.getPhaseHandler().devDeclareAttackers();
        AssertJUnit.assertTrue("the attack is skipped", copied.getAttackers().isEmpty());
    }

    @Test(timeOut = 30000)
    public void anAiPropagandaPaymentItCannotMakeCostsTheAttackNotTheGame() {
        final Game game = setUpMustAttack();
        addCard("Propaganda", defender);
        game.getAction().checkStateEffects(true);
        game.getPhaseHandler().setCombat(new Combat(attacker));

        game.getPhaseHandler().devDeclareAttackers();

        AssertJUnit.assertTrue(game.getPhaseHandler().getCombat().getAttackers().isEmpty());
        AssertJUnit.assertFalse(juggernaut.isTapped());
    }
}
