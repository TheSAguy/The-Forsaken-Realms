package forge.adventure.util;

import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.utils.Scaling;
import com.badlogic.gdx.utils.viewport.ScalingViewport;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * TFR round 990 (logs-1108b P2-1): the stage-viewport sync's decisions. A stage built while the window was 0x0 is stale
 * and gets the window's size; a minimized window (0x0) changes nothing; the game stages' cameras keep their size and
 * position, though their world size is the map's bounds. No GL here: only the screen-bounds path runs.
 */
public class StageViewportsTest {
    private static final float INTENDED_W = 480, INTENDED_H = 270;

    /** A stage's viewport as the Stage constructor leaves it at a 0x0 launch: world = intended, screen = 0x0. */
    private static ScalingViewport builtAtZero() {
        ScalingViewport viewport = new ScalingViewport(Scaling.stretch, INTENDED_W, INTENDED_H, new OrthographicCamera());
        viewport.setScreenBounds(0, 0, 0, 0);
        viewport.getCamera().viewportWidth = INTENDED_W;
        viewport.getCamera().viewportHeight = INTENDED_H;
        return viewport;
    }

    @Test
    public void realSizeNeedsBothSides() {
        AssertJUnit.assertTrue(StageViewports.isRealSize(1920, 1080));
        AssertJUnit.assertFalse(StageViewports.isRealSize(0, 0));
        AssertJUnit.assertFalse(StageViewports.isRealSize(1920, 0));
        AssertJUnit.assertFalse(StageViewports.isRealSize(0, 1080));
        AssertJUnit.assertFalse(StageViewports.isRealSize(-1, 1080));
    }

    @Test
    public void zeroViewportIsStaleOnceTheWindowIsReal() {
        ScalingViewport viewport = builtAtZero();
        AssertJUnit.assertTrue(StageViewports.isStale(viewport, 1920, 1080));
        // the window still minimized: nothing to fix yet, and nothing it could be fixed to
        AssertJUnit.assertFalse(StageViewports.isStale(viewport, 0, 0));
        AssertJUnit.assertFalse(StageViewports.isStale(null, 1920, 1080));
    }

    @Test
    public void matchingViewportIsLeftAlone() {
        ScalingViewport viewport = builtAtZero();
        viewport.setScreenBounds(0, 0, 1280, 720);
        AssertJUnit.assertFalse(StageViewports.isStale(viewport, 1280, 720));
        AssertJUnit.assertFalse(StageViewports.fitScreenOnly(viewport, 1280, 720));
    }

    @Test
    public void windowedResizeMakesItStale() {
        ScalingViewport viewport = builtAtZero();
        viewport.setScreenBounds(0, 0, 1280, 720);
        AssertJUnit.assertTrue(StageViewports.isStale(viewport, 1600, 900));
        AssertJUnit.assertTrue(StageViewports.fitScreenOnly(viewport, 1600, 900));
        AssertJUnit.assertEquals(1600, viewport.getScreenWidth());
        AssertJUnit.assertEquals(900, viewport.getScreenHeight());
    }

    @Test
    public void gameStageKeepsItsCamera() {
        // GameStage.setBounds: world size = the map's bounds; the camera shows the intended size round the player
        ScalingViewport viewport = builtAtZero();
        viewport.setWorldSize(3200, 2400);
        OrthographicCamera camera = (OrthographicCamera) viewport.getCamera();
        camera.position.set(1234, 567, 0);
        camera.zoom = 0.8f;

        AssertJUnit.assertTrue(StageViewports.fitScreenOnly(viewport, 1920, 1080));
        AssertJUnit.assertEquals(0, viewport.getScreenX());
        AssertJUnit.assertEquals(0, viewport.getScreenY());
        AssertJUnit.assertEquals(1920, viewport.getScreenWidth());
        AssertJUnit.assertEquals(1080, viewport.getScreenHeight());
        // what viewport.update(w, h, false) would have broken: apply() copies the world size into the camera
        AssertJUnit.assertEquals(INTENDED_W, camera.viewportWidth, 0.001f);
        AssertJUnit.assertEquals(INTENDED_H, camera.viewportHeight, 0.001f);
        AssertJUnit.assertEquals(1234f, camera.position.x, 0.001f);
        AssertJUnit.assertEquals(567f, camera.position.y, 0.001f);
        AssertJUnit.assertEquals(0.8f, camera.zoom, 0.001f);
    }

    @Test
    public void fitScaling() {
        // a letterboxing viewport gets the bounds its own update() would give it
        ScalingViewport viewport = new ScalingViewport(Scaling.fit, 160, 90, new OrthographicCamera());
        viewport.setScreenBounds(0, 0, 0, 0);
        int[] bounds = StageViewports.targetBounds(viewport, 1000, 900);
        AssertJUnit.assertEquals(0, bounds[0]);
        AssertJUnit.assertEquals(1000, bounds[2]);
        AssertJUnit.assertEquals(563, bounds[3]); // 1000 * 90 / 160 = 562.5, rounded
        AssertJUnit.assertEquals((900 - 563) / 2, bounds[1]);
    }

    @Test
    public void syncIgnoresAMinimizedWindow() {
        AssertJUnit.assertEquals(0, StageViewports.sync(0, 0, "on resize"));
    }
}
