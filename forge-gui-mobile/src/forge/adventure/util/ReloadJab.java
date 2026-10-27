package forge.adventure.util;

import forge.adventure.data.ConfigData;
import forge.adventure.stage.GameHUD;
import forge.adventure.world.WorldSave;
import forge.localinstance.properties.ForgeConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.Properties;

/**
 * Round 726 (the user: "keep track how many times a player loads/or quits the game right after losing a duel ... in the
 * log, start showing a message ... Some might say that's Cheating"): a teasing red line in the Notices log once a player
 * has reloaded past five lost duels.
 *
 * <p>What counts: a duel is lost (DuelScene.afterGameEnd), and the next load, before another duel starts, restores a
 * save written BEFORE that loss - the loss is undone. Loading a save made after the loss (the autosave that already
 * holds it) undoes nothing and does not count; starting another duel is "moving on" and clears the pending loss. A quit
 * in between changes nothing, because the pending loss is on disk.
 *
 * <p>Round 735: a quit DURING a duel (the game closed mid-rematch, so the loss never happened) counts too, as a lost duel
 * plus a reload - but only when the duel was still live (DuelHeartbeat): the game last changed under
 * {@link DuelHeartbeat#STALL_MS} before the quit, the AI was not stuck thinking that long, and the LoopBreaker had not
 * fired. And honest losses give bonus life back: every {@link #HONEST_PER_FORGIVE} losses the player moved on from
 * forgive one reload.
 *
 * <p>Kept OUT of the save, per profile, in {@code <user dir>/adventure/tfr_reload_jab.properties}: a count kept in the
 * save would go back down with the very reload it counts. One count per world (keyed by the world's seed), so another
 * character starts at 0. It never resets. Old games start at 0 (no entry).
 */
public final class ReloadJab {
    private static final String FILE = ForgeConstants.USER_DIR + "adventure" + File.separator + "tfr_reload_jab.properties";
    private static final String WORD = "Cheating";
    /** The count where the jab starts; the reveal runs one letter per reload after it, then the plain line. */
    private static final int FIRST = 5;
    private static final String INK = "[#C01818]";
    private static final String KIND_INK = "[#2b7a2b]";
    /** Round 735: honest losses per reload forgiven. */
    static final int HONEST_PER_FORGIVE = 5;
    /** The store is written by the render thread and DuelHeartbeat's thread: every read-change-write holds this. */
    private static final Object LOCK = new Object();

    private ReloadJab() {
    }

    public static boolean isEnabled() {
        ConfigData configData = Config.instance().getConfigData();
        return configData != null && configData.reloadJabEnabled;
    }

    private static long currentSeed() {
        WorldSave save = WorldSave.getCurrentSave();
        return save == null || save.getWorld() == null ? 0L : save.getWorld().getSeed();
    }

    /** DuelScene.afterGameEnd: the player lost a duel (not a draw, not Deck Tester). */
    public static void onDuelLost(String enemyName) {
        if (!isEnabled())
            return;
        synchronized (LOCK) {
            Properties p = read();
            long seed = currentSeed();
            long now = System.currentTimeMillis();
            p.setProperty("pendingSeed", String.valueOf(seed));
            p.setProperty("pendingLossAt", String.valueOf(now));
            write(p);
            System.out.println("[TFR-ReloadJab] lost to " + enemyName + " - a load of a save older than now counts (world "
                    + seed + ", " + count(p, seed) + " so far)");
        }
        DifficultyHints.onDuelLost(enemyName); // round 779: the 5th loss to one foe suggests an easier difficulty
    }

    /** DuelScene.initDuels: another duel starts - the player moved on from the last loss, an honest one (round 735). */
    public static void onDuelStarted() {
        if (!isEnabled())
            return;
        String line;
        synchronized (LOCK) {
            Properties p = read();
            if (p.getProperty("pendingLossAt") == null)
                return;
            long seed = currentSeed();
            boolean here = String.valueOf(seed).equals(p.getProperty("pendingSeed"));
            p.remove("pendingSeed");
            p.remove("pendingLossAt");
            line = here ? creditHonest(p, seed, "a new duel started") : null;
            write(p);
            System.out.println("[TFR-ReloadJab] a new duel started - the last loss stands");
        }
        postKind(line);
    }

    /** Round 735, WorldStage's day tick: a day passed since the last loss with no reload - an honest loss. */
    public static void onDayPassed(int day) {
        if (!isEnabled())
            return;
        String line;
        synchronized (LOCK) {
            Properties p = read();
            long seed = currentSeed();
            if (p.getProperty("pendingLossAt") == null || !String.valueOf(seed).equals(p.getProperty("pendingSeed")))
                return;
            p.remove("pendingSeed");
            p.remove("pendingLossAt");
            line = creditHonest(p, seed, "day " + day + " came with no reload");
            write(p);
        }
        postKind(line);
    }

    /** WorldSave.load, after a load succeeded: the save's own date says whether it undid the pending loss. */
    public static void onLoaded(Date saveDate) {
        if (!isEnabled())
            return;
        String quitLine;
        String line = null;
        String kind = null;
        synchronized (LOCK) {
            Properties p = read();
            long seed = currentSeed();
            quitLine = judgeMidDuelQuit(p, seed); // round 735: a duel left mid-fight, before the pending loss below
            String lossAt = p.getProperty("pendingLossAt");
            if (lossAt != null) {
                if (!String.valueOf(seed).equals(p.getProperty("pendingSeed"))) {
                    System.out.println("[TFR-ReloadJab] loaded another world (" + seed + ") - the loss stays pending");
                } else {
                    p.remove("pendingSeed");
                    p.remove("pendingLossAt");
                    long saved = saveDate == null ? 0L : saveDate.getTime();
                    if (saved >= Long.parseLong(lossAt)) {
                        System.out.println("[TFR-ReloadJab] loaded a save from after the loss - not counted");
                        kind = creditHonest(p, seed, "a save from after the loss was loaded"); // round 735: kept, so honest
                    } else {
                        int n = count(p, seed) + 1;
                        p.setProperty("count." + seed, String.valueOf(n));
                        System.out.println("[TFR-ReloadJab] " + n + " (a save from before the loss, world " + seed + ")");
                        line = withLife(message(n), p, seed);
                    }
                }
            }
            write(p);
        }
        if (quitLine != null)
            GameHUD.getInstance().logNotification(INK + quitLine, true);
        if (line != null)
            GameHUD.getInstance().logNotification(INK + line, true);
        postKind(kind);
        DifficultyHints.watch("a load"); // round 779: a hint due in this world shows on the world map
    }

    // ---- Round 735: the duel in progress (DuelHeartbeat writes it), judged on the next load ----

    /** DuelHeartbeat.start: a duel of the player's own began. False when the jab is off on this plane. */
    static boolean onDuelInProgress(String enemyName) {
        if (!isEnabled())
            return false;
        synchronized (LOCK) {
            Properties p = read();
            if (p.getProperty("duel.seed") != null)
                System.out.println("[TFR-ReloadJab] an unjudged mid-duel quit (world " + p.getProperty("duel.seed") + ", "
                        + p.getProperty("duel.enemy") + ") is dropped - a new duel started");
            clearDuel(p);
            long now = System.currentTimeMillis();
            p.setProperty("duel.seed", String.valueOf(currentSeed()));
            p.setProperty("duel.enemy", enemyName == null ? "?" : enemyName);
            p.setProperty("duel.startedAt", String.valueOf(now));
            p.setProperty("duel.lastChangeAt", String.valueOf(now));
            p.setProperty("duel.aliveAt", String.valueOf(now));
            write(p, false);
        }
        System.out.println("[TFR-ReloadJab] duel in progress: " + enemyName + " - a quit before it ends is judged on the next"
                + " load");
        return true;
    }

    /** DuelHeartbeat's writes: the last change, the last time the game was alive, who it waited on, the AI's thinking. */
    static void duelHeartbeat(long lastChangeAt, long aliveAt, String waitingOn, long aiSince, boolean loopFired) {
        synchronized (LOCK) {
            Properties p = read();
            if (p.getProperty("duel.seed") == null)
                return;
            p.setProperty("duel.lastChangeAt", String.valueOf(lastChangeAt));
            p.setProperty("duel.aliveAt", String.valueOf(aliveAt));
            p.setProperty("duel.waitingOn", waitingOn);
            p.setProperty("duel.aiSince", String.valueOf(aiSince));
            p.setProperty("duel.loopBreak", String.valueOf(loopFired));
            write(p, false);
        }
    }

    /** Round 783, DuelHeartbeat.onPause/onResume: the app left (at = the pause time) or came back (at = 0). */
    static void duelPaused(long at) {
        synchronized (LOCK) {
            Properties p = read();
            if (p.getProperty("duel.seed") == null)
                return;
            if (at > 0)
                p.setProperty("duel.pausedAt", String.valueOf(at));
            else
                p.remove("duel.pausedAt");
            write(p, false);
        }
    }

    /**
     * DuelScene.GameEnd: the match is decided. A quit from here on (the loss or ante dialogs, before afterGameEnd records
     * it) is judged by this result, not by the pulse: a decided loss always counts.
     */
    public static void onMatchDecided(boolean won, boolean drawn) {
        DuelHeartbeat.stop();
        duelResult(drawn ? "draw" : won ? "won" : "lost");
    }

    /** The decided match ("won", "lost", "draw"): DuelScene.GameEnd, or DuelHeartbeat as soon as the result screen shows. */
    static void duelResult(String result) {
        if (!isEnabled())
            return;
        synchronized (LOCK) {
            Properties p = read();
            if (p.getProperty("duel.seed") == null || result.equals(p.getProperty("duel.decided")))
                return; // the pulse saw it first, then GameEnd
            p.setProperty("duel.decided", result);
            write(p, false);
        }
        System.out.println("[TFR-ReloadJab] the duel is decided: " + result);
    }

    /** DuelScene.afterGameEnd: the duel ended normally - its result is recorded there, nothing is left to judge. */
    public static void onDuelEnded() {
        DuelHeartbeat.stop();
        if (!isEnabled())
            return;
        DifficultyHints.watch("a duel ended"); // round 779: the win streak is read once back on the world map
        synchronized (LOCK) {
            Properties p = read();
            if (p.getProperty("duel.seed") == null)
                return;
            clearDuel(p);
            write(p, false);
        }
    }

    private static void clearDuel(Properties p) {
        for (String key : new String[]{"seed", "enemy", "startedAt", "lastChangeAt", "aliveAt", "waitingOn", "aiSince",
                "loopBreak", "decided", "pausedAt"})
            p.remove("duel." + key);
    }

    /** onLoaded: a duel record left over means the game closed mid-duel. Counts it when it was live; the Notices line. */
    private static String judgeMidDuelQuit(Properties p, long seed) {
        String duelSeed = p.getProperty("duel.seed");
        if (duelSeed == null)
            return null;
        if (!duelSeed.equals(String.valueOf(seed))) {
            System.out.println("[TFR-ReloadJab] a mid-duel quit in world " + duelSeed + " waits for that world");
            return null;
        }
        String enemy = p.getProperty("duel.enemy", "?");
        // Round 783: a duel the player left (the app paused - Android's background, a minimized window) is judged at
        // that moment, however long the app then sat before it closed.
        long pausedAt = longProp(p, "duel.pausedAt");
        long alive = pausedAt > 0 ? pausedAt : longProp(p, "duel.aliveAt");
        long idle = Math.max(0, alive - longProp(p, "duel.lastChangeAt"));
        long aiSince = longProp(p, "duel.aiSince");
        long thinking = aiSince > 0 ? Math.max(0, alive - aiSince) : 0;
        boolean loop = "true".equals(p.getProperty("duel.loopBreak"));
        String decided = p.getProperty("duel.decided");
        String facts = enemy + ", last change " + idle / 1000 + " s before the quit, waiting on "
                + p.getProperty("duel.waitingOn", "?") + (thinking > 0 ? ", the AI thinking " + thinking / 1000 + " s" : "")
                + (loop ? ", the LoopBreaker fired" : "") + (decided != null ? ", the match already " + decided : "")
                + (pausedAt > 0 ? ", judged when the app was left" : "");
        clearDuel(p);
        boolean counted;
        if (decided != null)
            counted = decided.equals("lost");
        else
            counted = idle < DuelHeartbeat.STALL_MS && thinking < DuelHeartbeat.STALL_MS && !loop;
        if (!counted) {
            System.out.println("[TFR-ReloadJab] mid-duel quit judged " + (decided != null ? "after a " + decided : "stalled")
                    + " (" + facts + ") - not counted");
            return null;
        }
        int n = count(p, seed) + 1;
        p.setProperty("count." + seed, String.valueOf(n));
        System.out.println("[TFR-ReloadJab] " + n + " (left mid-duel: " + facts + " - a lost duel plus a reload, world "
                + seed + ")");
        String line = withLife(message(n), p, seed);
        return line == null ? null : "Left mid-duel. " + line;
    }

    // ---- Round 735: honest losses ----

    /**
     * A lost duel the player moved on from: one more toward forgiving a reload, and the line when one is forgiven (on
     * Hard and Insane, where the bonus is real). Nothing banks while no reload is left to forgive. Forgiving starts at
     * the capped bonus: a count above the cap is forgiven down to it first, so the first forgiveness shows.
     */
    private static String creditHonest(Properties p, long seed, String why) {
        int reloads = count(p, seed);
        int forgiven = intProp(p, "forgiven." + seed);
        if (reloads - forgiven <= 0) {
            System.out.println("[TFR-ReloadJab] honest loss (" + why + ") - no reload to forgive");
            return null;
        }
        int honest = intProp(p, "honest." + seed) + 1;
        if (honest < HONEST_PER_FORGIVE) {
            p.setProperty("honest." + seed, String.valueOf(honest));
            System.out.println("[TFR-ReloadJab] honest loss (" + why + ") - " + honest + "/" + HONEST_PER_FORGIVE
                    + " toward forgiving a reload");
            return null;
        }
        int before = lifeBonusFor(reloads - forgiven);
        forgiven = Math.max(forgiven, reloads - MAX_LIFE_BONUS) + 1;
        int after = lifeBonusFor(reloads - forgiven);
        p.setProperty("honest." + seed, "0");
        p.setProperty("forgiven." + seed, String.valueOf(forgiven));
        System.out.println("[TFR-ReloadJab] forgiven (" + why + "): " + HONEST_PER_FORGIVE + " honest losses - "
                + forgiven + " of " + reloads + " reloads forgiven, the bonus +" + before + " -> +" + after
                + " (Hard/Insane only)");
        if (!hardOrInsane())
            return null;
        return HONEST_PER_FORGIVE + " losses taken honestly - enemies lose 1 bonus life (now +" + after + ").";
    }

    private static void postKind(String line) {
        if (line != null)
            GameHUD.getInstance().logNotification(KIND_INK + line, true);
    }

    // Round 726 follow-up (the user: "each time you re-load, the counter goes up, you add +1 life to enemies on Hard and
    // Insane"): every enemy starts a duel with +count life on those two difficulties - DuelScene.enemyStartingLife(), so
    // the Alt plates show it (orange, above the difficulty life). +1 per counted reload up to MAX_LIFE_BONUS. Cached,
    // since a plate asks every frame; write() drops the cache.
    /**
     * Round 732 (the user: "Cap at 10 and don't retro-actively apply anything"): the enemy life bonus stops at +10. The
     * count itself keeps going, so the lines still progress; only the bonus is capped. Round 735: less the reloads
     * honest losses forgave.
     */
    static final int MAX_LIFE_BONUS = 10;
    private static volatile long cachedSeed = Long.MIN_VALUE;
    private static volatile int cachedOwed;

    /** The extra life every enemy starts with: this world's reloads less those forgiven, on Hard and Insane, else 0. */
    public static int enemyLifeBonus() {
        if (!isEnabled() || !hardOrInsane())
            return 0;
        long seed = currentSeed();
        if (seed != cachedSeed) {
            synchronized (LOCK) {
                Properties p = read();
                cachedOwed = count(p, seed) - intProp(p, "forgiven." + seed);
                cachedSeed = seed;
            }
        }
        return lifeBonusFor(cachedOwed);
    }

    private static boolean hardOrInsane() {
        return Current.player() != null && Current.player().isHardorInsaneDifficulty();
    }

    /** The bonus the unforgiven reloads give, capped at MAX_LIFE_BONUS. */
    static int lifeBonusFor(int owed) {
        return Math.max(0, Math.min(owed, MAX_LIFE_BONUS));
    }

    /** The console's {@code reloadjab <n>}: sets this world's count and posts its line. */
    public static String setForTest(int n) {
        String line;
        synchronized (LOCK) {
            Properties p = read();
            long seed = currentSeed();
            p.setProperty("count." + seed, String.valueOf(Math.max(0, n)));
            write(p);
            line = withLife(message(n), p, seed);
        }
        System.out.println("[TFR-ReloadJab] " + n + " (console)");
        if (line != null)
            GameHUD.getInstance().logNotification(INK + line, true);
        return "reload count " + n + (line == null ? " - no line below " + FIRST : ": " + line);
    }

    /** Round 735, the console's {@code reloadjab forgiven|honest <n>}: sets this world's forgiven count or honest losses. */
    public static String setForTest(String what, int n) {
        if (!what.equals("forgiven") && !what.equals("honest"))
            return "reloadjab [count] | reloadjab forgiven|honest <n>";
        synchronized (LOCK) {
            Properties p = read();
            p.setProperty(what + "." + currentSeed(), String.valueOf(Math.max(0, n)));
            write(p);
        }
        System.out.println("[TFR-ReloadJab] " + what + " " + n + " (console)");
        return describe();
    }

    /** The console's {@code reloadjab}: this world's count and any pending loss. */
    public static String describe() {
        synchronized (LOCK) {
            Properties p = read();
            long seed = currentSeed();
            return (isEnabled() ? "" : "(off on this plane) ") + "world " + seed + ": " + count(p, seed)
                    + " reload(s) after a loss, " + intProp(p, "forgiven." + seed) + " forgiven, " + intProp(p, "honest." + seed)
                    + "/" + HONEST_PER_FORGIVE + " honest losses toward the next, enemy bonus +" + enemyLifeBonus()
                    + (p.getProperty("pendingLossAt") != null ? ", a loss pending (world " + p.getProperty("pendingSeed") + ")" : "")
                    + (p.getProperty("duel.seed") != null ? ", a duel in progress (" + p.getProperty("duel.enemy") + ")" : "")
                    + ". " + DifficultyHints.info(); // round 779
        }
    }

    // ---- Round 779: DifficultyHints keeps its marks in this store, under the same lock, so a reload cannot undo them ----

    static long seed() {
        return currentSeed();
    }

    static Properties readStore() {
        synchronized (LOCK) {
            return read();
        }
    }

    static void updateStore(java.util.function.Consumer<Properties> change) {
        synchronized (LOCK) {
            Properties p = read();
            change.accept(p);
            write(p, false);
        }
    }

    /** The follow-up's suffix on Hard and Insane, so the player knows why the fights got harder. */
    private static String withLife(String line, Properties p, long seed) {
        int bonus = hardOrInsane() ? lifeBonusFor(count(p, seed) - intProp(p, "forgiven." + seed)) : 0;
        return line == null || bonus <= 0 ? line : line + " (enemies +" + bonus + " life)";
    }

    /** The line for the n-th counted reload, or null below FIRST. */
    static String message(int n) {
        if (n < FIRST)
            return null;
        String head = "You've now loaded " + n + " times after losing a duel";
        if (n == FIRST)
            return head + "....";
        int shown = n - FIRST; // 6 shows "C", 12 shows "Cheatin", 13 the whole word
        if (shown < WORD.length()) {
            StringBuilder reveal = new StringBuilder(WORD.substring(0, shown));
            for (int i = shown; i < WORD.length(); i++)
                reveal.append(" _");
            return head + ". Some might say that's " + reveal;
        }
        if (shown == WORD.length())
            return head + ". Some might say that's " + WORD + ".";
        return "You've now loaded " + n + " times since losing a duel, maybe drop the difficulty.";
    }

    private static int count(Properties p, long seed) {
        return intProp(p, "count." + seed);
    }

    private static int intProp(Properties p, String key) {
        try {
            return Integer.parseInt(p.getProperty(key, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long longProp(Properties p, String key) {
        try {
            return Long.parseLong(p.getProperty(key, "0"));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static Properties read() {
        Properties p = new Properties();
        File f = new File(FILE);
        if (f.exists()) {
            try (InputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (IOException e) {
                System.err.println("[TFR-ReloadJab] could not read " + FILE + ": " + e);
            }
        }
        return p;
    }

    private static void write(Properties p) {
        write(p, true);
    }

    /** {@code counts} false: only the duel record changed, so the life-bonus cache stays (the heartbeat writes often). */
    private static void write(Properties p, boolean counts) {
        if (counts)
            cachedSeed = Long.MIN_VALUE;
        File f = new File(FILE);
        f.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(f)) {
            p.store(out, "TFR round 726: reloads after a lost duel, per world seed");
        } catch (IOException e) {
            System.err.println("[TFR-ReloadJab] could not write " + FILE + ": " + e);
        }
    }
}
