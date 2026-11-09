package forge.ai.ability;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.PlayerControllerAi;
import forge.ai.SpellApiToAi;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Random;

/**
 * TFR round 998 (logs-1109 P3-14): Recruit had no AI (SpellApiToAi fell back to CannotPlayAi), so the AI never cast
 * Sound the Trumpets, whose Recruit sub-ability refused the whole counterspell.
 */
public class RecruitAiTest extends AITest {

    private boolean aiPlays(Player ai, Card host) {
        AiController brain = ((PlayerControllerAi) ai.getController()).getAi();
        // the stock heuristics decide here, not TFR's Monte Carlo layer (its rollouts need real libraries)
        brain.setFlatMonteCarloOverride(false);
        for (int seed = 0; seed < 5; seed++) {
            MyRandom.setRandom(new Random(seed));
            List<SpellAbility> plays = brain.chooseSpellAbilityToPlay();
            if (plays != null && plays.stream().anyMatch(p -> p.getHostCard() == host)) {
                return true;
            }
        }
        return false;
    }

    private static AbilitySub recruitSub(Card trumpets, Player ai) {
        SpellAbility sa = trumpets.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        AbilitySub sub = sa.getSubAbility();
        AssertJUnit.assertNotNull(sub);
        AssertJUnit.assertEquals(ApiType.Recruit, sub.getApi());
        return sub;
    }

    /** The opponent has cast Lightning Bolt at the AI, and it waits on the stack. */
    private static void boltAt(Game game, Player opp, Player target, Card bolt) {
        SpellAbility sa = bolt.getFirstSpellAbility();
        sa.setActivatingPlayer(opp);
        Card onStack = game.getAction().moveToStack(bolt, sa);
        sa.setHostCard(onStack);
        sa.getTargets().add(target);
        game.getStack().add(sa);
    }

    @Test
    public void recruitHasAnAi() {
        AssertJUnit.assertTrue(SpellApiToAi.Converter.get(ApiType.Recruit) instanceof RecruitAi);
    }

    @Test
    public void soundTheTrumpetsSubAbilityDoesNotBlockTheCounter() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        fillLibrary(ai, 10);
        Card trumpets = addCardToZone("Sound the Trumpets", ai, ZoneType.Hand);
        AbilitySub sub = recruitSub(trumpets, ai);
        AssertJUnit.assertTrue("Recruit as a sub-ability should not refuse the counterspell",
                SpellApiToAi.Converter.get(sub).chkDrawbackWithSubs(ai, sub).willingToPlay());
    }

    @Test
    public void neverRecruitsFromAnEmptyLibrary() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        for (Card c : ai.getCardsIn(ZoneType.Library)) {
            game.getAction().exile(c, null, null);
        }
        Card trumpets = addCardToZone("Sound the Trumpets", ai, ZoneType.Hand);
        AbilitySub sub = recruitSub(trumpets, ai);
        AssertJUnit.assertFalse("the draw would deck the AI",
                SpellApiToAi.Converter.get(sub).chkDrawbackWithSubs(ai, sub).willingToPlay());
        AssertJUnit.assertFalse("an optional recruit from an empty library is declined",
                SpellApiToAi.Converter.get(ApiType.Recruit).doTriggerNoCostWithSubs(ai, sub, false).willingToPlay());
        AssertJUnit.assertTrue("a mandatory one still resolves",
                SpellApiToAi.Converter.get(ApiType.Recruit).doTriggerNoCostWithSubs(ai, sub, true).willingToPlay());
    }

    @Test
    public void countersALightningBoltAimedAtIt() {
        Game game = initAndCreateGame();
        Player opp = game.getPlayers().get(0);
        Player ai = game.getPlayers().get(1);
        fillLibrary(ai, 10);
        addCards("Island", 3, ai);
        Card trumpets = addCardToZone("Sound the Trumpets", ai, ZoneType.Hand);
        Card bolt = addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        game.getAction().checkStateEffects(true);
        boltAt(game, opp, ai, bolt);

        AssertJUnit.assertTrue("AI should counter a Lightning Bolt with Sound the Trumpets (and recruit)",
                aiPlays(ai, trumpets));
    }
}
