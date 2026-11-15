package forge.adventure.world;

import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;

/**
 * TFR round 1040 (follow-up): Data > Restore under a loaded game left Resume on screen, and the old world's next save
 * wrote it over the restored files. A restore now blocks saves the way a failed load's rollback does.
 */
public class WorldSaveRestoreBlockTest {
    @BeforeClass
    public void localizer() {
        // WorldSave's static save builds an AdventurePlayer, whose decks are named through the Localizer
        forge.util.Localizer.getInstance().initialize("en-US", "../forge-gui/res/languages/");
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field f = WorldSave.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    @AfterMethod
    public void reset() throws Exception {
        setStatic("liveGame", false);
        setStatic("saveBlocked", false);
        setStatic("saveBlockReason", null);
    }

    @Test
    public void testNoLoadedGameNothingToDrop() throws Exception {
        setStatic("liveGame", false);
        AssertJUnit.assertFalse(WorldSave.blockSavesAfterRestore());
        AssertJUnit.assertFalse(WorldSave.isSaveBlocked());
        AssertJUnit.assertNull(WorldSave.getSaveBlockReason());
    }

    @Test
    public void testLoadedGameIsDroppedAndSavesRefused() throws Exception {
        setStatic("liveGame", true);
        AssertJUnit.assertTrue(WorldSave.blockSavesAfterRestore());
        AssertJUnit.assertTrue(WorldSave.isSaveBlocked());
        AssertJUnit.assertEquals("the save files were restored from a backup", WorldSave.getSaveBlockReason());
        // the refusal is in writeSave, ahead of any serializing: nothing reaches the disk
        AssertJUnit.assertFalse(WorldSave.getCurrentSave().save("test", 1));
    }

    @Test
    public void testAFailedLoadKeepsItsOwnReason() throws Exception {
        setStatic("saveBlocked", true);
        AssertJUnit.assertEquals("a failed load could not be rolled back", WorldSave.getSaveBlockReason());
    }

    @Test
    public void testFilesWrittenSince() throws Exception {
        File dir = Files.createTempDirectory("tfr-restore").toFile();
        try {
            File sub = new File(dir, "The Forsaken Realms");
            AssertJUnit.assertTrue(sub.mkdirs());
            File sav = new File(sub, "1_save_slot.sav");
            Files.write(sav.toPath(), new byte[] {1});
            AssertJUnit.assertTrue(sav.setLastModified(1_000_000L));
            AssertJUnit.assertFalse(WorldSave.filesWrittenSince(dir, 2_000_000L));
            AssertJUnit.assertTrue(WorldSave.filesWrittenSince(dir, 1_000_000L));
            AssertJUnit.assertFalse(WorldSave.filesWrittenSince(new File(dir, "missing"), 0L));
            AssertJUnit.assertFalse(WorldSave.filesWrittenSince(null, 0L));
        } finally {
            for (File f : new File(dir, "The Forsaken Realms").listFiles())
                f.delete();
            new File(dir, "The Forsaken Realms").delete();
            dir.delete();
        }
    }
}
