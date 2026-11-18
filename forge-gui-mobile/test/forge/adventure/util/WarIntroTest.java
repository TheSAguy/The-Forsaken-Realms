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
    public void androidSpentFlagIsReArmedOnce() throws Exception {
        SaveFileData spent = new SaveFileData(); // 1.29 on Android: flag set, pages threw
        spent.store(WarIntro.KEY, true);
        boolean[] state = WarIntro.readState(roundTrip(spent), true);
        AssertJUnit.assertFalse("re-armed", state[0]);
        AssertJUnit.assertTrue("through the old-save path", state[1]);
        AssertJUnit.assertEquals(Reason.OLD_SAVE, WarIntro.decide(state[0], state[1], 2, 0));

        SaveFileData after = new SaveFileData(); // the next save, after the pages showed
        WarIntro.store(after, true);
        state = WarIntro.readState(roundTrip(after), true);
        AssertJUnit.assertTrue("not a second time", state[0]);
        AssertJUnit.assertFalse(state[1]);
    }

    /** Round 1043: the log says why a save is on the old-save path - re-armed, or really from before 1.29. */
    @Test
    public void reArmedSaveIsLoggedApartFromAnOldOne() throws Exception {
        SaveFileData spent = new SaveFileData();
        spent.store(WarIntro.KEY, true);
        WarIntro.readState(roundTrip(spent), true);
        AssertJUnit.assertEquals("re-armed: 1.29 marked it shown without the pages", WarIntro.oldSaveLabel());

        WarIntro.readState(roundTrip(new SaveFileData()), true); // the next load: a save from before 1.29
        AssertJUnit.assertEquals("save from before 1.29", WarIntro.oldSaveLabel());

        WarIntro.readState(roundTrip(spent), false); // desktop never re-arms
        AssertJUnit.assertEquals("save from before 1.29", WarIntro.oldSaveLabel());
    }

    @Test
    public void desktopReaderIsNotShownTwice() throws Exception {
        SaveFileData read = new SaveFileData(); // 1.29 on desktop: the pages did show
        read.store(WarIntro.KEY, true);
        boolean[] state = WarIntro.readState(roundTrip(read), false);
        AssertJUnit.assertTrue(state[0]);
        AssertJUnit.assertFalse(state[1]);
    }

    @Test
    public void androidUnshownSaveUnchanged() throws Exception {
        SaveFileData waiting = new SaveFileData();
        waiting.store(WarIntro.KEY, false);
        boolean[] state = WarIntro.readState(roundTrip(waiting), true);
        AssertJUnit.assertFalse(state[0]);
        AssertJUnit.assertFalse("waits for its attack", state[1]);
        state = WarIntro.readState(roundTrip(new SaveFileData()), true);
        AssertJUnit.assertTrue("a save from before 1.29 is still old", state[1]);
    }

    @Test
    public void saveWithoutKeyIsOld() throws Exception {
        boolean[] state = WarIntro.readState(roundTrip(new SaveFileData()));
        AssertJUnit.assertFalse(state[0]);
        AssertJUnit.assertTrue("from before 1.29", state[1]);
        AssertJUnit.assertEquals(Reason.OLD_SAVE, WarIntro.decide(state[0], state[1], 1, 0));
    }
}
