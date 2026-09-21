import forge.adventure.util.SaveFileData;

import java.io.*;
import java.util.*;
import java.util.zip.InflaterInputStream;

/**
 * READ-ONLY census of a Forsaken Realms save's biomeMap/terrainMap index spaces (round 272).
 *
 * Round 266 made the full minimap re-bake decode every "claimed wasteland" tile (a tile carrying
 * the waste bit under a higher biome's bit) against the WASTE tables, on the theory that such a
 * tile's terrainMap value is always written in wasteland index space. Round 268 wrote down a doubt.
 * This tool answers it from real data instead of reasoning.
 *
 * Three writers produce a dual-bit tile, and they do NOT agree on the index space:
 *   claimWastelandRing()  - dual bit, value written natively in WASTE space.
 *   generateNew() Pass B  - a tile can carry waste+colour from the biome-claim OR; inside
 *                           CASTLE_KEEP_RADIUS of the colour's castle the value is in the COLOUR's
 *                           own space, outside it the colourless redirect is used (waste space).
 *   repaintBiomeAroundTown() - drops the waste bit except for "player", where the value is
 *                           translated into PLAYER space.
 *
 * The discriminator used here needs no castle positions: each biome's structure table has a
 * different LENGTH and a different internal layout, so the histogram of raw terrain indices is a
 * fingerprint of the space the value was written in. Waste has 2 terrain + 7 + 7 structures
 * (indices 3..16); green has 2 + 11 (3..13); white 2 + 3 + 7 (3..12). An index above a colour's own
 * highest is only reachable in waste space, and the SHAPE of the histogram separates the rest.
 *
 * Reads nothing but the save; opens nothing for write.
 */
public class BiomeSpace {

    // world.json's biomesNames order for The Forsaken Realms. highestOwn = terrain.length + every
    // structures[].mappingInfo.length, i.e. the last index that biome's own tables can express.
    static final String[] NAMES = {"base", "waste", "white", "blue", "black", "red", "green", "player"};
    static final int[] HIGHEST_OWN = {2, 16, 12, 15, 15, 13, 13, 16};
    static final int WASTE = 1;
    static final int ROAD_BIT_INDEX = NAMES.length; // one past the last real biome

    static final int COLLISION_BIT = 0b10000000000000000000000000000000;
    static final int STRUCTURE_BIT = 0b01000000000000000000000000000000;
    static final int TERRAIN_MASK = COLLISION_BIT | STRUCTURE_BIT;

    static int highestBiome(long bits) {
        return (int) (Math.log(Long.highestOneBit(bits)) / Math.log(2));
    }

    public static void main(String[] args) throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        String path = args[0];
        long[][] biomeMap;
        int[][] terrainMap;
        int width, height;
        try (FileInputStream fis = new FileInputStream(path);
             InflaterInputStream inf = new InflaterInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(inf)) {
            ois.readObject(); // header (embeds a Pixmap - hence GdxNativesLoader above)
            SaveFileData main = (SaveFileData) ois.readObject();
            SaveFileData world = main.readSubData("world");
            biomeMap = (long[][]) world.readObject("biomeMap");
            terrainMap = (int[][]) world.readObject("terrainMap");
            width = world.readInt("width");
            height = world.readInt("height");
            System.out.println("save: " + path);
            System.out.println("world: " + width + "x" + height + ", day " + world.readInt("dayCount"));
        }

        long roadBit = 1L << ROAD_BIT_INDEX;
        long wasteBit = 1L << WASTE;

        // [owner][index] histograms, split by whether the tile also carries the waste bit.
        int[][] dualHist = new int[NAMES.length][40];
        int[][] soleHist = new int[NAMES.length][40];
        int[] dualTotal = new int[NAMES.length];
        int[] soleTotal = new int[NAMES.length];
        int roadTiles = 0, noBits = 0;

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                long bits = biomeMap[x][y];
                if (bits == 0) { noBits++; continue; }
                int owner = highestBiome(bits);
                if (owner >= NAMES.length) { roadTiles++; continue; } // road pseudo-layer on top
                int idx = terrainMap[x][y] & ~TERRAIN_MASK;
                if (idx >= 40) idx = 39;
                boolean dual = owner != WASTE && (bits & wasteBit) != 0;
                if (dual) { dualHist[owner][idx]++; dualTotal[owner]++; }
                else { soleHist[owner][idx]++; soleTotal[owner]++; }
            }
        }

        System.out.println("\nroad-layer tiles " + roadTiles + ", tiles with no biome bit " + noBits);
        System.out.println("\n=== tile census (owner = highestBiome, dual = also carries the waste bit) ===");
        int dualAll = 0;
        for (int b = 0; b < NAMES.length; b++) {
            System.out.printf("%-7s  sole %7d   dual(waste under) %7d%n", NAMES[b], soleTotal[b], dualTotal[b]);
            if (b != WASTE) dualAll += dualTotal[b];
        }
        System.out.println("dual-bit total (what the re-bake decodes in waste space): " + dualAll);

        // The fingerprint. Waste-owned tiles are the reference distribution for "written in waste
        // space" - that is what claimWastelandRing() and the colourless redirect both produce.
        System.out.println("\n=== raw terrain index histograms ===");
        System.out.println("(reference: waste-owned tiles, i.e. values known to be in waste space)");
        printHist("waste sole", soleHist[WASTE], HIGHEST_OWN[WASTE]);
        for (int b = 2; b < NAMES.length; b++) {
            if (dualTotal[b] == 0 && soleTotal[b] == 0) continue;
            System.out.println();
            printHist(NAMES[b] + " DUAL", dualHist[b], HIGHEST_OWN[b]);
            printHist(NAMES[b] + " sole", soleHist[b], HIGHEST_OWN[b]);
        }

        // An index above the owner's own highest cannot have been written in the owner's space.
        System.out.println("\n=== indices only reachable in waste space (idx > the owner's own highest) ===");
        for (int b = 2; b < NAMES.length; b++) {
            int overDual = 0, overSole = 0;
            for (int i = HIGHEST_OWN[b] + 1; i < 40; i++) { overDual += dualHist[b][i]; overSole += soleHist[b][i]; }
            double refShare = shareAbove(soleHist[WASTE], HIGHEST_OWN[b], soleTotal[WASTE]);
            System.out.printf("%-7s (own max %2d): dual %6d/%6d = %5.2f%%  sole %6d/%6d = %5.2f%%"
                            + "   waste-space reference %5.2f%%%n",
                    NAMES[b], HIGHEST_OWN[b], overDual, dualTotal[b], pct(overDual, dualTotal[b]),
                    overSole, soleTotal[b], pct(overSole, soleTotal[b]), refShare * 100);
            if (dualTotal[b] > 0 && refShare > 0.005) {
                double colourSpaceShare = 1 - (pct(overDual, dualTotal[b]) / 100.0) / refShare;
                System.out.printf("         -> estimated %.1f%% of %s's dual-bit tiles hold a value NOT in waste space%n",
                        Math.max(0, colourSpaceShare) * 100, NAMES[b]);
            }
        }

        // Shape test. World-gen's overlap sits in ONE disc no wider than CASTLE_KEEP_RADIUS=20
        // around the colour's castle; daily expansion produces rings and fronts far from it. A
        // tight disc therefore means colour-space content, which is what the histograms above
        // already imply for white.
        System.out.println("\n=== dual-bit footprint per owner (structure tiles are the only ones that can mis-draw) ===");
        for (int b = 2; b < NAMES.length; b++) {
            long sx = 0, sy = 0; int n = 0, structs = 0;
            int minX = Integer.MAX_VALUE, maxX = -1, minY = Integer.MAX_VALUE, maxY = -1;
            for (int x = 0; x < width; x++)
                for (int y = 0; y < height; y++) {
                    long bits = biomeMap[x][y];
                    if (bits == 0 || highestBiome(bits) != b || (bits & wasteBit) == 0) continue;
                    sx += x; sy += y; n++;
                    if ((terrainMap[x][y] & STRUCTURE_BIT) != 0) structs++;
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            if (n == 0) { System.out.printf("%-7s  none%n", NAMES[b]); continue; }
            int cxr = (int) (sx / n), cyr = (int) (sy / n);
            double far = 0; int within20 = 0;
            for (int x = 0; x < width; x++)
                for (int y = 0; y < height; y++) {
                    long bits = biomeMap[x][y];
                    if (bits == 0 || highestBiome(bits) != b || (bits & wasteBit) == 0) continue;
                    double d = Math.sqrt((x - cxr) * (x - cxr) + (double) (y - cyr) * (y - cyr));
                    far = Math.max(far, d);
                    if (d <= 20) within20++;
                }
            // Round 272's one residual hole: a WASTE-owned tile inside the colour's own keep radius.
            // Daily expansion could later claim such a tile, writing waste space inside the radius that
            // holdsWasteSpaceValue() reads as colour space. Count how many exist to be claimed. The
            // dual-bit centroid stands in for the castle here - they are within a few tiles.
            int wasteInsideKeep = 0;
            for (int x = Math.max(0, cxr - 20); x <= Math.min(width - 1, cxr + 20); x++)
                for (int y = Math.max(0, cyr - 20); y <= Math.min(height - 1, cyr + 20); y++) {
                    if ((x - cxr) * (x - cxr) + (y - cyr) * (y - cyr) > 400) continue;
                    long bits = biomeMap[x][y];
                    if (bits != 0 && highestBiome(bits) == WASTE) wasteInsideKeep++;
                }
            System.out.printf("%-7s  n=%5d  structures=%4d  centroid=(%3d,%3d) raw-y  box=%dx%d  farthest=%.0f  within 20 of centroid=%d (%.0f%%)  waste-owned tiles inside that keep=%d%n",
                    NAMES[b], n, structs, cxr, cyr, maxX - minX + 1, maxY - minY + 1, far, within20,
                    100.0 * within20 / n, wasteInsideKeep);
        }

        // Where are they? A dual-bit disc around a castle is world-gen's own overlap (colour space
        // inside CASTLE_KEEP_RADIUS=20); rings and fronts are daily expansion (waste space).
        System.out.println("\n=== dual-bit density, 10x10-tile cells (digit = tens of percent of the cell) ===");
        int cell = 10;
        StringBuilder sb = new StringBuilder();
        for (int cy = height / cell - 1; cy >= 0; cy--) {
            for (int cx = 0; cx < width / cell; cx++) {
                int n = 0;
                for (int x = cx * cell; x < (cx + 1) * cell; x++)
                    for (int y = cy * cell; y < (cy + 1) * cell; y++) {
                        long bits = biomeMap[x][y];
                        int owner = bits == 0 ? -1 : highestBiome(bits);
                        if (owner > WASTE && owner < NAMES.length && (bits & (1L << WASTE)) != 0) n++;
                    }
                int tenth = n * 10 / (cell * cell);
                sb.append(n == 0 ? '.' : (tenth == 0 ? '-' : (char) ('0' + Math.min(9, tenth))));
            }
            sb.append('\n');
        }
        System.out.print(sb);
    }

    static double pct(int a, int b) { return b == 0 ? 0 : 100.0 * a / b; }

    static double shareAbove(int[] hist, int limit, int total) {
        if (total == 0) return 0;
        int over = 0;
        for (int i = limit + 1; i < hist.length; i++) over += hist[i];
        return (double) over / total;
    }

    static void printHist(String label, int[] hist, int ownMax) {
        int total = 0;
        for (int v : hist) total += v;
        StringBuilder sb = new StringBuilder(String.format("%-14s n=%-7d ", label, total));
        for (int i = 0; i <= 17; i++) {
            if (i == ownMax + 1) sb.append("| ");
            sb.append(String.format("%d:%s ", i, total == 0 ? "-" : String.format("%.1f%%", 100.0 * hist[i] / total)));
        }
        System.out.println(sb);
    }
}
