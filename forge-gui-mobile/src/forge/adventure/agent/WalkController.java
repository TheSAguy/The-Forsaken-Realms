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
 * over the world's collision tiles on the overworld and, inside a town or dungeon, from A* over an 8 px grid of
 * the map's collision with the player's own box (round 448; the enemies' navigation graph is the fallback). Also
 * runs the "wait N days" clock.
 */
final class WalkController {
    /** Round 214: how long a finished walk will stand on its destination waiting for world
     *  collision to come back before reporting arrival. The flicker GameHUD waits out is ~2 s;
     *  this is comfortably longer, and bounded so a walk can never hang on it. */
    private static final float ARRIVAL_COLLISION_GRACE = 3.5f;

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
    /** Round 214: seconds spent standing on the destination waiting for world collision to come
     *  back before the walk is allowed to report "arrived" - see the hold in tick(). */
    private float arrivalWait;

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
        if (worldWalk) {
            // Round 175: see AgentStageAccess.exemptPoiUnderPlayer() - fresh out of a town the player
            // still stands on it, and the first step used to walk straight back in.
            bridge.log("[TFR-Agent] walk start: " + forge.adventure.stage.AgentStageAccess.describeSurroundings());
            String exempt = forge.adventure.stage.AgentStageAccess.exemptPoiUnderPlayer();
            if (exempt != null)
                bridge.log("[TFR-Agent] standing on " + exempt + " - skipped by the entry check until the player steps off");
        }
        finalTarget = destPx.cpy();
        target = description;
        blocked.clear();
        mapGrid = null; // round 448: a new walk builds its own grid
        walkMap = worldWalk ? null : MapStage.getInstance().tiledMap;
        replans = 0;
        List<Vector2> p = plan(stage, playerCenter(stage), destPx);
        if (p == null)
            return failed("no path to " + description);
        path = p;
        index = 0;
        noProgress = 0;
        lastDist = Float.MAX_VALUE;
        walkTime = 0;
        arrivalWait = 0;
        walking = true;
        done = new CompletableFuture<>();
        StringBuilder first = new StringBuilder();
        for (int i = 0; i < Math.min(3, p.size()); i++)
            first.append(String.format(" (%.0f,%.0f)", p.get(i).x, p.get(i).y));
        Vector2 from = playerCenter(stage);
        bridge.log("[TFR-Agent] walk: " + description + " via " + p.size() + " waypoint(s), from "
                + String.format("(%.0f,%.0f)", from.x, from.y) + " first" + first);
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
        if (!worldWalk && walkMap != null && MapStage.getInstance().tiledMap != walkMap) {
            finish(true, "the level changed - took the stairs or a portal to " + target); // round 448
            return;
        }
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
            // Round 214: do not report "arrived" while world collision is still OFF.
            //
            // TileMapScene.leave() sets the world player's collisionHeight to 0 and GameHUD only
            // switches it back after the ~2 s arrival flicker (see AgentStageAccess.
            // exemptPoiUnderPlayer()'s comment). WorldStage's POI entry test is collideWith(), which
            // is always false at height 0 - so a walk that LANDS on a point of interest inside that
            // window enters nothing, and the player is left standing on an un-entered POI. The next
            // walk then calls exemptPoiUnderPlayer(), which sets collidingPoint to the POI underfoot
            // to stop the player walking back into the town they just left - and that seals the
            // un-entered POI shut for good ("standing on White Tower - skipped by the entry check
            // until the player steps off"). Observed in the 2026-09-15 session: four attempts to
            // enter the same tower, each reporting "arrived at White Tower" from the world map.
            //
            // Holding here costs nothing on a normal walk (collision is on, the branch never runs)
            // and lets the game's own entry check fire on the very next frame once collision
            // returns. The grace is bounded so a walk can never hang on it.
            if (worldWalk && player != null && player.getCollisionHeight() <= 0f
                    && arrivalWait < ARRIVAL_COLLISION_GRACE) {
                if (arrivalWait == 0f)
                    bridge.log("[TFR-Agent] reached " + target + " while world collision is still off"
                            + " - holding so the game's entry check can fire");
                arrivalWait += delta;
                stage.setTouchKnobInput(0, 0); // stand still rather than drift off the footprint
                return;
            }
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
        if (!worldWalk && noProgress > 0.4f) {
            // Round 448: inside a map a gap can be exactly as wide as the player's box (Cultists' Outpost's level-3
            // stairs: a 2 px strip of trigger beside a wall under the stair art), and a fraction of a pixel off holds the
            // player against the corner. After 0.4 s without progress, lean sideways - alternating sides every 0.25 s -
            // so the game's own wall sliding (GameStage.adjustMovement) frees it before a replan.
            float side = ((int) (noProgress / 0.25f)) % 2 == 0 ? 1f : -1f;
            dir.add(-dir.y * 0.7f * side, dir.x * 0.7f * side).nor();
        }
        stage.setTouchKnobInput(dir.x, dir.y);
        if (walkTime > 240f)
            finish(false, "gave up after 240s of walking");
    }

    private boolean replan(GameStage stage, Vector2 me) {
        // Round 448: a map walk closes one snagged cell per replan, so it gets a few more tries than a world walk.
        if (++replans > (worldWalk ? 4 : 8)) {
            finish(false, "stuck near " + tileOf(me) + " on the way to " + target);
            return false;
        }
        if (worldWalk && index < path.size()) {
            Vector2 wp = path.get(index);
            World world = Current.world();
            int ts = world.getTileSize();
            blocked.add(key((int) (wp.x / ts), (int) (wp.y / ts)));
        }
        if (!worldWalk && stage.getPlayerSprite() != null) {
            // Round 448: what the player is actually up against - its box and every wall rectangle within 12 px of it.
            com.badlogic.gdx.math.Rectangle box = stage.getPlayerSprite().boundingRect();
            StringBuilder near = new StringBuilder();
            for (com.badlogic.gdx.math.Rectangle r : MapStage.getInstance().collisionRect)
                if (r.x < box.x + box.width + 12 && r.x + r.width > box.x - 12 && r.y < box.y + box.height + 12
                        && r.y + r.height > box.y - 12)
                    near.append(String.format(" [%.1f,%.1f %.1fx%.1f]", r.x, r.y, r.width, r.height));
            bridge.log(String.format("[TFR-Agent] snagged at (%.1f,%.1f): box [%.1f,%.1f %.1fx%.1f], walls near:%s", me.x, me.y,
                    box.x, box.y, box.width, box.height, near.length() == 0 ? " none" : near.toString()));
        }
        if (!worldWalk && mapGrid != null && index < path.size()) {
            // Round 448: inside a map, the cell just ahead of the player toward the waypoint is where it snagged - close
            // it for the rest of this walk (the grid's own cell, so the line-of-sight shortcuts respect it too), unless
            // that is where the walk has to arrive.
            Vector2 dir = path.get(index).cpy().sub(me);
            if (dir.len2() > 0.0001f) {
                Vector2 ahead = me.cpy().add(dir.nor().scl(MAP_CELL));
                int i = mapGrid.ci(ahead.x), j = mapGrid.cj(ahead.y);
                // Never a cell where the walk has to arrive: one touching the target's trigger (it can be the only way
                // in - the level-3 stairs above), or an exact target's own cell.
                boolean goalCell = mapGrid.goalTrigger != null ? mapGrid.touches(i, j, mapGrid.goalTrigger)
                        : i == mapGrid.ci(finalTarget.x) && j == mapGrid.cj(finalTarget.y);
                if (!goalCell && !(i == mapGrid.ci(me.x) && j == mapGrid.cj(me.y))) {
                    mapGrid.solid[j * mapGrid.cols + i] = true;
                    mapGrid.stuckBlocks++;
                }
            }
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
        // Round 448: the map's own grid first (the player's collision box, stairs kept off), the enemy graph if it finds
        // nothing, a straight line last.
        List<Vector2> grid = planMapGrid(stage, from, to);
        return grid != null ? grid : planMap(from, to);
    }

    // ------------------------------------------------------------------ round 448: maps

    /**
     * Round 448 (the agent test of round 443: Cultists' Outpost's lower floors - "stuck near" at every gap, and level 3's
     * arrival walked straight back up its stairs). The enemy graph planMap() borrows is built for a 6.4 px enemy with a
     * fixed -8 px offset, not the player's 10 x 6.4 px feet, so it routed through gaps the player snags in; a replan
     * returned the same path four times; and it knew nothing of the stairs, so a path could cross the up-stairs the
     * player had just arrived beside and send them back. This plans on an 8 px grid stamped with the player's own
     * collision box against MapStage.collisionRect - the rectangles the player's movement really tests - keeps off every
     * stairs, portal and exit trigger but the destination's (and the one the player stands in, which moving inside does
     * not trigger), and a replan blocks the cell it got stuck at.
     */
    private static final float MAP_CELL = 8f;
    /** The current map walk's grid, built once per walk and reused by its replans. */
    private MapGrid mapGrid;
    /** The level a map walk started on. Stairs and portals swap the map inside the same MapStage, so the stage check in
     *  frame() never sees it - the walk went on following the old floor's path on the new one ("stuck near"). */
    private com.badlogic.gdx.maps.tiled.TiledMap walkMap;

    private static final class MapGrid {
        int cols, rows;
        boolean[] solid;
        // the player's collision box around playerCenter() (CharacterSprite.updateBoundingRect: x+4, y, w-6, h*0.4)
        float left, right, bottom, top;
        int walls, triggers, stuckBlocks;
        com.badlogic.gdx.math.Rectangle goalTrigger;

        boolean open(int i, int j) {
            return i >= 0 && j >= 0 && i < cols && j < rows && !solid[j * cols + i];
        }

        float cx(int i) {
            return (i + 0.5f) * MAP_CELL;
        }

        float cy(int j) {
            return (j + 0.5f) * MAP_CELL;
        }

        int ci(float x) {
            return clamp((int) (x / MAP_CELL), cols);
        }

        int cj(float y) {
            return clamp((int) (y / MAP_CELL), rows);
        }

        /** Solid: every cell where the player's box would overlap r (strict overlap, like Rectangle.overlaps). */
        void stamp(com.badlogic.gdx.math.Rectangle r) {
            int i0 = Math.max(0, (int) Math.floor((r.x - right) / MAP_CELL - 0.5f) + 1);
            int i1 = Math.min(cols - 1, (int) Math.ceil((r.x + r.width - left) / MAP_CELL - 0.5f) - 1);
            int j0 = Math.max(0, (int) Math.floor((r.y - top) / MAP_CELL - 0.5f) + 1);
            int j1 = Math.min(rows - 1, (int) Math.ceil((r.y + r.height - bottom) / MAP_CELL - 0.5f) - 1);
            for (int j = j0; j <= j1; j++)
                for (int i = i0; i <= i1; i++)
                    solid[j * cols + i] = true;
        }

        /** Would the player's box at this cell's centre touch r? */
        boolean touches(int i, int j, com.badlogic.gdx.math.Rectangle r) {
            float x = cx(i), y = cy(j);
            return x + left < r.x + r.width && x + right > r.x && y + bottom < r.y + r.height && y + top > r.y;
        }
    }

    private MapGrid buildMapGrid(GameStage stage, Vector2 to) {
        MapStage ms = MapStage.getInstance();
        if (ms.tiledMap == null || stage.getPlayerSprite() == null)
            return null;
        com.badlogic.gdx.maps.MapProperties props = ms.tiledMap.getProperties();
        float w = Float.parseFloat(props.get("width").toString()) * Float.parseFloat(props.get("tilewidth").toString());
        float h = Float.parseFloat(props.get("height").toString()) * Float.parseFloat(props.get("tileheight").toString());
        MapGrid g = new MapGrid();
        g.cols = (int) Math.ceil(w / MAP_CELL);
        g.rows = (int) Math.ceil(h / MAP_CELL);
        if (g.cols <= 0 || g.rows <= 0 || (long) g.cols * g.rows > 4_000_000L)
            return null;
        g.solid = new boolean[g.cols * g.rows];
        PlayerSprite player = stage.getPlayerSprite();
        Vector2 center = playerCenter(stage);
        com.badlogic.gdx.math.Rectangle box = player.boundingRect(); // what PlayerSprite.act() moves with
        if (box != null && box.width > 0f && box.height > 0f) {
            g.left = box.x - center.x;
            g.right = box.x + box.width - center.x;
            g.bottom = box.y - center.y;
            g.top = box.y + box.height - center.y;
        } else { // collision switched off for a moment - CharacterSprite.updateBoundingRect's usual shape
            float pw = player.getWidth(), ph = player.getHeight();
            g.left = 4f - pw / 2f;
            g.right = pw / 2f - 2f;
            g.bottom = -0.2f * ph;
            g.top = 0.2f * ph;
        }
        for (com.badlogic.gdx.math.Rectangle r : ms.collisionRect) {
            g.stamp(r);
            g.walls++;
        }
        Vector2 me = playerCenter(stage);
        com.badlogic.gdx.math.Rectangle myBox = new com.badlogic.gdx.math.Rectangle(me.x + g.left, me.y + g.bottom,
                g.right - g.left, g.top - g.bottom);
        for (forge.adventure.character.MapActor a : AgentStageAccess.mapActors()) {
            if (!(a instanceof forge.adventure.character.EntryActor) && !(a instanceof forge.adventure.character.OnCollide))
                continue;
            com.badlogic.gdx.math.Rectangle r = a.boundingRect();
            if (r == null || r.width <= 0f || r.height <= 0f)
                continue;
            if (r.contains(to)) {
                g.goalTrigger = new com.badlogic.gdx.math.Rectangle(r);
                continue;
            }
            if (r.overlaps(myBox))
                continue; // standing in it: moving inside triggers nothing (MapActor.collideWithPlayer fires on entry)
            g.stamp(r);
            g.triggers++;
        }
        return g;
    }

    private List<Vector2> planMapGrid(GameStage stage, Vector2 from, Vector2 to) {
        if (mapGrid == null)
            mapGrid = buildMapGrid(stage, to);
        MapGrid g = mapGrid;
        if (g == null)
            return null;
        int si = g.ci(from.x), sj = g.cj(from.y);
        // The goal: the cells touching the destination's trigger (stairs, an exit), else the destination's own cell,
        // else the nearest open cell to it (a destination drawn into a wall).
        int gi = g.ci(to.x), gj = g.cj(to.y);
        boolean exactGoal = g.goalTrigger == null && g.open(gi, gj);
        if (g.goalTrigger == null && !exactGoal) {
            float best = Float.MAX_VALUE;
            int bi = -1, bj = -1;
            for (int r = 1; r <= 6 && bi < 0; r++)
                for (int dj = -r; dj <= r; dj++)
                    for (int di = -r; di <= r; di++) {
                        if (Math.max(Math.abs(di), Math.abs(dj)) != r || !g.open(gi + di, gj + dj))
                            continue;
                        float d = new Vector2(g.cx(gi + di), g.cy(gj + dj)).dst2(to);
                        if (d < best) {
                            best = d;
                            bi = gi + di;
                            bj = gj + dj;
                        }
                    }
            if (bi < 0)
                return null;
            gi = bi;
            gj = bj;
        }
        int n = g.cols * g.rows;
        float[] cost = new float[n];
        java.util.Arrays.fill(cost, Float.MAX_VALUE);
        int[] came = new int[n];
        boolean[] closed = new boolean[n];
        int start = sj * g.cols + si;
        cost[start] = 0f;
        PriorityQueue<float[]> open = new PriorityQueue<>((a, b) -> Float.compare(a[1], b[1]));
        open.add(new float[]{start, heuristic(si, sj, gi, gj)});
        int found = -1;
        while (!open.isEmpty()) {
            int cur = (int) open.poll()[0];
            if (closed[cur])
                continue;
            closed[cur] = true;
            int ci = cur % g.cols, cj = cur / g.cols;
            if (g.goalTrigger != null ? g.touches(ci, cj, g.goalTrigger) : ci == gi && cj == gj) {
                found = cur;
                break;
            }
            for (int dj = -1; dj <= 1; dj++)
                for (int di = -1; di <= 1; di++) {
                    if (di == 0 && dj == 0)
                        continue;
                    int ni = ci + di, nj = cj + dj;
                    if (!g.open(ni, nj))
                        continue;
                    if (di != 0 && dj != 0 && (!g.open(ci + di, cj) || !g.open(ci, cj + dj)))
                        continue; // no corner cutting
                    int nk = nj * g.cols + ni;
                    if (closed[nk])
                        continue;
                    float ng = cost[cur] + (di != 0 && dj != 0 ? 1.4142f : 1f);
                    if (ng >= cost[nk])
                        continue;
                    cost[nk] = ng;
                    came[nk] = cur;
                    open.add(new float[]{nk, ng + heuristic(ni, nj, gi, gj)});
                }
        }
        if (found < 0)
            return null;
        List<Vector2> cells = new ArrayList<>();
        for (int cur = found; cur != start; cur = came[cur])
            cells.add(0, new Vector2(g.cx(cur % g.cols), g.cy(cur / g.cols)));
        cells.add(0, from.cpy());
        if (exactGoal || g.goalTrigger != null)
            cells.add(to.cpy());
        List<Vector2> out = smoothMap(g, cells);
        out.remove(0); // the player's own position
        if (out.isEmpty())
            out.add(to.cpy());
        bridge.log("[TFR-Agent] map plan: " + g.cols + "x" + g.rows + " cells of " + (int) MAP_CELL + " px, " + g.walls
                + " wall rect(s), " + g.triggers + " stairs/exit trigger(s) kept off"
                + (g.goalTrigger != null ? ", walking onto the target's trigger" : exactGoal ? "" : ", target in a wall - nearest open cell")
                + (g.stuckBlocks == 0 ? "" : ", " + g.stuckBlocks + " cell(s) blocked after getting stuck")
                + " -> " + out.size() + " waypoint(s)");
        return out;
    }

    /** Line-of-sight shortcuts over the grid: from each kept point, the farthest later point reachable in a straight
     *  line through open cells (sampled every half cell). The first point is the player's own cell, open or not. */
    private static List<Vector2> smoothMap(MapGrid g, List<Vector2> pts) {
        List<Vector2> out = new ArrayList<>();
        out.add(pts.get(0));
        int i = 0;
        while (i < pts.size() - 1) {
            int j = pts.size() - 1;
            while (j > i + 1 && !lineOpen(g, pts.get(i), pts.get(j), i == 0))
                j--;
            out.add(pts.get(j));
            i = j;
        }
        return out;
    }

    private static boolean lineOpen(MapGrid g, Vector2 a, Vector2 b, boolean fromPlayer) {
        float len = a.dst(b);
        int steps = Math.max(1, (int) Math.ceil(len / (MAP_CELL / 2f)));
        int startI = g.ci(a.x), startJ = g.cj(a.y), endI = g.ci(b.x), endJ = g.cj(b.y);
        for (int s = 1; s < steps; s++) {
            float t = s / (float) steps;
            int i = g.ci(a.x + (b.x - a.x) * t), j = g.cj(a.y + (b.y - a.y) * t);
            if ((fromPlayer && i == startI && j == startJ) || (i == endI && j == endJ))
                continue;
            if (!g.open(i, j))
                return false;
        }
        return true;
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

    /** Overworld: A* over collision tiles, 8-way without corner cutting. Round 175: strict about the
     *  point of interest the player stands next to first, lenient only if that finds no path. */
    private List<Vector2> planWorld(Vector2 fromPx, Vector2 toPx) {
        List<Vector2> strict = planWorld(fromPx, toPx, false);
        return strict != null ? strict : planWorld(fromPx, toPx, true);
    }

    private List<Vector2> planWorld(Vector2 fromPx, Vector2 toPx, boolean lenient) {
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
            // Round 175: the far edges are exclusive - a 16px town at x 5600 covers tile 350 only; the
            // old floor((x + width) / ts) counted tile 351 as well, so a player standing just north of
            // the Secluded Encampment was "inside" it and got every neighbour opened.
            int rx0 = (int) Math.floor(r.x / ts), ry0 = (int) Math.floor(r.y / ts);
            int rx1 = (int) Math.floor((r.x + r.width - 0.01f) / ts), ry1 = (int) Math.floor((r.y + r.height - 0.01f) / ts);
            int x0 = rx0 - 1, y0 = ry0 - 1, x1 = rx1 + 1, y1 = ry1 + 1;
            boolean isGoal = gx >= x0 && gx <= x1 && gy >= y0 && gy <= y1;
            boolean isStart = sx >= x0 && sx <= x1 && sy >= y0 && sy <= y1;
            boolean insideStart = isStart && sx >= rx0 && sx <= rx1 && sy >= ry0 && sy <= ry1;
            if (isGoal)
                continue; // the one we are walking to
            // Round 277: standing INSIDE a footprint opens the WHOLE of it, not just the 3x3 around the
            // player. The exemption below only ever opened the player's own neighbourhood, which is enough
            // for a 16px POI and not enough for anything bigger: Shimmering Crossing's rectangle is 48x48 px
            // = 3x3 tiles, 5x5 with its margin, so a player in the middle could take one step and no more -
            // every route out had to cross the ring at distance 2, and the planner answered "no path" to
            // everything. `goto`, `explore` and `wait` (which steps clear first) all failed, which means NO
            // bridge command could move the player at all: a permanent strand, found when a death respawn
            // dropped the player there during a soak (player rect [8210,5816 10x6] inside poiRect
            // [8189,5796 48x48]). The way out was an in-game teleport, the Homeward rune.
            //
            // Safe, and for the same reason the 3x3 exemption was safe: the game already exempts the POI
            // underfoot from entry (exemptPoiUnderPlayer()) until the player steps off, so crossing the rest
            // of its own footprint enters nothing.
            if (insideStart)
                continue;
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++) {
                    // Standing ON a footprint (the game puts you there when you leave a town): the
                    // tiles right around you stay open so the path can step OUT - the game skips the
                    // town you stand on (exemptPoiUnderPlayer()) - but the rest of it is off-limits.
                    // Round 175: standing NEXT to one, the margin stays closed: stepping from there into
                    // the ring around the rectangle swept the player's body across its corner, and the
                    // first isolated session walked back into the Secluded Encampment on every walk.
                    // `lenient` (the retry when that leaves no path) reopens the margin tiles only.
                    boolean inRect = x >= rx0 && x <= rx1 && y >= ry0 && y <= ry1;
                    boolean nextToMe = isStart && Math.max(Math.abs(x - sx), Math.abs(y - sy)) <= 1;
                    if (nextToMe && (insideStart || (lenient && !inRect)))
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
