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
 *  25 with nobody acting is a draw. Round 840: a chain where someone loses life is never drawn before 1000, and the AI
 *  takes an offered draw only after a bar this turn or while a chain is live at 10+. */
public class LoopBreakerTest extends AITest {

    @Test
    public void aiRepeatingOneAbilityIsBarredAt25AndOnlyThenAcceptsADraw() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        Card host = addCard("Runeclaw Bear", ai);
        SpellAbility sa = AbilityFactory.getAbility("AB$ GainLife | Cost$ 0 | LifeAmount$ 1 | SpellDescription$ Gain 1 life.", host);
        sa.setActivatingPlayer(ai);

        for (int i = 1; i <= 10; i++)
            game.getStack().add(sa);
        AssertJUnit.assertFalse("10 uses of a pump is not a loop: the AI declines (round 840)", LoopBreaker.loopSeen(game));
        AssertJUnit.assertFalse("not barred before 25", LoopBreaker.isBarred(game, sa));
        for (int i = 11; i <= 24; i++)
            game.getStack().add(sa);
        AssertJUnit.assertFalse("24 uses: still declined", LoopBreaker.loopSeen(game));
        game.getStack().add(sa);
        AssertJUnit.assertTrue("the 25th use bars it for the AI", LoopBreaker.isBarred(game, sa));
        AssertJUnit.assertTrue("after the bar the AI takes an offered draw", LoopBreaker.loopSeen(game));
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

        int creatures = enterOneAtATime(game, p, 40);
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

    @Test
    public void aDrainChainIsNotADraw() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Player opp = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        opp.setLife(100, null);
        addCard("Impact Tremors", p);
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();

        int creatures = enterOneAtATime(game, p, 30);
        AssertJUnit.assertEquals("all thirty creatures entered", 30, creatures);
        AssertJUnit.assertFalse("a chain that costs life runs to the end (round 840)", game.isGameOver());
        AssertJUnit.assertEquals("thirty pings landed", 70, opp.getLife());
        AssertJUnit.assertNull("no loop draw", LoopBreaker.drawReason(game));
    }

    @Test
    public void aDrainChainThatCannotEndIsADrawAt1000() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Player opp = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        opp.setLife(5000, null); // stands in for a "can't lose" effect
        addCard("Impact Tremors", p);
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();

        int creatures = enterOneAtATime(game, p, 1100);
        AssertJUnit.assertTrue("the backstop ended it", game.isGameOver() && game.getOutcome() != null && game.getOutcome().isDraw());
        AssertJUnit.assertEquals("at the 1000th trigger", LoopBreaker.TRIGGER_BACKSTOP_AT, creatures);
        AssertJUnit.assertEquals("999 pings landed", 5000 - 999, opp.getLife());
    }

    @Test
    public void aDrawOfferIsAcceptedOnlyWhileATriggerChainIsLive() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        Card warden = addCard("Soul Warden", p);
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();

        enterOneAtATime(game, p, 9);
        AssertJUnit.assertFalse("a chain at 9 is not a loop yet", LoopBreaker.loopSeen(game));
        enterOneAtATime(game, p, 1);
        AssertJUnit.assertTrue("a Soul Warden chain at 10: the AI takes an offered draw", LoopBreaker.loopSeen(game));

        SpellAbility cast = AbilityFactory.getAbility("AB$ GainLife | Cost$ 0 | LifeAmount$ 1 | SpellDescription$ Gain 1 life.", warden);
        cast.setActivatingPlayer(p);
        game.getStack().add(cast);
        AssertJUnit.assertFalse("one cast starts the chain over: declined", LoopBreaker.loopSeen(game));
        AssertJUnit.assertFalse(game.isGameOver());
    }

    /** Creatures enter one at a time, every trigger resolving before the next; returns how many entered. */
    private int enterOneAtATime(Game game, Player p, int max) {
        int creatures = 0;
        while (!game.isGameOver() && creatures < max) {
            Card bear = addCardToZone("Runeclaw Bear", p, ZoneType.Hand);
            game.getAction().moveToPlay(bear, null, null);
            creatures++;
            game.getTriggerHandler().runWaitingTriggers();
            game.getStack().addAllTriggeredAbilitiesToStack();
            while (!game.isGameOver() && !game.getStack().isEmpty())
                game.getStack().resolveStack();
        }
        return creatures;
    }
}
