package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import forge.game.player.GameLossReason;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TFR round 1047: "How a Duel Works" shows once, before a save's first duel - never after a duel is on the record, never
 * twice, never in New Game+ - and its five pages are all in the plane's tips.json with only the names DuelPrimer and
 * TfrTips fill. Also the [TFR-Duel] line's reason= word for each loss reason. Round 1048: the pages speak to the device's
 * pointer (touch or mouse), and the duel's Game menu shows them in Forge's popup with its own buttons and markup.
 */
public class DuelPrimerTest {
    private static final File TIPS = new File("../forge-gui/res/adventure/The Forsaken Realms/config tables/tips.json");

    @Test
    public void dueOnlyBeforeTheFirstDuelOfARun() {
        AssertJUnit.assertTrue("a new save", DuelPrimer.due(false, false, 0));
        AssertJUnit.assertFalse("shown once already", DuelPrimer.due(true, false, 0));
        AssertJUnit.assertFalse("an old save that has fought", DuelPrimer.due(false, false, 1));
        AssertJUnit.assertFalse("an old save deep in its run", DuelPrimer.due(false, false, 240));
        AssertJUnit.assertFalse("New Game+", DuelPrimer.due(false, true, 0));
    }

    @Test
    public void everyPageIsInTipsJsonWithKnownNamesOnly() {
        AssertJUnit.assertTrue(TIPS.getAbsolutePath(), TIPS.exists());
        JsonValue tips = new JsonReader().parse(new FileHandle(TIPS));
        Pattern name = Pattern.compile("\\{([\\w.]+)\\}");
        java.util.Set<String> known = new java.util.HashSet<>(java.util.Arrays.asList(
                "difficulty", "lossPct", "lossMin", "lifePct", "ante", "anteDay", "Tap", "tap", "readCard"));
        String[] keys = new String[DuelPrimer.PAGES * 2 + 5];
        int k = 0;
        for (int i = 1; i <= DuelPrimer.PAGES; i++) {
            keys[k++] = "duel.title" + i;
            keys[k++] = "duel.page" + i;
        }
        keys[k++] = "duel.anteLater";
        keys[k++] = "duel.anteNow";
        keys[k++] = "duel.readTouch";
        keys[k++] = "duel.readMouse";
        keys[k] = "duel.anteOff";
        for (String key : keys) {
            String text = tips.getString(key, null);
            AssertJUnit.assertNotNull("tips.json has " + key, text);
            if (!key.equals("duel.anteOff"))
                AssertJUnit.assertFalse(key + " is empty", text.trim().isEmpty());
            Matcher m = name.matcher(text);
            while (m.find())
                AssertJUnit.assertTrue(key + " names {" + m.group(1) + "}", known.contains(m.group(1)));
        }
        AssertJUnit.assertTrue("page 2 warns that End Turn skips the attack",
                tips.getString("duel.page2").contains("End Turn ends your turn at once and skips your attack"));
        AssertJUnit.assertTrue("an amount of gold uses the icon", tips.getString("duel.page4").contains("[+Gold]"));
    }

    @Test
    public void eachPageSpeaksToThePointer() {
        JsonValue tips = new JsonReader().parse(new FileHandle(TIPS));
        String readTouch = tips.getString("duel.readTouch"), readMouse = tips.getString("duel.readMouse");
        AssertJUnit.assertTrue("page 1 says how to read a card", tips.getString("duel.page1").contains("{readCard}"));
        Pattern touchOnly = Pattern.compile("(?i)\\b(tap|press and hold|swipe|finger)\\b");
        Pattern mouseOnly = Pattern.compile("(?i)\\b(click|right-click|mouse|point at|hover)\\b");
        for (int i = 1; i <= DuelPrimer.PAGES; i++) {
            String raw = tips.getString("duel.page" + i);
            String touch = DuelPrimer.pointerWords(raw, true, readTouch);
            String mouse = DuelPrimer.pointerWords(raw, false, readMouse);
            AssertJUnit.assertFalse("page " + i + " has a pointer word on a touch screen: " + touch, mouseOnly.matcher(touch).find());
            AssertJUnit.assertFalse("page " + i + " has a touch word on a computer: " + mouse, touchOnly.matcher(mouse).find());
            AssertJUnit.assertFalse("page " + i + " has a parenthesis, which the duel's popup would hide as reminder text",
                    raw.contains("(") || raw.contains(")"));
        }
        AssertJUnit.assertTrue(DuelPrimer.pointerWords(tips.getString("duel.page1"), true, readTouch).contains("Press and hold any card"));
        AssertJUnit.assertTrue(DuelPrimer.pointerWords(tips.getString("duel.page1"), false, readMouse).contains("Click a card in your hand"));
        AssertJUnit.assertTrue("page 5 names the Game menu entry", tips.getString("duel.page5").contains("How a Duel Works"));
    }

    @Test
    public void gameMenuButtonsPageByPage() {
        AssertJUnit.assertEquals(java.util.Arrays.asList("Next", "Close"), DuelPrimer.duelButtons(0, 5));
        AssertJUnit.assertEquals(java.util.Arrays.asList("Back", "Next", "Close"), DuelPrimer.duelButtons(2, 5));
        AssertJUnit.assertEquals(java.util.Arrays.asList("Back", "Done"), DuelPrimer.duelButtons(4, 5));
    }

    @Test
    public void popupMarkupIsTheEnginesOwn() {
        String gold = forge.assets.TextRenderer.startColor(com.badlogic.gdx.graphics.Color.GOLD);
        String end = forge.assets.TextRenderer.endColor();
        AssertJUnit.assertEquals("press " + gold + "Auto" + end + " now", DuelPrimer.forPopup("press [GOLD]Auto[] now", n -> true));
        AssertJUnit.assertEquals("10% of the {Gold} you carry", DuelPrimer.forPopup("10% of the [+Gold] you carry", "Gold"::equals));
        AssertJUnit.assertEquals("10% of the gold you carry", DuelPrimer.forPopup("10% of the [+Gold] you carry", n -> false));
        AssertJUnit.assertEquals("plain", DuelPrimer.forPopup("[GRAY]plain", n -> true));
        AssertJUnit.assertFalse(DuelPrimer.forPopup("[RED]End Turn[]", n -> true).contains("["));
    }

    @Test
    public void reasonWordForEachLoss() {
        AssertJUnit.assertEquals("life", SessionLog.lossReason(GameLossReason.LifeReachedZero));
        AssertJUnit.assertEquals("conceded", SessionLog.lossReason(GameLossReason.Conceded));
        AssertJUnit.assertEquals("decked", SessionLog.lossReason(GameLossReason.Milled));
        AssertJUnit.assertEquals("poison", SessionLog.lossReason(GameLossReason.Poisoned));
        AssertJUnit.assertEquals("other", SessionLog.lossReason(GameLossReason.SpellEffect));
        AssertJUnit.assertEquals("other", SessionLog.lossReason(GameLossReason.CommanderDamage));
        AssertJUnit.assertEquals("?", SessionLog.lossReason(null));
    }
}
