import forge.adventure.util.SaveFileData;

import java.io.*;
import java.util.*;
import java.util.zip.InflaterInputStream;

/**
 * READ-ONLY (round 690): walkable sea tiles in a Forsaken Realms save - how many, why (road bit, terrain index), where
 * (8-way clusters, world tile coordinates, y up), and what World.sealSea() would cut off by making the road-less ones
 * solid again: walkable land components before/after and every active place whose rectangle (plus one tile) loses its
 * last tile in the main walkable body. Safe while the game is open; run it on a COPY of the save.
 *
 *   javac -cp <game jar> -d <out> WaterCensus.java
 *   java  -cp "<game jar>;<out>" WaterCensus <save.sav> [more.sav ...]
 *
 * A save loaded by a round-690+ build reports only road tiles over the sea (kept as causeways).
 */
public class WaterCensus {
    static final int COLLISION_BIT = 0x80000000, STRUCTURE_BIT = 0x40000000;
    static final int NBIOMES = 8; // base(ocean), waste, white, blue, black, red, green, player
    static final long ROAD = 1L << NBIOMES, PROAD = 1L << (NBIOMES + 1), ROADS = ROAD | PROAD;

    static int W, H;
    static long[][] bm; static int[][] tm; static boolean[][] barrier;

    public static void main(String[] args) throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        for (String path : args) run(path);
    }

    static boolean water(int x, int y) { long own = bm[x][y] & ~ROADS; return own != 0 && Long.highestOneBit(own) == 1L; } // ocean = biome 0, as World.isWaterRaw
    static boolean walk(int x, int y) { return (tm[x][y] & COLLISION_BIT) == 0 && !(barrier != null && barrier[x][y]); }

    static void run(String path) throws Exception {
        int day;
        try (ObjectInputStream ois = new ObjectInputStream(new InflaterInputStream(new FileInputStream(path)))) {
            ois.readObject();
            SaveFileData main = (SaveFileData) ois.readObject();
            SaveFileData world = main.readSubData("world");
            bm = (long[][]) world.readObject("biomeMap");
            tm = (int[][]) world.readObject("terrainMap");
            W = world.readInt("width"); H = world.readInt("height");
            day = world.readInt("dayCount");
            readPois(world.readSubData("mapPoiIds"));
            barrier = null;
            if (world.containsKey("barrierMap")) {
                BitSet set = BitSet.valueOf((long[]) world.readObject("barrierMap"));
                barrier = new boolean[W][H];
                for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) if (i / H < W) barrier[i / H][i % H] = true;
            }
        }
        int waterTiles = 0, leak = 0, leakRoad = 0, leakPRoad = 0, leakStruct = 0, leakNearLand = 0;
        Map<Integer, Integer> idx = new TreeMap<>();
        boolean[][] leakMap = new boolean[W][H], sealMap = new boolean[W][H];
        for (int x = 0; x < W; x++) for (int y = 0; y < H; y++) {
            if (!water(x, y)) continue;
            waterTiles++;
            if (!walk(x, y)) continue;
            leak++; leakMap[x][y] = true; if ((bm[x][y] & ROADS) == 0) sealMap[x][y] = true;
            if ((bm[x][y] & ROAD) != 0) leakRoad++;
            if ((bm[x][y] & PROAD) != 0) leakPRoad++;
            if ((tm[x][y] & STRUCTURE_BIT) != 0) leakStruct++;
            int v = tm[x][y] & ~(COLLISION_BIT | STRUCTURE_BIT);
            idx.merge(v, 1, Integer::sum);
            boolean nearLand = false;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
                int nx = x + dx, ny = y + dy;
                if (nx >= 0 && ny >= 0 && nx < W && ny < H && !water(nx, ny)) nearLand = true;
            }
            if (nearLand) leakNearLand++;
        }
        System.out.printf("%n== %s (day %d) ==%nsea tiles %d, WALKABLE sea tiles %d (road bit %d, player road %d, structure bit %d, next to land %d) - sealSea() would seal %d%n",
                new File(path).getName(), day, waterTiles, leak, leakRoad, leakPRoad, leakStruct, leakNearLand, leak - leakRoad - leakPRoad + bothRoads(leakMap));
        System.out.println("terrain index of the walkable sea tiles: " + idx);

        // clusters of walkable sea (8-way), with size, box and road share
        boolean[][] seen = new boolean[W][H];
        List<String> clusters = new ArrayList<>();
        for (int x = 0; x < W; x++) for (int y = 0; y < H; y++) {
            if (!leakMap[x][y] || seen[x][y]) continue;
            ArrayDeque<int[]> q = new ArrayDeque<>(); q.add(new int[]{x, y}); seen[x][y] = true;
            int n = 0, roads = 0, x0 = x, x1 = x, y0 = y, y1 = y;
            while (!q.isEmpty()) {
                int[] c = q.poll(); n++;
                if ((bm[c[0]][c[1]] & ROADS) != 0) roads++;
                x0 = Math.min(x0, c[0]); x1 = Math.max(x1, c[0]); y0 = Math.min(y0, c[1]); y1 = Math.max(y1, c[1]);
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
                    int nx = c[0] + dx, ny = c[1] + dy;
                    if (nx >= 0 && ny >= 0 && nx < W && ny < H && leakMap[nx][ny] && !seen[nx][ny]) { seen[nx][ny] = true; q.add(new int[]{nx, ny}); }
                }
            }
            // world tile y (y up) = H-1-rawY
            clusters.add(String.format("%4d tiles (%d road) world x %d-%d y %d-%d", n, roads, x0, x1, H - 1 - y1, H - 1 - y0));
        }
        System.out.println(clusters.size() + " clusters:");
        clusters.sort(Comparator.comparingInt((String s) -> -Integer.parseInt(s.trim().split(" ")[0])));
        for (int i = 0; i < Math.min(40, clusters.size()); i++) System.out.println("  " + clusters.get(i));

        // does sealing them cut any walkable LAND off from the main body? (4-way, the player's box moves 4-way-ish)
        int[] before = components(null);
        int[][] compBefore = lastComp; int mainBefore = lastMain;
        int[] after = components(sealMap); // the fix seals sea tiles without a road
        System.out.printf("walkable land components: before %d (largest %d), after sealing %d (largest %d)%n", before[0], before[1], after[0], after[1]);
        System.out.printf("land tiles outside the largest component: before %d, after %d%n", before[2], after[2]);
        int[][] compAfter = lastComp; int mainAfter = lastMain;
        // every place: can the player's box reach its rectangle (a main-body tile inside it or 1 tile round it)?
        int places = 0, lost = 0, touchesLeak = 0;
        for (SaveFileData p : pois) {
            String name = p.readString("name");
            if (p.containsKey("active") && !p.readBool("active")) continue;
            com.badlogic.gdx.math.Rectangle r = p.readRectangle("rectangle");
            places++;
            int tx0 = (int) (r.x / 16) - 1, tx1 = (int) ((r.x + r.width) / 16) + 1;
            int ty0 = (int) (r.y / 16) - 1, ty1 = (int) ((r.y + r.height) / 16) + 1;
            boolean okB = false, okA = false, leakNear = false;
            for (int tx = tx0; tx <= tx1; tx++) for (int ty = ty0; ty <= ty1; ty++) {
                int rx = tx, ry = H - 1 - ty;
                if (rx < 0 || ry < 0 || rx >= W || ry >= H) continue;
                if (compBefore[rx][ry] == mainBefore) okB = true;
                if (compAfter[rx][ry] == mainAfter) okA = true;
                if (leakMap[rx][ry]) leakNear = true;
            }
            if (leakNear) touchesLeak++;
            if (okB && !okA) { lost++; System.out.println("  CUT OFF by sealing: " + name + " at tile " + (int) (r.x / 16) + "," + (int) (r.y / 16)); }
        }
        System.out.printf("places %d, next to a walkable-sea tile %d, cut off by sealing %d%n", places, touchesLeak, lost);
    }

    static int bothRoads(boolean[][] leakMap) {
        int n = 0;
        for (int x = 0; x < W; x++) for (int y = 0; y < H; y++) if (leakMap[x][y] && (bm[x][y] & ROAD) != 0 && (bm[x][y] & PROAD) != 0) n++;
        return n;
    }

    static List<SaveFileData> pois = new ArrayList<>();
    static int[][] lastComp; static int lastMain;
    static void readPois(SaveFileData m) {
        pois.clear();
        int cx = m.readInt("numberOfChunksX"), cy = m.readInt("numberOfChunksY");
        for (int x = 0; x < cx; x++) for (int y = 0; y < cy; y++) {
            String k = "mapObjects[" + x + "][" + y + "]";
            if (!m.containsKey(k)) continue;
            int n = m.readInt(k);
            for (int i = 0; i < n; i++) pois.add(m.readSubData(k + "[" + i + "]"));
        }
    }

    /** [count, largest, landOutsideLargest] of walkable components, counting land tiles only, with sealed tiles blocked. */
    static int[] components(boolean[][] sealed) {
        int[][] comp = new int[W][H];
        List<Integer> land = new ArrayList<>();
        int id = 0;
        for (int x = 0; x < W; x++) for (int y = 0; y < H; y++) {
            if (comp[x][y] != 0 || !walk(x, y) || (sealed != null && sealed[x][y])) continue;
            id++; int n = 0;
            ArrayDeque<int[]> q = new ArrayDeque<>(); q.add(new int[]{x, y}); comp[x][y] = id;
            while (!q.isEmpty()) {
                int[] c = q.poll();
                if (!water(c[0], c[1])) n++;
                int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] e : d) {
                    int nx = c[0] + e[0], ny = c[1] + e[1];
                    if (nx < 0 || ny < 0 || nx >= W || ny >= H || comp[nx][ny] != 0 || !walk(nx, ny) || (sealed != null && sealed[nx][ny])) continue;
                    comp[nx][ny] = id; q.add(new int[]{nx, ny});
                }
            }
            land.add(n);
        }
        int count = 0, largest = 0, total = 0;
        for (int i = 0; i < land.size(); i++) { int n = land.get(i); if (n > 0) { count++; if (n > largest) { largest = n; lastMain = i + 1; } total += n; } }
        lastComp = comp;
        return new int[]{count, largest, total - largest};
    }
}
