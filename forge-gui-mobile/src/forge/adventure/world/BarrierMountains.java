package forge.adventure.world;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import forge.adventure.util.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Round 296 (user: "full Resolution"): the barrier's mountains drawn from {@link #SHEET} at the art's own resolution -
 * 48 pixels per world tile, three times the 16-pixel tiles everything else is drawn in - over the terrain, under
 * every actor. The art is by Sythian Bard (standalone-packaging/CREDITS.md).
 * <p>
 * THE SHEET: any transparent PNG of mountain groups. Every separate group of pixels on it becomes one piece, so a
 * group can be redrawn, added or removed without touching anything else - only the scale (48 px per tile) is fixed.
 * A piece's size in tiles is its size in pixels / 48, rounded up.
 * <p>
 * THE PACKING (tuned with the user - their first look: "too many gaps"): a carpet of the one-tile groups on every
 * barrier tile, a second carpet half a tile across and down, a third half a tile across, then the big groups on top
 * - two layers, only where the whole piece sits on barrier - and a pass of the middle-sized ones; drawn by the
 * pieces' bottom edges, south last, so every peak overlaps the ones behind it. Deterministic from the world's seed,
 * computed when a world is first drawn - nothing is saved.
 * <p>
 * FOG: each piece is drawn per tile of the fog - nothing over unexplored ground, remembered ground dimmed exactly as
 * World.hazeTile() dims the terrain (55% black) - so mountains appear as the land around them does. Walls and
 * collision are the barrier's own and do not depend on any of this.
 */
public final class BarrierMountains {
    public static final String SHEET = "world/structures/barrier_mountains.png";
    /** The sheet's scale: pixels per world tile. */
    private static final int ART_TILE = 48;
    /** A group with fewer opaque pixels than this is a speck, not a mountain. */
    private static final int MIN_PIECE_PIXELS = 800;
    /** World.hazeTile() lays 55% black over a remembered tile; the same, as a tint. */
    private static final float HAZE = 0.45f;
    private static final int PACK_WIDTH = 512;
    private static final int PACK_GAP = 2;

    // --- the pieces (read once per run) ---
    private static boolean sheetRead;
    private static Pixmap packedPixmap; // CPU side until the first draw uploads it
    private static Texture texture;
    private static int[] pieceU = new int[0], pieceV = new int[0], pieceW = new int[0], pieceH = new int[0];
    private static int[] carpet = new int[0], bigs = new int[0], mids = new int[0];
    private static int minimapColor = 0x8c7b64ff;
    private static int floorColor = 0x463c34ff;

    // --- the placements of the world being drawn ---
    private static World placedWorld;
    private static int placedVersion = Integer.MIN_VALUE;
    private static int chunkSize, chunkCols, chunkRows;
    private static int[][] chunkPlacements = new int[0][]; // per chunk: (piece, x, y) triplets in world texels, y up
    private static int[] chunkCounts = new int[0];
    private static int mergedX = Integer.MIN_VALUE, mergedY = Integer.MIN_VALUE, mergedCount;
    private static int[] merged = new int[0];

    private BarrierMountains() {
    }

    /** Is there a mountain sheet with at least one piece? (Reads it on first use.) */
    public static boolean isAvailable() {
        readSheet();
        return pieceW.length > 0;
    }

    /** RGBA8888: the art's average colour, for a barrier tile on the minimap. */
    public static int minimapColor() {
        readSheet();
        return minimapColor;
    }

    /** RGBA8888: the art's shadow tone, laid over the ground of a barrier tile surrounded by barrier. */
    public static int floorColor() {
        readSheet();
        return floorColor;
    }

    private static synchronized void readSheet() {
        if (sheetRead)
            return;
        sheetRead = true;
        Pixmap sheet = null;
        try {
            FileHandle file = Config.instance().getFile(SHEET);
            if (file == null || !file.exists()) {
                System.out.println("[TFR-BarrierArt] no " + SHEET + " - the barrier keeps its tile art");
                return;
            }
            Pixmap loaded = new Pixmap(file);
            sheet = new Pixmap(loaded.getWidth(), loaded.getHeight(), Pixmap.Format.RGBA8888);
            sheet.setBlending(Pixmap.Blending.None);
            sheet.drawPixmap(loaded, 0, 0);
            loaded.dispose();
            cutPieces(sheet);
        } catch (Exception e) {
            System.err.println("[TFR-BarrierArt] could not read " + SHEET + ": " + e);
            pieceW = new int[0];
        } finally {
            if (sheet != null)
                sheet.dispose();
        }
    }

    /** Every 8-connected group of opaque pixels becomes a piece, copied alone (no neighbour's pixels) into a packed
     *  sheet; the pieces are sorted into carpet (one tile), middle (two or three tiles) and big (four or more). */
    private static void cutPieces(Pixmap sheet) {
        int w = sheet.getWidth(), h = sheet.getHeight();
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[w * h];
        List<int[]> groups = new ArrayList<>(); // {x0, y0, x1, y1, pixelCount, pixel indices...}
        long sumR = 0, sumG = 0, sumB = 0, opaque = 0;
        for (int start = 0; start < w * h; start++) {
            if (seen[start] || alpha(sheet.getPixel(start % w, start / w)) <= 20)
                continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            int x0 = w, y0 = h, x1 = -1, y1 = -1;
            while (head < tail) {
                int p = queue[head++];
                int px = p % w, py = p / w;
                x0 = Math.min(x0, px); x1 = Math.max(x1, px);
                y0 = Math.min(y0, py); y1 = Math.max(y1, py);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = px + dx, ny = py + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h)
                            continue;
                        int n = ny * w + nx;
                        if (!seen[n] && alpha(sheet.getPixel(nx, ny)) > 20) {
                            seen[n] = true;
                            queue[tail++] = n;
                        }
                    }
                }
            }
            if (tail < MIN_PIECE_PIXELS)
                continue;
            int[] group = new int[5 + tail];
            group[0] = x0; group[1] = y0; group[2] = x1; group[3] = y1; group[4] = tail;
            System.arraycopy(queue, 0, group, 5, tail);
            groups.add(group);
            for (int i = 0; i < tail; i++) {
                int c = sheet.getPixel(queue[i] % w, queue[i] / w);
                if (alpha(c) > 200) {
                    sumR += (c >>> 24) & 0xff; sumG += (c >>> 16) & 0xff; sumB += (c >>> 8) & 0xff;
                    opaque++;
                }
            }
        }
        if (groups.isEmpty())
            return;
        // Shelf-pack the pieces, tallest first, each alone in its own cell.
        groups.sort((a, b) -> (b[3] - b[1]) - (a[3] - a[1]));
        int n = groups.size();
        int[] u = new int[n], v = new int[n], pw = new int[n], ph = new int[n];
        int shelfX = 0, shelfY = 0, shelfH = 0;
        for (int i = 0; i < n; i++) {
            int[] g = groups.get(i);
            pw[i] = g[2] - g[0] + 1;
            ph[i] = g[3] - g[1] + 1;
            if (shelfX + pw[i] > PACK_WIDTH) {
                shelfX = 0;
                shelfY += shelfH + PACK_GAP;
                shelfH = 0;
            }
            u[i] = shelfX;
            v[i] = shelfY;
            shelfX += pw[i] + PACK_GAP;
            shelfH = Math.max(shelfH, ph[i]);
        }
        Pixmap packed = new Pixmap(PACK_WIDTH, shelfY + shelfH, Pixmap.Format.RGBA8888);
        packed.setBlending(Pixmap.Blending.None);
        packed.setColor(0, 0, 0, 0);
        packed.fill();
        for (int i = 0; i < n; i++) {
            int[] g = groups.get(i);
            for (int k = 5; k < g.length; k++) {
                int sx = g[k] % w, sy = g[k] / w;
                packed.drawPixel(u[i] + sx - g[0], v[i] + sy - g[1], sheet.getPixel(sx, sy));
            }
        }
        packedPixmap = packed;
        pieceU = u; pieceV = v; pieceW = pw; pieceH = ph;
        List<Integer> carpetList = new ArrayList<>(), bigList = new ArrayList<>(), midList = new ArrayList<>();
        StringBuilder sizes = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int area = tilesW(i) * tilesH(i);
            if (area == 1)
                carpetList.add(i);
            else if (area >= 4)
                bigList.add(i);
            else
                midList.add(i);
            sizes.append(sizes.length() > 0 ? " " : "").append(tilesW(i)).append('x').append(tilesH(i));
        }
        if (carpetList.isEmpty()) // no one-tile group on the sheet: the smallest piece carpets instead
            carpetList.add(n - 1);
        carpet = carpetList.stream().mapToInt(Integer::intValue).toArray();
        bigs = bigList.stream().mapToInt(Integer::intValue).toArray();
        mids = midList.stream().mapToInt(Integer::intValue).toArray();
        if (opaque > 0) {
            int r = (int) (sumR / opaque), gr = (int) (sumG / opaque), b = (int) (sumB / opaque);
            minimapColor = (r << 24) | (gr << 16) | (b << 8) | 0xff;
            floorColor = ((r / 2) << 24) | ((gr / 2) << 16) | ((b / 2) << 8) | 0xff;
        }
        System.out.println("[TFR-BarrierArt] " + SHEET + ": " + n + " piece(s) (" + sizes + " tiles) - "
                + carpet.length + " carpet, " + mids.length + " middle, " + bigs.length + " big");
    }

    private static int alpha(int rgba8888) {
        return rgba8888 & 0xff;
    }

    private static int tilesW(int piece) {
        return (pieceW[piece] + ART_TILE - 1) / ART_TILE;
    }

    private static int tilesH(int piece) {
        return (pieceH[piece] + ART_TILE - 1) / ART_TILE;
    }

    /** The whole barrier's pieces, per chunk - see the class comment. */
    private static void place(World world) {
        long started = System.nanoTime();
        int w = world.getWidthInTiles(), h = world.getHeightInTiles();
        chunkSize = world.getChunkSize();
        chunkCols = (w + chunkSize - 1) / chunkSize;
        chunkRows = (h + chunkSize - 1) / chunkSize;
        chunkPlacements = new int[chunkCols * chunkRows][];
        chunkCounts = new int[chunkCols * chunkRows];
        Random rng = new Random(world.getSeed() ^ 0x5eedb0a7L);
        // barrier tiles north to south, each row in a random order
        int[] tiles = new int[w * h];
        int count = 0;
        for (int y = h - 1; y >= 0; y--) {
            int rowStart = count;
            for (int x = 0; x < w; x++)
                if (world.isBarrierTile(x, y))
                    tiles[count++] = y * w + x;
            for (int i = count - 1; i > rowStart; i--) {
                int j = rowStart + rng.nextInt(i - rowStart + 1);
                int t = tiles[i]; tiles[i] = tiles[j]; tiles[j] = t;
            }
        }
        int placedPieces = 0;
        // 1) the carpets
        for (int i = 0; i < count; i++) {
            int x = tiles[i] % w, y = tiles[i] / w;
            int piece = carpet[rng.nextInt(carpet.length)];
            add(piece, x, y, x * ART_TILE + (ART_TILE - pieceW[piece]) / 2 + range(rng, 6), y * ART_TILE + range(rng, 4));
            placedPieces++;
        }
        for (int i = 0; i < count; i++) {
            int x = tiles[i] % w, y = tiles[i] / w;
            if (!world.isBarrierTile(x + 1, y) || !world.isBarrierTile(x, y - 1) || !world.isBarrierTile(x + 1, y - 1))
                continue;
            int piece = carpet[rng.nextInt(carpet.length)];
            add(piece, x, y, x * ART_TILE + ART_TILE / 2 + (ART_TILE - pieceW[piece]) / 2 + range(rng, 6),
                    y * ART_TILE - ART_TILE / 2 + range(rng, 4));
            placedPieces++;
        }
        for (int i = 0; i < count; i++) {
            int x = tiles[i] % w, y = tiles[i] / w;
            if (!world.isBarrierTile(x + 1, y) || rng.nextFloat() >= 0.7f)
                continue;
            int piece = carpet[rng.nextInt(carpet.length)];
            add(piece, x, y, x * ART_TILE + ART_TILE / 2 + (ART_TILE - pieceW[piece]) / 2 + range(rng, 8),
                    y * ART_TILE + range(rng, 10));
            placedPieces++;
        }
        // 2) the big shapes on top: two layers of the big ones, then the middle ones
        int[] cover = new int[w * h];
        int[][] passes = {bigs, bigs, mids};
        for (int pass = 0; pass < passes.length; pass++) {
            int[] pool = passes[pass];
            if (pool.length == 0)
                continue;
            int[] order = pool.clone();
            for (int i = 0; i < count; i++) {
                int x = tiles[i] % w, y = tiles[i] / w;
                if (cover[y * w + x] >= pass + 1)
                    continue;
                for (int k = order.length - 1; k > 0; k--) {
                    int j = rng.nextInt(k + 1);
                    int t = order[k]; order[k] = order[j]; order[j] = t;
                }
                for (int piece : order) {
                    int tw = tilesW(piece), th = tilesH(piece);
                    if (!fits(world, cover, w, x, y, tw, th, pass))
                        continue;
                    add(piece, x, y, x * ART_TILE + (tw * ART_TILE - pieceW[piece]) / 2 + range(rng, 10),
                            (y - th + 1) * ART_TILE + range(rng, 8));
                    for (int i2 = 0; i2 < tw; i2++)
                        for (int j2 = 0; j2 < th; j2++)
                            cover[(y - j2) * w + x + i2]++;
                    placedPieces++;
                    break;
                }
            }
        }
        // each chunk's pieces by bottom edge, north first
        for (int c = 0; c < chunkPlacements.length; c++) {
            if (chunkPlacements[c] == null)
                continue;
            chunkPlacements[c] = sortNorthFirst(chunkPlacements[c], chunkCounts[c]);
        }
        mergedX = Integer.MIN_VALUE;
        System.out.println("[TFR-BarrierArt] " + placedPieces + " mountain piece(s) over " + count + " barrier tile(s) in "
                + (System.nanoTime() - started) / 1_000_000 + " ms");
    }

    private static int range(Random rng, int reach) {
        return rng.nextInt(2 * reach + 1) - reach;
    }

    /** A big piece's footprint - tw across from x, th down from y - all barrier, and at most 40% of it already
     *  under this pass's layer. */
    private static boolean fits(World world, int[] cover, int w, int x, int y, int tw, int th, int pass) {
        int under = 0;
        for (int i = 0; i < tw; i++) {
            for (int j = 0; j < th; j++) {
                if (!world.isBarrierTile(x + i, y - j))
                    return false;
                if (cover[(y - j) * w + x + i] > pass)
                    under++;
            }
        }
        return under <= 0.4f * tw * th;
    }

    private static void add(int piece, int tileX, int tileY, int x, int y) {
        int c = (tileX / chunkSize) + (tileY / chunkSize) * chunkCols;
        if (c < 0 || c >= chunkPlacements.length)
            return;
        int[] list = chunkPlacements[c];
        int n = chunkCounts[c];
        if (list == null)
            list = chunkPlacements[c] = new int[3 * 64];
        else if (3 * n + 3 > list.length)
            list = chunkPlacements[c] = Arrays.copyOf(list, list.length * 2);
        list[3 * n] = piece;
        list[3 * n + 1] = x;
        list[3 * n + 2] = y;
        chunkCounts[c] = n + 1;
    }

    private static int[] sortNorthFirst(int[] list, int n) {
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++)
            order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(list[3 * b + 2], list[3 * a + 2]));
        int[] sorted = new int[3 * n];
        for (int i = 0; i < n; i++) {
            int k = order[i];
            sorted[3 * i] = list[3 * k];
            sorted[3 * i + 1] = list[3 * k + 1];
            sorted[3 * i + 2] = list[3 * k + 2];
        }
        return sorted;
    }

    /** The loaded 3x3 chunks' pieces in one list, bottom edge north first, so a piece overlapping a chunk border
     *  still lies behind the one south of it. */
    private static void merge(int centerX, int centerY) {
        int total = 0;
        for (int cx = centerX - 1; cx <= centerX + 1; cx++)
            for (int cy = centerY - 1; cy <= centerY + 1; cy++)
                total += chunkLength(cx, cy);
        int[] all = new int[3 * total];
        int at = 0;
        for (int cx = centerX - 1; cx <= centerX + 1; cx++) {
            for (int cy = centerY - 1; cy <= centerY + 1; cy++) {
                int len = chunkLength(cx, cy);
                if (len > 0)
                    System.arraycopy(chunkPlacements[cx + cy * chunkCols], 0, all, at, 3 * len);
                at += 3 * len;
            }
        }
        merged = sortNorthFirst(all, total);
        mergedCount = total;
        mergedX = centerX;
        mergedY = centerY;
    }

    private static int chunkLength(int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= chunkCols || cy >= chunkRows)
            return 0;
        int c = cx + cy * chunkCols;
        return chunkPlacements[c] == null ? 0 : chunkPlacements[c].length / 3;
    }

    /**
     * WorldBackground.draw(), right after the terrain chunks: the pieces of the 3x3 loaded chunks around
     * (centerChunkX, centerChunkY) that the camera sees, each clipped to the fog tile by tile.
     */
    public static void draw(Batch batch, World world, int centerChunkX, int centerChunkY, Camera camera) {
        if (world == null || !world.hasBarrier() || !isAvailable())
            return;
        if (texture == null) {
            if (packedPixmap == null)
                return;
            texture = new Texture(packedPixmap);
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            packedPixmap.dispose();
            packedPixmap = null;
        }
        if (world != placedWorld || world.getBarrierVersion() != placedVersion) {
            place(world);
            placedWorld = world;
            placedVersion = world.getBarrierVersion();
        }
        if (centerChunkX != mergedX || centerChunkY != mergedY)
            merge(centerChunkX, centerChunkY);
        int tileSize = world.getTileSize();
        float scale = tileSize / (float) ART_TILE; // world units per texel
        float zoom = camera instanceof OrthographicCamera ? ((OrthographicCamera) camera).zoom : 1f;
        float halfW = camera.viewportWidth * zoom / 2f + tileSize * 3, halfH = camera.viewportHeight * zoom / 2f + tileSize * 3;
        float left = (camera.position.x - halfW) / scale, right = (camera.position.x + halfW) / scale;
        float bottom = (camera.position.y - halfH) / scale, top = (camera.position.y + halfH) / scale;
        Color c = batch.getColor();
        float r = c.r, g = c.g, b = c.b, a = c.a;
        for (int i = 0; i < mergedCount; i++) {
            int piece = merged[3 * i], px = merged[3 * i + 1], py = merged[3 * i + 2];
            int pw = pieceW[piece], ph = pieceH[piece];
            if (px + pw < left || px > right || py + ph < bottom || py > top)
                continue;
            int tx0 = Math.floorDiv(px, ART_TILE), tx1 = Math.floorDiv(px + pw - 1, ART_TILE);
            int ty0 = Math.floorDiv(py, ART_TILE), ty1 = Math.floorDiv(py + ph - 1, ART_TILE);
            int state = fogState(world, tx0, ty0);
            boolean uniform = true;
            for (int tx = tx0; tx <= tx1 && uniform; tx++)
                for (int ty = ty0; ty <= ty1 && uniform; ty++)
                    uniform = fogState(world, tx, ty) == state;
            if (uniform) {
                if (state == 0)
                    continue;
                tint(batch, state, r, g, b, a);
                drawPart(batch, piece, px, py, px, py, px + pw, py + ph, scale);
                continue;
            }
            for (int tx = tx0; tx <= tx1; tx++) {
                for (int ty = ty0; ty <= ty1; ty++) {
                    int s = fogState(world, tx, ty);
                    if (s == 0)
                        continue;
                    tint(batch, s, r, g, b, a);
                    drawPart(batch, piece, px, py, Math.max(px, tx * ART_TILE), Math.max(py, ty * ART_TILE),
                            Math.min(px + pw, (tx + 1) * ART_TILE), Math.min(py + ph, (ty + 1) * ART_TILE), scale);
                }
            }
        }
        batch.setColor(r, g, b, a);
    }

    /** 0 never seen, 1 remembered (dimmed), 2 in view - the terrain's own three looks. */
    private static int fogState(World world, int tx, int ty) {
        if (!world.isExploredWorld(tx, ty))
            return 0;
        return world.isCurrentlyVisible(tx, ty) ? 2 : 1;
    }

    private static void tint(Batch batch, int state, float r, float g, float b, float a) {
        float f = state == 1 ? HAZE : 1f;
        batch.setColor(r * f, g * f, b * f, a);
    }

    /** Draws the part [x0,x1) x [y0,y1) (world texels, y up) of a piece whose bottom-left is (px, py). */
    private static void drawPart(Batch batch, int piece, int px, int py, int x0, int y0, int x1, int y1, float scale) {
        if (x1 <= x0 || y1 <= y0)
            return;
        int srcX = pieceU[piece] + (x0 - px);
        int srcY = pieceV[piece] + (py + pieceH[piece] - y1); // the texture runs top down
        batch.draw(texture, x0 * scale, y0 * scale, (x1 - x0) * scale, (y1 - y0) * scale,
                srcX, srcY, x1 - x0, y1 - y0, false, false);
    }
}
