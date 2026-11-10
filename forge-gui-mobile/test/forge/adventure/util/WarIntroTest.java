package forge.adventure.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.adventure.util.WarIntro.Reason;

/**
 * TFR round 1005: "The War Begins" shows once - at the first attack on a player town, or on load for a save from before
 * the round that already holds a town - and the flag survives a save and a load.
 */
public class WarIntroTest {
    @Test
    public void firstAttackShowsIt() {
        AssertJUnit.assertEquals(Reason.FIRST_ATTACK, WarIntro.decide(false, false, 1, 1));
        AssertJUnit.assertEquals(Reason.FIRST_ATTACK, WarIntro.decide(false, true, 3, 2)); // an old save mid-attack names it
    }

    @Test
    public void neverTwice() {
        AssertJUnit.assertEquals(Reason.NONE, WarIntro.decide(true, false, 4, 2));
        AssertJUnit.assertEquals(Reason.NONE, WarIntro.decide(true, true, 4, 0));
    }

    @Test
    public void noAttackNoPopup() {
        AssertJUnit.assertEquals(Reason.NONE, WarIntro.decide(false, false, 6, 0)); // towns, but nothing marching
        AssertJUnit.assertEquals(Reason.NONE, WarIntro.decide(false, false, 0, 0)); // a new run, day 1
    }

    @Test
    public void oldSaveWithTownShowsOnLoad() {
        AssertJUnit.assertEquals(Reason.OLD_SAVE, WarIntro.decide(false, true, 2, 0));
        AssertJUnit.assertEquals(Reason.NONE, WarIntro.decide(false, true, 0, 0)); // no town yet: wait for the attack
    }

    private static SaveFileData roundTrip(SaveFileData data) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(data);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (SaveFileData) in.readObject();
        }
    }

    @Test
    public void flagSurvivesSaveAndLoad() throws Exception {
        SaveFileData shown = new SaveFileData();
        shown.store(WarIntro.KEY, true);
        boolean[] state = WarIntro.readState(roundTrip(shown));
        AssertJUnit.assertTrue("shown", state[0]);
        AssertJUnit.assertFalse("not an old save", state[1]);

        SaveFileData waiting = new SaveFileData();
        waiting.store(WarIntro.KEY, false);
        state = WarIntro.readState(roundTrip(waiting));
        AssertJUnit.assertFalse(state[0]);
        AssertJUnit.assertFalse("a new-style save waits for its attack", state[1]);
    }

    @Test
    public void saveWithoutKeyIsOld() throws Exception {
        boolean[] state = WarIntro.readState(roundTrip(new SaveFileData()));
        AssertJUnit.assertFalse(state[0]);
        AssertJUnit.assertTrue("from before 1.29", state[1]);
        AssertJUnit.assertEquals(Reason.OLD_SAVE, WarIntro.decide(state[0], state[1], 1, 0));
    }
}
