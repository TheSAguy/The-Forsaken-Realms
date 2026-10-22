package forge.adventure.util;

import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import forge.adventure.character.EnemySprite;
import forge.adventure.world.World;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.WeakHashMap;

/**
 * Round 705 (the user: "The smallest obstacle in a direct path to the player blocks/stops the unit in its tracks. Can
 * any pathing logic be looked at?"): a world-map enemy that heads for the player - a roamer, a chasing legend, a
 * pillage raider - walks straight at its goal while the straight step is clear, exactly as before. When that step is
 * blocked it now plans a short way around on the world's collision grid (A* over tiles, 8 directions, no corner
 * cutting) and follows it until it can see the goal again.
 *
 * Every tile on a route has room for the enemy's whole feet box (the part of the sprite that collides, as
 * WorldStage tests it), so a route never squeezes a big creature through a one-tile gap. The search is bounded - the
 * box around the enemy and its goal plus a margin, at most MAX_NODES tiles, and none beyond MAX_TILES away - and an
 * enemy plans at most every REPLAN_SECONDS, at most MAX_PLANS_PER_FRAME plans in one frame. When no route is found the
 * caller falls back to sliding along the obstacle. The route lives here, keyed weakly by sprite, so nothing is saved.
 * [TFR-Chase] logs each detour planned and each failure.
 */
public final class ChasePath {
    private ChasePath() {
    }

    private static final int MAX_TILES = 48;          // farther than this, no planning: keep sliding
    private static final int MARGIN = 14;             // tiles of room around the enemy-goal box
    private static final int MAX_NODES = 6000;
    private static final float REPLAN_SECONDS = 0.6f;
    private static final float SIGHT_SECONDS = 0.25f; // how often a detouring enemy looks for a straight line again
    private static final int MAX_PLANS_PER_FRAME = 3;

    private static final class Route {
        final ArrayDeque<Vector2> waypoints = new ArrayDeque<>(); // positions for the sprite's (x, y)
        float sinceSight;
        float sincePlan = REPLAN_SECONDS; // ready to plan at once
        int goalTileX = Integer.MIN_VALUE, goalTileY = Integer.MIN_VALUE;
        boolean failedLogged;
        int lastLoggedStart = Integer.MIN_VALUE;
        int plansHere;
    }

    private static final Map<EnemySprite, Route> routes = new WeakHashMap<>();
    private static int plansThisFrame;
    private static long frameId = -1;
    private static final Rectangle probe = new Rectangle();

    /** True while this enemy is following a detour. */
    public static boolean detouring(EnemySprite mob) {
        Route r = routes.get(mob);
        return r != null && !r.waypoints.isEmpty();
    }

    /** Drops any detour - the straight step is open again, or the enemy stopped chasing. */
    public static void clear(EnemySprite mob) {
        Route r = routes.get(mob);
        if (r != null)
            r.waypoints.clear();
    }

    /**
     * The move for this frame toward {@code goal} (the goal sprite's x, y), written into {@code out}, or false when
     * there is no detour to follow (no route found, too far, or the planning budget is spent this frame) - the caller
     * then slides as before. {@code frame} is any number that changes every frame (the plan budget resets on it).
     */
    public static boolean step(World world, EnemySprite mob, float goalX, float goalY, float delta, long frame, Vector2 out) {
        if (frame != frameId) {
            frameId = frame;
            plansThisFrame = 0;
        }
        Route r = routes.computeIfAbsent(mob, k -> new Route());
        r.sinceSight += delta;
        r.sincePlan += delta;
        int ts = world.getTileSize();
        float fw = mob.getWidth(), fh = mob.getHeight() * mob.getCollisionHeight();
        int goalTx = (int) ((goalX + fw / 2f) / ts), goalTy = (int) ((goalY + fh / 2f) / ts);

        // On a detour: give it up as soon as the goal is in plain sight (checked a few times a second).
        if (!r.waypoints.isEmpty() && r.sinceSight >= SIGHT_SECONDS) {
            r.sinceSight = 0f;
            if (clearLine(world, mob.getX(), mob.getY(), goalX, goalY, fw, fh, ts)) {
                r.waypoints.clear();
                return false;
            }
        }
        boolean goalMoved = Math.abs(goalTx - r.goalTileX) > 2 || Math.abs(goalTy - r.goalTileY) > 2;
        if ((r.waypoints.isEmpty() || goalMoved) && r.sincePlan >= REPLAN_SECONDS && plansThisFrame < MAX_PLANS_PER_FRAME) {
            r.sincePlan = 0f;
            plansThisFrame++;
            plan(world, mob, r, goalX, goalY, fw, fh, ts, goalTx, goalTy);
        }
        if (r.waypoints.isEmpty())
            return false;

        float reach = mob.speed() * delta;
        Vector2 next = r.waypoints.peekFirst();
        while (next != null && next.dst(mob.getX(), mob.getY()) <= Math.max(reach, 1f)) {
            r.waypoints.pollFirst();
            next = r.waypoints.peekFirst();
        }
        if (next == null)
            return false;
        out.set(next).sub(mob.getX(), mob.getY());
        if (out.len() > reach)
            out.setLength(reach);
        // The route's tiles have room, but the enemy rarely stands centered on its tile, so the first steps toward the
        // next waypoint can clip a corner: slide along one axis into line (agent test: one wizard replanned 14 times on
        // one tile without this). Only when neither axis moves has the world changed under the route - replan.
        probe.set(mob.getX() + out.x, mob.getY() + out.y, fw, fh);
        if (world.collidingTile(probe)) {
            probe.set(mob.getX() + out.x, mob.getY(), fw, fh);
            if (out.x != 0f && !world.collidingTile(probe)) {
                out.y = 0f;
                return true;
            }
            probe.set(mob.getX(), mob.getY() + out.y, fw, fh);
            if (out.y != 0f && !world.collidingTile(probe)) {
                out.x = 0f;
                return true;
            }
            r.waypoints.clear();
            r.sincePlan = REPLAN_SECONDS;
            return false;
        }
        return true;
    }

    private static void plan(World world, EnemySprite mob, Route r, float goalX, float goalY, float fw, float fh, int ts,
                             int goalTx, int goalTy) {
        r.waypoints.clear();
        r.goalTileX = goalTx;
        r.goalTileY = goalTy;
        int startTx = (int) ((mob.getX() + fw / 2f) / ts), startTy = (int) ((mob.getY() + fh / 2f) / ts);
        int span = Math.max(Math.abs(goalTx - startTx), Math.abs(goalTy - startTy));
        if (span > MAX_TILES)
            return;
        int x0 = Math.max(0, Math.min(startTx, goalTx) - MARGIN), y0 = Math.max(0, Math.min(startTy, goalTy) - MARGIN);
        int x1 = Math.min(world.getWidthInTiles() - 1, Math.max(startTx, goalTx) + MARGIN);
        int y1 = Math.min(world.getHeightInTiles() - 1, Math.max(startTy, goalTy) + MARGIN);
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        int n = w * h;
        byte[] room = new byte[n];          // 0 unknown, 1 open, 2 blocked - the feet box tested once per tile
        float[] cost = new float[n];
        int[] from = new int[n];
        boolean[] closed = new boolean[n];
        Arrays.fill(cost, Float.MAX_VALUE);
        int start = (startTy - y0) * w + (startTx - x0), goal = (goalTy - y0) * w + (goalTx - x0);
        if (start < 0 || start >= n || goal < 0 || goal >= n)
            return;
        room[start] = 1; // the enemy stands here, whatever its edge touches
        room[goal] = 1;  // the player can stand where an enemy's box would not fit
        PriorityQueue<float[]> open = new PriorityQueue<>((a, b) -> Float.compare(a[0], b[0]));
        cost[start] = 0f;
        from[start] = -1;
        open.add(new float[]{octile(startTx, startTy, goalTx, goalTy), start});
        int expanded = 0;
        boolean found = false;
        while (!open.isEmpty() && expanded < MAX_NODES) {
            int cur = (int) open.poll()[1];
            if (closed[cur])
                continue;
            closed[cur] = true;
            expanded++;
            if (cur == goal) {
                found = true;
                break;
            }
            int cx = cur % w, cy = cur / w;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0)
                        continue;
                    int nx = cx + dx, ny = cy + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h)
                        continue;
                    int next = ny * w + nx;
                    if (closed[next] || !roomFor(world, room, w, x0, y0, nx, ny, fw, fh, ts))
                        continue;
                    if (dx != 0 && dy != 0 // no corner cutting: both side steps must be open too
                            && (!roomFor(world, room, w, x0, y0, cx + dx, cy, fw, fh, ts)
                            || !roomFor(world, room, w, x0, y0, cx, cy + dy, fw, fh, ts)))
                        continue;
                    float c = cost[cur] + (dx != 0 && dy != 0 ? 1.4142f : 1f);
                    if (c < cost[next]) {
                        cost[next] = c;
                        from[next] = cur;
                        open.add(new float[]{c + octile(x0 + nx, y0 + ny, goalTx, goalTy), next});
                    }
                }
            }
        }
        String name = mob.getData() == null ? mob.getName() : mob.getData().getName();
        if (!found) {
            if (!r.failedLogged) {
                r.failedLogged = true;
                System.out.println("[TFR-Chase] " + name + " is blocked at tile (" + startTx + "," + startTy
                        + ") and found no way around within " + expanded + " tiles searched - it slides along instead");
            }
            return;
        }
        r.failedLogged = false;
        ArrayDeque<Vector2> reversed = new ArrayDeque<>();
        for (int at = goal; at != -1 && at != start; at = from[at]) {
            int tx = x0 + at % w, ty = y0 + at / w;
            reversed.push(new Vector2(tx * ts + ts / 2f - fw / 2f, ty * ts + ts / 2f - fh / 2f));
        }
        if (!reversed.isEmpty()) {
            reversed.pollLast(); // the goal tile itself: the last stretch is walked straight at the player
            r.waypoints.addAll(reversed);
        }
        int startKey = startTx * 100000 + startTy;
        r.plansHere = startKey == r.lastLoggedStart ? r.plansHere + 1 : 0;
        if (r.plansHere == 5)
            System.out.println("[TFR-Chase] " + name + " still at tile (" + startTx + "," + startTy
                    + ") after 5 plans - its detour is not taking it anywhere");
        if (startKey != r.lastLoggedStart) { // one line per tile it is blocked on, not one per replan
            r.lastLoggedStart = startKey;
            System.out.println("[TFR-Chase] " + name + " blocked at tile (" + startTx + "," + startTy + ") - detour of "
                    + r.waypoints.size() + " tile(s) toward (" + goalTx + "," + goalTy + "), " + expanded + " tiles searched");
        }
    }

    private static boolean roomFor(World world, byte[] room, int w, int x0, int y0, int nx, int ny, float fw, float fh, int ts) {
        int i = ny * w + nx;
        if (room[i] == 0) {
            probe.set((x0 + nx) * ts + ts / 2f - fw / 2f, (y0 + ny) * ts + ts / 2f - fh / 2f, fw, fh);
            room[i] = world.collidingTile(probe) ? (byte) 2 : (byte) 1;
        }
        return room[i] == 1;
    }

    private static float octile(int ax, int ay, int bx, int by) {
        int dx = Math.abs(ax - bx), dy = Math.abs(ay - by);
        return Math.max(dx, dy) + 0.4142f * Math.min(dx, dy);
    }

    /** Can the feet box slide in a straight line from (x0, y0) to (x1, y1)? Sampled every half tile. */
    private static boolean clearLine(World world, float x0, float y0, float x1, float y1, float fw, float fh, int ts) {
        float dist = Vector2.dst(x0, y0, x1, y1);
        int steps = Math.max(1, (int) (dist / (ts / 2f)));
        for (int s = 1; s < steps; s++) {
            float t = s / (float) steps;
            probe.set(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, fw, fh);
            if (world.collidingTile(probe))
                return false;
        }
        return true;
    }
}
