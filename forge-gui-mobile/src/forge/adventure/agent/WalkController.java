package forge.adventure.agent;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import forge.Forge;
import forge.adventure.character.PlayerSprite;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.stage.AgentStageAccess;
import forge.adventure.stage.GameStage;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Current;
import forge.adventure.util.pathfinding.NavigationMap;
import forge.adventure.util.pathfinding.NavigationVertex;
import forge.adventure.util.pathfinding.ProgressableGraphPath;
import forge.adventure.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Round 161: walks the player somewhere, one frame at a time, through the same virtual joystick the
 * touch UI feeds ({@link GameStage#setTouchKnobInput}). An invisible actor on the active stage ticks
 * this before the stage reads its inputs, so no stock movement code changes. Paths come from A*
 * over the world's collision tiles on the overworld and from the map's own navigation graph inside
 * a town or dungeon. Also runs the "wait N days" clock.
 */
final class WalkController {
    private final AgentBridge bridge;
    private final TickActor tick = new TickActor();
    private final Map<GameStage, Boolean> attached = new HashMap<>();

    // the current walk
    private boolean walking;
    private List<Vector2> path = new ArrayList<>();
    private int index;
    private String target = "";
    private Vector2 finalTarget;
    private boolean worldWalk;
    private CompletableFuture<Map<String, Object>> done;
    private float noProgress;
    private float lastDist = Float.MAX_VALUE;
    private int replans;
    private final Set<Long> blocked = new HashSet<>();
    private float walkTime;

    // the current wait
    private boolean waiting;
    private int waitUntilDay;
    private CompletableFuture<Map<String, Object>> waitDone;

    WalkController(AgentBridge bridge) {
        this.bridge = bridge;
    }

    private final class TickActor extends Actor {
        @Override
        public void act(float delta) {
            GameStage stage = getStage() instanceof GameStage ? (GameStage) getStage() : null;
            if (stage != null)
                tick(stage, delta);
        }
    }

    boolean isBusy() {
        return walking || waiting;
    }

    /**
     * Called by the bridge at the end of EVERY frame, whichever stage is active. The ticking actor
     * only acts while its own stage does, so a walk that ends by changing scene (a portal out of a
     * town, a duel, a load) would otherwise stay "busy" forever - that is exactly what happened on
     * the first live run: the player walked out of the Secluded Encampment and the future never
     * completed.
     */
    void frame() {
        if (!walking && !waiting)
            return;
        GameStage active = null;
        try {
            active = Current.player() == null ? null : Current.player().getCurrentGameStage();
        } catch (Exception ignored) {
        }
        boolean onMapScene = Forge.getCurrentScene() instanceof forge.adventure.scene.HudScene;
        if (active == null || !onMapScene || tick.getStage() != active) {
            String why = active == null ? "no player stage" : !onMapScene ? "scene is " + (Forge.getCurrentScene() == null ? "none" : Forge.getCurrentScene().getClass().getSimpleName())
                    : "the player moved to " + active.getClass().getSimpleName() + (MapStage.getInstance().isInMap() ? " (inside a map)" : "");
            if (walking)
                finish(true, "stopped: " + why);
            if (waiting) {
                waiting = false;
                if (waitDone != null)
                    waitDone.complete(result(false, "interrupted: " + why));
            }
        }
    }

    String describe() {
        if (walking) return "walking to " + target + " (" + (path.size() - index) + " waypoints left)";
        if (waiting) return "waiting until day " + waitUntilDay;
        return "";
    }

    /** Make sure the ticking actor lives on the stage the player is on right now. */
    private void attach(GameStage stage) {
        if (tick.getStage() != stage) {
            tick.remove();
            stage.addActor(tick);
        }
    }

    // ------------------------------------------------------------------ starting things

    CompletableFuture<Map<String, Object>> walkTo(Vector2 destPx, String description) {
        cancel("replaced by a new walk");
        GameStage stage = Current.player().getCurrentGameStage();
        if (stage == null)
            return failed("no active stage");
        attach(stage);
        worldWalk = !MapStage.getInstance().isInMap();
        finalTarget = destPx.cpy();
        target = description;
        blocked.clear();
        replans = 0;
        List<Vector2> p = plan(stage, playerCenter(stage), destPx);
        if (p == null)
            return failed("no path to " + description);
        path = p;
        index = 0;
        noProgress = 0;
        lastDist = Float.MAX_VALUE;
        walkTime = 0;
        walking = true;
        done = new CompletableFuture<>();
        bridge.log("[TFR-Agent] walk: " + description + " via " + p.size() + " waypoint(s)");
        return done;
    }

    CompletableFuture<Map<String, Object>> waitDays(int days) {
        cancel("replaced by a wait");
        if (MapStage.getInstance().isInMap())
            return failed("time only passes on the world map");
        GameStage stage = Current.player().getCurrentGameStage();
        // Waiting advances time, and advancing time runs the point-of-interest collision check.
        // Fresh out of a town the player still overlaps it, so a wait would walk straight back in
        // (it did, on the first live run). Step clear first, then wait.
        PointOfInterest under = poiUnderPlayer(stage);
        if (under != null) {
            Vector2 clear = nearestClearTile(stage, under);
            if (clear == null)
                return failed("standing on " + under.getDisplayName() + " and no clear tile nearby - goto somewhere first");
            bridge.log("[TFR-Agent] stepping off " + under.getDisplayName() + " before waiting");
            return walkTo(clear, "clear of " + under.getDisplayName()).thenCompose(r -> {
                if (!Boolean.TRUE.equals(r.get("ok")) || poiUnderPlayer(stage) != null)
                    return CompletableFuture.completedFuture(result(false, "could not step clear of " + under.getDisplayName() + ": " + r.get("message")));
                return waitDays(days);
            });
        }
        attach(stage);
        World world = Current.world();
        waitUntilDay = world.getCurrentDay() + Math.max(1, days);
        WorldStage.getInstance().setWaitingForTime(true);
        waiting = true;
        waitDone = new CompletableFuture<>();
        bridge.log("[TFR-Agent] waiting until day " + waitUntilDay);
        return waitDone;
    }

    void cancel(String reason) {
        if (walking)
            finish(false, reason);
        if (waiting) {
            WorldStage.getInstance().setWaitingForTime(false);
            waiting = false;
            if (waitDone != null)
                waitDone.complete(result(false, reason));
        }
    }

    private static CompletableFuture<Map<String, Object>> failed(String why) {
        return CompletableFuture.completedFuture(result(false, why));
    }

    static Map<String, Object> result(boolean ok, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("message", message);
        return m;
    }

    // ------------------------------------------------------------------ per-frame

    private void tick(GameStage stage, float delta) {
        if (waiting) {
            World world = Current.world();
            if (world.getCurrentDay() >= waitUntilDay || Forge.advFreezePlayerControls || MapStage.getInstance().isInMap()) {
                WorldStage.getInstance().setWaitingForTime(false);
                waiting = false;
                waitDone.complete(result(world.getCurrentDay() >= waitUntilDay,
                        world.getCurrentDay() >= waitUntilDay ? "day " + world.getCurrentDay() : "interrupted on day " + world.getCurrentDay()));
            } else if (!WorldStage.getInstance().isWaitingForTime() && !stage.isPaused() && !stage.isDialogOnlyInput()) {
                WorldStage.getInstance().setWaitingForTime(true); // movement or a dialog reset it; keep waiting
            }
        }
        if (!walking)
            return;
        walkTime += delta;
        if (Forge.advFreezePlayerControls || stage.isPaused()) {
            // A map that has just loaded sits paused for a moment; a walk issued then must wait it
            // out, not report an event. After that grace period a pause means a duel, a POI entry
            // or a transition took over - which is usually the point of the walk.
            if (walkTime < 2f)
                return;
            finish(true, "stopped: " + (stage.isPaused() ? "entering / event" : "controls frozen"));
            return;
        }
        if (stage.isDialogOnlyInput() || (stage.getDialog() != null && stage.getDialog().getStage() != null && stage.getDialog().isVisible())) {
            finish(true, "stopped: a dialog is open");
            return;
        }
        PlayerSprite player = stage.getPlayerSprite();
        Vector2 me = playerCenter(stage);
        while (index < path.size() && me.dst(path.get(index)) < 5f)
            index++;
        if (index >= path.size()) {
            finish(true, "arrived at " + target);
            return;
        }
        Vector2 wp = path.get(index);
        float dist = me.dst(wp);
        if (dist < lastDist - 0.25f) {
            lastDist = dist;
            noProgress = 0;
        } else {
            noProgress += delta;
        }
        if (noProgress > 1.5f) {
            noProgress = 0;
            lastDist = Float.MAX_VALUE;
            if (!replan(stage, me))
                return;
            wp = path.get(index);
        }
        Vector2 dir = wp.cpy().sub(me);
        if (dir.len2() > 0.0001f)
            dir.nor();
        stage.setTouchKnobInput(dir.x, dir.y);
        if (walkTime > 240f)
            finish(false, "gave up after 240s of walking");
    }

    private boolean replan(GameStage stage, Vector2 me) {
        if (++replans > 4) {
            finish(false, "stuck near " + tileOf(me) + " on the way to " + target);
            return false;
        }
        if (worldWalk && index < path.size()) {
            Vector2 wp = path.get(index);
            World world = Current.world();
            int ts = world.getTileSize();
            blocked.add(key((int) (wp.x / ts), (int) (wp.y / ts)));
        }
        List<Vector2> p = plan(stage, me, finalTarget);
        if (p == null) {
            finish(false, "no path after getting stuck near " + tileOf(me));
            return false;
        }
        path = p;
        index = 0;
        bridge.log("[TFR-Agent] replanned (" + replans + "): " + p.size() + " waypoint(s)");
        return true;
    }

    private void finish(boolean ok, String message) {
        walking = false;
        GameStage stage = tick.getStage() instanceof GameStage ? (GameStage) tick.getStage() : null;
        if (stage != null) {
            stage.setTouchKnobInput(0, 0);
            if (stage.getPlayerSprite() != null && !Forge.advFreezePlayerControls)
                stage.getPlayerSprite().stop();
        }
        bridge.log("[TFR-Agent] walk " + (ok ? "done" : "FAILED") + ": " + message);
        if (done != null)
            done.complete(result(ok, message));
    }

    // ------------------------------------------------------------------ geometry

    static Vector2 playerCenter(GameStage stage) {
        PlayerSprite p = stage.getPlayerSprite();
        return new Vector2(p.getX() + p.getWidth() / 2f, p.getY() + p.getHeight() * 0.2f);
    }

    private static String tileOf(Vector2 px) {
        int ts = Current.world().getTileSize();
        return "(" + (int) (px.x / ts) + "," + (int) (px.y / ts) + ")";
    }

    private static long key(int x, int y) {
        return (((long) x) << 32) | (y & 0xffffffffL);
    }

    private List<Vector2> plan(GameStage stage, Vector2 from, Vector2 to) {
        if (worldWalk)
            return planWorld(from, to);
        return planMap(from, to);
    }

    /** Inside a map: the enemy AI's navigation graph, falling back to a straight line. */
    private List<Vector2> planMap(Vector2 from, Vector2 to) {
        List<Vector2> out = new ArrayList<>();
        try {
            float size = AgentStageAccess.mapNavSize();
            NavigationMap nav = MapStage.getInstance().navMaps.get(size);
            if (nav != null) {
                ProgressableGraphPath<NavigationVertex> p = nav.findShortestPath(size, from.cpy(), to.cpy());
                if (p != null && p.getCount() > 0) {
                    for (int i = 0; i < p.getCount(); i++)
                        out.add(p.get(i).pos.cpy());
                    if (out.get(out.size() - 1).dst(to) > 2f)
                        out.add(to.cpy());
                    return out;
                }
            }
        } catch (Exception e) {
            bridge.log("[TFR-Agent] map nav failed (" + e.getMessage() + ") - straight line");
        }
        out.add(to.cpy());
        return out;
    }

    /** Overworld: A* over collision tiles, 8-way without corner cutting. */
    private List<Vector2> planWorld(Vector2 fromPx, Vector2 toPx) {
        World world = Current.world();
        int ts = world.getTileSize();
        int w = world.getWidthInTiles(), h = world.getHeightInTiles();
        int sx = clamp((int) (fromPx.x / ts), w), sy = clamp((int) (fromPx.y / ts), h);
        int gx = clamp((int) (toPx.x / ts), w), gy = clamp((int) (toPx.y / ts), h);
        if (sx == gx && sy == gy) {
            List<Vector2> single = new ArrayList<>();
            single.add(toPx.cpy());
            return single;
        }
        // The goal tile itself may be solid (a town's footprint); accept arriving next to it.
        boolean goalSolid = !passable(world, gx, gy);
        long start = key(sx, sy), goal = key(gx, gy);
        // Touching ANY point of interest enters it, so every other POI's footprint (plus a one-tile
        // margin) is an obstacle - the first live walk to a cave went straight through a Ring City.
        Set<Long> footprints = new HashSet<>();
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (!poi.getActive())
                continue;
            com.badlogic.gdx.math.Rectangle r = poi.getBoundingRectangle();
            int x0 = (int) Math.floor(r.x / ts) - 1, y0 = (int) Math.floor(r.y / ts) - 1;
            int x1 = (int) Math.floor((r.x + r.width) / ts) + 1, y1 = (int) Math.floor((r.y + r.height) / ts) + 1;
            boolean isGoal = gx >= x0 && gx <= x1 && gy >= y0 && gy <= y1;
            boolean isStart = sx >= x0 && sx <= x1 && sy >= y0 && sy <= y1;
            if (isGoal)
                continue; // the one we are walking to
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++) {
                    // Standing inside a footprint (the game puts you there when you leave a town):
                    // the tiles right around you stay open so the path can step OUT, but the rest
                    // of that footprint is still off-limits - the first live run walked back in.
                    if (isStart && Math.max(Math.abs(x - sx), Math.abs(y - sy)) <= 1)
                        continue;
                    footprints.add(key(x, y));
                }
        }
        Map<Long, Float> g = new HashMap<>();
        Map<Long, Long> came = new HashMap<>();
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Float.compare(Float.intBitsToFloat((int) a[1]), Float.intBitsToFloat((int) b[1])));
        g.put(start, 0f);
        open.add(new long[]{start, Float.floatToIntBits(heuristic(sx, sy, gx, gy))});
        Set<Long> closed = new HashSet<>();
        long found = -1;
        int expanded = 0;
        while (!open.isEmpty()) {
            long cur = open.poll()[0];
            if (!closed.add(cur))
                continue;
            int cx = (int) (cur >> 32), cy = (int) cur;
            if (cur == goal || (goalSolid && Math.max(Math.abs(cx - gx), Math.abs(cy - gy)) <= 1)) {
                found = cur;
                break;
            }
            if (++expanded > 400_000)
                break;
            float gc = g.get(cur);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = cx + dx, ny = cy + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    long nk = key(nx, ny);
                    if (closed.contains(nk) || blocked.contains(nk) || footprints.contains(nk)) continue;
                    if (!passable(world, nx, ny) && nk != goal) continue;
                    if (dx != 0 && dy != 0 && (!passable(world, cx + dx, cy) || !passable(world, cx, cy + dy))) continue;
                    float step = (dx != 0 && dy != 0) ? 1.4142f : 1f;
                    float ng = gc + step;
                    Float old = g.get(nk);
                    if (old != null && old <= ng) continue;
                    g.put(nk, ng);
                    came.put(nk, cur);
                    open.add(new long[]{nk, Float.floatToIntBits(ng + heuristic(nx, ny, gx, gy))});
                }
            }
        }
        if (found == -1)
            return null;
        List<Vector2> rev = new ArrayList<>();
        long cur = found;
        while (cur != start) {
            int cx = (int) (cur >> 32), cy = (int) cur;
            rev.add(new Vector2((cx + 0.5f) * ts, (cy + 0.5f) * ts));
            cur = came.get(cur);
        }
        List<Vector2> out = new ArrayList<>();
        for (int i = rev.size() - 1; i >= 0; i--)
            out.add(rev.get(i));
        if (!goalSolid)
            out.add(toPx.cpy());
        return simplify(out);
    }

    /** Drop waypoints that lie on a straight run so the walk does not stutter every tile. */
    private static List<Vector2> simplify(List<Vector2> in) {
        if (in.size() < 3)
            return in;
        List<Vector2> out = new ArrayList<>();
        out.add(in.get(0));
        for (int i = 1; i < in.size() - 1; i++) {
            Vector2 a = out.get(out.size() - 1), b = in.get(i), c = in.get(i + 1);
            float cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
            if (Math.abs(cross) > 0.5f)
                out.add(b);
        }
        out.add(in.get(in.size() - 1));
        return out;
    }

    private static boolean passable(World world, int x, int y) {
        return !world.isColliding(x, y);
    }

    private static float heuristic(int x, int y, int gx, int gy) {
        int dx = Math.abs(x - gx), dy = Math.abs(y - gy);
        return Math.max(dx, dy) + 0.4142f * Math.min(dx, dy);
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max - 1, v));
    }

    /** Pixel center of a point of interest (its map sprite's centre). */
    static Vector2 poiCenter(PointOfInterest poi) {
        return poi.getCenter().cpy();
    }

    /** The active point of interest whose rectangle the player currently overlaps, or null. */
    static PointOfInterest poiUnderPlayer(GameStage stage) {
        PlayerSprite p = stage.getPlayerSprite();
        if (p == null)
            return null;
        com.badlogic.gdx.math.Rectangle me = new com.badlogic.gdx.math.Rectangle(p.getX() + 4, p.getY(), Math.max(1, p.getWidth() - 6), Math.max(1, p.getHeight() * 0.4f));
        for (PointOfInterest poi : Current.world().getAllPointOfInterest())
            if (poi.getActive() && poi.getBoundingRectangle().overlaps(me))
                return poi;
        return null;
    }

    /** The closest passable tile outside {@code poi}'s rectangle (plus a one-tile margin), as a pixel target. */
    private static Vector2 nearestClearTile(GameStage stage, PointOfInterest poi) {
        World world = Current.world();
        int ts = world.getTileSize();
        Vector2 me = playerCenter(stage);
        int mx = (int) (me.x / ts), my = (int) (me.y / ts);
        com.badlogic.gdx.math.Rectangle r = poi.getBoundingRectangle();
        int x0 = (int) Math.floor(r.x / ts) - 1, y0 = (int) Math.floor(r.y / ts) - 1;
        int x1 = (int) Math.floor((r.x + r.width) / ts) + 1, y1 = (int) Math.floor((r.y + r.height) / ts) + 1;
        Vector2 best = null;
        float bestD = Float.MAX_VALUE;
        for (int radius = 1; radius <= 8 && best == null; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != radius) continue;
                    int tx = mx + dx, ty = my + dy;
                    if (tx < 0 || ty < 0 || tx >= world.getWidthInTiles() || ty >= world.getHeightInTiles()) continue;
                    if (tx >= x0 && tx <= x1 && ty >= y0 && ty <= y1) continue;
                    if (world.isColliding(tx, ty)) continue;
                    Vector2 c = new Vector2((tx + 0.5f) * ts, (ty + 0.5f) * ts);
                    float d = c.dst2(me);
                    if (d < bestD) { bestD = d; best = c; }
                }
            }
        }
        return best;
    }
}
