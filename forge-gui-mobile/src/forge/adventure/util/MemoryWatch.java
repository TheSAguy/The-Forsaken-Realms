package forge.adventure.util;

import com.badlogic.gdx.assets.AssetManager;
import forge.Forge;
import forge.assets.ImageCache;
import forge.game.Game;
import forge.game.GameView;
import forge.game.player.Player;
import forge.game.undo.UndoManager;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.screens.match.MatchController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Round 898 (notes/logs-1031.md P1-1: two OutOfMemoryErrors on a phone, and no line saying what held the heap):
 * {@code [TFR-Memory]}, one line when the heap runs high, so the next one names its cause before undo, the heap size or
 * the image cache are touched. A share of the maximum, so a phone's 512 MB and a PC's 4 GB each get their own mark.
 *
 * <p>Fires once per crossing: it re-arms only when the heap falls back under {@link #REARM}, and never less than
 * {@link #MIN_GAP_MS} apart, so a heap sawing around the mark is one line. Used memory counts garbage not yet collected,
 * so a line alone doesn't prove the memory was held; a {@code save} line (WorldSave, right after an OutOfMemoryError,
 * when the collector has just run) does.
 *
 * <p>A daemon thread reads the numbers because the GL thread may be the one in trouble, and the game runs on threads of
 * its own, so everything here is a size read, nothing walks a list another thread may be changing, and the line never
 * throws: a full heap can fail anywhere in it. {@code game=over} is a finished game still held after its duel (the match
 * screen keeps its view until the next one).
 *
 * <p>Round 916 (notes/logs-1102.md P1-1: replayed on a duel OOM, the 78% line last fired 21 minutes before the crash,
 * because a heap that stays over 60% never re-arms it): {@code full} at 95%, once per crossing; {@code oom} from the crash
 * report; {@code duel} at each later duel's start, only over 60%, to show what stays between duels; and {@code turn},
 * {@code threads} and {@code aiEval} on every line. Thinking takes one eval thread, so {@code aiEval} above 1 is a timed-out
 * one still running (Android has no {@code Thread.stop}).
 *
 * <p>Round 942 (notes/logs-1103b.md P3-1: a 15-permanent board and every save load crossed 95% too): {@code floor=} after
 * {@code used=}, the lowest used of the last minute's samples and now. A floor near the max is memory really held; a low
 * floor says the high {@code used} was garbage the collector hadn't taken yet.
 *
 * <p>Round 949 (notes/logs-1103c.md P3-2: a phone's {@code assets=} climbed from 398 to 1,008 in 3.7 h and never fell):
 * {@code assetTypes=} and {@code assetDirs=} at the end of the line, the loaded assets counted by class and by folder
 * (top {@link #TOP}, then {@code +N} for the rest), to tell which kind keeps growing. AssetManager's methods are
 * synchronized, so the read waits for a load in progress rather than racing it.
 */
public final class MemoryWatch {
    private static final double HIGH = 0.78; // 400 MB of a phone's 512
    private static final double REARM = 0.60;
    private static final double FULL = 0.95; // 486 MB of a phone's 512
    private static final long SAMPLE_MS = 2_000;
    private static final long MIN_GAP_MS = 60_000;
    private static final int TOP = 4;

    private static ScheduledExecutorService timer;
    private static boolean armed = true;
    private static long lastLineAt;
    private static boolean fullArmed = true;
    private static long lastFullAt;
    /** Round 942: one minute of samples, a ring; read from other threads without a lock (a stale slot only moves floor=). */
    private static final long[] recent = new long[(int) (MIN_GAP_MS / SAMPLE_MS)];
    private static int recentNext;
    /** MatchController and ImageCache are only touched after a duel loaded them on the GL thread, never first from here. */
    private static volatile boolean duelSeen;

    private MemoryWatch() {
    }

    /** SessionLog.onStart. A retained Android process runs that again: one timer a process. */
    public static synchronized void start() {
        if (timer != null)
            return;
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TFR-MemoryWatch");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(MemoryWatch::sample, SAMPLE_MS, SAMPLE_MS, TimeUnit.MILLISECONDS);
    }

    /** SessionLog.onScene, a duel started. The first has no game to read, and MatchController stays unloaded until it runs. */
    public static void onDuel() {
        Runtime runtime = Runtime.getRuntime();
        if (duelSeen && runtime.totalMemory() - runtime.freeMemory() >= runtime.maxMemory() * REARM)
            report("duel");
        duelSeen = true;
    }

    private static void sample() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long used = runtime.totalMemory() - runtime.freeMemory();
            long max = runtime.maxMemory();
            recent[recentNext++ % recent.length] = used;
            if (used < max * HIGH) {
                fullArmed = true;
            } else if (fullArmed && used >= max * FULL && System.currentTimeMillis() - lastFullAt >= MIN_GAP_MS) {
                fullArmed = false;
                lastFullAt = System.currentTimeMillis();
                report("full");
            }
            if (used < max * REARM) {
                armed = true;
            } else if (armed && used >= max * HIGH && System.currentTimeMillis() - lastLineAt >= MIN_GAP_MS) {
                armed = false;
                lastLineAt = System.currentTimeMillis();
                report("high");
            }
        } catch (Throwable t) { // a scheduled task that throws never runs again
            armed = true;
        }
    }

    /** One line. Any thread, any heap: it never throws. */
    public static void report(String why) {
        try {
            Runtime runtime = Runtime.getRuntime();
            long used = runtime.totalMemory() - runtime.freeMemory();
            System.out.println("[TFR-Memory] " + why + " used=" + (used >> 20) + "MB of " + (runtime.maxMemory() >> 20)
                    + "MB floor=" + (floorOf(used) >> 20) + "MB " + gameText() + " " + imagesText() + " " + threadsText()
                    + assetsText());
        } catch (Throwable ignored) { // the heap is full; the next crossing tries again
        }
    }

    /** Round 942: the lowest of the last minute's samples and {@code used}; an empty slot (0) is no sample yet. */
    private static long floorOf(long used) {
        long floor = used;
        for (long sample : recent)
            if (sample > 0 && sample < floor)
                floor = sample;
        return floor;
    }

    private static String gameText() {
        try {
            GameView view = duelSeen ? MatchController.instance.getGameView() : null;
            Game game = view == null ? null : view.getGame();
            if (game == null)
                return "game=none";
            int cards = 0, battlefield = 0;
            for (Player player : game.getRegisteredPlayers()) {
                for (ZoneType type : ZoneType.values()) {
                    Zone zone = player.getZone(type);
                    if (zone == null)
                        continue;
                    cards += zone.size();
                    if (type == ZoneType.Battlefield)
                        battlefield += zone.size();
                }
            }
            UndoManager undo = game.getUndoManager();
            return "game=" + (game.isGameOver() ? "over" : "live") + " turn=" + game.getPhaseHandler().getTurn() + " cards=" + cards + " battlefield=" + battlefield
                    + " logEntries=" + game.getGameLog().size() + " undo=" + undo.depth() + " undoObjects=" + undo.snapshotObjects();
        } catch (Throwable t) { // another thread changed the game under us, or the heap is full
            return "game=? (" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String imagesText() {
        try {
            if (!duelSeen)
                return "images=-";
            return "images=" + ImageCache.getInstance().counter + " assets=" + Forge.getAssets().manager().getLoadedAssets();
        } catch (Throwable t) {
            return "images=? (" + t.getClass().getSimpleName() + ")";
        }
    }

    /** Round 949: " assetTypes=Texture:950,TextureAtlas:40,+3 assetDirs=cards:120,sprites:410,+5"; empty before a duel. */
    private static String assetsText() {
        try {
            if (!duelSeen)
                return "";
            AssetManager manager = Forge.getAssets().manager();
            Map<String, Integer> types = new HashMap<>(), dirs = new HashMap<>();
            for (String name : manager.getAssetNames()) {
                Class<?> type = manager.getAssetType(name);
                types.merge(type == null ? "?" : type.getSimpleName(), 1, Integer::sum);
                dirs.merge(assetDir(name), 1, Integer::sum);
            }
            return " assetTypes=" + top(types) + " assetDirs=" + top(dirs);
        } catch (Throwable t) {
            return " assetTypes=? (" + t.getClass().getSimpleName() + ")";
        }
    }

    /** The folder that says what an asset is: after pics/ (cards, tokens), after adventure/<plane>/ (sprites, maps), after res/. */
    private static String assetDir(String path) {
        String p = path.replace('\\', '/');
        int at;
        if ((at = p.indexOf("/pics/")) >= 0)
            p = p.substring(at + 6);
        else if ((at = p.indexOf("/adventure/")) >= 0) {
            p = p.substring(at + 11);
            p = p.substring(p.indexOf('/') + 1); // the plane folder, or "common"
        } else if ((at = p.indexOf("/res/")) >= 0)
            p = p.substring(at + 5);
        else {
            int slash = p.lastIndexOf('/');
            p = slash > 0 ? p.substring(p.lastIndexOf('/', slash - 1) + 1) : p;
        }
        int slash = p.indexOf('/');
        return slash > 0 ? p.substring(0, slash) : "-";
    }

    private static String top(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        int rest = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (i < TOP)
                sb.append(i == 0 ? "" : ",").append(entries.get(i).getKey().replace(' ', '_')).append(':').append(entries.get(i).getValue());
            else
                rest += entries.get(i).getValue();
        }
        if (rest > 0)
            sb.append(",+").append(rest);
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static String threadsText() {
        try {
            Thread[] threads = new Thread[Thread.activeCount() + 8];
            int count = Thread.enumerate(threads), aiEval = 0;
            for (int i = 0; i < count; i++)
                if ("Game AI Eval".equals(threads[i].getName()))
                    aiEval++;
            return "threads=" + count + " aiEval=" + aiEval;
        } catch (Throwable t) {
            return "threads=? (" + t.getClass().getSimpleName() + ")";
        }
    }
}
