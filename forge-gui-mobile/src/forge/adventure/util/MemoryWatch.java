package forge.adventure.util;

import forge.Forge;
import forge.assets.ImageCache;
import forge.game.Game;
import forge.game.GameView;
import forge.game.player.Player;
import forge.game.undo.UndoManager;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.screens.match.MatchController;

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
 */
public final class MemoryWatch {
    private static final double HIGH = 0.78; // 400 MB of a phone's 512
    private static final double REARM = 0.60;
    private static final long SAMPLE_MS = 2_000;
    private static final long MIN_GAP_MS = 60_000;

    private static ScheduledExecutorService timer;
    private static boolean armed = true;
    private static long lastLineAt;
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

    /** SessionLog.onScene, a duel started. */
    public static void onDuel() {
        duelSeen = true;
    }

    private static void sample() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long used = runtime.totalMemory() - runtime.freeMemory();
            long max = runtime.maxMemory();
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
            System.out.println("[TFR-Memory] " + why + " used=" + ((runtime.totalMemory() - runtime.freeMemory()) >> 20)
                    + "MB of " + (runtime.maxMemory() >> 20) + "MB " + gameText() + " " + imagesText());
        } catch (Throwable ignored) { // the heap is full; the next crossing tries again
        }
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
            return "game=" + (game.isGameOver() ? "over" : "live") + " cards=" + cards + " battlefield=" + battlefield
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
}
