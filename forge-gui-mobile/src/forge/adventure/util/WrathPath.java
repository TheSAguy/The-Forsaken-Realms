package forge.adventure.util;

import com.badlogic.gdx.Gdx;
import forge.adventure.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Round 814: the Wrath hunter's long-range walk (util/WrathHunt). No world-map walker could cross 100+ tiles: castle mages
 * walk a straight line with no collision test, chasers (ChasePath, round 705) plan only inside a 48-tile box, and
 * BarrierPaths (round 294) measures distances on 4x4 cells with water open. This plans on the FULL tile grid:
 * <ul>
 *   <li>The grid is World.isColliding plus the barrier for every tile, copied once into a byte[] (rebuilt when the
 *   world or its barrier changes), so the worker reads an immutable copy and needs no lock.</li>
 *   <li>A* with 8 directions, no corner cutting and the octile heuristic, on one daemon worker thread; a newer request
 *   replaces a queued one. The route is string-pulled (line-of-sight shortcuts on the grid) into waypoints and handed
 *   back on the GL thread (Gdx.app.postRunnable).</li>
 *   <li>No route (an island, the barrier): the route ends at the reached tile nearest the goal, flagged {@code reached
 *   = false} - the hunter walks there and holds.</li>
 * </ul>
 * Waypoints are tile centers in world pixels. Nothing here is saved.
 */
public final class WrathPath {
    private WrathPath() {
    }

    /** A planned walk: waypoints (world px, tile centers), whether it reaches the goal tile, and its cost. */
    public static final class Route {
        public final List<float[]> waypoints;
        public final boolean reached;
        public final int goalX, goalY, endX, endY, tiles, nodes;
        public final long millis;

        Route(List<float[]> waypoints, boolean reached, int goalX, int goalY, int endX, int endY, int tiles, int nodes,
              long millis) {
            this.waypoints = waypoints;
            this.reached = reached;
            this.goalX = goalX;
            this.goalY = goalY;
            this.endX = endX;
            this.endY = endY;
            this.tiles = tiles;
            this.nodes = nodes;
            this.millis = millis;
        }
    }

    private static final class Grid {
        final World world;
        final int version, w, h, ts;
        final byte[] blocked;

        Grid(World world, int version, int w, int h, int ts, byte[] blocked) {
            this.world = world;
            this.version = version;
            this.w = w;
            this.h = h;
            this.ts = ts;
            this.blocked = blocked;
        }

        boolean open(int x, int y) {
            return x >= 0 && y >= 0 && x < w && y < h && blocked[y * w + x] == 0;
        }
    }

    private static volatile Grid grid;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TFR-WrathPath");
        t.setDaemon(true);
        return t;
    });
    /** The newest request's number - an older request still queued skips its search. */
    private static final AtomicLong latest = new AtomicLong();

    /** The grid for this world, built on the calling (GL) thread when the world or its barrier changed. */
    private static Grid grid(World world) {
        Grid g = grid;
        if (g != null && g.world == world && g.version == world.getBarrierVersion())
            return g;
        long start = System.nanoTime();
        int w = world.getWidthInTiles(), h = world.getHeightInTiles();
        byte[] blocked = new byte[w * h];
        int count = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (world.isColliding(x, y) || world.isBarrierTile(x, y)) {
                    blocked[y * w + x] = 1;
                    count++;
                }
        g = new Grid(world, world.getBarrierVersion(), w, h, world.getTileSize(), blocked);
        grid = g;
        System.out.println("[TFR-Wrath] path grid: " + w + "x" + h + " tiles, " + count + " blocked, built in "
                + (System.nanoTime() - start) / 1_000_000 + " ms");
        return g;
    }

    /** True when the tile is open (not water, mountain or barrier) - GL thread. */
    public static boolean open(World world, int x, int y) {
        return grid(world).open(x, y);
    }

    /** The open tile nearest (x, y) within {@code radius} tiles, or null. */
    public static int[] nearestOpen(World world, int x, int y, int radius) {
        Grid g = grid(world);
        if (g.open(x, y))
            return new int[]{x, y};
        for (int r = 1; r <= radius; r++)
            for (int dy = -r; dy <= r; dy++)
                for (int dx = -r; dx <= r; dx++)
                    if ((Math.abs(dx) == r || Math.abs(dy) == r) && g.open(x + dx, y + dy))
                        return new int[]{x + dx, y + dy};
        return null;
    }

    /** True when the straight line between two tiles crosses only open tiles (and cuts no blocked corner). */
    public static boolean clearLine(World world, int x0, int y0, int x1, int y1) {
        return clearLine(grid(world), x0, y0, x1, y1);
    }

    private static boolean clearLine(Grid g, int x0, int y0, int x1, int y1) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx - dy, x = x0, y = y0;
        while (true) {
            if (!g.open(x, y))
                return false;
            if (x == x1 && y == y1)
                return true;
            int e2 = 2 * err;
            boolean stepX = e2 > -dy, stepY = e2 < dx;
            if (stepX && stepY && (!g.open(x + sx, y) || !g.open(x, y + sy)))
                return false; // a diagonal step between two blocked tiles - no corner cutting
            if (stepX) {
                err -= dy;
                x += sx;
            }
            if (stepY) {
                err += dx;
                y += sy;
            }
        }
    }

    /**
     * Plans from tile (fx, fy) to tile (gx, gy) on the worker; {@code done} gets the route on the GL thread. A newer
     * request makes this one skip its search if it has not started.
     */
    public static void request(World world, int fx, int fy, int gx, int gy, Consumer<Route> done) {
        Grid g = grid(world);
        long id = latest.incrementAndGet();
        worker.execute(() -> {
            if (latest.get() != id)
                return;
            Route route;
            try {
                route = search(g, fx, fy, gx, gy);
            } catch (RuntimeException e) {
                System.out.println("[TFR-Wrath] path search failed: " + e);
                return;
            }
            Gdx.app.postRunnable(() -> {
                if (grid == g)
                    done.accept(route);
            });
        });
    }

    // Search buffers, reused (worker thread only).
    private static float[] cost;
    private static int[] parent, stamp, heapNode;
    private static float[] heapKey;
    private static int generation;
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DY = {0, 0, 1, -1, 1, -1, 1, -1};
    private static final float DIAG = (float) Math.sqrt(2);

    private static Route search(Grid g, int fx, int fy, int gx, int gy) {
        long start = System.nanoTime();
        int n = g.w * g.h;
        if (cost == null || cost.length != n) {
            cost = new float[n];
            parent = new int[n];
            stamp = new int[n];
            heapNode = new int[1024];
            heapKey = new float[1024];
            generation = 0;
        }
        generation++;
        int from = fy * g.w + fx, goal = gy * g.w + gx;
        int size = 0;
        cost[from] = 0f;
        parent[from] = -1;
        stamp[from] = generation;
        size = push(size, from, octile(fx, fy, gx, gy));
        int best = from;
        float bestH = octile(fx, fy, gx, gy);
        int nodes = 0;
        boolean reached = false;
        while (size > 0) {
            int cur = heapNode[0];
            size = pop(size);
            if (stamp[cur] == -generation)
                continue; // already closed (a stale heap entry)
            stamp[cur] = -generation;
            nodes++;
            int cx = cur % g.w, cy = cur / g.w;
            float hCur = octile(cx, cy, gx, gy);
            if (hCur < bestH) {
                bestH = hCur;
                best = cur;
            }
            if (cur == goal) {
                reached = true;
                best = cur;
                break;
            }
            for (int d = 0; d < 8; d++) {
                int nx = cx + DX[d], ny = cy + DY[d];
                if (!g.open(nx, ny))
                    continue;
                if (d >= 4 && (!g.open(cx + DX[d], cy) || !g.open(cx, cy + DY[d])))
                    continue; // no corner cutting
                int next = ny * g.w + nx;
                if (stamp[next] == -generation)
                    continue;
                float c = cost[cur] + (d >= 4 ? DIAG : 1f);
                if (stamp[next] == generation && c >= cost[next])
                    continue;
                stamp[next] = generation;
                cost[next] = c;
                parent[next] = cur;
                size = push(size, next, c + octile(nx, ny, gx, gy));
            }
        }
        List<int[]> tiles = new ArrayList<>();
        for (int t = best; t != -1; t = parent[t])
            tiles.add(0, new int[]{t % g.w, t / g.w});
        List<float[]> waypoints = new ArrayList<>();
        int at = 0;
        while (at < tiles.size() - 1) { // string-pull: forward to the last tile still in a clear line (at most 64 on)
            int far = at + 1;
            for (int j = at + 2; j < tiles.size() && j <= at + 64; j++) {
                if (!clearLine(g, tiles.get(at)[0], tiles.get(at)[1], tiles.get(j)[0], tiles.get(j)[1]))
                    break;
                far = j;
            }
            waypoints.add(new float[]{(tiles.get(far)[0] + 0.5f) * g.ts, (tiles.get(far)[1] + 0.5f) * g.ts});
            at = far;
        }
        int end = tiles.isEmpty() ? from : best;
        return new Route(waypoints, reached, gx, gy, end % g.w, end / g.w, tiles.size(), nodes,
                (System.nanoTime() - start) / 1_000_000);
    }

    private static float octile(int ax, int ay, int bx, int by) {
        int dx = Math.abs(ax - bx), dy = Math.abs(ay - by);
        return Math.max(dx, dy) + (DIAG - 1f) * Math.min(dx, dy);
    }

    private static int push(int size, int node, float key) {
        if (size == heapNode.length) { // a node can sit in the heap more than once (lazy deletion)
            heapNode = java.util.Arrays.copyOf(heapNode, size * 2);
            heapKey = java.util.Arrays.copyOf(heapKey, size * 2);
        }
        int i = size++;
        while (i > 0) {
            int p = (i - 1) / 2;
            if (heapKey[p] <= key)
                break;
            heapKey[i] = heapKey[p];
            heapNode[i] = heapNode[p];
            i = p;
        }
        heapKey[i] = key;
        heapNode[i] = node;
        return size;
    }

    private static int pop(int size) {
        size--;
        if (size == 0)
            return 0;
        float key = heapKey[size];
        int node = heapNode[size];
        int i = 0;
        while (true) {
            int c = 2 * i + 1;
            if (c >= size)
                break;
            if (c + 1 < size && heapKey[c + 1] < heapKey[c])
                c++;
            if (heapKey[c] >= key)
                break;
            heapKey[i] = heapKey[c];
            heapNode[i] = heapNode[c];
            i = c;
        }
        heapKey[i] = key;
        heapNode[i] = node;
        return size;
    }
}
