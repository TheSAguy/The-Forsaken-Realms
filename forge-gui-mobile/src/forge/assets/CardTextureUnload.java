package forge.assets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * TFR round 985 (logs-1108 P3-11): ImageCache's "unload every card texture" loops, one file at a time.
 *
 * ImageCache.disposeTextures() and unloadCardTextures(true) used to unload the whole cardsLoaded set inside one try:
 * one exception partway through (a download adding to the set while the loop walked it, or one bad unload) stopped the
 * loop, and disposeTextures() then cleared the set anyway, so the rest stayed loaded and could never be freed again
 * (885 card textures on one 1.27 install). Here the set is copied first, each file gets its own try, a file that
 * unloaded is dropped from the set, and a file whose unload threw stays in it for the next unload to retry.
 */
final class CardTextureUnload {
    private static final AtomicBoolean failureLogged = new AtomicBoolean(false);

    private CardTextureUnload() {
    }

    /**
     * Unloads every path in {@code loaded} with {@code unloadOne}, each on its own. Returns how many failed; those stay
     * in {@code loaded}. The first failure of a launch is logged as {@code [TFR-Memory] card texture unload failed}.
     */
    static int unloadAll(Set<String> loaded, Consumer<String> unloadOne) {
        List<String> paths = snapshot(loaded);
        int failed = 0;
        for (String path : paths) {
            try {
                unloadOne.accept(path);
                loaded.remove(path);
            } catch (RuntimeException e) {
                failed++;
                logOnce(path, e);
            }
        }
        return failed;
    }

    /** A copy of the set; a copy that collides with a concurrent add is simply taken again. */
    private static List<String> snapshot(Set<String> loaded) {
        ConcurrentModificationException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return new ArrayList<>(loaded);
            } catch (ConcurrentModificationException e) {
                last = e;
            }
        }
        logOnce("(copying " + loaded.size() + " paths)", last);
        return Collections.emptyList();
    }

    private static void logOnce(String path, Exception e) {
        if (failureLogged.compareAndSet(false, true))
            System.err.println("[TFR-Memory] card texture unload failed: " + path + " " + e
                    + " (logged once a launch; the file stays listed for the next unload)");
    }

    /** For tests: lets the next failure log again. */
    static void resetFailureLogForTest() {
        failureLogged.set(false);
    }
}
