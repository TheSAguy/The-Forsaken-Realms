package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/** TFR round 720: a regenerate ability paid with sacrifice mana (Yavimaya Hollow + Utopia Mycon + Saprolings) sent the
 *  AI's combat prediction round canRegenerate -> canPayCost -> choose a Saproling to sacrifice -> will it die this turn?
 *  -> canRegenerate until the stack overflowed and the duel froze. */
public class RegenerateRecursionTest extends AITest {

    private Game boardWithHollowMyconAndSaprolings(Player[] out) {
        Game game = initAndCreateGame();
        Player attacker = game.getPlayers().get(1);
        Player defender = game.getPlayers().get(0);
        out[0] = attacker;
        out[1] = defender;

        // no other land: the {G} for the Hollow can only come from sacrificing a Saproling to the Mycon
        addCard("Yavimaya Hollow", defender);
        addCard("Utopia Mycon", defender);
        addTokens("g_1_1_saproling", 3, defender);

        Card wurm = addCard("Craw Wurm", attacker);
        wurm.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, attacker);
        game.getAction().checkStateEffects(true);

        Combat combat = new Combat(attacker);
        combat.addAttacker(wurm, defender);
        game.getPhaseHandler().setCombat(combat);
        return game;
    }

    @Test
    public void canRegenerateWithSacrificeManaFinishes() {
        Player[] p = new Player[2];
        Game game = boardWithHollowMyconAndSaprolings(p);
        Player defender = p[1];
        Card wurm = game.getPhaseHandler().getCombat().getAttackers().get(0);
        Card blocker = null;
        for (Card c : defender.getCreaturesInPlay())
            if (c.isToken())
                blocker = c;
        game.getPhaseHandler().getCombat().addBlocker(wurm, blocker);

        // the Hollow could still pay its {G} with a Saproling - the answer does not matter, only that there is one
        ComputerUtil.canRegenerate(defender, blocker);
        AssertJUnit.assertTrue("a nested ask for the same ability was cut off", ComputerUtil.regenerateRecursionTripped(game));
    }

    /** Another way in: a creature that regenerates itself ({B}), with Phyrexian Altar sacrificing creatures for mana. */
    @Test
    public void altarAndSelfRegeneratorFinishes() {
        Game game = initAndCreateGame();
        Player attacker = game.getPlayers().get(1);
        Player defender = game.getPlayers().get(0);
        addCard("Phyrexian Altar", defender);
        Card skeletons = addCard("Drudge Skeletons", defender);
        addCards("Grizzly Bears", 2, defender);
        Card wurm = addCard("Craw Wurm", attacker);
        wurm.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, attacker);
        game.getAction().checkStateEffects(true);
        Combat combat = new Combat(attacker);
        combat.addAttacker(wurm, defender);
        game.getPhaseHandler().setCombat(combat);
        combat.addBlocker(wurm, skeletons);

        ComputerUtil.canRegenerate(defender, skeletons);
        ((PlayerControllerAi) defender.getController()).getAi().declareBlockersFor(defender, combat);
    }

    /** The same loop without a regenerator: a pump paid with Phyrexian Altar mana, where the Altar may sacrifice the
     *  pumped creature itself, asks whether that creature dies in combat, which asks about the pump again. */
    @Test
    public void pumpWithSacrificeManaFinishes() {
        Game game = initAndCreateGame();
        Player attacker = game.getPlayers().get(1);
        Player defender = game.getPlayers().get(0);
        addCard("Phyrexian Altar", defender);
        Card shade = addCard("Frozen Shade", defender);
        addCards("Grizzly Bears", 2, defender);
        Card wurm = addCard("Craw Wurm", attacker);
        wurm.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, attacker);
        game.getAction().checkStateEffects(true);
        Combat combat = new Combat(attacker);
        combat.addAttacker(wurm, defender);
        game.getPhaseHandler().setCombat(combat);
        combat.addBlocker(wurm, shade);

        ComputerUtilCombat.combatantWouldBeDestroyed(defender, shade, combat);
    }

    @Test
    public void aiDeclaresBlocksAgainstTheMushroomlingBoard() {
        Player[] p = new Player[2];
        Game game = boardWithHollowMyconAndSaprolings(p);
        Combat combat = game.getPhaseHandler().getCombat();
        ((PlayerControllerAi) p[1].getController()).getAi().declareBlockersFor(p[1], combat);
        AssertJUnit.assertNotNull("blocks declared without the AI recursing to death", combat.getAttackers());
    }
}
