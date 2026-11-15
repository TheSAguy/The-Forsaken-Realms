package forge.adventure.world;

import org.testng.AssertJUnit;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * TFR round 1040 (logs-1115 P2-4): Data > Restore sets lastActiveSave to null, and Continue asked
 * {@link WorldSave#isSafeFile} about it - an NPE that closed the Android app.
 */
public class WorldSaveSafeFileTest {
    @BeforeClass
    public void localizer() {
        // WorldSave's static save builds an AdventurePlayer, whose decks are named through the Localizer; the module's
        // tests run in forge-gui-mobile (as QuestOfferEffectsTest does)
        forge.util.Localizer.getInstance().initialize("en-US", "../forge-gui/res/languages/");
    }

    @Test
    public void testNullIsNotASafeFile() {
        AssertJUnit.assertFalse(WorldSave.isSafeFile(null));
    }

    @Test
    public void testTheNamesTheGameWritesAreSafe() {
        AssertJUnit.assertTrue(WorldSave.isSafeFile("auto_save.sav"));
        AssertJUnit.assertTrue(WorldSave.isSafeFile("quick_save.sav"));
        AssertJUnit.assertTrue(WorldSave.isSafeFile("12_save_slot.sav"));
        AssertJUnit.assertFalse(WorldSave.isSafeFile("12_save_slot - Copy.sav"));
        AssertJUnit.assertFalse(WorldSave.isSafeFile(""));
    }
}
