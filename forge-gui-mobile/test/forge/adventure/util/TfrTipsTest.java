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

    /**
     * Round 993 (follow-up): a wide HUD tip keeps its box. The wrap-at-170 block packed the label to a near-zero width
     * and the frame was built around that (a gold sliver, the text spilling across the map); the box now always takes
     * the packed label's own size.
     */
    @Test
    public void aWideTipKeepsItsBox() {
        float[] box = TfrTips.boxSize(470f, 36f); // hud.clock's three lines at [%60] on the world-map HUD
        AssertJUnit.assertTrue("wider than its text", box[0] >= 470f);
        AssertJUnit.assertTrue("taller than its text", box[1] >= 36f);
        AssertJUnit.assertEquals(478f, box[0], 0.001f);
        AssertJUnit.assertEquals(40f, box[1], 0.001f);
    }

    /** With no wrapping, every tip breaks its own lines ("\n"): no line runs past 125 characters, markup aside. */
    @Test
    public void tipsBreakTheirOwnLines() {
        java.io.File f = new java.io.File("../forge-gui/res/adventure/The Forsaken Realms/" + TfrTips.FILE);
        AssertJUnit.assertTrue(f.getAbsolutePath() + " not found", f.isFile());
        com.badlogic.gdx.utils.JsonValue d = new com.badlogic.gdx.utils.JsonReader().parse(new com.badlogic.gdx.files.FileHandle(f));
        int count = 0;
        for (com.badlogic.gdx.utils.JsonValue v = d.child; v != null; v = v.next, count++)
            for (String line : v.asString().split("\n"))
                AssertJUnit.assertTrue(v.name + ": a " + line.length() + "-character line - break it with \\n",
                        line.replaceAll("\\[[^\\]]*]", "").length() <= 125);
        AssertJUnit.assertTrue("tips read", count > 20);
    }
}
