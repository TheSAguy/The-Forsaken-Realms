package forge.game;

import forge.ai.AITest;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/** TFR round 708: the loop breaker (LoopBreaker) - the AI's 25th use of one ability in a turn bars it, a trigger chain of
 *  25 with nobody acting is a draw, and the AI takes an offered draw only after 10 repeats. */
public class LoopBreakerTest extends AITest {

    @Test
    public void aiRepeatingOneAbilityIsBarredAt25AndAcceptsADrawFrom10() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        Card host = addCard("Runeclaw Bear", ai);
        SpellAbility sa = AbilityFactory.getAbility("AB$ GainLife | Cost$ 0 | LifeAmount$ 1 | SpellDescription$ Gain 1 life.", host);
        sa.setActivatingPlayer(ai);

        for (int i = 1; i <= 9; i++)
            game.getStack().add(sa);
        AssertJUnit.assertFalse("9 repeats is not a loop yet", LoopBreaker.loopSeen(game));
        game.getStack().add(sa);
        AssertJUnit.assertTrue("10 repeats: the AI takes an offered draw", LoopBreaker.loopSeen(game));
        AssertJUnit.assertFalse("not barred before 25", LoopBreaker.isBarred(game, sa));
        for (int i = 11; i <= 25; i++)
            game.getStack().add(sa);
        AssertJUnit.assertTrue("the 25th use bars it for the AI", LoopBreaker.isBarred(game, sa));
        AssertJUnit.assertFalse("barring is not a draw", game.isGameOver());
    }

    @Test
    public void aTriggerComingBack25TimesWithNobodyActingIsADraw() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        addCard("Soul Warden", p);
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers(); // a card added straight to a zone registers its triggers here
        int lifeBefore = p.getLife();

        int creatures = 0;
        while (!game.isGameOver() && creatures < 40) {
            Card bear = addCardToZone("Runeclaw Bear", p, ZoneType.Hand);
            game.getAction().moveToPlay(bear, null, null);
            creatures++;
            game.getTriggerHandler().runWaitingTriggers();
            game.getStack().addAllTriggeredAbilitiesToStack();
            while (!game.isGameOver() && !game.getStack().isEmpty())
                game.getStack().resolveStack();
        }
        AssertJUnit.assertTrue("Soul Warden triggered (life " + lifeBefore + " -> " + p.getLife() + ")", p.getLife() > lifeBefore);
        AssertJUnit.assertTrue("the game ended", game.isGameOver());
        AssertJUnit.assertTrue("as a draw", game.getOutcome() != null && game.getOutcome().isDraw());
        AssertJUnit.assertEquals("at the 25th trigger, before Forge's own 50", 25, creatures);
        AssertJUnit.assertNotNull("with a reason", LoopBreaker.drawReason(game));
    }

    @Test
    public void aBatchOfTheSameTriggerIsNotALoop() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        addCard("Soul Warden", p);
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers(); // a card added straight to a zone registers its triggers here
        int lifeBefore = p.getLife();

        for (int i = 0; i < 30; i++) // thirty creatures at once: thirty triggers waiting together
            game.getAction().moveToPlay(addCardToZone("Runeclaw Bear", p, ZoneType.Hand), null, null);
        game.getTriggerHandler().runWaitingTriggers();
        game.getStack().addAllTriggeredAbilitiesToStack();
        while (!game.isGameOver() && !game.getStack().isEmpty())
            game.getStack().resolveStack();
        AssertJUnit.assertEquals("all thirty triggers resolved", lifeBefore + 30, p.getLife());
        AssertJUnit.assertFalse("a batch is one, not thirty", game.isGameOver());
    }
}
