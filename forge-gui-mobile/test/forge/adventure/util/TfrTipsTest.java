package forge.adventure.util;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * TFR round 993: the "Show tooltips: Auto / On / Off" rule - Auto on for Easy and Normal, off for Hard and Insane (a
 * custom difficulty carries its preset's name), on with no game; On and Off overrule it both ways - and the tip text
 * helpers.
 */
public class TfrTipsTest {
    @Test
    public void autoFollowsTheDifficulty() {
        AssertJUnit.assertTrue(TfrTips.resolve("Auto", "Easy"));
        AssertJUnit.assertTrue(TfrTips.resolve("Auto", "Normal"));
        AssertJUnit.assertFalse(TfrTips.resolve("Auto", "Hard"));
        AssertJUnit.assertFalse(TfrTips.resolve("Auto", "Insane"));
        AssertJUnit.assertTrue("no game running", TfrTips.resolve("Auto", null));
        AssertJUnit.assertFalse("null mode reads as Auto", TfrTips.resolve(null, "Hard"));
        AssertJUnit.assertTrue("an unknown mode reads as Auto", TfrTips.resolve("maybe", "Easy"));
    }

    @Test
    public void onAndOffOverruleAuto() {
        AssertJUnit.assertTrue(TfrTips.resolve("On", "Insane"));
        AssertJUnit.assertTrue(TfrTips.resolve("on", "Hard"));
        AssertJUnit.assertFalse(TfrTips.resolve("Off", "Easy"));
        AssertJUnit.assertFalse(TfrTips.resolve("OFF", null));
    }

    @Test
    public void labelsAndCycle() {
        AssertJUnit.assertEquals("Auto (on)", TfrTips.label("Auto", "Normal"));
        AssertJUnit.assertEquals("Auto (off)", TfrTips.label("Auto", "Hard"));
        AssertJUnit.assertEquals("On", TfrTips.label("on", "Hard"));
        AssertJUnit.assertEquals("On", TfrTips.next("Auto"));
        AssertJUnit.assertEquals("Off", TfrTips.next("On"));
        AssertJUnit.assertEquals("Auto", TfrTips.next("Off"));
        AssertJUnit.assertEquals("Off: off", TfrTips.describe("Off", "Easy", false));
        AssertJUnit.assertEquals("Auto (Hard): off", TfrTips.describe("Auto", "Hard", false));
    }

    @Test
    public void textHelpers() {
        AssertJUnit.assertEquals("3", TfrTips.num(3.0));
        AssertJUnit.assertEquals("0.5", TfrTips.num(0.5));
        AssertJUnit.assertEquals("a [WHITE][+Gold]" + TfrTips.INK + " 5", TfrTips.ink("a [+Gold] 5"));
    }
}
