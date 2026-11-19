package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import forge.adventure.data.TuningData;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * TFR round 1046: the week-1 ante grace. Week 1 (days 1-7) on Easy and Normal plays without ante; day 8 plays for ante
 * again; Hard and Insane ante from day 1; New Game+ never gets the grace. The day is read at each duel, so a save past
 * week 1 is unaffected and a save loaded mid-week-1 keeps the grace for its remaining days. The shipped values are read
 * from the plane's own settings.json, since a misspelled key there fails silently at runtime.
 */
public class AnteGraceTest {
    private static final String PLANE = "../forge-gui/res/adventure/The Forsaken Realms/";

    private static TuningData shipped() {
        File f = new File(PLANE + "config tables/settings.json");
        AssertJUnit.assertTrue("settings.json at " + f.getAbsolutePath(), f.exists());
        return new Json().fromJson(TuningData.class, new FileHandle(f));
    }

    private static boolean grace(TuningData t, String difficulty, int day) {
        return AnteGrace.inGrace(t.anteGraceWeeksFor(difficulty), day, false);
    }

    @Test
    public void shippedValuesAreOneWeekOnEasyAndNormalOnly() {
        TuningData t = shipped();
        AssertJUnit.assertEquals(1, t.anteGraceWeeksFor("Easy"));
        AssertJUnit.assertEquals(1, t.anteGraceWeeksFor("Normal"));
        AssertJUnit.assertEquals(0, t.anteGraceWeeksFor("Hard"));
        AssertJUnit.assertEquals(0, t.anteGraceWeeksFor("Insane"));
        AssertJUnit.assertEquals("an unknown name gets no grace", 0, t.anteGraceWeeksFor("Custom"));
        AssertJUnit.assertEquals(0, t.anteGraceWeeksFor(null));
    }

    @Test
    public void easyAndNormalSkipAnteThroughDaySevenOnly() {
        TuningData t = shipped();
        for (String d : new String[]{"Easy", "Normal", "normal"}) {
            for (int day = 1; day <= 7; day++)
                AssertJUnit.assertTrue(d + " day " + day + " has no ante", grace(t, d, day));
            for (int day : new int[]{8, 9, 14, 15, 60})
                AssertJUnit.assertFalse(d + " day " + day + " plays for ante", grace(t, d, day));
        }
        AssertJUnit.assertEquals(8, AnteGrace.anteFromDay(1));
    }

    @Test
    public void hardAndInsaneAnteFromTheFirstDuel() {
        TuningData t = shipped();
        for (String d : new String[]{"Hard", "Insane"})
            for (int day : new int[]{1, 3, 7, 8})
                AssertJUnit.assertFalse(d + " day " + day, grace(t, d, day));
    }

    @Test
    public void newGamePlusAndBadDaysNeverGetIt() {
        AssertJUnit.assertFalse("New Game+", AnteGrace.inGrace(1, 2, true));
        AssertJUnit.assertFalse("no world day yet", AnteGrace.inGrace(1, 0, false));
        AssertJUnit.assertFalse("0 weeks", AnteGrace.inGrace(0, 1, false));
        AssertJUnit.assertFalse("negative weeks", AnteGrace.inGrace(-2, 1, false));
    }

    @Test
    public void aCustomKnobOfTwoWeeksRunsThroughDayFourteen() {
        AssertJUnit.assertTrue(AnteGrace.inGrace(2, 14, false));
        AssertJUnit.assertFalse(AnteGrace.inGrace(2, 15, false));
        AssertJUnit.assertEquals(15, AnteGrace.anteFromDay(2));
    }

    @Test
    public void theCustomKnobReachesEveryDifficultyColumn() throws Exception {
        String knobs = new String(Files.readAllBytes(new File(PLANE + "config tables/custom_difficulty.json").toPath()),
                StandardCharsets.UTF_8);
        AssertJUnit.assertTrue("knob anteGraceWeeks", knobs.contains("\"id\": \"anteGraceWeeks\""));
        AssertJUnit.assertTrue("{D} target", knobs.contains("\"target\": \"tuning.anteGraceWeeks{D}\""));
        for (String col : new String[]{"Easy", "Normal", "Hard", "Insane"})
            TuningData.class.getField("anteGraceWeeks" + col); // throws if a column the {D} target names is missing
    }
}
