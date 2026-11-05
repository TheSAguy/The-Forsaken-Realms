package forge.adventure.util;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * TFR round 963: the support asks' gating. "Not until 40 hours" (supportNeverAsk, round 756's "Don't ask again") stops
 * only the 5 and 15 h asks; the 40 h ask and the 75 h splash always come, as thank-yous for a player who clicked Support
 * TFR; a total past several marks gets only the latest ask.
 */
public class SupportPromptTest {
    private static final int ASK5 = 0, ASK15 = 1, ASK40 = 2, ASK75 = 3;

    @Test
    public void asksComeOneByOneForAPlayerWhoAnsweredMaybeLater() {
        AssertJUnit.assertEquals(-1, SupportPrompt.dueAsk(4.9f, 0, false, false));
        AssertJUnit.assertEquals(ASK5, SupportPrompt.dueAsk(5f, 0, false, false));
        AssertJUnit.assertEquals(ASK15, SupportPrompt.dueAsk(15f, 1, false, false));
        AssertJUnit.assertEquals(ASK40, SupportPrompt.dueAsk(40f, 2, false, false));
        AssertJUnit.assertEquals(-1, SupportPrompt.dueAsk(74.9f, 3, false, false));
        AssertJUnit.assertEquals(ASK75, SupportPrompt.dueAsk(75f, 3, false, false));
        AssertJUnit.assertEquals("nothing after the splash", -1, SupportPrompt.dueAsk(500f, 4, false, false));
    }

    @Test
    public void notUntil40StopsOnlyTheEarlyAsks() {
        // an old save: "Don't ask again" at the 5 h ask
        AssertJUnit.assertEquals("no 15 h ask", -1, SupportPrompt.dueAsk(20f, 1, true, false));
        AssertJUnit.assertEquals("the 40 h ask comes anyway", ASK40, SupportPrompt.dueAsk(40f, 1, true, false));
        AssertJUnit.assertEquals("the 75 h ask comes anyway", ASK75, SupportPrompt.dueAsk(75f, 3, true, false));
        AssertJUnit.assertEquals(ASK40, SupportPrompt.nextAsk(1, true, false));
        AssertJUnit.assertFalse(SupportPrompt.isThanks(ASK40, false));
    }

    @Test
    public void anOldSaveThatSawTheOld40hAskStillGetsThe75hAsk() {
        AssertJUnit.assertEquals(ASK75, SupportPrompt.dueAsk(80f, 3, false, false));
        AssertJUnit.assertEquals(ASK75, SupportPrompt.nextAsk(3, false, false));
    }

    @Test
    public void aSupportClickTurnsThe40And75hAsksIntoThankYous() {
        AssertJUnit.assertEquals("no 15 h ask", -1, SupportPrompt.dueAsk(20f, 1, false, true));
        AssertJUnit.assertEquals(ASK40, SupportPrompt.dueAsk(40f, 1, false, true));
        AssertJUnit.assertTrue(SupportPrompt.isThanks(ASK40, true));
        AssertJUnit.assertEquals(ASK75, SupportPrompt.dueAsk(75f, 3, false, true));
        AssertJUnit.assertTrue(SupportPrompt.isThanks(ASK75, true));
        AssertJUnit.assertFalse(SupportPrompt.isThanks(ASK5, true));
    }

    @Test
    public void aPlayerPastBothMarksGetsOneAskNotTwo() {
        AssertJUnit.assertEquals("the latest wins", ASK75, SupportPrompt.dueAsk(90f, 0, false, false));
        AssertJUnit.assertEquals(ASK75, SupportPrompt.dueAsk(90f, 1, true, false));
        AssertJUnit.assertEquals(ASK40, SupportPrompt.dueAsk(50f, 0, true, false));
        // show() sets supportAsksShown to the index + 1, so the 40 h ask is used up with it
        AssertJUnit.assertEquals(-1, SupportPrompt.dueAsk(90f, ASK75 + 1, true, false));
    }

    @Test
    public void aPendingDuelResultIsNeverCalm() {
        AssertJUnit.assertTrue(SupportPrompt.isCalm(false, false, false, false, false));
        AssertJUnit.assertFalse("a win's animations or a loss's pause still to play",
                SupportPrompt.isCalm(false, false, false, false, true));
        AssertJUnit.assertFalse(SupportPrompt.isCalm(true, false, false, false, false));
        AssertJUnit.assertFalse(SupportPrompt.isCalm(false, true, false, false, false));
        AssertJUnit.assertFalse(SupportPrompt.isCalm(false, false, true, false, false));
        AssertJUnit.assertFalse(SupportPrompt.isCalm(false, false, false, true, false));
    }

    @Test
    public void theQuietTimeStartsOverAfterThePendingResult() {
        // round 967 (logs-1105 P3-3): back on the map after a win, 1.4 s of quiet, then the result lands - no ask yet
        float quiet = 0f;
        for (int frame = 0; frame < 14; frame++)
            quiet = SupportPrompt.quietTime(quiet, 0.1f, SupportPrompt.isCalm(false, false, false, false, false));
        AssertJUnit.assertTrue(quiet > 1.3f);
        quiet = SupportPrompt.quietTime(quiet, 0.1f, SupportPrompt.isCalm(false, false, false, false, true));
        AssertJUnit.assertEquals(0f, quiet, 0f);
    }

    @Test
    public void the75hTextNamesTheWinsFrom50On() {
        String few = SupportPrompt.text75(49), many = SupportPrompt.text75(412);
        AssertJUnit.assertTrue(few.startsWith("You're not visiting the Realms anymore. You live here."));
        AssertJUnit.assertTrue(many.startsWith("412 duels won. You're not visiting the Realms anymore."));
        AssertJUnit.assertTrue(SupportPrompt.text75(50).startsWith("50 duels won. "));
        AssertJUnit.assertTrue(many.contains("\n\nEvery hour was free"));
        AssertJUnit.assertTrue(many.endsWith("This is the last time we'll ask. Thank you, Realm-walker."));
    }
}
