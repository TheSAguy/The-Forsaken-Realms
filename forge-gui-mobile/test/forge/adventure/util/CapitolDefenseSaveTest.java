package forge.adventure.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.Map;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.adventure.data.EnemyData;

/**
 * TFR round 998: the mage waiting at the Capitol survives a save and a load, through the same serialized form the
 * .sav file uses; an old save without the keys loads as none pending; a mage that no longer resolves is dropped.
 */
public class CapitolDefenseSaveTest {
    private static EnemyData enemy(String name, String tier) {
        EnemyData e = new EnemyData();
        e.name = name;
        e.tier = tier;
        return e;
    }

    private static final Map<String, EnemyData> CATALOG = new HashMap<>();
    static {
        CATALOG.put("Archmage", enemy("Archmage", "Mythic"));
        CATALOG.put("Bear|Rare", enemy("Bear", "Rare"));
    }

    /** Through Java serialization, as WorldSave writes the world stage's data into the .sav. */
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
    public void aWaitingMageComesBack() throws Exception {
        SaveFileData data = new SaveFileData();
        CapitolDefenseSave.write(data, "Archmage", "red");
        CapitolDefenseSave.Pending p = CapitolDefenseSave.read(roundTrip(data), CATALOG::get);
        AssertJUnit.assertNotNull(p);
        AssertJUnit.assertEquals("Archmage", p.data.name);
        AssertJUnit.assertEquals("Mythic", p.data.tier);
        AssertJUnit.assertEquals("red", p.color);
    }

    @Test
    public void aTieredMageKeepsItsTier() throws Exception {
        SaveFileData data = new SaveFileData();
        CapitolDefenseSave.write(data, "Bear|Rare", "green");
        CapitolDefenseSave.Pending p = CapitolDefenseSave.read(roundTrip(data), CATALOG::get);
        AssertJUnit.assertNotNull(p);
        AssertJUnit.assertEquals("Rare", p.data.tier);
        AssertJUnit.assertEquals("Bear|Rare", p.savedName);
    }

    @Test
    public void anOldSaveLoadsAsNonePending() throws Exception {
        SaveFileData data = new SaveFileData();
        data.store("globalTimer", 12f); // a world stage's data from before this round: no Capitol keys
        AssertJUnit.assertNull(CapitolDefenseSave.read(roundTrip(data), CATALOG::get));
    }

    @Test
    public void noMageWaitingWritesNothing() {
        SaveFileData data = new SaveFileData();
        CapitolDefenseSave.write(data, null, null);
        CapitolDefenseSave.write(data, "Archmage", null);
        AssertJUnit.assertTrue(data.isEmpty());
    }

    @Test
    public void aMageThatNoLongerResolvesIsDropped() throws Exception {
        SaveFileData data = new SaveFileData();
        CapitolDefenseSave.write(data, "Renamed Mage", "blue");
        AssertJUnit.assertNull(CapitolDefenseSave.read(roundTrip(data), CATALOG::get));
    }
}
