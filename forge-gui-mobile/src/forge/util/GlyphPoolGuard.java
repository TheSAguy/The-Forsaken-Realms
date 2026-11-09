package forge.util;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Pool;

import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;

/**
 * TFR round 995 (logs-1109b P2-1, a player's End Turn "twitch"). libGDX 1.14.2's {@link GlyphLayout} takes the runs of
 * every text it lays out from one static, unsynchronized pool ({@code glyphRunPool}). Text laid out off the GL thread
 * races the GL thread's own layouts and can leave a null, or the same {@code GlyphRun} twice, in the pool's free list.
 * A null throws the caught "[TFR-Render] text not drawn" NPE ("run" is null). A duplicate hands one run to two lines of a
 * caption, so "End\nTurn" lays out as one shared run, misplaced, and the button's caption twitches. libGDX never drops
 * a duplicate (freeing the layout puts it back twice), so the pool stays broken until the process ends.
 * <p>
 * {@link #heal} scans the free list for nulls and identity duplicates and empties it with the public
 * {@link Pool#clear()}; libGDX makes new runs on demand. It runs on the GL thread only (from anywhere else it is posted
 * there): at each turn's start in a match and after a caught text failure. {@link #checkThread} logs the first layout of
 * a launch made off the GL thread, with its stack, so the next player log names the racing caller. The pool is read by
 * reflection, never written; if that fails (a future libGDX), the guard logs once and does nothing.
 */
public final class GlyphPoolGuard {
    private GlyphPoolGuard() {
    }

    /** One scan of the free list. */
    public static final class Report {
        public final int free, duplicates, nulls;

        Report(int free, int duplicates, int nulls) {
            this.free = free;
            this.duplicates = duplicates;
            this.nulls = nulls;
        }

        public boolean damaged() {
            return duplicates > 0 || nulls > 0;
        }

        @Override
        public String toString() {
            return free + " free, " + duplicates + " duplicates, " + nulls + " nulls";
        }
    }

    private static boolean resolved;
    private static Pool<GlyphLayout.GlyphRun> runPool;
    private static Array<GlyphLayout.GlyphRun> freeObjects;

    private static int heals, damagedHeals, nextDamagedLog = 1;
    private static final AtomicBoolean healPending = new AtomicBoolean();
    private static final AtomicInteger offThreadLayouts = new AtomicInteger();
    private static volatile boolean measureFailureReported;
    private static volatile Thread glThreadForTest;
    /** Console {@code glyphpool poison N hold}: no heal until {@code glyphpool heal}, so a shot can show the damage. */
    private static volatile boolean heldForTest;

    /** True on the GL thread (the thread {@code Forge.create} ran on), and wherever the GUI is not up (tests). */
    static boolean isGlThread() {
        Thread test = glThreadForTest;
        if (test != null)
            return Thread.currentThread() == test;
        IGuiBase gui = GuiBase.getInterface();
        return gui == null || gui.isGuiThread();
    }

    @SuppressWarnings("unchecked")
    private static synchronized boolean resolve() {
        if (resolved)
            return runPool != null;
        resolved = true;
        try {
            Field poolField = GlyphLayout.class.getDeclaredField("glyphRunPool");
            poolField.setAccessible(true);
            Pool<GlyphLayout.GlyphRun> pool = (Pool<GlyphLayout.GlyphRun>) poolField.get(null);
            Field listField = Pool.class.getDeclaredField("freeObjects");
            listField.setAccessible(true);
            Array<GlyphLayout.GlyphRun> list = (Array<GlyphLayout.GlyphRun>) listField.get(pool);
            if (pool == null || list == null)
                throw new IllegalStateException("the pool or its free list is null");
            runPool = pool;
            freeObjects = list;
            return true;
        } catch (Exception | LinkageError e) {
            System.err.println("[TFR-GlyphPool] guard off: libGDX's glyph run pool not found (" + e
                    + "); the text pool is not healed this launch");
            return false;
        }
    }

    /** Counts the free list's nulls and identity duplicates. Read-only; call it on the GL thread. */
    static Report scan() {
        Array<GlyphLayout.GlyphRun> list = freeObjects;
        Object[] items = list.items;
        int size = Math.min(list.size, items.length);
        int nulls = 0, duplicates = 0;
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>(size * 2 + 1);
        for (int i = 0; i < size; i++) {
            Object run = items[i];
            if (run == null)
                nulls++;
            else if (seen.put(run, Boolean.TRUE) != null)
                duplicates++;
        }
        return new Report(size, duplicates, nulls);
    }

    /**
     * Scans and empties libGDX's glyph run pool. On the GL thread it runs now and returns the scan; anywhere else it is
     * posted to the GL thread (once until it runs) and returns null. Logs the first heal of a launch, and a damaged pool
     * at its 1st, 2nd, 4th, 8th... damaged heal.
     */
    public static Report heal(String reason) {
        if (!isGlThread()) {
            if (Gdx.app != null && healPending.compareAndSet(false, true)) {
                Gdx.app.postRunnable(() -> {
                    healPending.set(false);
                    heal(reason);
                });
            }
            return null;
        }
        if (!resolve() || (heldForTest && !"console".equals(reason)))
            return null;
        heldForTest = false;
        Report report = scan();
        runPool.clear();
        heals++;
        if (report.damaged()) {
            damagedHeals++;
            if (damagedHeals == nextDamagedLog) {
                nextDamagedLog *= 2;
                System.err.println("[TFR-GlyphPool] " + reason + ": " + report + " - cleared (damaged pool " + damagedHeals
                        + " this launch, logged at 1, 2, 4, 8...; off-GL-thread layouts so far: " + offThreadLayouts.get()
                        + ")");
            }
        } else if (heals == 1) {
            System.out.println("[TFR-GlyphPool] guard on: first heal at " + reason + ", " + report
                    + " - cleared (clean heals are not logged)");
        }
        return report;
    }

    /**
     * One thread comparison. The first text layout of a launch made off the GL thread is logged with its stack; later
     * ones are only counted (the count is in the next damaged-pool line and the console's {@code glyphpool}).
     */
    public static void checkThread(String where) {
        if (isGlThread())
            return;
        if (offThreadLayouts.getAndIncrement() == 0) {
            Thread t = Thread.currentThread();
            System.err.println("[TFR-GlyphPool] text laid out off the GL thread: " + t.getName() + " in " + where
                    + " (logged once a launch; libGDX's text pool is not thread-safe)");
            new Throwable("off-GL-thread text layout, thread " + t.getName()).printStackTrace();
        }
    }

    /**
     * A caught failure inside libGDX's text layout while measuring: logged once a launch; the failed layout's runs are
     * dropped, then the pool is healed. Round 995 (follow-up, the agent proof): a layout whose setText threw midway keeps
     * the broken runs (a run twice, glyphs and advances out of step). Its next setText starts with reset(), which frees
     * them into the pool again, so a heal alone left the same text failing every frame. Dropping them (to the garbage
     * collector, never back to the pool) ends that.
     */
    public static void onLayoutFailure(String where, CharSequence text, Exception e, GlyphLayout failed) {
        if (!measureFailureReported) {
            measureFailureReported = true;
            System.err.println("[TFR-GlyphPool] text not measured in " + where + ": " + e + ", text \"" + text
                    + "\", thread " + Thread.currentThread().getName() + " (logged once a launch)");
            e.printStackTrace();
        }
        dropRuns(failed);
        heal("after a failed measure");
    }

    /**
     * A caught failure inside {@code BitmapFont.draw}: the font cache's failed layout is out of reach (libGDX keeps it in
     * a private pool), so the cache is cleared, which frees it and its broken runs into the glyph run pool, and the heal
     * then empties that pool. On the GL thread only, where every draw runs.
     */
    public static void onDrawFailure(BitmapFont font) {
        if (font == null || !isGlThread())
            return;
        try {
            font.getCache().clear();
        } catch (RuntimeException ignored) {
            // a layout too broken to free: the heal below still empties the pool
        }
        heal("after text not drawn");
    }

    /** Forgets a layout's runs without freeing them into the pool. */
    static void dropRuns(GlyphLayout layout) {
        if (layout == null)
            return;
        layout.runs.clear();
        layout.colors.clear();
        layout.glyphCount = 0;
        layout.width = 0;
        layout.height = 0;
    }

    /** Console {@code glyphpool [state|heal|poison N [hold]]}; poison plants N duplicate runs (test only). */
    public static String console(String[] args) {
        String op = args.length > 0 ? args[0].toLowerCase() : "state";
        if (!isGlThread())
            return "glyphpool: run it on the GL thread";
        if (!resolve())
            return "glyphpool: guard off (libGDX's glyph run pool not found)";
        switch (op) {
            case "heal":
                return "glyphpool: " + heal("console") + " - cleared";
            case "poison": {
                int n = 1;
                if (args.length > 1) {
                    try {
                        n = Math.max(1, Math.min(64, Integer.parseInt(args[1])));
                    } catch (NumberFormatException ignored) {
                    }
                }
                boolean hold = args.length > 2 && "hold".equalsIgnoreCase(args[2]);
                for (int i = 0; i < n; i++) {
                    GlyphLayout.GlyphRun run = new GlyphLayout.GlyphRun();
                    runPool.free(run);
                    runPool.free(run);
                }
                heldForTest = hold;
                System.err.println("[TFR-GlyphPool] console: planted " + n + " duplicate runs (a test of round 995's heal)"
                        + (hold ? "; heals held until \"glyphpool heal\"" : ""));
                return "glyphpool: planted " + n + " duplicate runs" + (hold ? ", heals held" : "") + "; " + scan();
            }
            default:
                return "glyphpool: " + scan() + "; heals " + heals + ", damaged " + damagedHeals
                        + ", off-GL-thread layouts " + offThreadLayouts.get();
        }
    }

    // --- tests ---

    static Pool<GlyphLayout.GlyphRun> runPoolForTest() {
        return resolve() ? runPool : null;
    }

    /** Puts a null in the free list, as the race does (Pool.free refuses null). */
    static void plantNullForTest() {
        if (resolve())
            freeObjects.add(null);
    }

    static void setGlThreadForTest(Thread t) {
        glThreadForTest = t;
    }

    static int offThreadLayoutsForTest() {
        return offThreadLayouts.get();
    }

    static void resetForTest() {
        heals = 0;
        damagedHeals = 0;
        nextDamagedLog = 1;
        healPending.set(false);
        offThreadLayouts.set(0);
        measureFailureReported = false;
        glThreadForTest = null;
        heldForTest = false;
    }
}
