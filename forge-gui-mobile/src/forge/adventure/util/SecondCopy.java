package forge.adventure.util;

/**
 * Round 1013 (notes/logs-1111b.md P3-26: a desktop player had the game open twice on one save for 14 minutes, and both
 * copies wrote the same auto and quick slots; the user: "Yes, start both"). {@link LastExit} already knew - its
 * {@code lastexit.lock} was held by the other copy - but told nobody. Now the start screen asks once per launch, before
 * the player can load or start a game: <b>Quit</b> (the default) or <b>Play anyway</b> (today's behavior).
 * <p>
 * Telling a real second copy from a false alarm is the lock's own job: {@link LastExit} holds the lock for the life of
 * its process, and the OS lets go of it when a process ends, killed or crashed. So a lock file a crash left behind is
 * free again and is simply taken ({@link Lock#TAKEN}), a lock this same JVM already holds throws
 * OverlappingFileLockException ({@link Lock#SAME_PROCESS}), and only a lock another live process holds makes
 * {@code tryLock} return null ({@link Lock#HELD_ELSEWHERE}). A file system without locks ({@link Lock#UNSUPPORTED})
 * says nothing either way, so it never warns.
 * <p>
 * Desktop only: Android runs one process per app (a second tap on the icon brings the running game back), and
 * LastExit doesn't run there at all. The texts are in the plane's {@code config tables/tips.json}
 * ({@code secondCopy.*}). [TFR-SecondCopy].
 */
public final class SecondCopy {
    /** What {@link LastExit} found when it asked for {@code lastexit.lock}. */
    public enum Lock {
        /** Taken: no other copy (a file a crash left is free again, and is taken too). */
        TAKEN,
        /** Another live process holds it: a second copy of the game. */
        HELD_ELSEWHERE,
        /** This JVM already holds it (OverlappingFileLockException): the same copy, not a second one. */
        SAME_PROCESS,
        /** No lock to be had on this file system: unknown, so no warning. */
        UNSUPPORTED,
        /** Not asked (Android, iOS, or before LastExit ran). */
        NOT_CHECKED
    }

    private static volatile Lock state = Lock.NOT_CHECKED;
    private static boolean asked;

    private SecondCopy() {
    }

    /** The pure rule: warn only when another live process holds the lock, and never on a phone. */
    public static boolean shouldWarn(Lock lock, boolean mobile) {
        return !mobile && lock == Lock.HELD_ELSEWHERE;
    }

    /** LastExit.onStart: what the lock said. */
    static void record(Lock lock) {
        state = lock == null ? Lock.NOT_CHECKED : lock;
        if (state == Lock.HELD_ELSEWHERE)
            System.out.println("[TFR-SecondCopy] detected: another copy of the game holds lastexit.lock; the start screen"
                    + " will warn");
    }

    public static Lock state() {
        return state;
    }

    /** StartScene.enter: true once per launch when the warning should show now (and marks it shown). */
    public static synchronized boolean takeWarning() {
        if (asked)
            return false;
        boolean mobile;
        try {
            mobile = forge.gui.GuiBase.isAndroid() || forge.gui.GuiBase.isIOS();
        } catch (RuntimeException e) {
            mobile = false;
        }
        if (!shouldWarn(state, mobile))
            return false;
        asked = true;
        return true;
    }

    /** The player's answer, for the log. */
    public static void answered(boolean quit) {
        System.out.println("[TFR-SecondCopy] " + (quit ? "quit: the player closed this copy"
                : "play anyway: the player kept this copy open beside the other one"));
    }

    /** A secondCopy.* text of tips.json; the plane always has them, the fallback only keeps a broken file from a blank. */
    public static String text(String key) {
        String s = null;
        try {
            s = TfrTips.text("secondCopy." + key);
        } catch (RuntimeException e) {
            System.err.println("[TFR-SecondCopy] tips.json text " + key + " failed: " + e);
        }
        return s != null ? s : key;
    }
}
