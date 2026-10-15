package forge.adventure.world;

import com.badlogic.gdx.math.Vector2;
import forge.adventure.data.BiomeData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.util.TownRestoration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Round 626 - the History replay. The user: "The game Civilization 3 had a replay feature that showed the game from turn 1
 * and how cities grew on the map ... a button on the Mini-map that you click and it will play a history timeline of how
 * towns were captured and territory spread."
 * <p>
 * The world keeps no past - its biome map is repainted in place every day - so this records one: the owner of every tile
 * and every town on the day the history starts, then once a day only what changed since (any cause: growth rings, captures,
 * raids, restorations). HistoryScene plays it back. A save from before this round starts its history the first time it is
 * loaded; nothing earlier can be rebuilt.
 * <ul>
 * <li>A tile's owner is the strongest owning biome on it: the player, then the five colors, then the wasteland; a tile with
 * none of them is sea. Road bits are not owners.</li>
 * <li>Towns are keyed by their tile - a capture re-keys the POI id (transformInto()), never its place. Owner and kind (town
 * or capital) per town, by the same rule as TerritoryControl.getTownCounts(): a restored town is the player's, otherwise
 * the town's color noun decides.</li>
 * <li>Saved inside the world's SaveFileData as one deflated blob ("replayHistory"); about 4 bytes a changed tile.</li>
 * </ul>
 */
public final class WorldHistory {
    public static final byte SEA = 0, WASTE = 1, WHITE = 2, BLUE = 3, BLACK = 4, RED = 5, GREEN = 6, PLAYER = 7;
    /** Owner code of a town that is not on the map on that day (not there yet, or gone). */
    public static final int NONE = 0;
    public static final int KIND_CAPITAL = 8;
    /** Round 635 (the user: "add the 5 AI Castles on there ... a slightly different symbol. Since they can fall and is a
     *  major event"): a color's castle - its color while the color stands, WASTE once it has fallen. Not a town: the
     *  legend's town counts leave castles out. */
    public static final int KIND_CASTLE = 16;
    /** A town record is townIndex << TOWN_SHIFT | kind | owner (format 1 packed townIndex << 4, before castles). */
    private static final int TOWN_SHIFT = 5, CODE_MASK = 31;
    public static final String[] NAMES = {"Sea", "Unclaimed", "White", "Blue", "Black", "Red", "Green", "You"};
    private static final int FORMAT = 2;
    private static final String[] BIOME_NAMES = {null, "waste", "white", "blue", "black", "red", "green", "player"};
    private static final String[][] TOWN_NOUNS = {{"Plains", "2"}, {"Island", "3"}, {"Swamp", "4"}, {"Mountain", "5"},
            {"Forest", "6"}};

    /** One town's place and its latest name (the Capitol takes its new name; captions use this one). */
    public static final class Town {
        public final int x, row;
        public String name;

        Town(int x, int row, String name) {
            this.x = x;
            this.row = row;
            this.name = name;
        }
    }

    /** What changed on one day: tiles packed as index << 3 | owner, towns as townIndex << 5 | kind | owner. */
    public static final class Record {
        public final int day;
        int[] tiles = new int[16];
        int tileCount;
        int[] towns = new int[4];
        int townCount;

        Record(int day) {
            this.day = day;
        }

        void addTile(int packed) {
            if (tileCount == tiles.length)
                tiles = java.util.Arrays.copyOf(tiles, tiles.length * 2);
            tiles[tileCount++] = packed;
        }

        void addTown(int packed) {
            if (townCount == towns.length)
                towns = java.util.Arrays.copyOf(towns, towns.length * 2);
            towns[townCount++] = packed;
        }
    }

    /** A line for the replay's caption: one of the player's milestones. */
    public static final class Milestone {
        public final int day;
        public final String text;

        Milestone(int day, String text) {
            this.day = day;
            this.text = text;
        }
    }

    public final int width, height, startDay;
    private final byte[] base;
    private final List<Town> towns = new ArrayList<>();
    private final Map<Integer, Integer> townIndex = new HashMap<>();
    private final List<Record> records = new ArrayList<>();
    // The state after the last record - what the next record() diffs against.
    private final byte[] current;
    private int[] currentTown = new int[64];

    private WorldHistory(int width, int height, int startDay, byte[] base) {
        this.width = width;
        this.height = height;
        this.startDay = startDay;
        this.base = base;
        this.current = base.clone();
    }

    /** The first page: today's tiles and towns, as day {@code day}. */
    static WorldHistory start(World world, int day) {
        byte[] tiles = tileOwners(world);
        if (tiles == null)
            return null;
        WorldHistory history = new WorldHistory(world.getWidthInTiles(), world.getHeightInTiles(), day, tiles);
        Record first = new Record(day);
        history.scanTowns(world, first);
        history.records.add(first);
        System.out.println("[TFR-History] started on day " + day + ": " + history.towns.size() + " towns, "
                + history.landTiles() + " land tiles");
        return history;
    }

    /** Appends what changed since the last record, as day {@code day} (merged into it when it is the same day). */
    void record(World world, int day) {
        long t0 = System.nanoTime();
        byte[] now = tileOwners(world);
        if (now == null || now.length != current.length)
            return;
        Record last = records.isEmpty() ? null : records.get(records.size() - 1);
        Record rec = last != null && last.day == day ? last : new Record(day);
        int tilesBefore = rec.tileCount, townsBefore = rec.townCount;
        for (int i = 0; i < now.length; i++) {
            if (now[i] != current[i]) {
                current[i] = now[i];
                rec.addTile(i << 3 | now[i]);
            }
        }
        scanTowns(world, rec);
        int tiles = rec.tileCount - tilesBefore, townChanges = rec.townCount - townsBefore;
        if (rec != last && (rec.tileCount > 0 || rec.townCount > 0))
            records.add(rec);
        if (tiles > 0 || townChanges > 0)
            System.out.println("[TFR-History] day " + day + ": " + tiles + " tiles, " + townChanges + " towns changed ("
                    + records.size() + " days recorded, " + (System.nanoTime() - t0) / 1_000_000 + "ms)");
    }

    private void scanTowns(World world, Record rec) {
        BitSet seen = new BitSet(towns.size());
        int tileSize = world.getTileSize();
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            int code = townCode(world, poi);
            if (code < 0)
                continue;
            Vector2 pos = poi.getPosition();
            int x = Math.max(0, Math.min(width - 1, (int) (pos.x / tileSize)));
            int row = Math.max(0, Math.min(height - 1, height - 1 - (int) (pos.y / tileSize)));
            Integer idx = townIndex.get(x * height + row);
            String name = poi.getDisplayName();
            if (idx == null) {
                idx = towns.size();
                towns.add(new Town(x, row, name));
                townIndex.put(x * height + row, idx);
                if (idx >= currentTown.length)
                    currentTown = java.util.Arrays.copyOf(currentTown, currentTown.length * 2);
            } else if (name != null && !name.equals(towns.get(idx).name)) {
                towns.get(idx).name = name;
            }
            seen.set(idx);
            if (currentTown[idx] != code) {
                currentTown[idx] = code;
                rec.addTown(idx << TOWN_SHIFT | code);
            }
        }
        for (int idx = 0; idx < towns.size(); idx++) {
            if (seen.get(idx) || currentTown[idx] == NONE)
                continue;
            // A castle whose POI left the map stays on it as fallen, if its color is defeated (round 635).
            int code = (currentTown[idx] & KIND_CASTLE) != 0 && world.isColorDefeated(castleColor(towns.get(idx).name))
                    ? WASTE | KIND_CASTLE : NONE;
            if (currentTown[idx] != code) {
                currentTown[idx] = code;
                rec.addTown(idx << TOWN_SHIFT | code);
            }
        }
    }

    /** "white" for "White Castle" - the color word World.isColorDefeated() takes; null for anything else. */
    static String castleColor(String name) {
        if (name == null || !name.endsWith(" Castle"))
            return null;
        String color = name.substring(0, name.length() - " Castle".length()).toLowerCase(java.util.Locale.ROOT);
        for (int c = WHITE; c <= GREEN; c++)
            if (NAMES[c].toLowerCase(java.util.Locale.ROOT).equals(color))
                return color;
        return null;
    }

    /** Owner | kind of a town POI or a color's castle, or -1 for anything that is not a town anyone can hold. */
    static int townCode(World world, PointOfInterest poi) {
        String type = poi.getData().type;
        if ("castle".equals(type)) { // round 635: the five color castles - their color, or WASTE once fallen
            String color = castleColor(poi.getData().name);
            if (color == null)
                return -1;
            if (world.isColorDefeated(color))
                return WASTE | KIND_CASTLE;
            for (int c = WHITE; c <= GREEN; c++)
                if (NAMES[c].equalsIgnoreCase(color))
                    return c | KIND_CASTLE;
            return -1;
        }
        if (!"town".equals(type) && !"capital".equals(type))
            return -1;
        int kind = "capital".equals(type) ? KIND_CAPITAL : 0;
        String name = poi.getData().name;
        WorldSave save = WorldSave.getCurrentSave();
        if ((save != null && TownRestoration.isTownRestored(save.peekPointOfInterestChanges(poi.getID())))
                || (name != null && name.startsWith("Player")))
            return PLAYER | kind;
        if (name == null)
            return -1;
        if (name.startsWith("Waste Town"))
            return WASTE | kind;
        for (String[] noun : TOWN_NOUNS)
            if (name.startsWith(noun[0]))
                return Integer.parseInt(noun[1]) | kind;
        return -1;
    }

    /** Every tile's owner today, row 0 at the top of the map (biomeMap's own layout). Null without a biome map. */
    static byte[] tileOwners(World world) {
        long[][] map = world.biomeMapForHistory();
        if (map == null)
            return null;
        int w = world.getWidthInTiles(), h = world.getHeightInTiles();
        long[] bit = new long[8];
        List<BiomeData> biomes = world.getData().GetBiomes();
        for (int i = 0; i < biomes.size() && i < 62; i++)
            for (int c = 1; c < BIOME_NAMES.length; c++)
                if (BIOME_NAMES[c].equalsIgnoreCase(biomes.get(i).name))
                    bit[c] |= 1L << i;
        byte[] out = new byte[w * h];
        for (int x = 0; x < w && x < map.length; x++) {
            long[] column = map[x];
            for (int row = 0; row < h && row < column.length; row++) {
                long v = column[row];
                byte c = SEA;
                if ((v & bit[PLAYER]) != 0)
                    c = PLAYER;
                else if ((v & bit[WHITE]) != 0)
                    c = WHITE;
                else if ((v & bit[BLUE]) != 0)
                    c = BLUE;
                else if ((v & bit[BLACK]) != 0)
                    c = BLACK;
                else if ((v & bit[RED]) != 0)
                    c = RED;
                else if ((v & bit[GREEN]) != 0)
                    c = GREEN;
                else if ((v & bit[WASTE]) != 0)
                    c = WASTE;
                out[row * w + x] = c;
            }
        }
        return out;
    }

    private int landTiles() {
        int n = 0;
        for (byte b : base)
            if (b != SEA)
                n++;
        return n;
    }

    public int lastDay() {
        return records.isEmpty() ? startDay : records.get(records.size() - 1).day;
    }

    public List<Town> towns() {
        return towns;
    }

    /** The player's milestones, oldest first: towns gained and lost, the Capitol raised. */
    public List<Milestone> milestones() {
        List<Milestone> out = new ArrayList<>();
        int[] owner = new int[towns.size()];
        for (int r = 0; r < records.size(); r++) {
            Record rec = records.get(r);
            for (int i = 0; i < rec.townCount; i++) {
                int packed = rec.towns[i];
                int idx = packed >>> TOWN_SHIFT, code = packed & CODE_MASK;
                int before = owner[idx];
                owner[idx] = code;
                if (r == 0)
                    continue; // the first page is where things stood, not news
                if ((code & KIND_CASTLE) != 0 || (before & KIND_CASTLE) != 0) {
                    // Round 635: a castle's fall is everyone's news, not only the player's.
                    int was = before & 7;
                    if ((code & 7) == WASTE && was >= WHITE && was <= GREEN)
                        out.add(new Milestone(rec.day, "The " + towns.get(idx).name + " fell - " + NAMES[was]
                                + " is defeated"));
                    continue;
                }
                String text = milestoneText(towns.get(idx).name, before, code);
                if (text != null)
                    out.add(new Milestone(rec.day, text));
            }
        }
        return out;
    }

    private static String milestoneText(String name, int before, int after) {
        int was = before & 7, now = after & 7;
        boolean capitalNow = (after & KIND_CAPITAL) != 0, capitalBefore = (before & KIND_CAPITAL) != 0;
        if (name == null)
            name = "a town";
        if (now == PLAYER && was == PLAYER)
            return capitalNow && !capitalBefore ? "You raised the Capitol at " + name : null;
        if (now == PLAYER) {
            if (capitalNow)
                return "You raised the Capitol at " + name;
            if (before == NONE || was == WASTE)
                return "You restored " + name;
            return "You took " + name + " from " + NAMES[was];
        }
        if (was == PLAYER) {
            if (after == NONE)
                return name + " was lost";
            if (now == WASTE)
                return name + " fell to ruin";
            return NAMES[now] + " took " + name + " from you";
        }
        return null;
    }

    /** A playhead: the state on one day, moved forward record by record (back = from the first page again). */
    public final class Cursor {
        public final byte[] tiles = new byte[base.length];
        public final int[] town = new int[towns.size()];
        public final int[] tileCounts = new int[8];
        public final int[] townCounts = new int[8];
        public int day;
        private int next;

        Cursor() {
            reset();
        }

        public void reset() {
            System.arraycopy(base, 0, tiles, 0, base.length);
            java.util.Arrays.fill(town, NONE);
            java.util.Arrays.fill(tileCounts, 0);
            java.util.Arrays.fill(townCounts, 0);
            for (byte b : tiles)
                tileCounts[b]++;
            next = 0;
            day = startDay;
            advanceTo(startDay, null);
        }

        /** Moves to {@code target} (or later than now, only); changed tile indexes go to {@code changedTiles}. Returns
         *  whether any town changed. */
        public boolean advanceTo(int target, java.util.function.IntConsumer changedTiles) {
            boolean townChanged = false;
            while (next < records.size() && records.get(next).day <= target) {
                Record rec = records.get(next++);
                for (int i = 0; i < rec.tileCount; i++) {
                    int idx = rec.tiles[i] >>> 3;
                    byte c = (byte) (rec.tiles[i] & 7);
                    tileCounts[tiles[idx]]--;
                    tiles[idx] = c;
                    tileCounts[c]++;
                    if (changedTiles != null)
                        changedTiles.accept(idx);
                }
                for (int i = 0; i < rec.townCount; i++) {
                    int idx = rec.towns[i] >>> TOWN_SHIFT, code = rec.towns[i] & CODE_MASK;
                    if (idx >= town.length)
                        continue;
                    if (town[idx] != NONE && (town[idx] & KIND_CASTLE) == 0) // a castle is not a town (round 635)
                        townCounts[town[idx] & 7]--;
                    town[idx] = code;
                    if (code != NONE && (code & KIND_CASTLE) == 0)
                        townCounts[code & 7]++;
                    townChanged = true;
                }
            }
            day = Math.max(day, target);
            return townChanged;
        }
    }

    public Cursor cursor() {
        return new Cursor();
    }

    // ---- the save ----

    byte[] toBytes() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            out.writeInt(FORMAT);
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(startDay);
            out.write(base);
            out.writeInt(towns.size());
            for (Town t : towns) {
                out.writeShort(t.x);
                out.writeShort(t.row);
                out.writeUTF(t.name == null ? "" : t.name);
            }
            out.writeInt(records.size());
            for (Record rec : records) {
                out.writeInt(rec.day);
                out.writeInt(rec.tileCount);
                for (int i = 0; i < rec.tileCount; i++)
                    out.writeInt(rec.tiles[i]);
                out.writeInt(rec.townCount);
                for (int i = 0; i < rec.townCount; i++)
                    out.writeInt(rec.towns[i]);
            }
        }
        return bytes.toByteArray();
    }

    static WorldHistory fromBytes(byte[] blob) throws IOException {
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(blob)))) {
            int format = in.readInt();
            if (format != FORMAT && format != 1)
                throw new IOException("history format " + format + ", this build reads 1 and " + FORMAT);
            int w = in.readInt(), h = in.readInt(), start = in.readInt();
            byte[] base = new byte[w * h];
            in.readFully(base);
            WorldHistory history = new WorldHistory(w, h, start, base);
            int townCount = in.readInt();
            for (int i = 0; i < townCount; i++) {
                int x = in.readShort(), row = in.readShort();
                String name = in.readUTF();
                history.towns.add(new Town(x, row, name.isEmpty() ? null : name));
                history.townIndex.put(x * h + row, i);
            }
            history.currentTown = new int[Math.max(64, townCount * 2)];
            int recordCount = in.readInt();
            for (int r = 0; r < recordCount; r++) {
                Record rec = new Record(in.readInt());
                int n = in.readInt();
                rec.tiles = new int[Math.max(1, n)];
                for (int i = 0; i < n; i++)
                    rec.tiles[i] = in.readInt();
                rec.tileCount = n;
                n = in.readInt();
                rec.towns = new int[Math.max(1, n)];
                for (int i = 0; i < n; i++) {
                    int packed = in.readInt();
                    // Format 1 (round 626) packed townIndex << 4 with no castles; re-pack it as this build does.
                    rec.towns[i] = format == 1 ? (packed >>> 4) << TOWN_SHIFT | (packed & 15) : packed;
                }
                rec.townCount = n;
                history.records.add(rec);
                // Bring the live state up to the end, for the next day's diff.
                for (int i = 0; i < rec.tileCount; i++)
                    history.current[rec.tiles[i] >>> 3] = (byte) (rec.tiles[i] & 7);
                for (int i = 0; i < rec.townCount; i++)
                    history.currentTown[rec.towns[i] >>> TOWN_SHIFT] = rec.towns[i] & CODE_MASK;
            }
            return history;
        }
    }
}
