package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import forge.adventure.data.EnemyData;
import forge.adventure.data.ItemData;
import forge.adventure.data.ItemListData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * TFR round 1030, Mirror Gear (the user: "if you are equipped with 3 items. Masters and Archmages also get 1 random item
 * equipped on them. 5+ they get 2"; then: "a check-box ... in the game advanced setup settings. By default it would be
 * checked for Hard and Insane ... 40+ Notoriety AND Win Ratio of 75%+ - Insane / 50+ Notoriety AND Win Ratio of 85%+ -
 * Hard. Grey it out / hide on Easy and Normal"). The design study: C:\CLAUDE\PROJECTS\TFR1\notes\design-mirror-items\report.md.
 * <p>
 * A save that switched it on at its New Game (World's "mirrorGear" key; an older save has none, so it is off), on a
 * difficulty the table lists, with the seat's gate met, starts each Master and Archmage seat with a copy of one of the
 * gear items the player wears (3+ worn), or two (5+). The gate is per rank and difficulty (the user's follow-up: Archmages
 * "as is" - Insane 40+ Notoriety and 75%+ wins, Hard 50+ and 85%+; Masters: Insane 20+, 75%+ and 51%+ against that
 * enemy, Hard 40+, 85%+ and 75%+ against it). "Against that enemy" is the VS screen's record (PlayerStatistic's
 * winLossRecord by the enemy's name - a tier variant keeps its catalog name, so one record covers all its ranks). Copies come only from the
 * table's curated pool (no shard-paid, fast-mana, command-zone, handicap, map-only or self-harming items), never the same
 * item twice, never two from the top value band, and the pick is seeded from saved state, so a reload rolls the same.
 * Not in a boss fight, an Inn or Arena event, a town or Capitol fight, a guard's fight, Deck Tester or a spectator duel
 * (DuelScene decides that). The table, {@code config tables/mirror_gear.json}, is the plane's switch: no file, no feature.
 */
public final class MirrorGear {
    private MirrorGear() {
    }

    public static final String TABLE = "config tables/mirror_gear.json";
    /** The save key (World). */
    public static final String KEY = "mirrorGear";
    /** The one-time notice's save key (World). */
    public static final String NOTICE_KEY = "mirrorGearNoticeShown";
    /** The seed's site salt ("MIRRORGR"). */
    static final long SALT = 0x4D4952524F524752L;

    // ---- the table ----------------------------------------------------------------------------------------------

    /** mirror_gear.json, parsed. */
    public static final class Table {
        /** Rank ("Master", "Archmage") -> difficulty name -> {Notoriety needed, win ratio needed (0..1), win ratio
         *  needed against this enemy (0..1; -1 = not checked)}. A rank or difficulty not listed never mirrors. */
        public final Map<String, Map<String, float[]>> gates = new LinkedHashMap<>();
        /** The fewest duels against an enemy before its own record can open a gate that checks it. */
        public int minEnemyDuels = 1;
        /** Worn gear items for one copy and for two. */
        public int itemsForOne = 3, itemsForTwo = 5;
        /** The slots counted as worn gear (runes, staffs, Medals, Hearts, Blessings and Tokens are not gear). */
        public final Set<String> gearSlots = new HashSet<>();
        /** Item name (lower case) -> its value to the enemy, in mana; only these are ever copied. */
        public final Map<String, Float> pool = new LinkedHashMap<>();
        /** The proper names of the pool, keyed like pool. */
        final Map<String, String> names = new HashMap<>();
        /** A value at or above this is the top band: two copies never both come from it. */
        public float topBand = 4f;
        /** The Bounty's points per copy (StreakBounty). */
        public int bountyPointsPerCopy = 3;

        public boolean inPool(String item) {
            return item != null && pool.containsKey(item.toLowerCase(Locale.ROOT));
        }

        public float value(String item) {
            Float v = item == null ? null : pool.get(item.toLowerCase(Locale.ROOT));
            return v == null ? 0f : v;
        }

        public boolean top(String item) {
            return value(item) >= topBand;
        }

        String properName(String item) {
            String n = item == null ? null : names.get(item.toLowerCase(Locale.ROOT));
            return n == null ? item : n;
        }
    }

    private static Table table;
    private static boolean loaded;

    /** The plane's table, or null: no file (another plane) or a file that did not parse - the feature is off. */
    public static Table table() {
        if (!loaded) {
            loaded = true;
            table = null;
            try {
                FileHandle f = Config.instance().getFile(TABLE);
                if (f != null && f.exists()) {
                    table = parse(f.readString("UTF-8"));
                    System.out.println("[TFR-MirrorGear] table: " + table.pool.size() + " item(s) in the pool, gates "
                            + gateWords(table) + ", " + table.itemsForOne + "/" + table.itemsForTwo
                            + " worn for 1/2 copies, top band " + table.topBand + ", enemy record from "
                            + table.minEnemyDuels + " duel(s)");
                }
            } catch (Exception e) {
                System.err.println("[TFR-MirrorGear] " + TABLE + " failed to load - Mirror Gear is off: " + e);
                table = null;
            }
        }
        return table;
    }

    /** Tests: a table of their own (null = load the plane's again on the next read). */
    static void setTableForTest(Table t) {
        table = t;
        loaded = t != null;
    }

    /** The table from its json text (comments allowed). */
    public static Table parse(String json) {
        JsonValue root = new JsonReader().parse(json);
        Table t = new Table();
        JsonValue gates = root.get("gates");
        if (gates != null)
            for (JsonValue rank = gates.child; rank != null; rank = rank.next) {
                Map<String, float[]> byDifficulty = new LinkedHashMap<>();
                for (JsonValue g = rank.child; g != null; g = g.next)
                    byDifficulty.put(g.name, new float[]{g.getFloat("notoriety", 0f), g.getFloat("winRatio", 0f),
                            g.getFloat("enemyWinRatio", -1f)});
                t.gates.put(rank.name, byDifficulty);
            }
        t.minEnemyDuels = Math.max(1, root.getInt("minEnemyDuels", t.minEnemyDuels));
        t.itemsForOne = root.getInt("itemsForOne", t.itemsForOne);
        t.itemsForTwo = root.getInt("itemsForTwo", t.itemsForTwo);
        t.topBand = root.getFloat("topBand", t.topBand);
        t.bountyPointsPerCopy = root.getInt("bountyPointsPerCopy", t.bountyPointsPerCopy);
        JsonValue slots = root.get("gearSlots");
        if (slots != null)
            for (JsonValue s = slots.child; s != null; s = s.next)
                t.gearSlots.add(s.asString());
        JsonValue pool = root.get("pool");
        if (pool != null)
            for (JsonValue p = pool.child; p != null; p = p.next) {
                String key = p.name.toLowerCase(Locale.ROOT);
                t.pool.put(key, p.asFloat());
                t.names.put(key, p.name);
            }
        return t;
    }

    private static String gateWords(Table t) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, float[]>> r : t.gates.entrySet())
            for (Map.Entry<String, float[]> e : r.getValue().entrySet())
                out.add(r.getKey() + "/" + e.getKey() + " " + Math.round(e.getValue()[0]) + "+ "
                        + Math.round(e.getValue()[1] * 100f) + "%"
                        + (e.getValue()[2] >= 0f ? " vs " + Math.round(e.getValue()[2] * 100f) + "%" : ""));
        return String.join(", ", out);
    }

    /** A rank's gate on a difficulty, or null. rank: the tier display name ("Master", "Archmage"). */
    public static float[] gateOf(Table t, String rank, String difficulty) {
        if (t == null || rank == null || difficulty == null)
            return null;
        Map<String, float[]> byDifficulty = t.gates.get(rank);
        return byDifficulty == null ? null : byDifficulty.get(difficulty);
    }

    // ---- the New Game switch ------------------------------------------------------------------------------------

    /** Can a game on this difficulty have it at all (the checkbox is greyed out where not)? */
    public static boolean availableOn(String difficulty) {
        Table t = table();
        if (t == null || difficulty == null)
            return false;
        for (Map<String, float[]> byDifficulty : t.gates.values())
            if (byDifficulty.containsKey(difficulty))
                return true;
        return false;
    }

    /** The checkbox's default for a difficulty: on wherever it is available (Hard and Insane). */
    public static boolean defaultOn(String difficulty) {
        return availableOn(difficulty);
    }

    /** The New Game screen's choice for the world about to be generated; null = none (New Game+ keeps the save's). */
    private static Boolean pendingNewGame;

    public static void setPendingNewGame(boolean on) {
        pendingNewGame = on;
    }

    /** World.generateNew: the flag the new world starts with - the New Game screen's choice, or the current one (New
     *  Game+ regenerates the loaded save's world and keeps its choice). The choice is used once. */
    public static boolean takePendingNewGame(boolean current) {
        Boolean p = pendingNewGame;
        pendingNewGame = null;
        boolean on = p != null ? p : current;
        System.out.println("[TFR-MirrorGear] new world: " + (on ? "on" : "off")
                + (p != null ? " (New Game screen)" : " (kept: New Game+)"));
        return on;
    }

    // ---- the gate -----------------------------------------------------------------------------------------------

    /** The gate's answer and its words for the log. */
    public static final class Gate {
        public boolean open;
        public String why = "";
    }

    /**
     * The gate, from plain values (testable). rank: "Master" or "Archmage". wins/losses: the save's whole record
     * (PlayerStatistic.totalWins and totalLoss: draws are not counted, a concession is a loss, Inn matches are not in
     * it); enemyWins/enemyLosses: the record against this enemy (the VS screen's), read only when the gate checks it.
     */
    public static Gate gate(Table t, boolean saveOn, String difficulty, String rank, boolean notorietyOff, int notoriety,
                            int wins, int losses, int enemyWins, int enemyLosses) {
        Gate g = new Gate();
        if (t == null) {
            g.why = "no table";
            return g;
        }
        if (!saveOn) {
            g.why = "off for this save";
            return g;
        }
        float[] need = gateOf(t, rank, difficulty);
        if (need == null) {
            g.why = "no gate for " + rank + " on " + difficulty;
            return g;
        }
        if (notorietyOff) {
            g.why = "Notoriety is off in Settings";
            return g;
        }
        int duels = wins + losses;
        float ratio = duels <= 0 ? 0f : (float) wins / duels;
        String state = "notoriety " + notoriety + "/" + Math.round(need[0]) + ", win ratio " + percent(ratio) + "/"
                + percent(need[1]) + " (" + wins + " of " + duels + ")";
        g.open = notoriety >= need[0] && duels > 0 && ratio + 1e-6f >= need[1];
        if (need[2] >= 0f) { // Masters: the record against this very enemy too
            int met = enemyWins + enemyLosses;
            float vs = met <= 0 ? 0f : (float) enemyWins / met;
            state += ", vs this enemy " + (met <= 0 ? "never fought" : percent(vs) + " (" + enemyWins + "-" + enemyLosses + ")")
                    + "/" + percent(need[2]);
            g.open = g.open && met >= t.minEnemyDuels && vs + 1e-6f >= need[2];
        }
        g.why = (g.open ? "open: " : "closed: ") + rank + ", " + state;
        return g;
    }

    static String percent(float f) {
        return Math.round(f * 100f) + "%";
    }

    /** The gate for this seat in the running save. */
    public static Gate gateNow(EnemyData seat) {
        AdventurePlayer p = Current.player();
        World w = world();
        String diff = p == null || p.getDifficulty() == null ? null : p.getDifficulty().name;
        int ew = 0, el = 0;
        if (p != null && seat != null) {
            org.apache.commons.lang3.tuple.Pair<Integer, Integer> rec = p.getStatistic().getWinLossRecord().get(seat.getName());
            if (rec != null) {
                ew = rec.getLeft();
                el = rec.getRight();
            }
        }
        return gate(table(), w != null && w.isMirrorGear(), diff, seat == null ? null : EnemyData.tierDisplayName(seat.tier),
                AdventurePlayer.notorietyDisabled(), p == null ? 0 : p.notorietyStreak(),
                p == null ? 0 : p.getStatistic().totalWins(), p == null ? 0 : p.getStatistic().totalLoss(), ew, el);
    }

    /** Can this rank be copied at all (a gate of its own on some difficulty)? */
    static boolean rankListed(Table t, EnemyData seat) {
        return t != null && seat != null && t.gates.containsKey(EnemyData.tierDisplayName(seat.tier));
    }

    private static World world() {
        WorldSave s = WorldSave.getCurrentSave();
        return s == null ? null : s.getWorld();
    }

    // ---- the pool and the pick ----------------------------------------------------------------------------------

    /** How many copies a seat gets for this many worn gear items: 0, 1 or 2. */
    public static int copiesFor(Table t, int wornGear) {
        if (t == null)
            return 0;
        return wornGear >= t.itemsForTwo ? 2 : wornGear >= t.itemsForOne ? 1 : 0;
    }

    /** The worn gear items that may be copied: in the pool, each once, sorted (the pick must not depend on the order
     *  the inventory holds them in). */
    public static List<String> candidates(Table t, Collection<String> wornGear) {
        TreeSet<String> out = new TreeSet<>();
        if (t != null && wornGear != null)
            for (String n : wornGear)
                if (t.inPool(n))
                    out.add(t.properName(n));
        return new ArrayList<>(out);
    }

    /**
     * The copies, seeded: the first is any candidate; the second is another one, not from the top band when the first
     * is. Fewer when the candidates run out.
     */
    public static List<String> pick(Table t, List<String> candidates, int copies, long seed) {
        List<String> out = new ArrayList<>();
        if (t == null || candidates == null || candidates.isEmpty() || copies <= 0)
            return out;
        List<String> order = new ArrayList<>(candidates);
        Collections.shuffle(order, new Random(seed));
        for (String c : order) {
            if (out.size() >= copies)
                break;
            if (out.contains(c))
                continue;
            if (!out.isEmpty() && t.top(c) && t.top(out.get(0)))
                continue;
            out.add(c);
        }
        return out;
    }

    /** The seed of one seat's pick: the world seed, the enemy, its seat, the day and the duels played so far - all
     *  saved state, so a reload before the duel reaches the same pick; the next duel or day rolls anew. */
    public static long seed(long worldSeed, String enemy, int seat, int day, int duels) {
        return SeededRolls.seedOf(worldSeed ^ SALT, enemy, seat, day, duels);
    }

    /** The names of the gear the player wears now (slots in the table's gearSlots). */
    public static List<String> wornGear(AdventurePlayer p, Table t) {
        List<String> out = new ArrayList<>();
        if (p == null || t == null)
            return out;
        for (Long id : p.getEquippedItems()) {
            ItemData item = p.getEquippedItem(id);
            if (item != null && item.name != null && item.equipmentSlot != null && t.gearSlots.contains(item.equipmentSlot))
                out.add(item.name);
        }
        return out;
    }

    /** What one seat gets, and the words for the log. */
    public static final class SeatCopies {
        public final List<String> items = new ArrayList<>();
        public String why = "";
        public int worn;
    }

    /**
     * The copies a seat would start with now - for the duel and, ahead of it, the Alt plate and the Bounty. Only the
     * rank and the boss flag are read here; DuelScene excludes the kinds of duel (events, towns, guards, ...).
     */
    public static SeatCopies forSeat(EnemyData seat, int seatIndex) {
        SeatCopies s = new SeatCopies();
        Table t = table();
        if (t == null || seat == null) {
            s.why = "no table";
            return s;
        }
        if (seat.boss) {
            s.why = "a boss";
            return s;
        }
        if (!rankListed(t, seat)) {
            s.why = "rank " + EnemyData.tierDisplayName(seat.tier);
            return s;
        }
        Gate g = gateNow(seat);
        if (!g.open) {
            s.why = g.why;
            return s;
        }
        AdventurePlayer p = Current.player();
        List<String> worn = wornGear(p, t);
        s.worn = worn.size();
        int copies = copiesFor(t, worn.size());
        if (copies <= 0) {
            s.why = g.why + "; " + worn.size() + " gear item(s) worn (" + t.itemsForOne + " needed)";
            return s;
        }
        List<String> cands = candidates(t, worn);
        World w = world();
        long sd = seed(w == null ? 0L : w.getSeed(), seat.getName(), seatIndex, w == null ? 0 : w.getCurrentDay(),
                p.getStatistic().totalWins() + p.getStatistic().totalLoss());
        s.items.addAll(pick(t, cands, copies, sd));
        s.why = g.why + "; " + worn.size() + " gear worn, " + cands.size() + " in the pool -> " + copies + " cop"
                + (copies == 1 ? "y" : "ies") + (s.items.size() < copies ? " (" + s.items.size() + " possible)" : "");
        return s;
    }

    /** The number of copies a seat would start with now (StreakBounty's plates and Journal). */
    public static int copiesNow(EnemyData seat) {
        Table t = table();
        if (t == null || seat == null || seat.boss || !rankListed(t, seat))
            return 0; // the cheap answers first: the plates ask twice a second for every enemy on screen
        return forSeat(seat, 0).items.size();
    }

    /** The copies' item data (each a fresh copy of the catalog's item), skipping a name the catalog no longer has. */
    public static List<ItemData> items(List<String> names) {
        List<ItemData> out = new ArrayList<>();
        for (String n : names) {
            ItemData d = ItemListData.getItem(n);
            if (d == null || d.effect == null)
                System.err.println("[TFR-MirrorGear] item " + n + " not found - not copied");
            else
                out.add(d);
        }
        return out;
    }

    // ---- the notice ---------------------------------------------------------------------------------------------

    /** The one-time notice for these copies, or null when the save has seen it (and marks it seen). */
    public static String noticeOnce(String enemyName, List<String> copies) {
        World w = world();
        if (w == null || copies.isEmpty() || w.isMirrorGearNoticeShown())
            return null;
        w.setMirrorGearNoticeShown(true);
        return enemyName + " has studied your gear: it starts this duel with " + String.join(" and ", copies)
                + ". From now on, Archmages copy one item you wear (two at 5 or more) while your Notoriety and win ratio"
                + " stay high, and so do Masters you keep beating.";
    }
}
