package forge.game;

import forge.ai.AITest;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/** TFR round 708: the loop breaker (LoopBreaker) - the AI's 25th use of one ability in a turn bars it, a trigger chain of
 *  25 with nobody acting is a draw. Round 840: a chain where someone loses life is never drawn before 1000, and the AI
 *  takes an offered draw only after a bar this turn or while a chain is live at 10+. Round 930: a chain on a stack that's
 *  draining only counts from that stack's lowest point, so a big batch can't draw its own payoff. */
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

    /** Round 994: an AI loop that keeps costing its opponent life (upstream's LoopRunner: Mikaeus and a Triskelion) starts
     *  over each time, so it runs past 25; the count is the AI's opponents' life, not its own. */
    @Test
    public void anAiLoopThatCostsTheOpponentLifeStartsOver() {
        Game game = initAndCreateGame();
        Player opp = game.getPlayers().get(0);
        Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        opp.setLife(100, null);
        SpellAbility ping = aiAbility(addCard("Triskelion", ai), ai);

        for (int i = 1; i <= 60; i++) {
            game.getStack().add(ping);
            if (i % 2 == 0)
                opp.loseLife(1, true, false, ping); // every second use lands on the opponent, as the Triskelion's round does
        }
        AssertJUnit.assertFalse("60 uses that keep costing the opponent life are not barred", LoopBreaker.isBarred(game, ping));
        AssertJUnit.assertEquals(70, opp.getLife());

        ai.loseLife(1, true, false, ping); // the AI's own life is no progress
        for (int i = 1; i <= 24; i++)
            game.getStack().add(ping);
        AssertJUnit.assertFalse("24 in a row with no opponent life lost: not yet", LoopBreaker.isBarred(game, ping));
        game.getStack().add(ping);
        AssertJUnit.assertTrue("the 25th in a row with nothing lost bars it", LoopBreaker.isBarred(game, ping));
    }

    /** Round 994: a loop that drains life but cannot kill (Platinum Angel, a life-gain offset) still ends, at
     *  AI_BACKSTOP_AT uses of that card name's ability in a turn. */
    @Test
    public void anAiLoopThatCannotKillStopsAtTheBackstop() {
        Game game = initAndCreateGame();
        Player opp = game.getPlayers().get(0);
        Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        opp.setLife(5000, null); // stands in for "can't lose"
        SpellAbility ping = aiAbility(addCard("Triskelion", ai), ai);

        for (int i = 1; i < LoopBreaker.AI_BACKSTOP_AT; i++) {
            game.getStack().add(ping);
            opp.loseLife(1, true, false, ping);
        }
        AssertJUnit.assertFalse("299 uses with life lost each time: not barred yet", LoopBreaker.isBarred(game, ping));
        game.getStack().add(ping);
        AssertJUnit.assertTrue("the 300th bars it, life lost or not", LoopBreaker.isBarred(game, ping));

        // a fresh object of the same card (undying, a blink) is the same loop
        SpellAbility again = aiAbility(addCard("Triskelion", ai), ai);
        AssertJUnit.assertTrue("a new Triskelion is barred too", LoopBreaker.isBarred(game, again));
        AssertJUnit.assertFalse("barring is not a draw", game.isGameOver());
    }

    private SpellAbility aiAbility(Card host, Player ai) {
        SpellAbility sa = AbilityFactory.getAbility("AB$ DealDamage | Cost$ 0 | ValidTgts$ Any | NumDmg$ 1 | SpellDescription$ Ping.", host);
        sa.setActivatingPlayer(ai);
        return sa;
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

    @Test
    public void aMandatoryPayoffOnADrainingStackIsNotADraw() {
        payoffOnADrainingStack("Ajani's Pridemate", 120);
    }

    @Test
    public void anOptionalPayoffOnADrainingStackIsNotADraw() {
        payoffOnADrainingStack("Dawn of Hope", 120);
    }

    /** The report: a batch of life-gain triggers, each one setting off a "whenever you gain life" trigger alone on the stack. */
    private void payoffOnADrainingStack(String payoff, int creatures) {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        addCard("Soul Warden", p);
        addCard(payoff, p);
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        int lifeBefore = p.getLife();

        for (int i = 0; i < creatures; i++)
            game.getAction().moveToPlay(addCardToZone("Runeclaw Bear", p, ZoneType.Hand), null, null);
        game.getTriggerHandler().runWaitingTriggers();
        game.getStack().addAllTriggeredAbilitiesToStack();
        int resolved = 0;
        while (!game.isGameOver() && !game.getStack().isEmpty()) {
            game.getStack().resolveStack();
            resolved++;
            game.getStack().addAllTriggeredAbilitiesToStack();
        }
        AssertJUnit.assertFalse(payoff + ": drawn after " + (p.getLife() - lifeBefore) + " of " + creatures + " life gains, "
                + resolved + " items resolved: " + LoopBreaker.drawReason(game), game.isGameOver());
        AssertJUnit.assertEquals("every trigger resolved", lifeBefore + creatures, p.getLife());
    }

    @Test
    public void aSawtoothLoopIsStillADrawAt25() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        Card host = addCard("Runeclaw Bear", p);
        SpellAbility loop = trigger(host, p);
        for (int i = 0; i < 4; i++)
            game.getStack().add(trigger(host, p));

        int repeats = 0;
        while (!game.isGameOver() && repeats < 200) { // the stack is 5, 4, 5, 4 each time the loop comes back
            boolean high = repeats % 2 == 0;
            if (high)
                game.getStack().add(trigger(host, p));
            repeats++;
            game.getStack().add(loop);
            if (!game.isGameOver()) {
                game.getStack().resolveStack();
                if (high)
                    game.getStack().resolveStack();
            }
        }
        AssertJUnit.assertTrue("a stack can't make new lows forever", game.isGameOver() && game.getOutcome().isDraw());
        AssertJUnit.assertEquals("the second time back, at 4, is the one new low", LoopBreaker.TRIGGER_DRAW_AT + 1, repeats);
    }

    @Test
    public void aLoopOnAGrowingStackIsStillADrawAt25() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        Card host = addCard("Runeclaw Bear", p);
        SpellAbility loop = trigger(host, p);

        int repeats = 0;
        while (!game.isGameOver() && repeats < 200) {
            game.getStack().add(trigger(host, p));
            repeats++;
            game.getStack().add(loop);
            if (!game.isGameOver())
                game.getStack().resolveStack();
        }
        AssertJUnit.assertTrue(game.isGameOver() && game.getOutcome().isDraw());
        AssertJUnit.assertEquals(LoopBreaker.TRIGGER_DRAW_AT, repeats);
    }

    @Test
    public void aLoopIsADrawOnceTheStackUnderItHasDrained() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        Card host = addCard("Runeclaw Bear", p);
        SpellAbility loop = trigger(host, p);
        for (int i = 0; i < 40; i++)
            game.getStack().add(trigger(host, p));

        int repeats = 0;
        while (!game.isGameOver() && repeats < 200) {
            if (!game.getStack().isEmpty())
                game.getStack().resolveStack();
            repeats++;
            game.getStack().add(loop);
            if (!game.isGameOver())
                game.getStack().resolveStack();
        }
        AssertJUnit.assertTrue(game.isGameOver() && game.getOutcome().isDraw());
        AssertJUnit.assertEquals("40 lows, the 40th the first of the 25 on the empty stack", 40 + LoopBreaker.TRIGGER_DRAW_AT - 1, repeats);
    }

    // ---- round 1028: the no-progress draw ----------------------------------------------------------------------------

    /** P3-29: both players gain life every turn, nobody gets through, the boards hold - the 9th turn's offer is a draw. */
    @Test
    public void aTwoSidedLifegainStallIsAcceptedAfter8Turns() {
        Stall st = new Stall();
        for (int t = 1; t <= LoopBreaker.NO_PROGRESS_TURNS; t++) {
            st.turn(t, () -> st.bothGain(3));
            AssertJUnit.assertFalse("turn " + t + ": too early", LoopBreaker.loopSeen(st.game));
        }
        st.turn(LoopBreaker.NO_PROGRESS_TURNS + 1, () -> st.bothGain(3));
        AssertJUnit.assertTrue("8 turns of a lifegain stall: the AI takes the draw", LoopBreaker.loopSeen(st.game));
        AssertJUnit.assertTrue(LoopBreaker.noProgressDraw(st.game));
        AssertJUnit.assertTrue(LoopBreaker.drawReason(st.game), LoopBreaker.drawReason(st.game).startsWith("no progress for 8 turns"));
        AssertJUnit.assertFalse("taking the offer is the coordinator's job, not the breaker's", st.game.isGameOver());
    }

    @Test
    public void aBoardWhereNothingHappensIsAccepted() {
        Stall st = new Stall();
        st.play(LoopBreaker.NO_PROGRESS_TURNS + 1, () -> { });
        AssertJUnit.assertTrue(LoopBreaker.loopSeen(st.game));
    }

    @Test
    public void anOpponentLosingLifeIsProgress() {
        Stall st = new Stall();
        st.play(LoopBreaker.NO_PROGRESS_TURNS + 1, () -> st.ai.loseLife(1, false, false, null));
        AssertJUnit.assertFalse("the AI's life goes down: the player is winning, not stuck", LoopBreaker.loopSeen(st.game));
        AssertJUnit.assertFalse(LoopBreaker.noProgressDraw(st.game));
    }

    @Test
    public void aBeatdownOnThePlayerIsProgress() {
        Stall st = new Stall();
        st.play(LoopBreaker.NO_PROGRESS_TURNS + 1, () -> st.human.loseLife(2, false, false, null));
        AssertJUnit.assertFalse("the player is heading for a loss: no dodging it with a draw", LoopBreaker.loopSeen(st.game));
    }

    @Test
    public void aRaceIsProgress() {
        Stall st = new Stall();
        st.play(LoopBreaker.NO_PROGRESS_TURNS + 1, () -> {
            st.human.loseLife(3, false, false, null);
            st.ai.loseLife(3, false, false, null);
        });
        AssertJUnit.assertFalse(LoopBreaker.loopSeen(st.game));
    }

    /** Lifegain in the first half, then the life starts going down: still above where the window began, below its middle. */
    @Test
    public void aLifeTotalTurningDownIsProgress() {
        Stall st = new Stall();
        for (int t = 1; t <= LoopBreaker.NO_PROGRESS_TURNS + 1; t++) {
            final int turn = t;
            st.turn(t, () -> {
                if (turn <= 5)
                    st.human.gainLife(10, null, null);
                else
                    st.human.loseLife(5, false, false, null);
            });
        }
        AssertJUnit.assertTrue("still above the window's start", st.human.getLife() > 20 + 10);
        AssertJUnit.assertFalse("but lower than 4 turns ago", LoopBreaker.loopSeen(st.game));
    }

    @Test
    public void poisonCountersAreProgress() {
        Stall st = new Stall();
        st.play(LoopBreaker.NO_PROGRESS_TURNS + 1, () -> st.bothGain(5));
        st.human.setPoisonCounters(1, st.ai);
        AssertJUnit.assertFalse("a poison counter since the window began", LoopBreaker.loopSeen(st.game));
    }

    @Test
    public void aShrinkingLibraryIsProgress() {
        Stall st = new Stall();
        st.play(LoopBreaker.NO_PROGRESS_TURNS + 1, () -> st.mill(st.human, 3));
        AssertJUnit.assertFalse("milled 3 a turn: the library runs out in a few more turns", LoopBreaker.loopSeen(st.game));
    }

    @Test
    public void naturalDrawsFromAFullLibraryAreNotProgress() {
        Stall st = new Stall();
        st.play(LoopBreaker.NO_PROGRESS_TURNS + 1, () -> st.mill(st.game.getPhaseHandler().getPlayerTurn(), 1));
        AssertJUnit.assertTrue("one draw a turn from 40 cards", LoopBreaker.loopSeen(st.game));
    }

    @Test
    public void aGrowingBoardIsProgress() {
        Stall st = new Stall();
        st.play(LoopBreaker.NO_PROGRESS_TURNS + 1, () -> addCard("Runeclaw Bear", st.ai));
        AssertJUnit.assertFalse("the AI adds a creature every turn", LoopBreaker.loopSeen(st.game));
    }

    @Test
    public void oneMoreCreatureIsNotProgress() {
        Stall st = new Stall();
        for (int t = 1; t <= LoopBreaker.NO_PROGRESS_TURNS + 1; t++) {
            final int turn = t;
            st.turn(t, () -> {
                if (turn == 3)
                    addCard("Runeclaw Bear", st.ai); // +2 power on 8: inside the slack
            });
        }
        AssertJUnit.assertTrue(LoopBreaker.loopSeen(st.game));
    }

    @Test
    public void aBoardTradedAwayIsProgress() {
        Stall st = new Stall();
        st.turn(1, () -> { });
        for (Card c : new java.util.ArrayList<>(st.human.getCreaturesInPlay()))
            st.game.getAction().moveToGraveyard(c, null);
        for (int t = 2; t <= LoopBreaker.NO_PROGRESS_TURNS + 1; t++)
            st.turn(t, () -> { });
        AssertJUnit.assertFalse("the player's board is gone since the window began", LoopBreaker.loopSeen(st.game));
    }

    @Test
    public void aPlaneswalkerGainingLoyaltyIsProgress() {
        Stall st = new Stall();
        Card walker = addCard("Ajani Goldmane", st.ai);
        walker.setCounters(forge.game.card.CounterEnumType.LOYALTY, 4);
        for (int t = 1; t <= LoopBreaker.NO_PROGRESS_TURNS + 1; t++)
            st.turn(t, () -> walker.setCounters(forge.game.card.CounterEnumType.LOYALTY, walker.getCurrentLoyalty() + 1));
        AssertJUnit.assertFalse("an ultimate on its way", LoopBreaker.loopSeen(st.game));
    }

    /** The loop test still runs first: a loop is a draw on turn 1, history or not, and says it was a loop. */
    @Test
    public void theLoopTestStillComesFirst() {
        Stall st = new Stall();
        st.play(LoopBreaker.NO_PROGRESS_TURNS + 1, () -> st.human.loseLife(2, false, false, null)); // progress
        st.game.getPhaseHandler().devModeSet(PhaseType.MAIN1, st.ai);
        SpellAbility sa = AbilityFactory.getAbility("AB$ GainLife | Cost$ 0 | LifeAmount$ 1 | SpellDescription$ Gain 1 life.",
                st.ai.getCreaturesInPlay().getFirst());
        sa.setActivatingPlayer(st.ai);
        for (int i = 1; i <= LoopBreaker.AI_BAR_AT; i++)
            st.game.getStack().add(sa);
        AssertJUnit.assertTrue("a barred AI loop: accepted, whatever the life totals did", LoopBreaker.loopSeen(st.game));
        AssertJUnit.assertFalse(LoopBreaker.noProgressDraw(st.game));
    }

    /** The hook: PhaseHandler takes the snapshots itself as real turns pass. Two AIs with only lands play 9 turns. */
    @Test
    public void realTurnsTakeTheSnapshots() {
        Game game = initAndCreateGame();
        for (Player p : game.getPlayers())
            for (int i = 0; i < 40; i++)
                addCardToZone("Plains", p, ZoneType.Library);
        int turns = 0;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() < LoopBreaker.NO_PROGRESS_TURNS + 2 && turns++ < 40)
            playUntilNextTurn(game);
        AssertJUnit.assertFalse(game.isGameOver());
        AssertJUnit.assertTrue("turn " + game.getPhaseHandler().getTurn() + ": lands only, nothing moved",
                LoopBreaker.loopSeen(game));
    }

    /** A board of two 4-power creatures a side, 20 life each and 40-card libraries; each turn() starts a turn. */
    private final class Stall {
        final Game game = initAndCreateGame();
        final Player human = game.getPlayers().get(0);
        final Player ai = game.getPlayers().get(1);

        Stall() {
            for (Player p : game.getPlayers()) {
                addCard("Serra Angel", p);
                addCard("Serra Angel", p);
                for (int i = 0; i < 40; i++)
                    addCardToZone("Plains", p, ZoneType.Library);
            }
        }

        /** Turn t begins (the snapshot), then what happens in it. */
        void turn(int t, Runnable during) {
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, t % 2 == 1 ? human : ai, t);
            LoopBreaker.onTurnBegan(game);
            during.run();
        }

        void play(int turns, Runnable during) {
            for (int t = 1; t <= turns; t++)
                turn(t, during);
        }

        void bothGain(int n) {
            human.gainLife(n, null, null);
            ai.gainLife(n, null, null);
        }

        void mill(Player p, int n) {
            for (int i = 0; i < n; i++)
                game.getAction().moveToGraveyard(p.getCardsIn(ZoneType.Library).getFirst(), null);
        }
    }

    /** A mandatory, untargeted trigger of its own, built by hand so a test sets the stack size each time it comes back. */
    private SpellAbility trigger(Card host, Player p) {
        Trigger t = TriggerHandler.parseTrigger("Mode$ Always | TriggerDescription$ A test trigger.", host, true);
        SpellAbility sa = AbilityFactory.getAbility("DB$ Pump | Defined$ Self", host);
        sa.setTrigger(t);
        sa.setActivatingPlayer(p);
        return sa;
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
