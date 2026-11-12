package forge.adventure.util;

import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Round 948 (notes/logs-1103c.md P2-1: a new Windows player's log stopped three times with no end line, no exception and
 * no crash report, and nothing could say whether the window was closed while frozen or the JVM died):
 * {@code [TFR-LastExit]}, one line at launch saying how the last desktop session ended. Android has
 * {@code [TFR-ExitReason]} from the OS instead, so this runs on desktop only.
 *
 * <p>A marker in the profile folder ({@code lastexit.txt} beside forge.log, never the game folder): written at
 * {@link #onStart}, touched once a minute while the game runs, and rewritten as {@code clean} by {@link #onCleanExit}
 * (Forge.dispose, through SessionLog.end). A marker still saying {@code running} at the next launch is a session that
 * never reached its dispose: a killed process, "Close the program" on a window Windows called not responding, or a
 * native crash. A native crash usually leaves a JVM {@code hs_err_pid*.log}, so the line names one written since that
 * session started (in the game's working folder, the jar's folder or the temp folder; its name, never the path).
 *
 * <p>The last touch dates the end within a minute. A second copy of the game is told apart by a lock on
 * {@code lastexit.lock}, held for the session's life (the OS drops it when a process dies, killed or not): a copy that
 * can't take it says so and leaves the marker to the copy that holds it, and since round 1013 tells {@link SecondCopy},
 * whose start-screen dialog asks the player to quit or play anyway. Never throws: a log line must not stop a launch.
 */
public final class LastExit {
    private static final String FILE = "lastexit.txt";
    private static final String LOCK = "lastexit.lock";
    private static final long TOUCH_MS = 60_000;

    private static File marker;
    private static long startedAt;
    private static Timer timer;
    /** Held, never released by hand: the OS lets go when the process ends. */
    private static FileChannel lockChannel;
    private static FileLock lock;

    private LastExit() {
    }

    /** SessionLog.onStart, once per launch: the line about the last session, then this session's marker. */
    public static synchronized void onStart() {
        if (marker != null || GuiBase.isAndroid() || GuiBase.isIOS())
            return;
        try {
            marker = new File(ForgeConstants.USER_DIR, FILE);
            startedAt = System.currentTimeMillis();
            SecondCopy.Lock got = takeLock(new File(ForgeConstants.USER_DIR, LOCK));
            SecondCopy.record(got); // round 1013: the start screen warns about a second copy
            if (got == SecondCopy.Lock.HELD_ELSEWHERE || got == SecondCopy.Lock.SAME_PROCESS) {
                System.out.println("[TFR-LastExit] " + (got == SecondCopy.Lock.SAME_PROCESS
                        ? "this process already holds " + LOCK + "; the marker stays as it is"
                        : "another copy of the game is running (it holds " + LOCK + "); this one leaves the marker to it"));
                marker = null;
                return;
            }
            System.out.println("[TFR-LastExit] " + lastSessionText());
            write("running " + startedAt);
            timer = new Timer("TFR-LastExit", true);
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        //noinspection ResultOfMethodCallIgnored
                        marker.setLastModified(System.currentTimeMillis());
                    } catch (Throwable ignored) { // the next touch tries again
                    }
                }
            }, TOUCH_MS, TOUCH_MS);
        } catch (Throwable t) {
            System.out.println("[TFR-LastExit] failed: " + t);
        }
    }

    /** SessionLog.end("exit"), from Forge.dispose: this session ended cleanly. */
    public static synchronized void onCleanExit() {
        if (marker == null)
            return;
        try {
            if (timer != null)
                timer.cancel();
            write("clean " + startedAt + " " + System.currentTimeMillis());
        } catch (Throwable ignored) { // the next launch reads it as not clean, which it isn't; rare enough
        }
    }

    private static String lastSessionText() throws IOException {
        if (!marker.isFile())
            return "none: no marker from a last session (first launch with this check)";
        long touched = marker.lastModified();
        String[] parts = new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).trim().split("\\s+");
        long started = parts.length > 1 ? parse(parts[1]) : 0;
        if ("clean".equals(parts[0])) {
            long ended = parts.length > 2 ? parse(parts[2]) : touched;
            return "clean: the last session closed normally (started " + when(started) + ", " + hours(ended - started) + ")";
        }
        if (!"running".equals(parts[0]))
            return "? unreadable marker \"" + parts[0] + "\"";
        String text = "NOT clean: the last session (started " + when(started) + ") ended without closing - a killed process,"
                + " a forced close or a native crash; last seen " + when(touched) + ", about " + hours(touched - started)
                + " after its start";
        String crash = crashFile(started);
        return text + (crash == null ? "; no hs_err_pid crash file since then" : "; JVM crash file " + crash);
    }

    /**
     * Round 1013: what the lock says, not just yes/no. The OS drops a lock when its process ends, so a file a crash left
     * behind is free and is TAKEN; tryLock returning null means another live process holds it (HELD_ELSEWHERE); an
     * OverlappingFileLockException means this JVM already holds it (SAME_PROCESS, never a second copy); any other
     * failure is a file system without locks (UNSUPPORTED: the session is tracked anyway, and nobody is warned).
     * Package-private and file-taking for LastExitLockTest; the channel and lock stay held for the process's life.
     */
    static SecondCopy.Lock takeLock(File file) {
        FileChannel channel = null;
        try {
            channel = new RandomAccessFile(file, "rw").getChannel();
            FileLock got = channel.tryLock();
            if (got == null) {
                channel.close();
                return SecondCopy.Lock.HELD_ELSEWHERE;
            }
            lockChannel = channel;
            lock = got;
            return SecondCopy.Lock.TAKEN;
        } catch (OverlappingFileLockException e) {
            closeQuietly(channel);
            return SecondCopy.Lock.SAME_PROCESS;
        } catch (Throwable t) {
            closeQuietly(channel);
            return SecondCopy.Lock.UNSUPPORTED;
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            if (channel != null)
                channel.close();
        } catch (Throwable ignored) {
        }
    }

    /** The newest hs_err_pid*.log written since {@code since}, as "name (folder)", or null. */
    private static String crashFile(long since) {
        String best = null;
        long bestTime = 0;
        String[][] dirs = {
                {System.getProperty("user.dir"), "working folder"},
                {jarDir(), "game folder"},
                {System.getProperty("java.io.tmpdir"), "temp folder"}};
        for (String[] dir : dirs) {
            if (dir[0] == null)
                continue;
            File[] files = new File(dir[0]).listFiles((d, name) -> name.startsWith("hs_err_pid") && name.endsWith(".log"));
            if (files == null)
                continue;
            for (File f : files) {
                long t = f.lastModified();
                if (t >= since && t > bestTime) {
                    bestTime = t;
                    best = f.getName() + " (" + dir[1] + ", " + when(t) + ")";
                }
            }
        }
        return best;
    }

    private static String jarDir() {
        try {
            File jar = new File(LastExit.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.isFile() ? jar.getParent() : jar.getPath();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void write(String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String when(long ms) {
        return ms <= 0 ? "?" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(ms));
    }

    private static String hours(long ms) {
        return ms < 0 ? "?" : String.format(Locale.ROOT, "%.2f h", ms / 3600000d);
    }
}
