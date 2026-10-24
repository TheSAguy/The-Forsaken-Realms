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
        Properties p = read();
        long seed = currentSeed();
        long now = System.currentTimeMillis();
        p.setProperty("pendingSeed", String.valueOf(seed));
        p.setProperty("pendingLossAt", String.valueOf(now));
        write(p);
        System.out.println("[TFR-ReloadJab] lost to " + enemyName + " - a load of a save older than now counts (world "
                + seed + ", " + count(p, seed) + " so far)");
    }

    /** DuelScene.initDuels: another duel starts - the player moved on from the last loss. */
    public static void onDuelStarted() {
        if (!isEnabled())
            return;
        Properties p = read();
        if (p.getProperty("pendingLossAt") == null)
            return;
        p.remove("pendingSeed");
        p.remove("pendingLossAt");
        write(p);
        System.out.println("[TFR-ReloadJab] a new duel started - the last loss stands");
    }

    /** WorldSave.load, after a load succeeded: the save's own date says whether it undid the pending loss. */
    public static void onLoaded(Date saveDate) {
        if (!isEnabled())
            return;
        Properties p = read();
        String lossAt = p.getProperty("pendingLossAt");
        if (lossAt == null)
            return;
        long seed = currentSeed();
        if (!String.valueOf(seed).equals(p.getProperty("pendingSeed"))) {
            System.out.println("[TFR-ReloadJab] loaded another world (" + seed + ") - the loss stays pending");
            return;
        }
        p.remove("pendingSeed");
        p.remove("pendingLossAt");
        long saved = saveDate == null ? 0L : saveDate.getTime();
        if (saved >= Long.parseLong(lossAt)) {
            write(p);
            System.out.println("[TFR-ReloadJab] loaded a save from after the loss - not counted");
            return;
        }
        int n = count(p, seed) + 1;
        p.setProperty("count." + seed, String.valueOf(n));
        write(p);
        System.out.println("[TFR-ReloadJab] " + n + " (a save from before the loss, world " + seed + ")");
        post(n);
    }

    // Round 726 follow-up (the user: "each time you re-load, the counter goes up, you add +1 life to enemies on Hard and
    // Insane"): every enemy starts a duel with +count life on those two difficulties - DuelScene.enemyStartingLife(), so
    // the Alt plates show it (orange, above the difficulty life). +1 per counted reload up to MAX_LIFE_BONUS. Cached,
    // since a plate asks every frame; write() drops the cache.
    /**
     * Round 732 (the user: "Cap at 10 and don't retro-actively apply anything"): the enemy life bonus stops at +10. The
     * count itself keeps going, so the lines still progress; only the bonus is capped.
     */
    static final int MAX_LIFE_BONUS = 10;
    private static long cachedSeed = Long.MIN_VALUE;
    private static int cachedCount;

    /** The extra life every enemy starts with: this world's count on Hard and Insane, else 0. */
    public static int enemyLifeBonus() {
        if (!isEnabled() || Current.player() == null)
            return 0;
        if (!Current.player().isHardorInsaneDifficulty())
            return 0;
        long seed = currentSeed();
        if (seed != cachedSeed) {
            cachedCount = count(read(), seed);
            cachedSeed = seed;
        }
        return lifeBonusFor(cachedCount);
    }

    /** The bonus a count gives: the count, capped at MAX_LIFE_BONUS. */
    static int lifeBonusFor(int count) {
        return Math.max(0, Math.min(count, MAX_LIFE_BONUS));
    }

    /** The console's {@code reloadjab <n>}: sets this world's count and posts its line. */
    public static String setForTest(int n) {
        Properties p = read();
        long seed = currentSeed();
        p.setProperty("count." + seed, String.valueOf(Math.max(0, n)));
        write(p);
        System.out.println("[TFR-ReloadJab] " + n + " (console)");
        String line = withLife(message(n));
        if (line != null)
            GameHUD.getInstance().logNotification(INK + line, true);
        return "reload count " + n + (line == null ? " - no line below " + FIRST : ": " + line);
    }

    /** The console's {@code reloadjab}: this world's count and any pending loss. */
    public static String describe() {
        Properties p = read();
        long seed = currentSeed();
        return (isEnabled() ? "" : "(off on this plane) ") + "world " + seed + ": " + count(p, seed) + " reload(s) after a loss"
                + (p.getProperty("pendingLossAt") != null ? ", a loss pending (world " + p.getProperty("pendingSeed") + ")" : "");
    }

    private static void post(int n) {
        String line = withLife(message(n));
        if (line != null)
            GameHUD.getInstance().logNotification(INK + line, true);
    }

    /** The follow-up's suffix on Hard and Insane, so the player knows why the fights got harder. */
    private static String withLife(String line) {
        int bonus = enemyLifeBonus();
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
        try {
            return Integer.parseInt(p.getProperty("count." + seed, "0"));
        } catch (NumberFormatException e) {
            return 0;
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
