package forge;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.Disposable;
import forge.adventure.scene.HudScene;
import forge.util.ScreenUtil;

public class Adventure implements Disposable {
    public static Adventure instance;
    private float transitionTimeout;
    boolean sceneWasSwapped;
    private SpriteBatch transitionBatch, uiBatch;
    public boolean renderTransitionScreen = true;
    private boolean isDisposed = false;

    private Adventure() {
        sceneWasSwapped = false;
        transitionBatch = new SpriteBatch(Forge.LOW_SPRITES_CAP);
        // adventureBatch is used on UIScene so every scene passed will use this shared batch
        // instead of creating new SpriteBatch each with default 1000 capacity (14 scenes currently)
        uiBatch = new SpriteBatch(Forge.HIGH_SPRITES_CAP);
    }

    public SpriteBatch getUiBatch() {
        return uiBatch;
    }

    public static Adventure getInstance() {
        return instance == null ? instance = new Adventure() : instance;
    }

    void render(float delta) {
        try {
            if (renderTransitionScreen) {
                // Transition Overlay
                float transitionTime = 0.12f;
                if (sceneWasSwapped) {
                    sceneWasSwapped = false;
                    transitionTimeout = transitionTime;
                    clearScreen();
                    return;
                }
                if (transitionTimeout >= 0) {
                    clearScreen();
                    transitionBatch.begin();
                    transitionTimeout -= delta;
                    transitionBatch.setColor(1, 1, 1, 1);
                    transitionBatch.draw(ScreenUtil.getInstance().getLastScreenTexture(), 0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    transitionBatch.setColor(1, 1, 1, 1 - (1 / transitionTime) * transitionTimeout);
                    transitionBatch.draw(Forge.getAssets().fallback_skins().get("transition"), 0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    FrameRate.getInstance().sampleAdventure(transitionBatch, Forge.showFPS);
                    transitionBatch.end();
                    if (transitionTimeout < 0) {
                        Forge.currentScene.render();
                        Forge.storeScreen();
                        clearScreen();
                    } else {
                        return;
                    }
                }
                if (transitionTimeout >= -transitionTime) {
                    clearScreen();
                    transitionBatch.begin();
                    transitionTimeout -= delta;
                    transitionBatch.setColor(1, 1, 1, 1);
                    transitionBatch.draw(ScreenUtil.getInstance().getLastScreenTexture(), 0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    transitionBatch.setColor(1, 1, 1, (1 / transitionTime) * (transitionTimeout + transitionTime));
                    transitionBatch.draw(Forge.getAssets().fallback_skins().get("transition"), 0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    FrameRate.getInstance().sampleAdventure(transitionBatch, Forge.showFPS);
                    transitionBatch.end();
                    return;
                }
            }
            // Adventure UIScene
            Forge.currentScene.render();
            Forge.currentScene.act(delta);
            if (Forge.currentScene instanceof HudScene hudScene)
                FrameRate.getInstance().sampleAdventure(hudScene.getBatch(), Forge.showFPS);
        } catch (IllegalStateException | NullPointerException ie) {
            // Round 143 (code review S4-6). This was "//silence this.. //TODO: Don't silence
            // this." - and it is the RENDER loop, so a real drawing bug threw here sixty times a
            // second and left nothing behind at all. The reason it was silenced is sound: logging
            // every occurrence would bury the log under one identical stack trace per frame. So
            // log the FIRST of each distinct exception and count the rest.
            //
            // Distinctness is the exception class plus its top stack frame, which is what
            // separates two different bugs while collapsing the same bug repeating - the message
            // alone is usually null on an NPE, and the whole trace is too noisy to key on.
            reportSilencedRenderException(ie);
        }
    }

    /** Round 143 (S4-6): first occurrence of each distinct render-loop exception, then a count. */
    private static final java.util.Map<String, Integer> silencedRenderExceptions = new java.util.HashMap<>();
    private static final int SILENCED_REPORT_EVERY = 600; // ~10s at 60fps, for something still going wrong

    private static void reportSilencedRenderException(RuntimeException ie) {
        StackTraceElement[] trace = ie.getStackTrace();
        String key = ie.getClass().getSimpleName() + " at " + (trace.length > 0 ? trace[0].toString() : "(no frame)");
        int seen = silencedRenderExceptions.merge(key, 1, Integer::sum);
        if (seen == 1) {
            System.err.println("[TFR-Render] swallowed exception (first occurrence): " + key);
            ie.printStackTrace();
        } else if (seen % SILENCED_REPORT_EVERY == 0) {
            System.err.println("[TFR-Render] still swallowing: " + key + " x" + seen);
        }
    }

    void clearScreen() {
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    }
    @Override
    public void dispose() {
        if (!isDisposed) {
            isDisposed = true;
            Forge.safeDispose(transitionBatch, uiBatch);
        }
    }
}
