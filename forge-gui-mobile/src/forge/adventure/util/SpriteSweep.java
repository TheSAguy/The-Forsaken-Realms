package forge.adventure.util;

import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import forge.Forge;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Round 969 (notes/r965-asset-climb.md, fix B): an enemy sprite atlas ({@code sprites/enemy/**}) loaded through
 * {@link Config#getAtlas} the first time an enemy kind was shown and was never unloaded - 36% of a long session's
 * {@code assets=} climb, about 2.4 MB a kind, 5.97 GB if a player met every kind. On each switch to the world map
 * ({@code GameScene}, from {@link SessionLog#onScene}) this unloads the least recently used enemy atlases past
 * {@link #cap} that nothing alive still draws from, and logs {@code [TFR-SpriteSweep]}.
 *
 * <p>"Alive" is a weak reference per holder, kept under the atlas's file: every {@code CharacterSprite} registers itself
 * when it loads ({@link #beginLoad}), and every Avatar it hands out is a fresh copy registered too
 * ({@code CharacterSprite.getAvatar}), so the Image a dialog, the Arena, an event or PlayerStatisticScene wraps round it
 * keeps the atlas loaded for as long as that Image is kept. A collected holder no longer counts; one the collector has
 * not reached yet still does, so the sweep errs toward keeping.
 *
 * <p>Pinned (never swept): an enemy atlas whose regions Config handed out to code that does not register -
 * {@code getAtlasSprite}, {@code getPOISprites}, or {@code getAnimatedSprites} outside a CharacterSprite's load (a map's
 * PortalActor). Direct {@code getAtlas} callers on an enemy file were audited in round 969: TransitionScreen makes its
 * VS Avatar from the atlas each frame (a swept atlas just loads again), and CharacterSprite.readFootprint keeps no
 * region. A new caller that keeps a region from an enemy atlas must {@link #hold} it.
 */
public final class SpriteSweep {
    private static final String ENEMY_DIR = "/sprites/enemy/";
    /** About 150 MB of enemy pages at the soak's 2.4 MB a kind; {@code spritesweep cap N} changes it for the launch. */
    static final int DEFAULT_CAP = 64;
    private static int cap = DEFAULT_CAP;

    /** File (Config.getFile(path).path()) -> the holders that draw from it. */
    private static final Map<String, List<WeakReference<Object>>> holders = new HashMap<>();
    /** File -> the paths Config cached sprites under for it (animatedSprites/atlasSprites are keyed by the path). */
    private static final Map<String, Set<String>> pathsOf = new HashMap<>();
    private static final Map<String, String> fileOf = new HashMap<>();
    /** File -> the sweep tick it was last used or last seen held. */
    private static final Map<String, Long> lastUse = new HashMap<>();
    private static final Set<String> pinned = new HashSet<>();
    private static long tick = 0;
    private static int sweeps = 0, unloadedTotal = 0;
    private static final ThreadLocal<Object> loading = new ThreadLocal<>();

    private SpriteSweep() {
    }

    static boolean isEnemy(String file) {
        return file != null && file.replace('\\', '/').contains(ENEMY_DIR);
    }

    /** Config.getAtlas: the last use of an enemy atlas, and which path it was asked for under. */
    static synchronized void used(String path, String file) {
        if (!isEnemy(file))
            return;
        lastUse.put(file, ++tick);
        fileOf.put(path, file);
        pathsOf.computeIfAbsent(file, k -> new HashSet<>()).add(path);
    }

    /** Config.getAtlasSprite / getPOISprites: sprites cached for whoever asks, so the atlas stays for the launch. */
    static synchronized void pin(String path) {
        String file = fileOf(path);
        if (isEnemy(file) && pinned.add(file))
            System.out.println("[TFR-SpriteSweep] pinned " + shortName(file) + " (its sprites went to an unregistered holder)");
    }

    /** Config.getAnimatedSprites: outside a CharacterSprite's load, the frames go to a holder nobody registered. */
    static void handedOut(String path) {
        if (loading.get() == null)
            pin(path);
    }

    /** CharacterSprite.load: the sprite holds this atlas from now on; endLoad() closes the bracket. */
    public static void beginLoad(Object sprite, String path) {
        loading.set(sprite);
        hold(path, sprite);
    }

    public static void endLoad() {
        loading.remove();
    }

    /** {@code holder} draws regions of the atlas at {@code path} for as long as it is reachable. */
    public static synchronized void hold(String path, Object holder) {
        if (path == null || path.isEmpty() || holder == null)
            return;
        String file = fileOf(path);
        if (!isEnemy(file))
            return;
        List<WeakReference<Object>> list = holders.computeIfAbsent(file, k -> new ArrayList<>());
        for (Iterator<WeakReference<Object>> it = list.iterator(); it.hasNext(); ) {
            Object o = it.next().get();
            if (o == null)
                it.remove();
            else if (o == holder)
                return;
        }
        list.add(new WeakReference<>(holder));
    }

    private static String fileOf(String path) {
        String file = fileOf.get(path);
        if (file == null) {
            try {
                file = Config.instance().getFile(path).path();
            } catch (RuntimeException e) {
                file = path;
            }
            fileOf.put(path, file);
        }
        return file;
    }

    private static int liveHolders(String file) {
        List<WeakReference<Object>> list = holders.get(file);
        if (list == null)
            return 0;
        list.removeIf(r -> r.get() == null);
        return list.size();
    }

    /** SessionLog.onScene: the world map is coming up - no duel, no dungeon. */
    public static void onWorldMap() {
        try {
            sweep(cap, "world map");
        } catch (RuntimeException e) { // never let the sweep take the map down with it
            System.out.println("[TFR-SpriteSweep] sweep failed: " + e);
        }
    }

    static synchronized String sweep(int limit, String why) {
        AssetManager manager = Forge.getAssets().manager();
        List<String> loaded = new ArrayList<>();
        for (String file : lastUse.keySet())
            if (manager.isLoaded(file, TextureAtlas.class))
                loaded.add(file);
        if (loaded.size() <= limit)
            return "[TFR-SpriteSweep] " + loaded.size() + " enemy atlas(es) loaded, cap " + limit + " - nothing to do";
        int assetsBefore = manager.getLoadedAssets();
        List<String> free = new ArrayList<>();
        int live = 0, pins = 0;
        for (String file : loaded) {
            if (pinned.contains(file)) {
                pins++;
            } else if (liveHolders(file) > 0) {
                live++;
                lastUse.put(file, ++tick); // still drawn: as recent as now
            } else if (holders.containsKey(file)) {
                free.add(file); // a CharacterSprite loaded it once and none is left
            } else {
                pins++; // loaded by something that never registered: keep it
            }
        }
        free.sort((a, b) -> Long.compare(lastUse.get(a), lastUse.get(b)));
        int toUnload = Math.min(loaded.size() - limit, free.size());
        long bytes = 0;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < toUnload; i++) {
            String file = free.get(i);
            TextureAtlas atlas = manager.get(file, TextureAtlas.class, false);
            if (atlas != null)
                for (Texture t : atlas.getTextures())
                    bytes += (long) t.getWidth() * t.getHeight() * 4;
            Set<String> paths = pathsOf.remove(file);
            if (paths != null)
                for (String path : paths)
                    Config.instance().forgetSprites(path);
            holders.remove(file);
            manager.unload(file);
            if (i < 6)
                names.append(i > 0 ? ", " : "").append(shortName(file));
        }
        if (toUnload > 6)
            names.append(", +").append(toUnload - 6);
        sweeps++;
        unloadedTotal += toUnload;
        String line = "[TFR-SpriteSweep] " + why + ": unloaded " + toUnload + " atlas(es) (~" + (bytes >> 20) + " MB"
                + (toUnload > 0 ? ": " + names : "") + "), " + (loaded.size() - toUnload) + " kept (" + live
                + " held by a live sprite or image, " + pins + " pinned, " + (free.size() - toUnload)
                + " recent), cap " + limit + ", assets " + assetsBefore + " -> " + manager.getLoadedAssets()
                + " (sweep " + sweeps + ", " + unloadedTotal + " unloaded this launch)";
        if (toUnload > 0 || !"world map".equals(why)) // a map return that frees nothing is not worth a line each time
            System.out.println(line);
        return line;
    }

    private static String shortName(String file) {
        String f = file.replace('\\', '/');
        int at = f.indexOf(ENEMY_DIR);
        return at < 0 ? f : f.substring(at + ENEMY_DIR.length());
    }

    /** Console: {@code spritesweep} (status), {@code spritesweep now [gc]}, {@code spritesweep cap N [gc]} (sweeps at N). */
    public static String console(String[] s) {
        boolean gc = s.length > 0 && "gc".equalsIgnoreCase(s[s.length - 1]);
        if (gc) {
            System.gc(); // test only: let the weak references of dead sprites clear before the sweep reads them
            System.runFinalization();
        }
        if (s.length >= 2 && "cap".equalsIgnoreCase(s[0])) {
            try {
                cap = Math.max(0, Integer.parseInt(s[1]));
            } catch (NumberFormatException e) {
                return "spritesweep cap N: N is a number";
            }
            return sweep(cap, "console");
        }
        if (s.length >= 1 && "now".equalsIgnoreCase(s[0]))
            return sweep(cap, "console");
        return status();
    }

    static synchronized String status() {
        AssetManager manager = Forge.getAssets().manager();
        int loaded = 0, live = 0, pins = 0;
        for (String file : lastUse.keySet()) {
            if (!manager.isLoaded(file, TextureAtlas.class))
                continue;
            loaded++;
            if (pinned.contains(file))
                pins++;
            else if (liveHolders(file) > 0)
                live++;
        }
        return "[TFR-SpriteSweep] " + loaded + " enemy atlas(es) loaded (" + live + " held, " + pins + " pinned), cap "
                + cap + ", assets " + manager.getLoadedAssets() + ", " + sweeps + " sweep(s), " + unloadedTotal
                + " unloaded this launch";
    }
}
