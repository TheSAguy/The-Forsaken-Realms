package forge.ai.ability;

import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.PlayerControllerAi;
import forge.ai.SpellApiToAi;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.combat.Combat;
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
 * Incubate and Empower had no AI (SpellApiToAi fell back to CannotPlayAi), so the AI never used those abilities and
 * never cast a spell that carries one as a sub-ability (Sunfall, Excise the Imperfect, Countersculpt).
 */
public class IncubateEmpowerAiTest extends AITest {

    /**
     * Whether the AI picks a play of this card. Several seeds, because some stock main-effect AIs roll dice
     * (ChangeZoneAllAi plays a board wipe 80% of the time); before Incubate and Empower had an AI every seed refused.
     */
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

    private static SpellAbility byApi(Card c, ApiType api) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.getApi() == api) {
                return sa;
            }
        }
        return null;
    }

    private AiPlayDecision decision(Player ai, SpellAbility sa) {
        sa.setActivatingPlayer(ai);
        return ((PlayerControllerAi) ai.getController()).getAi().canPlaySa(sa);
    }

    @Test
    public void bothApisHaveAnAi() {
        AssertJUnit.assertTrue(SpellApiToAi.Converter.get(ApiType.Incubate) instanceof IncubateAi);
        AssertJUnit.assertTrue(SpellApiToAi.Converter.get(ApiType.Empower) instanceof EmpowerAi);
    }

    @Test
    public void subAbilitiesDoNotBlockTheSpell() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        for (String name : new String[] {"Sunfall", "Excise the Imperfect", "Countersculpt"}) {
            Card c = addCardToZone(name, ai, ZoneType.Hand);
            SpellAbility sa = c.getFirstSpellAbility();
            sa.setActivatingPlayer(ai);
            AbilitySub sub = sa.getSubAbility();
            AssertJUnit.assertNotNull(name, sub);
            AssertJUnit.assertTrue(name + " sub-ability should not refuse the spell",
                    SpellApiToAi.Converter.get(sub).chkDrawbackWithSubs(ai, sub).willingToPlay());
        }
    }

    @Test
    public void castsSunfallIntoABiggerBoard() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        addCards("Plains", 5, ai);
        addCards("Hill Giant", 3, opp);
        Card sunfall = addCardToZone("Sunfall", ai, ZoneType.Hand);
        // ChangeZoneAllAi holds a board wipe for main 2
        moveToMain2(game, ai);

        AssertJUnit.assertTrue("AI should cast Sunfall against three creatures", aiPlays(ai, sunfall));
    }

    @Test
    public void castsExciseTheImperfectOnAnAttacker() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        addCards("Plains", 3, ai);
        Card angel = addCard("Serra Angel", opp);
        angel.setSickness(false);
        Card excise = addCardToZone("Excise the Imperfect", ai, ZoneType.Hand);

        // instant removal: the stock heuristics hold it for the opponent's attack
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, opp);
        Combat combat = new Combat(opp);
        combat.addAttacker(angel, ai);
        game.getPhaseHandler().setCombat(combat);
        game.getAction().checkStateEffects(true);

        // the sub-ability makes the TARGET's controller incubate; that must not stop the removal
        AssertJUnit.assertTrue("AI should cast Excise the Imperfect on an attacking Serra Angel", aiPlays(ai, excise));
    }

    @Test
    public void campusCrierWaitsForTheOpponentsEndStep() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        addCards("Island", 2, ai);
        Card crier = addCardToZone("Campus Crier", ai, ZoneType.Graveyard);
        SpellAbility empower = byApi(crier, ApiType.Empower);
        AssertJUnit.assertNotNull(empower);
        game.getAction().checkStateEffects(true);

        AssertJUnit.assertFalse("instant-speed Empower should not be used in the AI's main 1",
                decision(ai, empower).willingToPlay());

        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, decision(ai, empower));
        AssertJUnit.assertTrue("AI should exile Campus Crier to empower at the opponent's end step", aiPlays(ai, crier));
    }

    @Test
    public void huntmasterSacrificesOnlySomethingWorthLess() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        addCards("Swamp", 2, ai);
        Card huntmaster = addCard("Compleated Huntmaster", ai);
        huntmaster.setSickness(false);
        Card angel = addCard("Serra Angel", ai);
        angel.setSickness(false);
        SpellAbility incubate = byApi(huntmaster, ApiType.Incubate);
        AssertJUnit.assertNotNull(incubate);
        moveToMain2(game, ai);

        AssertJUnit.assertFalse("AI should not sacrifice a Serra Angel for an Incubator",
                decision(ai, incubate).willingToPlay());

        addToken("w_1_1_soldier", ai);
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("AI should sacrifice a 1/1 token for Incubate 3",
                AiPlayDecision.WillPlay, decision(ai, incubate));
        AssertJUnit.assertTrue(aiPlays(ai, huntmaster));
    }

    @Test
    public void jacePlusOneJustWorks() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        addCards("Island", 3, ai);
        Card jace = addCard("Jace, Reality Sculptor", ai);
        jace.setCounters(CounterEnumType.LOYALTY, 5);
        SpellAbility plusOne = byApi(jace, ApiType.Empower);
        AssertJUnit.assertNotNull(plusOne);
        game.getAction().checkStateEffects(true);

        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, decision(ai, plusOne));
    }
}
