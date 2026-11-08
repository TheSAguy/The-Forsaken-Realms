package forge.adventure.util;

import forge.StaticData;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Round 985 (logs-1108 P3-9): builds Forge's print sheets on a background thread once a game is loaded or a new world
 * is made, so the first booster of a launch (an Inn's draft event on town entry, a shop, a reward) does not pay for it
 * on the GL thread. A 3.8 GB tablet took more than 22 s there, and a home press during it got the app killed by
 * libGDX's 4 s pause wait.
 *
 * Once per launch. The build itself is StaticData.getPrintSheets(): locked, so a booster asked for while this runs
 * waits for this build instead of starting a second one. It reads text files and the loaded card database only.
 */
public final class PrintSheetWarm {
    private static final AtomicBoolean started = new AtomicBoolean(false);

    private PrintSheetWarm() {
    }

    /** Starts the warm-up the first time it is called in a launch; later calls do nothing. */
    public static void start(String when) {
        if (!started.compareAndSet(false, true))
            return;
        // StaticData.instance(), not FModel.getMagicDb(): the latter would build the whole card database if nothing has.
        StaticData db = StaticData.instance();
        if (db == null) {
            started.set(false); // the card database is not up yet: the next load or new world tries again
            return;
        }
        final StaticData data = db;
        Thread thread = new Thread(() -> warm(data, when), "TFR-PrintSheets");
        thread.setDaemon(true); // never keeps the app (or a test JVM) alive
        thread.setPriority(Thread.MIN_PRIORITY); // a slow device's GL thread comes first
        thread.start();
    }

    static void warm(StaticData data, String when) {
        if (data.arePrintSheetsLoaded()) {
            System.out.println("[TFR-PrintSheets] already loaded " + when + ", nothing to warm");
            return;
        }
        long t0 = System.nanoTime();
        try {
            int sheets = data.getPrintSheets().size();
            System.out.println("[TFR-PrintSheets] warmed in " + (System.nanoTime() - t0) / 1000000 + " ms ("
                    + Thread.currentThread().getName() + ", " + when + ", " + sheets + " sheets)");
        } catch (Throwable e) { // an OutOfMemoryError included: the first booster then builds them as before
            System.err.println("[TFR-PrintSheets] warm failed " + when + " after " + (System.nanoTime() - t0) / 1000000
                    + " ms, the first booster builds them instead: " + e);
        }
    }
}
