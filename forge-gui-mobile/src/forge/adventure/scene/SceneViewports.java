package forge.adventure.scene;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.viewport.Viewport;
import forge.adventure.util.StageViewports;

/**
 * TFR round 990 (logs-1108b P2-1, the wider half): a UI scene that was not on screen when the window changed size kept
 * its old screen viewport - {@code Forge.resize} reaches only the current scene. Found in the round's agent test: after
 * a windowed resize on the map, the inventory (built earlier) drew its item list empty, its scroll pane clipped by the
 * stale viewport; a scene built during a 0x0 launch and entered later would be the same. Before a scene is entered,
 * {@code Forge.switchScene} and {@code switchToLast} call {@link #beforeEnter}: a stale UI scene gets its own stock
 * {@code resize}, exactly what it would have had on screen. In this package so it can read {@code UIScene.stage}
 * (protected) without a stock accessor. HudScene has its own backstop in {@code enter()}.
 */
public final class SceneViewports {
    private SceneViewports() {
    }

    public static void beforeEnter(Scene scene) {
        if (!(scene instanceof UIScene ui) || ui.stage == null || Gdx.graphics == null)
            return;
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        Viewport viewport = ui.stage.getViewport();
        if (!StageViewports.isStale(viewport, w, h))
            return;
        int oldW = viewport.getScreenWidth(), oldH = viewport.getScreenHeight();
        scene.resize(w, h);
        StageViewports.logFix(scene.getClass().getSimpleName(), oldW, oldH, viewport.getScreenWidth(),
                viewport.getScreenHeight(), "on enter");
    }
}
