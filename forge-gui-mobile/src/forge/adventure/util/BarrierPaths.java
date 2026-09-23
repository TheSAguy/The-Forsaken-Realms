package forge.adventure.util;

import com.badlogic.gdx.math.Vector2;
import forge.adventure.world.World;

import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Round 294 (user: "When calculating the closest town to attack, is it possible to take the distance to the town
 * going around a barrier, vs. straight line? That way, it's okay if the attacking mage crosses the barrier, but
 * towns on the other side of the barrier will be considered further, since distance is calculated going around the
 * barrier."). Walking distance for TARGET SELECTION only - the mage itself still walks its straight line.
 * <p>
 * A town a straight line reaches without touching the barrier is that straight distance away, exactly as before.
 * Otherwise the distance is the shortest walk around the barrier, on a coarse grid of CELL x CELL tiles: a cell with
 * any barrier tile in it is a wall, everything else - water included, since the mage crosses it anyway - is open,
 * and moves go eight ways without ever cutting a wall's corner. Only a world with a barrier gets here.
 */
public final class BarrierPaths {
    private static final int CELL = 4;
    private static final float DIAGONAL = (float) Math.sqrt(2);

    // The wall grid, rebuilt only when the world or its barrier changes (World.getBarrierVersion()).
    private static World gridWorld;
    private static int gridVersion = Integer.MIN_VALUE;
    private static boolean[] wall;
    private static int cols, rows;

    private BarrierPaths() {
    }

    /** Walking distances from a set of points - see {@link #from}. */
    public static final class Field {
        private final World world;
        private final List<Vector2> sources;
        private final float[] cells; // distance in cells from the nearest source; +inf = not reachable on foot

        private Field(World world, List<Vector2> sources, float[] cells) {
            this.world = world;
            this.sources = sources;
            this.cells = cells;
        }

        /**
         * Squared distance in PIXELS - the unit the straight-line code already compares in - from the nearest
         * source to {@code target}: the straight line where one is clear of the barrier, the walk around it
         * otherwise.
         */
        public double dist2(Vector2 target) {
            int tile = world.getTileSize();
            int tx = (int) (target.x / tile), ty = (int) (target.y / tile);
            double best = Double.MAX_VALUE;
            for (Vector2 source : sources) {
                if (!world.barrierBetween((int) (source.x / tile), (int) (source.y / tile), tx, ty))
                    best = Math.min(best, source.dst2(target));
            }
            float walked = cellsTo(target);
            if (!Float.isInfinite(walked)) {
                double px = (double) walked * CELL * tile;
                best = Math.min(best, px * px);
            }
            if (best != Double.MAX_VALUE)
                return best;
            for (Vector2 source : sources) // walled in on every side: the straight line is all there is
                best = Math.min(best, source.dst2(target));
            return best;
        }

        /** True when every source's straight line to {@code target} runs into the barrier. */
        public boolean behindBarrier(Vector2 target) {
            int tile = world.getTileSize();
            int tx = (int) (target.x / tile), ty = (int) (target.y / tile);
            for (Vector2 source : sources)
                if (!world.barrierBetween((int) (source.x / tile), (int) (source.y / tile), tx, ty))
                    return false;
            return !sources.isEmpty();
        }

        private float cellsTo(Vector2 target) {
            int c = cellIndex(world, target);
            if (c < 0)
                return Float.POSITIVE_INFINITY;
            if (!wall[c])
                return cells[c];
            // A place in a cell the barrier also touches: its best open neighbor, one step on.
            int cx = c % cols, cy = c / cols;
            float best = Float.POSITIVE_INFINITY;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = cx + dx, ny = cy + dy;
                    if ((dx == 0 && dy == 0) || nx < 0 || ny < 0 || nx >= cols || ny >= rows)
                        continue;
                    best = Math.min(best, cells[ny * cols + nx] + 1f);
                }
            }
            return best;
        }
    }

    /** Walking distances from {@code sources} (world pixel positions) over the whole map. */
    public static Field from(World world, List<Vector2> sources) {
        ensureGrid(world);
        float[] dist = new float[cols * rows];
        Arrays.fill(dist, Float.POSITIVE_INFINITY);
        // Packed (distance bits << 32 | cell): a non-negative float's bits order the same way as the float does.
        PriorityQueue<Long> queue = new PriorityQueue<>();
        for (Vector2 source : sources) {
            int c = cellIndex(world, source);
            if (c >= 0 && dist[c] > 0f) {
                dist[c] = 0f;
                queue.add((long) c);
            }
        }
        while (!queue.isEmpty()) {
            long top = queue.poll();
            int c = (int) (top & 0xffffffffL);
            float d = Float.intBitsToFloat((int) (top >>> 32));
            if (d > dist[c])
                continue;
            int cx = c % cols, cy = c / cols;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0)
                        continue;
                    int nx = cx + dx, ny = cy + dy;
                    if (nx < 0 || ny < 0 || nx >= cols || ny >= rows)
                        continue;
                    int n = ny * cols + nx;
                    if (wall[n])
                        continue;
                    boolean diagonal = dx != 0 && dy != 0;
                    if (diagonal && (wall[cy * cols + nx] || wall[ny * cols + cx]))
                        continue; // never squeeze between two wall cells
                    float nd = d + (diagonal ? DIAGONAL : 1f);
                    if (nd < dist[n]) {
                        dist[n] = nd;
                        queue.add(((long) Float.floatToIntBits(nd) << 32) | n);
                    }
                }
            }
        }
        return new Field(world, sources, dist);
    }

    private static int cellIndex(World world, Vector2 px) {
        int tile = world.getTileSize();
        int tx = (int) (px.x / tile), ty = (int) (px.y / tile);
        if (tx < 0 || ty < 0 || tx >= world.getWidthInTiles() || ty >= world.getHeightInTiles())
            return -1;
        return (ty / CELL) * cols + tx / CELL;
    }

    private static void ensureGrid(World world) {
        if (world == gridWorld && world.getBarrierVersion() == gridVersion && wall != null)
            return;
        int w = world.getWidthInTiles(), h = world.getHeightInTiles();
        cols = (w + CELL - 1) / CELL;
        rows = (h + CELL - 1) / CELL;
        wall = new boolean[cols * rows];
        int walls = 0;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int c = (y / CELL) * cols + x / CELL;
                if (!wall[c] && world.isBarrierTile(x, y)) {
                    wall[c] = true;
                    walls++;
                }
            }
        }
        gridWorld = world;
        gridVersion = world.getBarrierVersion();
        System.out.println("[TFR-BarrierPath] walking grid built: " + cols + "x" + rows + " cells of " + CELL + "x"
                + CELL + " tiles, " + walls + " of them barrier");
    }
}
