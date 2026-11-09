package forge.adventure.util;

import com.badlogic.gdx.Gdx;
import forge.Forge;
import forge.adventure.scene.DuelScene;
import forge.adventure.scene.GameScene;
import forge.adventure.scene.TileMapScene;
import forge.assets.ImageCache;

/**
 * Round 997 (notes/r127-test/report.md P3-2): a lost duel's card art stayed loaded until the next win. Round 965 frees
 * duel art in RewardScene.quitScene, and a loss has no loot screen, so on a losing streak {@code assets=} climbed about 20
 * a loss, up to the 300-card cap, on the phones with the least memory to spare. Now the first world or dungeon map after
 * any duel frees it, win or loss: a win's loot screen loads only its own cards and frees them again when it closes.
 *
 * <p>Called from {@link SessionLog#onScene} (Forge.switchScene and switchToLast), so neither DuelScene nor Forge changes.
 * The unload is posted to the next frame, after the scene switch has finished. {@code [TFR-Memory] duel art freed}.
 */
public final class DuelArtRelease {
    enum Kind { DUEL, MAP, OTHER }

    private static boolean afterDuel;

    private DuelArtRelease() {
    }

    /** One scene change; true when the duel's art should be freed now. Other scenes (an Inn bracket, a dialog scene)
     *  keep the mark, so the map the player finally reaches frees it. */
    static synchronized boolean step(Kind kind) {
        if (kind == Kind.DUEL) {
            afterDuel = true;
            return false;
        }
        if (kind == Kind.MAP && afterDuel) {
            afterDuel = false;
            return true;
        }
        return false;
    }

    static synchronized void reset() {
        afterDuel = false;
    }

    /** SessionLog.onScene. Never throws. */
    public static void onScene(Object scene) {
        try {
            Kind kind = scene instanceof DuelScene ? Kind.DUEL
                    : scene instanceof GameScene || scene instanceof TileMapScene ? Kind.MAP : Kind.OTHER;
            if (!step(kind) || Gdx.app == null)
                return;
            final String to = scene.getClass().getSimpleName();
            Gdx.app.postRunnable(() -> {
                try {
                    int before = Forge.getAssets().manager().getLoadedAssets();
                    ImageCache.getInstance().unloadCardTextures(true);
                    int after = Forge.getAssets().manager().getLoadedAssets();
                    System.out.println("[TFR-Memory] duel art freed on the return to " + to + ": assets " + before
                            + " -> " + after);
                } catch (Exception e) {
                    System.err.println("[TFR-Memory] freeing duel art failed: " + e);
                }
            });
        } catch (Exception e) {
            System.err.println("[TFR-Memory] duel art release skipped: " + e);
        }
    }
}
