package forge.adventure.util;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.viewport.ScalingViewport;
import com.badlogic.gdx.utils.viewport.Viewport;
import forge.adventure.stage.GameHUD;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * TFR round 990 (logs-1108b P2-1): keeps the three singleton adventure stages' screen viewports in step with the window.
 *
 * <p>libGDX's {@code Stage} sizes its viewport to the window once, in its constructor. {@code GameHUD}, {@code MapStage}
 * and {@code WorldStage} are built once a launch, before the menu, and {@code Forge.resize} only reaches the current
 * scene ({@code HudScene} has no resize of its own). A launch that loads at 0x0 (minimized, or alt-tabbed during the
 * splash) therefore kept a 0x0 screen viewport on all three for the whole launch. Map dialogs live on the HUD stage; a
 * {@code Dialog} is a {@code Window} that clips its children with a scissor computed from that viewport, so the frame
 * drew empty and every click unprojected to nowhere - the "freeze turning in the slime quest". A windowed game resized
 * after launch had the same stale size, so its clip and clicks were off too.
 *
 * <p>The two game stages are NOT given a plain {@code viewport.update}: {@code GameStage.setBounds} sets their world
 * size to the map's bounds (used to clamp the camera), and {@code update} ends in {@code apply}, which copies the world
 * size into {@code camera.viewportWidth/Height} - the camera would show the whole map. They get only their screen
 * bounds (what the scissor and unproject read); the camera, which follows the player, is left alone. The HUD's world
 * size is the intended size and its camera never moves, so it gets the ordinary {@code update(w, h, true)}.
 */
public final class StageViewports {
    private StageViewports() {
    }

    /** MapStage and WorldStage register themselves here from the GameStage constructor (WorldStage's instance is private). */
    private static final Set<Stage> GAME_STAGES =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    /** Window drags fire a resize a frame; their fixes are logged at most this often. Every other fix is logged. */
    private static final long RESIZE_LOG_GAP_MS = 1000L;
    private static long lastResizeLog = Long.MIN_VALUE / 2;

    /** Called by the GameStage constructor. Never constructs anything. */
    public static void track(Stage gameStage) {
        if (gameStage != null)
            GAME_STAGES.add(gameStage);
    }

    /** Syncs to the window's current size; {@code why} goes into the log line ("on enter", "on resume", ...). */
    public static void syncToWindow(String why) {
        if (Gdx.graphics == null)
            return;
        sync(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), why);
    }

    /**
     * Brings every built singleton stage's screen viewport to {@code w x h}. A 0 or negative size (the window
     * minimized) changes nothing: the next real size fixes it. Stages not built yet are skipped, never built here.
     * Returns how many stages it changed.
     */
    public static int sync(int w, int h, String why) {
        if (!isRealSize(w, h))
            return 0;
        int changed = 0;
        long now = System.currentTimeMillis();
        boolean quiet = "on resize".equals(why) && now - lastResizeLog < RESIZE_LOG_GAP_MS;
        GameHUD hud = GameHUD.instance;
        if (hud != null && fit(hud.getViewport(), w, h, true, "HUD", why, quiet))
            changed++;
        List<Stage> stages;
        synchronized (GAME_STAGES) {
            stages = new ArrayList<>(GAME_STAGES);
        }
        for (Stage stage : stages) {
            if (stage != null && fit(stage.getViewport(), w, h, false, stage.getClass().getSimpleName(), why, quiet))
                changed++;
        }
        if (changed > 0 && !quiet && "on resize".equals(why))
            lastResizeLog = now;
        return changed;
    }

    /** True when the window size is one a viewport can take (a minimized window reports 0x0). */
    static boolean isRealSize(int w, int h) {
        return w > 0 && h > 0;
    }

    /** True when this viewport's screen size is broken (0-wide or 0-high) or differs from the window's. */
    public static boolean isStale(Viewport viewport, int windowW, int windowH) {
        if (viewport == null || !isRealSize(windowW, windowH))
            return false;
        int[] want = targetBounds(viewport, windowW, windowH);
        return viewport.getScreenX() != want[0] || viewport.getScreenY() != want[1]
                || viewport.getScreenWidth() != want[2] || viewport.getScreenHeight() != want[3];
    }

    /** {x, y, width, height} the viewport's update would give it for this window, without touching GL or the camera. */
    static int[] targetBounds(Viewport viewport, int w, int h) {
        if (viewport instanceof ScalingViewport scaling) {
            Vector2 scaled = scaling.getScaling().apply(viewport.getWorldWidth(), viewport.getWorldHeight(), w, h);
            int vw = Math.round(scaled.x), vh = Math.round(scaled.y);
            return new int[]{(w - vw) / 2, (h - vh) / 2, vw, vh};
        }
        return new int[]{0, 0, w, h};
    }

    /**
     * Sets only the screen bounds, leaving the camera's size and position as they are. Pure: no GL call, so the game
     * stages use it and the unit test can too.
     */
    static boolean fitScreenOnly(Viewport viewport, int w, int h) {
        if (viewport == null || !isStale(viewport, w, h))
            return false;
        int[] want = targetBounds(viewport, w, h);
        viewport.setScreenBounds(want[0], want[1], want[2], want[3]);
        return true;
    }

    private static boolean fit(Viewport viewport, int w, int h, boolean fullUpdate, String name, String why,
                               boolean quiet) {
        if (viewport == null || !isStale(viewport, w, h))
            return false;
        int oldW = viewport.getScreenWidth(), oldH = viewport.getScreenHeight();
        if (fullUpdate)
            viewport.update(w, h, true);
        else
            fitScreenOnly(viewport, w, h);
        // A fix from a broken (0-sized) viewport is always logged; a window drag's run of ordinary resizes is thinned.
        if (!quiet || !isRealSize(oldW, oldH))
            logFix(name, oldW, oldH, viewport.getScreenWidth(), viewport.getScreenHeight(), why);
        return true;
    }

    /** One line a fix: "[TFR-Viewport] HUD stage was 0x0 - updated to 1920x1080 (on enter)". */
    public static void logFix(String name, int oldW, int oldH, int newW, int newH, String why) {
        System.out.println("[TFR-Viewport] " + name + " stage was " + oldW + "x" + oldH + " - updated to " + newW + "x"
                + newH + " (" + why + ")");
    }
}
