package forge.adventure.util;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * TFR round 1030: Mirror Gear - the gate (Notoriety and the save's win ratio per difficulty), the copy count, the pool
 * filter, the seeded pick (never the same item twice, never two from the top band) and the plane's table.
 */
public class MirrorGearTest {
    private static final String TABLE_JSON = "{ // a comment, as the plane's file has\n"
            + "\"gates\": {\"Archmage\": {\"Hard\": {\"notoriety\": 50, \"winRatio\": 0.85},"
            + " \"Insane\": {\"notoriety\": 40, \"winRatio\": 0.75}},\n"
            + " \"Master\": {\"Hard\": {\"notoriety\": 40, \"winRatio\": 0.85, \"enemyWinRatio\": 0.75},"
            + " \"Insane\": {\"notoriety\": 20, \"winRatio\": 0.75, \"enemyWinRatio\": 0.51}}},\n"
            + "\"minEnemyDuels\": 1, \"itemsForOne\": 3, \"itemsForTwo\": 5,\n"
            + "\"gearSlots\": [\"Head\", \"Neck\", \"Body\", \"Left\", \"Right\", \"Legs\", \"Boots\"],\n"
            + "\"topBand\": 4.0, \"bountyPointsPerCopy\": 3,\n"
            + "\"pool\": {\"Greatsword\": 5.0, \"Ring of Renewal\": 5.0, \"Boots\": 2.0, \"Cloak\": 1.5, \"Hat\": 3.0}}";

    private static MirrorGear.Table table() {
        return MirrorGear.parse(TABLE_JSON);
    }

    @Test
    public void tableParses() {
        MirrorGear.Table t = table();
        AssertJUnit.assertEquals(2, t.gates.size());
        AssertJUnit.assertEquals(50f, MirrorGear.gateOf(t, "Archmage", "Hard")[0], 0.001f);
        AssertJUnit.assertEquals(0.75f, MirrorGear.gateOf(t, "Archmage", "Insane")[1], 0.001f);
        AssertJUnit.assertEquals("an Archmage's gate has no enemy check", -1f, MirrorGear.gateOf(t, "Archmage", "Hard")[2], 0.001f);
        AssertJUnit.assertEquals(0.51f, MirrorGear.gateOf(t, "Master", "Insane")[2], 0.001f);
        AssertJUnit.assertNull(MirrorGear.gateOf(t, "Adept", "Hard"));
        AssertJUnit.assertEquals(5, t.pool.size());
        AssertJUnit.assertTrue("the pool ignores case", t.inPool("greatsword"));
        AssertJUnit.assertTrue(t.top("Greatsword"));
        AssertJUnit.assertFalse(t.top("Boots"));
    }

    @Test
    public void hardGate() {
        MirrorGear.Table t = table();
        // 85 of 100 = 85%: the edge opens
        AssertJUnit.assertTrue(MirrorGear.gate(t, true, "Hard", "Archmage", false, 50, 85, 15, 0, 0).open);
        AssertJUnit.assertFalse("notoriety 49", MirrorGear.gate(t, true, "Hard", "Archmage", false, 49, 85, 15, 0, 0).open);
        AssertJUnit.assertFalse("84%", MirrorGear.gate(t, true, "Hard", "Archmage", false, 60, 84, 16, 0, 0).open);
        // Insane's numbers do not open Hard
        AssertJUnit.assertFalse(MirrorGear.gate(t, true, "Hard", "Archmage", false, 45, 80, 20, 0, 0).open);
    }

    @Test
    public void insaneGate() {
        MirrorGear.Table t = table();
        AssertJUnit.assertTrue(MirrorGear.gate(t, true, "Insane", "Archmage", false, 40, 75, 25, 0, 0).open);
        AssertJUnit.assertFalse("notoriety 39", MirrorGear.gate(t, true, "Insane", "Archmage", false, 39, 90, 10, 0, 0).open);
        AssertJUnit.assertFalse("74%", MirrorGear.gate(t, true, "Insane", "Archmage", false, 40, 74, 26, 0, 0).open);
    }

    @Test
    public void closedWhereItDoesNotApply() {
        MirrorGear.Table t = table();
        AssertJUnit.assertFalse("Easy", MirrorGear.gate(t, true, "Easy", "Archmage", false, 200, 100, 0, 0, 0).open);
        AssertJUnit.assertFalse("Normal", MirrorGear.gate(t, true, "Normal", "Archmage", false, 200, 100, 0, 0, 0).open);
        AssertJUnit.assertFalse("the save left it off", MirrorGear.gate(t, false, "Hard", "Archmage", false, 200, 100, 0, 0, 0).open);
        AssertJUnit.assertFalse("Notoriety off in Settings", MirrorGear.gate(t, true, "Hard", "Archmage", true, 200, 100, 0, 0, 0).open);
        AssertJUnit.assertFalse("no duels yet", MirrorGear.gate(t, true, "Insane", "Archmage", false, 40, 0, 0, 0, 0).open);
        AssertJUnit.assertFalse("no table", MirrorGear.gate(null, true, "Hard", "Archmage", false, 200, 100, 0, 0, 0).open);
    }

    @Test
    public void masterGates() {
        MirrorGear.Table t = table();
        // Insane: 20+, 75%+, and 51%+ against this enemy
        AssertJUnit.assertTrue(MirrorGear.gate(t, true, "Insane", "Master", false, 20, 75, 25, 2, 1).open);
        AssertJUnit.assertFalse("50% against it", MirrorGear.gate(t, true, "Insane", "Master", false, 20, 75, 25, 1, 1).open);
        AssertJUnit.assertFalse("never fought it", MirrorGear.gate(t, true, "Insane", "Master", false, 90, 99, 1, 0, 0).open);
        AssertJUnit.assertTrue("a single win is 100%", MirrorGear.gate(t, true, "Insane", "Master", false, 20, 75, 25, 1, 0).open);
        AssertJUnit.assertFalse("notoriety 19", MirrorGear.gate(t, true, "Insane", "Master", false, 19, 75, 25, 5, 0).open);
        // Hard: 40+, 85%+, and 75%+ against this enemy
        AssertJUnit.assertTrue(MirrorGear.gate(t, true, "Hard", "Master", false, 40, 85, 15, 3, 1).open);
        AssertJUnit.assertFalse("67% against it", MirrorGear.gate(t, true, "Hard", "Master", false, 40, 85, 15, 2, 1).open);
        AssertJUnit.assertFalse("notoriety 39", MirrorGear.gate(t, true, "Hard", "Master", false, 39, 90, 10, 9, 0).open);
        AssertJUnit.assertFalse("84% overall", MirrorGear.gate(t, true, "Hard", "Master", false, 60, 84, 16, 9, 0).open);
        // the Master's numbers do not open an Archmage, and an Archmage needs no record against itself
        AssertJUnit.assertFalse(MirrorGear.gate(t, true, "Insane", "Archmage", false, 20, 75, 25, 5, 0).open);
        AssertJUnit.assertTrue(MirrorGear.gate(t, true, "Insane", "Archmage", false, 40, 75, 25, 0, 0).open);
        AssertJUnit.assertFalse("no Adept gate", MirrorGear.gate(t, true, "Insane", "Adept", false, 99, 99, 1, 9, 0).open);
        AssertJUnit.assertFalse("Normal", MirrorGear.gate(t, true, "Normal", "Master", false, 99, 99, 1, 9, 0).open);
    }

    @Test
    public void minEnemyDuels() {
        MirrorGear.Table t = MirrorGear.parse(TABLE_JSON.replace("\"minEnemyDuels\": 1", "\"minEnemyDuels\": 3"));
        AssertJUnit.assertEquals(3, t.minEnemyDuels);
        AssertJUnit.assertFalse("2 duels", MirrorGear.gate(t, true, "Insane", "Master", false, 20, 75, 25, 2, 0).open);
        AssertJUnit.assertTrue("3 duels", MirrorGear.gate(t, true, "Insane", "Master", false, 20, 75, 25, 3, 0).open);
    }

    @Test
    public void copyCount() {
        MirrorGear.Table t = table();
        AssertJUnit.assertEquals(0, MirrorGear.copiesFor(t, 2));
        AssertJUnit.assertEquals(1, MirrorGear.copiesFor(t, 3));
        AssertJUnit.assertEquals(1, MirrorGear.copiesFor(t, 4));
        AssertJUnit.assertEquals(2, MirrorGear.copiesFor(t, 5));
        AssertJUnit.assertEquals(2, MirrorGear.copiesFor(t, 7));
    }

    @Test
    public void candidatesAreThePoolOnlyOnceSorted() {
        MirrorGear.Table t = table();
        List<String> c = MirrorGear.candidates(t, Arrays.asList("Sol Ring", "hat", "Boots", "Boots", "Greatsword"));
        AssertJUnit.assertEquals(Arrays.asList("Boots", "Greatsword", "Hat"), c);
        // the order the inventory holds them in does not matter
        AssertJUnit.assertEquals(c, MirrorGear.candidates(t, Arrays.asList("Greatsword", "Boots", "Hat", "Sol Ring")));
    }

    @Test
    public void pickIsSeeded() {
        MirrorGear.Table t = table();
        List<String> c = Arrays.asList("Boots", "Cloak", "Greatsword", "Hat", "Ring of Renewal");
        long seed = MirrorGear.seed(1234L, "Archmage of Ash", 0, 31, 140);
        AssertJUnit.assertEquals("a reload rolls the same", MirrorGear.pick(t, c, 2, seed), MirrorGear.pick(t, c, 2, seed));
        AssertJUnit.assertTrue("the next duel has another seed",
                seed != MirrorGear.seed(1234L, "Archmage of Ash", 0, 31, 141));
        AssertJUnit.assertTrue("another seat has another seed",
                seed != MirrorGear.seed(1234L, "Archmage of Ash", 1, 31, 140));
    }

    @Test
    public void neverTwiceNeverTwoTopBand() {
        MirrorGear.Table t = table();
        List<String> c = Arrays.asList("Boots", "Cloak", "Greatsword", "Hat", "Ring of Renewal");
        for (long s = 0; s < 500; s++) {
            List<String> p = MirrorGear.pick(t, c, 2, s);
            AssertJUnit.assertEquals(2, p.size());
            AssertJUnit.assertFalse("same item twice: " + p, p.get(0).equals(p.get(1)));
            AssertJUnit.assertFalse("two from the top band: " + p, t.top(p.get(0)) && t.top(p.get(1)));
        }
        // only two top-band items worn: one copy, not two
        AssertJUnit.assertEquals(1, MirrorGear.pick(t, Arrays.asList("Greatsword", "Ring of Renewal"), 2, 7L).size());
        AssertJUnit.assertEquals(1, MirrorGear.pick(t, Arrays.asList("Hat"), 2, 7L).size());
        AssertJUnit.assertTrue(MirrorGear.pick(t, Arrays.asList(), 2, 7L).isEmpty());
        AssertJUnit.assertTrue(MirrorGear.pick(t, c, 0, 7L).isEmpty());
    }

    /** Every pick lands on every candidate now and then: no item is stuck out of reach by the seed's order. */
    @Test
    public void everyCandidateCanBePicked() {
        MirrorGear.Table t = table();
        List<String> c = Arrays.asList("Boots", "Cloak", "Greatsword", "Hat", "Ring of Renewal");
        Set<String> seen = new HashSet<>();
        for (long s = 0; s < 200; s++)
            seen.addAll(MirrorGear.pick(t, c, 1, MirrorGear.seed(s, "X", 0, 1, 1)));
        AssertJUnit.assertEquals(new HashSet<>(c), seen);
    }

    // ---- the plane's own table ----------------------------------------------------------------------------------

    private static File plane(String path) {
        File f = new File("../forge-gui/res/adventure/The Forsaken Realms/" + path);
        if (!f.isFile())
            f = new File("forge-gui/res/adventure/The Forsaken Realms/" + path);
        return f;
    }

    private static void assertGate(MirrorGear.Table t, String rank, String diff, float noto, float ratio, float vs) {
        float[] g = MirrorGear.gateOf(t, rank, diff);
        AssertJUnit.assertNotNull(rank + "/" + diff, g);
        AssertJUnit.assertEquals(rank + "/" + diff + " notoriety", noto, g[0], 0.001f);
        AssertJUnit.assertEquals(rank + "/" + diff + " win ratio", ratio, g[1], 0.001f);
        AssertJUnit.assertEquals(rank + "/" + diff + " vs this enemy", vs, g[2], 0.001f);
    }

    @Test
    public void planeTable() throws Exception {
        File f = plane(MirrorGear.TABLE);
        AssertJUnit.assertTrue(f.getAbsolutePath() + " not found", f.isFile());
        MirrorGear.Table t = MirrorGear.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        AssertJUnit.assertEquals("Masters and Archmages only", new HashSet<>(Arrays.asList("Master", "Archmage")),
                t.gates.keySet());
        for (String rank : t.gates.keySet())
            AssertJUnit.assertEquals(rank + ": Hard and Insane only", new HashSet<>(Arrays.asList("Hard", "Insane")),
                    t.gates.get(rank).keySet());
        // the user's numbers (2026-11-14)
        assertGate(t, "Archmage", "Insane", 40, 0.75f, -1f);
        assertGate(t, "Archmage", "Hard", 50, 0.85f, -1f);
        assertGate(t, "Master", "Insane", 20, 0.75f, 0.51f);
        assertGate(t, "Master", "Hard", 40, 0.85f, 0.75f);
        AssertJUnit.assertEquals(1, t.minEnemyDuels);
        AssertJUnit.assertFalse("Medals are not gear", t.gearSlots.contains("Medal"));
        AssertJUnit.assertTrue(t.pool.size() > 300);
        for (String banned : new String[]{"Sol Ring", "Mox Jet", "Mox Pearl", "Mox Ruby", "Mox Emerald", "Mox Sapphire",
                "Black Lotus", "Lotus Petal", "Cheat", "Jester's Cap", "Armor of Horrors", "Flame Sword"})
            AssertJUnit.assertFalse(banned + " must not be copied", t.inPool(banned));
        for (float v : t.pool.values())
            AssertJUnit.assertTrue("an item worth 0.5 or less weakens the enemy: " + v, v > 0.5f);

        // every pool name is a gear-slot item of world/items.json
        JsonValue items = new JsonReader().parse(new String(Files.readAllBytes(plane("world/items.json").toPath()),
                StandardCharsets.UTF_8));
        Set<String> gear = new HashSet<>();
        for (JsonValue it = items.child; it != null; it = it.next) {
            String slot = it.getString("equipmentSlot", null);
            if (slot != null && t.gearSlots.contains(slot))
                gear.add(it.getString("name", "").toLowerCase(Locale.ROOT));
        }
        AssertJUnit.assertTrue("items.json read", gear.size() > 400);
        for (String name : t.pool.keySet())
            AssertJUnit.assertTrue("not a gear item in items.json: " + name, gear.contains(name));
    }
}
