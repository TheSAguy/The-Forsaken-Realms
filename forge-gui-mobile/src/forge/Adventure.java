package forge;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import forge.adventure.scene.HudScene;
//import forge.util.ScreenUtil;

public class Adventure {
    public static Adventure instance;
    //private float transitionTimeout;
    boolean sceneWasSwapped;
    public boolean renderTransitionScreen = true;

    private Adventure() {
        sceneWasSwapped = false;
    }

    public static Adventure getInstance() {
        return instance == null ? instance = new Adventure() : instance;
    }

    void render(float delta) {
        try {
            // TODO: Use better transistion. Fixes Android slow screen updates on switching scenes.
            /*if (renderTransitionScreen) {
                Forge.getGraphics().getBatch().setProjectionMatrix(Forge.camera.combined);
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
                    Forge.getGraphics().getBatch().begin();
                    transitionTimeout -= delta;
                    Forge.getGraphics().getBatch().setColor(1, 1, 1, 1);
                    Forge.getGraphics().getBatch().draw(ScreenUtil.getInstance().getLastScreenTexture(), 0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    Forge.getGraphics().getBatch().setColor(1, 1, 1, 1 - (1 / transitionTime) * transitionTimeout);
                    Forge.getGraphics().getBatch().draw(Forge.getAssets().fallback_skins().get("transition"), 0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    FrameRate.getInstance().sampleAdventure(Forge.getGraphics().getBatch(), Forge.showFPS);
                    Forge.getGraphics().getBatch().end();
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
                    Forge.getGraphics().getBatch().begin();
                    transitionTimeout -= delta;
                    Forge.getGraphics().getBatch().setColor(1, 1, 1, 1);
                    Forge.getGraphics().getBatch().draw(ScreenUtil.getInstance().getLastScreenTexture(), 0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    Forge.getGraphics().getBatch().setColor(1, 1, 1, (1 / transitionTime) * (transitionTimeout + transitionTime));
                    Forge.getGraphics().getBatch().draw(Forge.getAssets().fallback_skins().get("transition"), 0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    FrameRate.getInstance().sampleAdventure(Forge.getGraphics().getBatch(), Forge.showFPS);
                    Forge.getGraphics().getBatch().end();
                    return;
                }
            }*/
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
            // Round 316: upstream's 09.23 daily (#11888) added its own logOnce() here; ours is kept (it
            // counts repeats and keys on the first frame in Forge's own code), with its scene name added.
            reportSilencedRenderException(ie);
        }
    }

    /** Round 143 (S4-6): first occurrence of each distinct render-loop exception, then a count. */
    private static final java.util.Map<String, Integer> silencedRenderExceptions = new java.util.HashMap<>();
    private static final int SILENCED_REPORT_EVERY = 600; // ~10s at 60fps, for something still going wrong

    private static void reportSilencedRenderException(RuntimeException ie) {
        StackTraceElement[] trace = ie.getStackTrace();
        // Round 183 (code review D6): the throwing frame AND the first frame in Forge's own code. Keying on the top
        // frame alone put every call site that throws from the same JDK/libGDX method under one key, so only the
        // first of them ever printed a trace.
        String site = null;
        for (StackTraceElement frame : trace) {
            if (frame.getClassName().startsWith("forge.")) {
                site = frame.toString();
                break;
            }
        }
        String top = trace.length > 0 ? trace[0].toString() : "(no frame)";
        String key = ie.getClass().getSimpleName() + " at " + top
                + (site == null || site.equals(top) ? "" : " via " + site);
        int seen = silencedRenderExceptions.merge(key, 1, Integer::sum);
        if (seen == 1) {
            System.err.println("[TFR-Render] swallowed exception (first occurrence) in scene "
                    + (Forge.currentScene == null ? "null" : Forge.currentScene.getClass().getSimpleName()) + ": " + key);
            ie.printStackTrace();
        } else if (seen % SILENCED_REPORT_EVERY == 0) {
            System.err.println("[TFR-Render] still swallowing: " + key + " x" + seen);
        }
    }

    void clearScreen() {
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    }
}
