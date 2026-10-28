package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import forge.adventure.data.EnemyData;
import forge.adventure.data.WorldData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Round 812 (the user: "each would randomly choose from a pool [of] similar creatures to populate the dungeon", and
 * "they should not be able to increase the level of enemies inside. Since they are thematic 3, 2, 1 Creature levels
 * already"; then "no group twice in a row").
 *
 * <p>world/gauntlet_groups.json lists, per gauntlet POI, its themed groups: three Apprentices, two Adepts and a Master
 * each. Each enemy object of a gauntlet map names its slot ({@code gauntletSlot} A1-A3, D1-D2, M) besides its authored
 * {@code enemy}; {@link #resolve} turns the slot into the drawn group's member at the slot's own rank. The placement
 * stays keepAuthored, so MapStage's re-theme, content filter, week roll, DungeonUpgrades and roster override all pass it
 * by - the rank is the slot's, always.
 *
 * <p>The group is drawn at the first entry of an appearance and kept in the map flag {@link #GROUP}, so re-entry, a
 * reload and a loss keep it. A new appearance starts with no map flags - another of the place's reserve copies, or this
 * one after round 798's {@link DungeonRotation} reset - so it draws again. The place's last group is kept in the
 * character flag {@link #LAST} + the POI name (save-bound, shared by the place's copies), and the draw leaves it out.
 * The draw is seeded (world seed, the copy, the appearance's despawn day), so a reload from before the first entry draws
 * the same group. An older save that already has progress in the place keeps the map's own enemies until the place
 * rotates ({@link #AUTHORED}).
 */
public final class GauntletGroups {
    public static final String TABLE = "world/gauntlet_groups.json";
    /** The group of this appearance: index + 1 (0 or absent = not drawn yet), or {@link #AUTHORED}. */
    public static final String GROUP = "gauntletGroup";
    /** The character flag (+ the POI name) holding the place's last group (index + 1), across its reserve copies. */
    public static final String LAST = "gauntletLast.";
    /** An older save with progress in the place: its map's own enemies until the place rotates. */
    static final byte AUTHORED = 100;
    private static final String[] PROGRESS = {"gauntletApprentice", "gauntletAdept", "gauntletMaster"};

    private static final class Group {
        String name;
        String[] apprentice, adept;
        String master;
    }

    private static Map<String, List<Group>> table;

    private GauntletGroups() {
    }

    private static Map<String, List<Group>> table() {
        if (table != null)
            return table;
        Map<String, List<Group>> loaded = new HashMap<>();
        try {
            FileHandle handle = Config.instance().getFile(TABLE);
            if (handle != null && handle.exists()) {
                JsonValue root = new JsonReader().parse(handle);
                for (JsonValue place = root.child; place != null; place = place.next) {
                    List<Group> groups = new ArrayList<>();
                    for (JsonValue g = place.child; g != null; g = g.next) {
                        Group group = new Group();
                        group.name = g.getString("name");
                        group.apprentice = g.get("apprentice").asStringArray();
                        group.adept = g.get("adept").asStringArray();
                        group.master = g.getString("master");
                        if (group.apprentice.length == 3 && group.adept.length == 2)
                            groups.add(group);
                    }
                    if (!groups.isEmpty())
                        loaded.put(place.name, groups);
                }
            }
        } catch (RuntimeException ex) {
            System.err.println("[TFR-Gauntlet] could not read " + TABLE + ": " + ex);
            loaded.clear();
        }
        table = loaded;
        System.out.println("[TFR-Gauntlet] " + loaded.size() + " gauntlets with themed groups");
        return table;
    }

    /** The rank a slot stands for: A1-A3 Apprentice (Common), D1-D2 Adept (Uncommon), M Master (Rare). */
    private static String tierOf(String slot) {
        switch (slot.charAt(0)) {
            case 'A': return "Common";
            case 'D': return "Uncommon";
            case 'M': return "Rare";
            default: return null;
        }
    }

    private static String member(Group g, String slot) {
        int n = slot.length() > 1 ? slot.charAt(1) - '1' : 0;
        switch (slot.charAt(0)) {
            case 'A': return n >= 0 && n < g.apprentice.length ? g.apprentice[n] : null;
            case 'D': return n >= 0 && n < g.adept.length ? g.adept[n] : null;
            case 'M': return g.master;
            default: return null;
        }
    }

    /**
     * The enemy for a gauntlet slot, or null to keep the map's own placement (not a gauntlet, no groups for it, an
     * older save with progress, an unknown slot or a member that does not resolve).
     */
    public static EnemyData resolve(PointOfInterest root, PointOfInterestChanges changes, String slot) {
        if (root == null || root.getData() == null || changes == null || slot == null || slot.isEmpty()
                || !DungeonRotation.isGauntlet(root.getData()))
            return null;
        List<Group> groups = table().get(root.getData().name);
        String tier = tierOf(slot);
        if (groups == null || tier == null)
            return null;
        int index = groupIndex(root, changes, groups, "A1".equals(slot));
        if (index < 0)
            return null;
        String name = member(groups.get(index), slot);
        EnemyData enemy = name == null ? null : WorldData.getEnemy(name + "|" + tier);
        if (enemy == null)
            System.err.println("[TFR-Gauntlet] " + root.getDisplayName() + ": slot " + slot + " of group \""
                    + groups.get(index).name + "\" (" + name + ") does not resolve - the map's own enemy stands");
        return enemy;
    }

    private static int groupIndex(PointOfInterest root, PointOfInterestChanges changes, List<Group> groups, boolean log) {
        Map<String, Byte> flags = changes.getMapFlags();
        Byte stored = flags.get(GROUP);
        if (stored != null && stored == AUTHORED)
            return -1;
        if (stored != null && stored >= 1 && stored <= groups.size()) {
            if (log)
                System.out.println("[TFR-Gauntlet] " + root.getDisplayName() + ": group " + stored + " of " + groups.size()
                        + " \"" + groups.get(stored - 1).name + "\" (kept)");
            return stored - 1;
        }
        // An older save already in the middle of this place: keep its creatures until the place rotates.
        boolean progress = changes.getDeletedObjectCount() > 0;
        for (String key : PROGRESS)
            progress |= flags.getOrDefault(key, (byte) 0) > 0;
        if (progress) {
            flags.put(GROUP, AUTHORED);
            System.out.println("[TFR-Gauntlet] " + root.getDisplayName()
                    + ": authored enemies (an older save with progress here) until the place rotates");
            return -1;
        }
        String lastKey = LAST + root.getData().name;
        int last = Current.player() == null ? 0 : Current.player().getCharacterFlag(lastKey);
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++)
            if (groups.size() < 2 || i != last - 1)
                pool.add(i);
        World world = Current.world();
        Integer despawnDay = world == null ? null : world.getPoiDespawnDay().get(root.getID());
        long seed = (world == null ? 0L : world.getSeed()) ^ ((long) root.getID().hashCode() << 20)
                ^ ((despawnDay == null ? 0L : despawnDay) * 1000003L);
        int pick = pool.get(new Random(seed).nextInt(pool.size()));
        flags.put(GROUP, (byte) (pick + 1));
        if (Current.player() != null)
            Current.player().setCharacterFlag(lastKey, pick + 1);
        System.out.println("[TFR-Gauntlet] " + root.getDisplayName() + ": group " + (pick + 1) + " of " + groups.size() + " \"" + groups.get(pick).name + "\" (drawn" + (last > 0 && groups.size() >= 2
                ? ", not group " + last + " again" : "") + ", despawn day " + despawnDay + ")");
        return pick;
    }
}
