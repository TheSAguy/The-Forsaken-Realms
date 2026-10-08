package forge.adventure.util;

import com.badlogic.gdx.graphics.Pixmap;
import forge.adventure.character.EnemySprite;
import forge.adventure.character.PlayerSprite;
import forge.adventure.data.BiomeData;
import forge.adventure.data.BiomeStructureData;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.RewardData;
import forge.adventure.data.WorldData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Round 478 - the lost-treasure hunts, a TEST version (design: docs/design/2026-10-07-lost-treasure.md). The user: "On
 * this sheet ... there is an 'X' we can use and a shovel ... Here ... are obelisks we can use ... We can place one in each
 * biome and each time you find one you will get one piece of the map. They will spawn randomly in that biome. Last 1 week
 * and then re-spawn somewhere else in the biome. So you can only find one a week. Can we build a test version" - then
 * "Each time the player 'digs' it will cost 5 shards. The player will get the shovel when they encounter their first
 * obelisk."
 * <ul>
 * <li><b>Six hunts a world</b>, one per region - the Wastes and the five colors. A region is a biome's world-gen circle
 * (World.computeBarrier's geometry), not the land's current owner: Territory Control repaints ownership every day.</li>
 * <li><b>The treasure</b> lies on a walkable tile, reachable on foot from where the player stood at seeding, well clear of
 * every place. Its map is a 30x30-tile crop of the minimap (3x3 pieces of 10x10); the treasure sits inside the CENTER
 * piece but off its middle, so eight pieces narrow it to that square and only the ninth shows the X.</li>
 * <li><b>One obelisk per region</b>, on a reachable tile in that region. Walking onto it gives a fragment (the next outer
 * piece in this world's order, the center last) and it is gone; a week after it appeared it rises again somewhere else
 * in the region, found or not - one fragment a region a week. The first fragment also brings the Spade. The maps open
 * from the inventory's Treasure Maps button (the user: "one map button that when clicked, will open a new interface
 * showing the 6 biome maps. Then you click on each of those to see the progress/map itself" - TreasureMapScene).</li>
 * <li><b>Digging</b>: the Spade (5 shards a dig, ItemData.shardsNeeded) digs where the player stands; within DIG_RADIUS
 * (2 tiles) of an unclaimed treasure the region's guardian rises and a duel starts (the user: "dig within a certain
 * radius of the hidden treasure to trigger a find"). With all nine pieces the X shows on the world map and walking onto
 * it offers the dig (the same 5 shards, and it leaves a hole too).</li>
 * <li><b>Winning</b> pays the guardian's own loot plus the treasure (gold, shards and four rare cards of the region's
 * color) and the hunt is done; a loss leaves the treasure where it is.</li>
 * </ul>
 * Test stand-ins: the guardians are existing non-boss Archmages, the treasure a placeholder purse. [TFR-Treasure] logs
 * seeding, every obelisk move and fragment, every dig and the guardian results.
 */
public final class TreasureHunt {
    private TreasureHunt() {
    }

    public static final int VERSION = 1;
    /** Biome names in the plane's world.json, one hunt each. */
    public static final String[] REGIONS = {"waste", "white", "blue", "black", "red", "green"};
    public static final String[] REGION_NAMES = {"Wastes", "White", "Blue", "Black", "Red", "Green"};
    /** sprites/treasure_obelisks.atlas regions, per REGIONS entry. */
    public static final String[] OBELISK_REGIONS = {"ObeliskWaste", "ObeliskWhite", "ObeliskBlue", "ObeliskBlack",
            "ObeliskRed", "ObeliskGreen"};
    public static final String OBELISK_ATLAS = "sprites/treasure_obelisks.atlas";
    /** Test stand-ins: existing non-boss Archmages (a boss's win cracks an equipped item - too harsh for an optional hunt). */
    private static final String[] GUARDIANS = {"Artifact Warrior", "Angel Warrior", "Storm Titan", "Bone Dragon",
            "Volcano Dragon", "Hydra"};
    private static final String[] TREASURE_COLORS = {"Colorless", "White", "Blue", "Black", "Red", "Green"};
    public static final String SPADE_ITEM = "Spade";

    public static final int FRAGMENTS = 9;
    public static final int PIECE_TILES = 10;
    public static final int CROP_TILES = PIECE_TILES * 3;
    private static final int OBELISK_DAYS = 7;
    // The user: "The player will need to dig within a certain radius of the hidden treasure to trigger a find" - 2 tiles
    // (a 5x5 patch): eight pieces narrow the treasure to a 10x10 square, about four guesses at 5 shards a dig.
    public static final int DIG_RADIUS = 2;
    // The user: "Each time the player 'digs' it will cost 5 shards" - the Spade's shardsNeeded in items.json, and the
    // whole map's Dig at the X (WorldStage.showTreasureDigDialog) charges the same.
    public static final int DIG_SHARDS = 5;
    private static final float TOUCH_RADIUS_TILES = 0.75f;
    private static final int TARGET_POI_CLEARANCE = 8, OBELISK_POI_CLEARANCE = 4, OBELISK_TARGET_CLEARANCE = 8;
    private static final int SPOT_ATTEMPTS = 6000;

    // The int[] layout of one hunt (World.getTreasureHunts()).
    public static final int H_REGION = 0, H_TX = 1, H_TY = 2, H_CROPX = 3, H_CROPY = 4, H_FRAGS = 5, H_FOUND = 6,
            H_OBX = 7, H_OBY = 8, H_OBDAY = 9, H_OBACTIVE = 10, H_ORDER = 11, H_LENGTH = H_ORDER + 8;

    private static int lastProcessedDay = Integer.MIN_VALUE;
    private static int promptedRegion = -1; // the X the player was last offered a dig on, until they step away

    public static boolean isEnabled() {
        ConfigData configData = Config.instance().getConfigData();
        return configData != null && configData.treasureHuntEnabled;
    }

    /** WorldStage.clearCache(): a load or a new world. */
    public static void resetSessionState() {
        lastProcessedDay = Integer.MIN_VALUE;
        promptedRegion = -1;
    }

    // ------------------------------------------------------------------------------------------------ the tick

    /** Called from WorldStage.onActing() while the clock runs (the player moves or waits), like ResourceSpawns.tick(). */
    public static void tick(World world, int currentDay) {
        if (!isEnabled() || world == null || WorldStage.getInstance().getPlayerSprite() == null)
            return;
        if (world.getTreasureVersion() < VERSION) {
            seed(world, currentDay);
            return;
        }
        if (currentDay != lastProcessedDay) {
            lastProcessedDay = currentDay;
            moveObelisks(world, currentDay);
            expireHoles(world, currentDay);
        }
        checkObelisks(world);
        checkX(world);
    }

    public static List<int[]> hunts(World world) {
        return world.getTreasureHunts();
    }

    public static int[] hunt(World world, int region) {
        for (int[] h : world.getTreasureHunts())
            if (h[H_REGION] == region)
                return h;
        return null;
    }

    public static int regionIndex(String nameOrBiome) {
        for (int i = 0; i < REGIONS.length; i++)
            if (REGIONS[i].equalsIgnoreCase(nameOrBiome) || REGION_NAMES[i].equalsIgnoreCase(nameOrBiome))
                return i;
        return -1;
    }

    // ------------------------------------------------------------------------------------------------ seeding

    private static void seed(World world, int currentDay) {
        long start = System.nanoTime();
        world.getTreasureHunts().clear();
        boolean[][] reachable = reachableFromPlayer(world);
        List<List<float[]>> circles = regionCircles(world);
        Random rnd = world.getRandom();
        for (int r = 0; r < REGIONS.length; r++) {
            if (circles.get(r).isEmpty()) {
                System.out.println("[TFR-Treasure] seeding: no '" + REGIONS[r] + "' biome circle in this world - no hunt there");
                continue;
            }
            int[] target = pickSpot(world, r, circles, reachable, TARGET_POI_CLEARANCE, null, 0, 0.7f);
            if (target == null) {
                System.out.println("[TFR-Treasure] seeding: no reachable spot for the " + REGION_NAMES[r] + " treasure - no hunt there");
                continue;
            }
            int[] h = new int[H_LENGTH];
            h[H_REGION] = r;
            h[H_TX] = target[0];
            h[H_TY] = target[1];
            // The treasure inside the center piece (crop tiles 10..19) but off its middle: 2..7 tiles in on each axis.
            h[H_CROPX] = target[0] - (PIECE_TILES + 2 + rnd.nextInt(PIECE_TILES - 4));
            h[H_CROPY] = target[1] - (PIECE_TILES + 2 + rnd.nextInt(PIECE_TILES - 4));
            List<Integer> outer = new ArrayList<>(java.util.Arrays.asList(0, 1, 2, 3, 5, 6, 7, 8));
            Collections.shuffle(outer, rnd);
            for (int i = 0; i < 8; i++)
                h[H_ORDER + i] = outer.get(i);
            world.getTreasureHunts().add(h);
            placeObelisk(world, h, circles, reachable, currentDay);
            System.out.println("[TFR-Treasure] seeded the " + REGION_NAMES[r] + " treasure at (" + h[H_TX] + "," + h[H_TY]
                    + "), its map crop from (" + h[H_CROPX] + "," + h[H_CROPY] + "), obelisk at (" + h[H_OBX] + "," + h[H_OBY]
                    + ") until day " + (h[H_OBDAY] + OBELISK_DAYS));
        }
        world.setTreasureVersion(VERSION);
        world.bumpTreasureStamp();
        lastProcessedDay = currentDay;
        System.out.println("[TFR-Treasure] " + world.getTreasureHunts().size() + " hunt(s) seeded on day " + currentDay
                + " in " + (System.nanoTime() - start) / 1_000_000 + " ms");
    }

    /** A new spot for this hunt's obelisk, active from today; false (and the obelisk stays hidden) when none was found. */
    private static boolean placeObelisk(World world, int[] h, List<List<float[]>> circles, boolean[][] reachable, int day) {
        int[] spot = pickSpot(world, h[H_REGION], circles, reachable, OBELISK_POI_CLEARANCE,
                new int[]{h[H_TX], h[H_TY]}, OBELISK_TARGET_CLEARANCE, 0.85f);
        h[H_OBDAY] = day;
        if (spot == null) {
            h[H_OBACTIVE] = 0;
            System.out.println("[TFR-Treasure] no reachable spot for the " + REGION_NAMES[h[H_REGION]] + " obelisk on day " + day);
            return false;
        }
        h[H_OBX] = spot[0];
        h[H_OBY] = spot[1];
        h[H_OBACTIVE] = 1;
        return true;
    }

    /** The day tick: an obelisk whose week is up rises again elsewhere in its region - found or not. */
    private static void moveObelisks(World world, int currentDay) {
        List<List<float[]>> circles = null;
        boolean[][] reachable = null;
        boolean changed = false;
        for (int[] h : world.getTreasureHunts()) {
            if (h[H_FOUND] != 0 || h[H_FRAGS] >= FRAGMENTS || currentDay < h[H_OBDAY] + OBELISK_DAYS)
                continue;
            if (circles == null) {
                circles = regionCircles(world);
                reachable = reachableFromPlayer(world);
            }
            boolean wasActive = h[H_OBACTIVE] != 0;
            if (placeObelisk(world, h, circles, reachable, currentDay))
                System.out.println("[TFR-Treasure] day " + currentDay + ": the " + REGION_NAMES[h[H_REGION]] + " obelisk "
                        + (wasActive ? "moves" : "rises again") + " - now at (" + h[H_OBX] + "," + h[H_OBY] + ") until day "
                        + (currentDay + OBELISK_DAYS));
            changed = true;
        }
        if (changed)
            world.bumpTreasureStamp();
    }

    /**
     * The six regions' circles in RAW array coordinates (y down, as World.computeBarrier reads biomeMap), one list per
     * REGIONS entry - each biome structure's circle, as world generation sized it.
     */
    static List<List<float[]>> regionCircles(World world) {
        List<List<float[]>> out = new ArrayList<>();
        for (int i = 0; i < REGIONS.length; i++)
            out.add(new ArrayList<>());
        int width = world.getWidthInTiles(), height = world.getHeightInTiles();
        for (BiomeData biome : world.getData().GetBiomes()) {
            int r = regionIndex(biome.name);
            if (r < 0 || biome.structures == null || biome.width <= 0 || biome.height <= 0)
                continue;
            int biomeWidth = (int) Math.round(biome.width * (double) width);
            int biomeHeight = (int) Math.round(biome.height * (double) height);
            int biomeXStart = (int) Math.round(biome.startPointX * (double) width);
            int biomeYStart = (int) Math.round(biome.startPointY * (double) height);
            for (BiomeStructureData structure : biome.structures) {
                float cx = biomeXStart - biomeWidth / 2f + structure.x * biomeWidth;
                float cy = biomeYStart - biomeHeight / 2f + structure.y * biomeHeight;
                float radius = Math.min(structure.width * biomeWidth, structure.height * biomeHeight) / 2f;
                if (radius > 4)
                    out.get(r).add(new float[]{cx, cy, radius});
            }
        }
        return out;
    }

    /** The region a WORLD tile belongs to: of the circles holding it, the one it sits deepest in. -1 = none. */
    static int regionOf(World world, List<List<float[]>> circles, int wx, int wy) {
        int rawY = world.getHeightInTiles() - wy - 1;
        int best = -1;
        float bestDepth = Float.MAX_VALUE;
        for (int r = 0; r < circles.size(); r++)
            for (float[] c : circles.get(r)) {
                float dx = wx - c[0], dy = rawY - c[1];
                float depth = (float) Math.sqrt(dx * dx + dy * dy) / c[2];
                if (depth <= 1f && depth < bestDepth) {
                    bestDepth = depth;
                    best = r;
                }
            }
        return best;
    }

    /** A random reachable, walkable, road-free tile deep in region r, clear of places (and of `avoid`); null if none. */
    private static int[] pickSpot(World world, int r, List<List<float[]>> circles, boolean[][] reachable, int poiClearance,
                                  int[] avoid, int avoidClearance, float depth) {
        List<float[]> own = circles.get(r);
        if (own.isEmpty())
            return null;
        Random rnd = world.getRandom();
        int width = world.getWidthInTiles(), height = world.getHeightInTiles();
        int tileSize = world.getTileSize();
        long roads = world.roadMask();
        for (int attempt = 0; attempt < SPOT_ATTEMPTS; attempt++) {
            float[] c = own.get(rnd.nextInt(own.size()));
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = Math.sqrt(rnd.nextDouble()) * c[2] * depth;
            int wx = (int) Math.round(c[0] + Math.cos(angle) * dist);
            int rawY = (int) Math.round(c[1] + Math.sin(angle) * dist);
            int wy = height - rawY - 1;
            if (wx < 3 || wy < 3 || wx >= width - 3 || wy >= height - 3)
                continue;
            if (world.isColliding(wx, wy) || (reachable != null && !reachable[wx][wy]))
                continue;
            if ((world.getBiome(wx, wy) & roads) != 0L)
                continue;
            if (regionOf(world, circles, wx, wy) != r)
                continue;
            if (avoid != null && Math.max(Math.abs(avoid[0] - wx), Math.abs(avoid[1] - wy)) < avoidClearance)
                continue;
            boolean nearPlace = false;
            for (PointOfInterest poi : world.getAllPointOfInterest()) {
                int px = (int) (poi.getPosition().x / tileSize), py = (int) (poi.getPosition().y / tileSize);
                if (Math.abs(px - wx) <= poiClearance && Math.abs(py - wy) <= poiClearance) {
                    nearPlace = true;
                    break;
                }
            }
            if (!nearPlace)
                return new int[]{wx, wy};
        }
        return null;
    }

    /** Every world tile the player can walk to from where they stand (4-neighbor flood over non-colliding tiles). */
    static boolean[][] reachableFromPlayer(World world) {
        int width = world.getWidthInTiles(), height = world.getHeightInTiles();
        boolean[][] seen = new boolean[width][height];
        WorldStage stage = WorldStage.getInstance();
        int sx = stage.playerTileX(), sy = stage.playerTileY();
        int[] start = null;
        for (int ring = 0; ring <= 6 && start == null; ring++)
            for (int dx = -ring; dx <= ring && start == null; dx++)
                for (int dy = -ring; dy <= ring && start == null; dy++)
                    if (!world.isColliding(sx + dx, sy + dy))
                        start = new int[]{sx + dx, sy + dy};
        if (start == null) {
            System.out.println("[TFR-Treasure] the player stands nowhere walkable - reachability not checked this time");
            return null;
        }
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(start);
        seen[start[0]][start[1]] = true;
        int count = 0;
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] t = queue.poll();
            count++;
            for (int[] s : steps) {
                int nx = t[0] + s[0], ny = t[1] + s[1];
                if (nx < 0 || ny < 0 || nx >= width || ny >= height || seen[nx][ny] || world.isColliding(nx, ny))
                    continue;
                seen[nx][ny] = true;
                queue.add(new int[]{nx, ny});
            }
        }
        System.out.println("[TFR-Treasure] " + count + " tile(s) reachable on foot from (" + start[0] + "," + start[1] + ")");
        return seen;
    }

    // ------------------------------------------------------------------------------------------------ obelisks and the X

    private static float[] playerCenter() {
        PlayerSprite player = WorldStage.getInstance().getPlayerSprite();
        return player == null ? null : new float[]{player.getX() + player.getWidth() / 2f, player.getY() + player.getHeight() / 2f};
    }

    private static boolean touching(World world, float[] center, int tx, int ty, float radiusTiles) {
        int ts = world.getTileSize();
        float dx = center[0] - (tx * ts + ts / 2f), dy = center[1] - (ty * ts + ts / 2f);
        float r = radiusTiles * ts;
        return dx * dx + dy * dy <= r * r;
    }

    private static void checkObelisks(World world) {
        float[] center = playerCenter();
        if (center == null)
            return;
        for (int[] h : world.getTreasureHunts()) {
            if (h[H_OBACTIVE] == 0 || h[H_FOUND] != 0 || h[H_FRAGS] >= FRAGMENTS)
                continue;
            if (!touching(world, center, h[H_OBX], h[H_OBY], TOUCH_RADIUS_TILES))
                continue;
            h[H_OBACTIVE] = 0;
            h[H_FRAGS]++;
            world.bumpTreasureStamp();
            // The user: "The player will get the shovel when they encounter their first obelisk." The maps themselves open
            // from the inventory's Treasure Maps button (InventoryScene), shown once any piece is found.
            boolean first = !Current.player().hasItem(SPADE_ITEM);
            if (first)
                Current.player().addItem(SPADE_ITEM);
            String name = REGION_NAMES[h[H_REGION]];
            String message = h[H_FRAGS] >= FRAGMENTS
                    ? "The " + name + " obelisk gives up the last piece of its map - the " + name
                    + " map is whole, and an X marks the spot on your world map."
                    : "You touch the " + name + " obelisk and a piece of an old map comes away in your hand (" + h[H_FRAGS]
                    + " of " + FRAGMENTS + ")." + (first ? " An old spade lies at its foot - you take it." : "");
            GameHUD.getInstance().addNotification(message);
            System.out.println("[TFR-Treasure] " + name + " obelisk at (" + h[H_OBX] + "," + h[H_OBY] + ") touched - fragment "
                    + h[H_FRAGS] + "/" + FRAGMENTS + (first ? " (the first: the Spade given)" : "")
                    + "; it rises again on day " + (h[H_OBDAY] + OBELISK_DAYS));
        }
    }

    /** With all nine pieces, stepping onto the X offers the dig - free - once until the player steps away. */
    private static void checkX(World world) {
        float[] center = playerCenter();
        if (center == null)
            return;
        int onX = -1;
        for (int[] h : world.getTreasureHunts())
            if (h[H_FOUND] == 0 && h[H_FRAGS] >= FRAGMENTS && touching(world, center, h[H_TX], h[H_TY], TOUCH_RADIUS_TILES))
                onX = h[H_REGION];
        if (onX < 0) {
            promptedRegion = -1;
            return;
        }
        if (onX == promptedRegion)
            return;
        promptedRegion = onX;
        WorldStage.getInstance().showTreasureDigDialog(onX, REGION_NAMES[onX]);
    }

    /** Whether any map has a piece yet (or was claimed) - the HUD and inventory Treasure Maps buttons show from then on. */
    public static boolean anyPieces(World world) {
        if (!isEnabled() || world == null)
            return false;
        for (int[] h : world.getTreasureHunts())
            if (h[H_FRAGS] > 0 || h[H_FOUND] != 0)
                return true;
        return false;
    }

    // The user: "Here are some 'Dig' graphics you can use on the overworld" - every dig leaves a hole for HOLE_DAYS, so
    // the player sees where they have already searched. {tileX, tileY, day, region}, saved with the world.
    public static final int HOLE_DAYS = 7;
    public static final String[] HOLE_REGIONS = {"HoleWaste", "HoleWhite", "HoleBlue", "HoleBlack", "HoleRed", "HoleGreen"};

    public static void addHole(World world, int tx, int ty) {
        world.getTreasureHoles().removeIf(h -> h[0] == tx && h[1] == ty);
        int region = regionOf(world, regionCircles(world), tx, ty);
        world.getTreasureHoles().add(new int[]{tx, ty, world.getCurrentDay(), Math.max(0, region)});
        world.bumpTreasureStamp();
    }

    private static void expireHoles(World world, int currentDay) {
        if (world.getTreasureHoles().removeIf(h -> currentDay >= h[2] + HOLE_DAYS))
            world.bumpTreasureStamp();
    }

    /** The X's tile for a whole, unclaimed map - what WorldStage and the map screen mark. */
    public static List<int[]> xMarks(World world) {
        List<int[]> out = new ArrayList<>();
        for (int[] h : world.getTreasureHunts())
            if (h[H_FOUND] == 0 && h[H_FRAGS] >= FRAGMENTS)
                out.add(new int[]{h[H_TX], h[H_TY], h[H_REGION]});
        return out;
    }

    // ------------------------------------------------------------------------------------------------ the dig and the guardian

    /** The Spade (console "treasure dig"): the region whose unclaimed treasure lies within DIG_RADIUS tiles, or -1. */
    public static int treasureAt(World world, int tx, int ty) {
        for (int[] h : world.getTreasureHunts())
            if (h[H_FOUND] == 0 && Math.max(Math.abs(h[H_TX] - tx), Math.abs(h[H_TY] - ty)) <= DIG_RADIUS)
                return h[H_REGION];
        return -1;
    }

    /** Raises region r's guardian and starts the duel. False when its enemy is missing (logged). */
    public static boolean startGuardian(World world, int r) {
        if (MapStage.getInstance().isInMap())
            return false;
        EnemyData base = WorldData.getEnemy(GUARDIANS[r]);
        if (base == null) {
            System.out.println("[TFR-Treasure] the " + REGION_NAMES[r] + " guardian '" + GUARDIANS[r] + "' is not in enemies.json");
            return false;
        }
        EnemyData guardian = new EnemyData(base);
        guardian.life = Math.round(guardian.life * 1.5f);
        EnemySprite sprite = new EnemySprite(guardian);
        sprite.rewards = treasureRewards(r);
        GameHUD.getInstance().addNotification("The ground gives way - the guardian of the " + REGION_NAMES[r]
                + " treasure rises to meet you!");
        System.out.println("[TFR-Treasure] dig hit the " + REGION_NAMES[r] + " treasure - guardian " + guardian.getName()
                + " (life " + guardian.life + ") rises");
        WorldStage.getInstance().startTreasureDuel(sprite, r);
        return true;
    }

    /** Test placeholder treasure: 1000 gold, 40 shards and four rare-or-mythic cards of the region's color. */
    private static RewardData[] treasureRewards(int r) {
        RewardData gold = new RewardData();
        gold.type = "gold";
        gold.count = 1000;
        gold.probability = 1;
        RewardData shards = new RewardData();
        shards.type = "shards";
        shards.count = 40;
        shards.probability = 1;
        RewardData cards = new RewardData();
        cards.type = "card";
        cards.count = 4;
        cards.probability = 1;
        cards.rarity = new String[]{"Rare", "Mythic Rare"};
        cards.colors = new String[]{TREASURE_COLORS[r]};
        return new RewardData[]{gold, shards, cards};
    }

    public static void onGuardianBeaten(int r) {
        World world = Current.world();
        int[] h = world == null ? null : hunt(world, r);
        if (h == null)
            return;
        h[H_FOUND] = 1;
        h[H_OBACTIVE] = 0;
        world.bumpTreasureStamp();
        GameHUD.getInstance().addNotification("The " + REGION_NAMES[r] + " treasure is yours!");
        System.out.println("[TFR-Treasure] the " + REGION_NAMES[r] + " guardian beaten - treasure claimed");
    }

    public static void onGuardianLost(int r) {
        GameHUD.getInstance().addNotification("The guardian drives you off - the " + REGION_NAMES[r]
                + " treasure is still buried here.");
        System.out.println("[TFR-Treasure] the " + REGION_NAMES[r] + " guardian won - the treasure stays buried");
    }

    // ------------------------------------------------------------------------------------------------ the map picture

    /** How many of the 3x3 pieces show, and which: piece index (row*3+col, row 0 at the top) -> revealed. */
    public static boolean[] revealedPieces(int[] h) {
        boolean[] shown = new boolean[9];
        int outer = Math.min(8, h[H_FRAGS]);
        for (int i = 0; i < outer; i++)
            shown[h[H_ORDER + i]] = true;
        if (h[H_FRAGS] >= FRAGMENTS)
            shown[4] = true;
        return shown;
    }

    public static final int MAP_SCALE = 3; // minimap pixels (4 a tile) enlarged to 12 a tile

    /**
     * The hunt's map: its 30x30-tile crop of the clean minimap (no fog - the map shows land never seen), enlarged with hard
     * edges and washed sepia, each covered piece a stained-paper patch, faint lines between the pieces, and - with the
     * center piece - a red X on the treasure. A new Pixmap the caller disposes.
     */
    public static Pixmap renderMap(World world, int[] h) {
        Pixmap source = world.getCleanBiomeImage();
        int mm = source == null ? 4 : source.getWidth() / Math.max(1, world.getWidthInTiles());
        int tilePx = mm * MAP_SCALE;
        int size = CROP_TILES * tilePx;
        Pixmap out = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        out.setColor(0.22f, 0.32f, 0.42f, 1f); // off the map's edge: open sea
        out.fill();
        int height = world.getHeightInTiles();
        if (source != null) {
            out.setFilter(Pixmap.Filter.NearestNeighbour);
            for (int cy = 0; cy < CROP_TILES; cy++) {
                int ty = h[H_CROPY] + (CROP_TILES - 1 - cy); // row 0 of the picture = the crop's northern edge
                if (ty < 0 || ty >= height)
                    continue;
                for (int cx = 0; cx < CROP_TILES; cx++) {
                    int tx = h[H_CROPX] + cx;
                    if (tx < 0 || tx >= world.getWidthInTiles())
                        continue;
                    out.drawPixmap(source, tx * mm, (height - ty - 1) * mm, mm, mm, cx * tilePx, cy * tilePx, tilePx, tilePx);
                }
            }
        }
        // Sepia wash: the land keeps its shapes and a hint of its color, read as old ink on paper.
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                int c = out.getPixel(x, y);
                float r = ((c >>> 24) & 0xff) / 255f, g = ((c >>> 16) & 0xff) / 255f, b = ((c >>> 8) & 0xff) / 255f;
                float lum = 0.3f * r + 0.59f * g + 0.11f * b;
                float sr = Math.min(1f, lum * 1.07f + 0.10f), sg = Math.min(1f, lum * 0.95f + 0.07f), sb = Math.min(1f, lum * 0.75f + 0.03f);
                out.drawPixel(x, y, rgba(0.55f * sr + 0.45f * r, 0.55f * sg + 0.45f * g, 0.55f * sb + 0.45f * b));
            }
        boolean[] shown = revealedPieces(h);
        int piecePx = PIECE_TILES * tilePx;
        Random speckle = new Random(31L * h[H_TX] + h[H_TY]);
        for (int p = 0; p < 9; p++) {
            if (shown[p])
                continue;
            int px = (p % 3) * piecePx, py = (p / 3) * piecePx;
            out.setColor(0.85f, 0.76f, 0.58f, 1f);
            out.fillRectangle(px, py, piecePx, piecePx);
            for (int i = 0; i < piecePx * 3; i++) {
                float k = 0.70f + speckle.nextFloat() * 0.15f;
                out.drawPixel(px + speckle.nextInt(piecePx), py + speckle.nextInt(piecePx), rgba(0.85f * k, 0.76f * k, 0.58f * k));
            }
        }
        out.setColor(0.35f, 0.25f, 0.15f, 0.6f);
        for (int i = 1; i < 3; i++) {
            out.drawLine(i * piecePx, 0, i * piecePx, size);
            out.drawLine(0, i * piecePx, size, i * piecePx);
        }
        if (shown[4]) {
            int cx = (h[H_TX] - h[H_CROPX]) * tilePx + tilePx / 2;
            int cy = (CROP_TILES - 1 - (h[H_TY] - h[H_CROPY])) * tilePx + tilePx / 2;
            int arm = tilePx;
            for (int pass = 0; pass < 2; pass++) {
                if (pass == 0)
                    out.setColor(0.15f, 0.05f, 0.05f, 1f);
                else
                    out.setColor(0.85f, 0.10f, 0.10f, 1f);
                int w = pass == 0 ? 3 : 2;
                for (int o = -w; o <= w; o++) {
                    out.drawLine(cx - arm + o, cy - arm, cx + arm + o, cy + arm);
                    out.drawLine(cx - arm + o, cy + arm, cx + arm + o, cy - arm);
                }
            }
        }
        return out;
    }

    private static int rgba(float r, float g, float b) {
        return ((int) (Math.min(1f, r) * 255) << 24) | ((int) (Math.min(1f, g) * 255) << 16) | ((int) (Math.min(1f, b) * 255) << 8) | 0xff;
    }

    // ------------------------------------------------------------------------------------------------ test cheats

    /** Console "treasure info": every hunt's state, for testing. */
    public static String describe(World world) {
        if (world.getTreasureHunts().isEmpty())
            return "No treasure hunts in this world yet (they seed on the first world-map tick)";
        StringBuilder sb = new StringBuilder();
        for (int[] h : world.getTreasureHunts())
            sb.append(sb.length() == 0 ? "" : " | ").append(REGION_NAMES[h[H_REGION]]).append(": ").append(h[H_FRAGS]).append("/9")
                    .append(h[H_FOUND] != 0 ? " FOUND" : "").append(" treasure (").append(h[H_TX]).append(",").append(h[H_TY])
                    .append(") obelisk ").append(h[H_OBACTIVE] != 0 ? "(" + h[H_OBX] + "," + h[H_OBY] + ")" : "gone")
                    .append(" until day ").append(h[H_OBDAY] + OBELISK_DAYS);
        return sb.toString();
    }

    /** Console "treasure fragments <region> <n>": set a map's fragment count (0-9), for testing. */
    public static String setFragments(World world, String regionName, int n) {
        int r = regionIndex(regionName);
        int[] h = r < 0 ? null : hunt(world, r);
        if (h == null)
            return "No hunt for '" + regionName + "' (regions: Wastes, White, Blue, Black, Red, Green)";
        h[H_FRAGS] = Math.max(0, Math.min(FRAGMENTS, n));
        world.bumpTreasureStamp();
        if (!Current.player().hasItem(SPADE_ITEM))
            Current.player().addItem(SPADE_ITEM);
        return "The " + REGION_NAMES[r] + " map now has " + h[H_FRAGS] + "/9 pieces";
    }

    /** Console "treasure here <region>": that region's treasure moved 4 tiles from the player (its map crop with it), for
     *  testing the X, the dig and the guardian without crossing the world. */
    public static String treasureHere(World world, String regionName) {
        int r = regionIndex(regionName);
        int[] h = r < 0 ? null : hunt(world, r);
        if (h == null)
            return "No hunt for '" + regionName + "'";
        WorldStage stage = WorldStage.getInstance();
        int px = stage.playerTileX(), py = stage.playerTileY();
        for (int[] d : new int[][]{{4, 0}, {-4, 0}, {0, 4}, {0, -4}, {5, 2}, {-5, -2}})
            if (!world.isColliding(px + d[0], py + d[1])) {
                int dx = h[H_TX] - h[H_CROPX], dy = h[H_TY] - h[H_CROPY];
                h[H_TX] = px + d[0];
                h[H_TY] = py + d[1];
                h[H_CROPX] = h[H_TX] - dx;
                h[H_CROPY] = h[H_TY] - dy;
                world.bumpTreasureStamp();
                return "The " + REGION_NAMES[r] + " treasure is now at (" + h[H_TX] + "," + h[H_TY] + ")";
            }
        return "No walkable tile near the player";
    }

    /** Console "treasure obelisk <region>": that region's obelisk right beside the player, for testing. */
    public static String obeliskHere(World world, String regionName) {
        int r = regionIndex(regionName);
        int[] h = r < 0 ? null : hunt(world, r);
        if (h == null)
            return "No hunt for '" + regionName + "'";
        WorldStage stage = WorldStage.getInstance();
        int px = stage.playerTileX(), py = stage.playerTileY();
        for (int[] d : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {3, 0}, {-3, 0}})
            if (!world.isColliding(px + d[0], py + d[1])) {
                h[H_OBX] = px + d[0];
                h[H_OBY] = py + d[1];
                h[H_OBACTIVE] = 1;
                h[H_OBDAY] = world.getCurrentDay();
                world.bumpTreasureStamp();
                return "The " + REGION_NAMES[r] + " obelisk is at (" + h[H_OBX] + "," + h[H_OBY] + ")";
            }
        return "No walkable tile beside the player";
    }
}
