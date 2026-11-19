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
 * TfrTips fill. Also the [TFR-Duel] line's reason= word for each loss reason.
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
                "difficulty", "lossPct", "lossMin", "lifePct", "ante", "anteDay"));
        String[] keys = new String[DuelPrimer.PAGES * 2 + 3];
        int k = 0;
        for (int i = 1; i <= DuelPrimer.PAGES; i++) {
            keys[k++] = "duel.title" + i;
            keys[k++] = "duel.page" + i;
        }
        keys[k++] = "duel.anteLater";
        keys[k++] = "duel.anteNow";
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
