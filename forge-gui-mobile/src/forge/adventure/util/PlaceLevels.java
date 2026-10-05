package forge.adventure.util;

import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.world.WorldSave;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Round 443 (the user: "Scoured Valley gave me multiple Cleared bonuses and worse, it was not even cleared. It was a
 * multi level dungeon that was not done"): a place with stairs is cleared only when EVERY level is. Each clear test
 * before this read only the level the player stood on - Cultists' Outpost paid its bonus and left the map after the
 * first of its four floors, with the player still inside.
 * <p>
 * The level ledger: when the player leaves a level (by its stairs, or by walking out), what is left there - enemies,
 * loot, and where its stairs lead - goes into that level's saved map flags. The level being played is read live, every
 * other level from its ledger, and a level with no ledger (never visited, or not visited since this round) counts as
 * not clear. Nothing is read from the map files, so no spawn rule has to be copied here.
 * <p>
 * A ledger also keeps the level's deleted-object count from when it was written. Objects are only deleted while the
 * player is on that level, so a different count means the level was restocked or reset since (DungeonRotation.restock,
 * MapStage.resetMapRecursive, a quest that resets its target) and the ledger is ignored. No new save fields: mapFlags
 * already exists and is saved.
 */
public final class PlaceLevels {
    private static final String PREFIX = "tfrLedger";
    private static final String ENEMIES = PREFIX + "Enemies";
    private static final String LOOT = PREFIX + "Loot";
    private static final String DELETED = PREFIX + "Deleted";
    private static final String STAIR = PREFIX + "Stair:";
    /** mapFlags hold bytes. */
    private static final int CAP = 127;

    private PlaceLevels() {
    }

    /** What is left in a whole place, every level counted. */
    public static final class Status {
        /** Enemies and loot known to be left: the live level plus every level with a valid ledger. */
        public int enemies;
        public int loot;
        /** Levels the stairs reach that have no valid ledger - not visited, so not clear. */
        public int unvisited;
        /** Levels counted, the live one included. */
        public int levels = 1;
        private final StringBuilder others = new StringBuilder();

        public boolean enemiesLeft() {
            return enemies > 0 || unvisited > 0;
        }

        public boolean lootLeft() {
            return loot > 0;
        }

        public boolean nothingLeft() {
            return enemies == 0 && loot == 0 && unvisited == 0;
        }

        /** The OTHER levels that are not done, for the log: "cave_18N.tmx: not visited; cave_21D.tmx: 3 enemies, 2 loot". */
        public String others() {
            return others.length() == 0 ? "none" : others.toString();
        }

        private void note(String text) {
            others.append(others.length() == 0 ? "" : "; ").append(text);
        }
    }

    /** Writes the ledger of the level the player is leaving: what is left on it and where its stairs lead. */
    public static void record(PointOfInterestChanges changes, int enemies, int loot, Collection<String> stairs) {
        if (changes == null)
            return;
        Map<String, Byte> flags = changes.getMapFlags();
        flags.keySet().removeIf(k -> k.startsWith(STAIR));
        flags.put(ENEMIES, cap(enemies));
        flags.put(LOOT, cap(loot));
        flags.put(DELETED, cap(changes.getDeletedObjectCount()));
        if (stairs != null)
            for (String stair : stairs)
                flags.put(STAIR + stair, (byte) 1);
    }

    /**
     * The whole place: the live level (its saved-changes key, what is left on it, where its stairs lead) and every level
     * those stairs reach, followed through the ledgers. A level reached only through an unvisited one is not seen - the
     * unvisited one already makes the place not clear. A place with no stairs is just its live level, as before.
     */
    public static Status status(PointOfInterest root, String liveKey, int liveEnemies, int liveLoot,
                                Collection<String> liveStairs) {
        Status status = new Status();
        status.enemies = Math.max(0, liveEnemies);
        status.loot = Math.max(0, liveLoot);
        if (root == null || liveKey == null || WorldSave.getCurrentSave() == null)
            return status;
        String rootId = root.getID();
        Set<String> seen = new HashSet<>();
        seen.add(liveKey);
        Deque<String> next = new ArrayDeque<>();
        enqueue(next, seen, rootId, liveStairs);
        while (!next.isEmpty()) {
            String map = next.poll();
            status.levels++;
            PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(keyOf(rootId, map));
            Map<String, Byte> flags = changes == null ? null : changes.getMapFlags();
            Byte written = flags == null ? null : flags.get(DELETED);
            if (written == null || written != cap(changes.getDeletedObjectCount())) {
                status.unvisited++;
                status.note(levelName(map) + ": " + (written == null ? "not visited" : "restocked since the last visit"));
                continue;
            }
            int enemies = flags.getOrDefault(ENEMIES, (byte) 0);
            int loot = flags.getOrDefault(LOOT, (byte) 0);
            status.enemies += enemies;
            status.loot += loot;
            if (enemies > 0 || loot > 0)
                status.note(levelName(map) + ": " + enemies + (enemies == 1 ? " enemy, " : " enemies, ") + loot + " loot");
            List<String> stairs = new ArrayList<>();
            for (String key : flags.keySet())
                if (key.startsWith(STAIR))
                    stairs.add(key.substring(STAIR.length()));
            enqueue(next, seen, rootId, stairs);
        }
        return status;
    }

    private static void enqueue(Deque<String> next, Set<String> seen, String rootId, Collection<String> stairs) {
        if (stairs == null)
            return;
        for (String map : stairs)
            if (map != null && !map.isEmpty() && seen.add(keyOf(rootId, map)))
                next.add(map);
    }

    /** The saved-changes key of one of a place's levels - TileMapScene.getPointOfInterestChanges(String)'s rule. */
    private static String keyOf(String rootId, String map) {
        return rootId.endsWith(map) ? rootId : rootId + map;
    }

    private static String levelName(String map) {
        int slash = map.lastIndexOf('/');
        return slash < 0 ? map : map.substring(slash + 1);
    }

    private static byte cap(int n) {
        return (byte) Math.max(0, Math.min(CAP, n));
    }
}
