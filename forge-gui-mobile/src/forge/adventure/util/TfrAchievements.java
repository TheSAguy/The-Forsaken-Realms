package forge.adventure.util;

import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.WorldData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.stage.GameHUD;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.localinstance.achievements.Achievement;
import forge.localinstance.achievements.AchievementCollection;
import forge.localinstance.properties.ForgeConstants;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Round 717 (the user: "I'd like to add new achievements to TFR1"; proposal 2026-10-23): TFR's own achievements.
 * Kept per player profile in {@code <user dir>/achievements/tfr.xml}, like Forge's stock ones, and shown first on
 * the Statistics screen's trophy page. Trophy only - no reward.
 * <p>
 * Forge's achievements read a finished duel; these are told of an event by the hook where it happens (a castle falls,
 * a capital is taken, a level is reached) and record through Achievement.record(). State the save already holds is
 * checked again on every load ({@link #backfill}), so a save from before this round earns what it has done.
 * <p>
 * A typed or scripted console command never grants one ({@link #consoleBegin}); an item running its command (a rune)
 * is play and does. The back-fill reads the save's state and cannot tell how that state came about.
 * {@code [TFR-Achievement]} logs every grant, every refusal and every back-fill.
 */
public final class TfrAchievements extends AchievementCollection {
    public static final String LORD_SLAYER = "tfr_lord_slayer";
    public static final String CAPITAL_GAINS = "tfr_capital_gains";
    public static final String LEGEND_HUNTER = "tfr_legend_hunter";
    public static final String NOTORIOUS = "tfr_notorious";
    public static final String ASCENDANT = "tfr_ascendant";
    public static final String ASHES_TO_HEARTH = "tfr_ashes_to_hearth";
    public static final String SEAT_OF_YOUR_OWN = "tfr_seat_of_your_own";
    public static final String RING_IS_WHOLE = "tfr_ring_is_whole";

    /** Per-save record of the colors whose capital the player took (character flags clear on New Game+). */
    private static final String CAPITAL_TAKEN_FLAG = "tfrCapitalTaken_";

    private static TfrAchievements instance;
    private static int consoleDepth;
    private static List<String> backfillEarned; // non-null while a back-fill runs

    private TfrAchievements() {
        super("lblAdventure", ForgeConstants.ACHIEVEMENTS_DIR + "tfr.xml", false);
    }

    public static synchronized TfrAchievements get() {
        if (instance == null)
            instance = new TfrAchievements();
        return instance;
    }

    /** Forge's shared duel achievements already live in the Adventure list; this one holds only TFR's. */
    @Override
    protected void addSharedAchievements() {
    }

    @Override
    protected void addAchievements() {
        add(new TfrAchievement(LORD_SLAYER, "Lord Slayer", "Bring down a Lord in their own castle.", false,
                new int[]{1, 3, 4, 5}, new String[]{"1 castle", "3 castles", "4 castles", "all 5 castles"}));
        add(new TfrAchievement(CAPITAL_GAINS, "Capital Gains",
                "Take a color's capital before its castle falls.", false,
                new int[]{1, 3, 4, 5}, new String[]{"1 capital", "3 capitals", "4 capitals", "all 5 capitals"}));
        add(new TfrAchievement(LEGEND_HUNTER, "Legend Hunter", "Defeat the legends of the realm, each one once.",
                false, new int[]{1, 10, 25, 50}, new String[]{"1 legend", "10 legends", "25 legends", "50 legends"}));
        add(new TfrAchievement(NOTORIOUS, "Notorious",
                "Beat a fully armed foe on a 50-win streak.", false,
                new int[]{1, 2, 3, 4}, new String[]{"an Apprentice", "an Adept", "a Master", "an Archmage"}));
        add(new TfrAchievement(ASCENDANT, "Ascendant", "Rise through the Ascendance.", false,
                new int[]{5, 10, 20, 30}, new String[]{"level 5", "level 10", "level 20", "level 30"}));
        add(new TfrAchievement(ASHES_TO_HEARTH, "Ashes to Hearth",
                "Hold towns raised from ruin or taken back.", false,
                new int[]{1, 5, 10, 20}, new String[]{"1 town", "5 towns", "10 towns", "20 towns"}));
        add(new TfrAchievement(SEAT_OF_YOUR_OWN, "A Seat of Your Own", "Raise your Capitol.", false));
        add(new TfrAchievement(RING_IS_WHOLE, "The Ring is Whole",
                "Hold all five Ring Cities, every castle fallen.", false));
    }

    public static boolean isEnabled() {
        ConfigData configData = Config.instance().getConfigData();
        return configData != null && configData.tfrAchievementsEnabled;
    }

    private static TfrAchievement find(String key) {
        for (Achievement a : get())
            if (a.getKey().equals(key))
                return (TfrAchievement) a;
        return null;
    }

    // ---- console guard ----------------------------------------------------------------------------------------

    /** A typed or scripted console command is running (ConsoleCommandInterpreter.command): grant nothing. */
    public static void consoleBegin() {
        consoleDepth++;
    }

    public static void consoleEnd() {
        consoleDepth = Math.max(0, consoleDepth - 1);
    }

    // ---- recording --------------------------------------------------------------------------------------------

    /** Records {@code value} for {@code key} if it beats the best so far, and announces a new tier. */
    public static void reach(String key, int value, String why) {
        if (!isEnabled())
            return;
        TfrAchievement a = find(key);
        if (a == null) {
            System.err.println("[TFR-Achievement] no achievement " + key);
            return;
        }
        if (value <= a.getBest())
            return;
        if (consoleDepth > 0) {
            System.out.println("[TFR-Achievement] " + key + ": " + value + " not recorded - a console command ("
                    + why + ")");
            return;
        }
        int before = a.getBest();
        String tierBefore = a.tierName();
        a.record(value, false);
        get().save();
        String tier = a.tierName();
        boolean newTier = tier != null && !tier.equals(tierBefore);
        System.out.println("[TFR-Achievement] " + key + ": " + before + " -> " + value + " (" + why + ")"
                + (newTier ? " - new trophy: " + tier : "") + (backfillEarned != null ? " [back-fill]" : ""));
        if (!newTier)
            return;
        String shown = a.getDisplayName() + (a.isTiered() ? " (" + tier + ": " + a.tierGoal() + ")" : "");
        if (backfillEarned != null)
            backfillEarned.add(shown);
        else
            GameHUD.getInstance().addNotification("Achievement earned: " + shown);
    }

    public static void earn(String key, String why) {
        reach(key, 1, why);
    }

    /** Adds {@code name} to a collection achievement's profile-wide list, then records the list's size. */
    public static void addSeen(String key, String name, String why) {
        if (!isEnabled() || name == null || name.isEmpty())
            return;
        TfrAchievement a = find(key);
        if (a == null || a.seen().contains(name))
            return;
        if (consoleDepth > 0) {
            System.out.println("[TFR-Achievement] " + key + ": " + name + " not counted - a console command");
            return;
        }
        a.seen().add(name);
        System.out.println("[TFR-Achievement] " + key + ": + " + name + " (" + why + "), " + a.seen().size() + " in all");
        if (a.seen().size() > a.getBest())
            reach(key, a.seen().size(), why);
        else
            get().save();
    }

    // ---- the hooks --------------------------------------------------------------------------------------------

    /** TerritoryControl.defeatColor, after the fall is marked. */
    public static void onColorDefeated(World world, String color) {
        if (world != null)
            reach(LORD_SLAYER, world.getDefeatedColorCount(), color + "'s castle fell");
    }

    /** TownRestoration.captureTownForPlayer, a capital taken; {@code originalColor} is the capital's own color. */
    public static void onCapitalTaken(String originalColor) {
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        if (player == null || originalColor == null)
            return;
        if (consoleDepth == 0)
            player.setCharacterFlag(CAPITAL_TAKEN_FLAG + originalColor, 1);
        reach(CAPITAL_GAINS, capitalsTaken(), "took " + originalColor + "'s capital");
    }

    /** A town restored or captured: the towns the player holds now. */
    public static void onTownsChanged(String why) {
        reach(ASHES_TO_HEARTH, TownRestoration.countPlayerTowns(), why);
    }

    /** Ascendance.onLevelUp. */
    public static void onLevel(int level) {
        reach(ASCENDANT, level, "reached level " + level);
    }

    /**
     * DuelScene.afterGameEnd, a won duel the player fought (not an Inn event, a guard's fight or Deck Tester).
     * {@code notoriousRank} is the highest rank (0 Apprentice .. 3 Archmage) of a seat that started fully armed by
     * notoriety and the win ratio, or -1.
     */
    public static void onDuelWon(EnemyData enemy, int notoriousRank) {
        if (enemy != null && LegendSpawns.isMember(enemy))
            addSeen(LEGEND_HUNTER, enemy.getName(), "beat " + enemy.getName());
        if (notoriousRank >= 0)
            reach(NOTORIOUS, notoriousRank + 1, "beat a fully armed " + EnemyData.tierDisplayName(rankTier(notoriousRank))
                    + " at " + WorldSave.getCurrentSave().getPlayer().notorietyStreak() + " wins in a row");
    }

    /**
     * Notorious' "fully armed": the seat's Walls hold every notoriety bonus Wall (45 and 50 wins in a row) and its
     * win-ratio help holds both the Clue and the Treasure (95%+ of the last 50 duels - which a 50-win streak always
     * is). The regular Walls are left out on purpose: they reach only some ranks on the lower difficulties.
     */
    public static boolean fullyArmed(List<String> walls, String[] helpTokens) {
        forge.adventure.data.TuningData tuning = Config.instance().getTuningData();
        if (walls == null || helpTokens == null || tuning == null || tuning.notorietyBonusWalls == null
                || tuning.notorietyBonusWalls.length == 0)
            return false;
        for (String bonus : tuning.notorietyBonusWalls)
            if (!walls.contains(bonus))
                return false;
        boolean clue = false, treasure = false;
        for (String token : helpTokens) {
            clue |= token.contains("clue");
            treasure |= token.contains("treasure");
        }
        return clue && treasure;
    }

    private static String rankTier(int rank) {
        switch (rank) {
            case 1: return "Uncommon";
            case 2: return "Rare";
            case 3: return "Mythic";
            default: return "Common";
        }
    }

    // ---- counting what the save holds -------------------------------------------------------------------------

    private static int capitalsTaken() {
        World world = WorldSave.getCurrentSave().getWorld();
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        int count = 0;
        for (String color : TerritoryControl.COLORS)
            if (player.getCharacterFlag(CAPITAL_TAKEN_FLAG + color) > 0 || (world != null && world.isCapitolLost(color)))
                count++;
        return count;
    }

    /**
     * Checks everything the loaded save already holds, once per load (WorldSave.load). New tiers it finds are
     * announced in one notice rather than one each.
     */
    public static void backfill(String why) {
        if (!isEnabled() || WorldSave.getCurrentSave() == null)
            return;
        World world = WorldSave.getCurrentSave().getWorld();
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        if (world == null || player == null)
            return;
        backfillEarned = new ArrayList<>();
        try {
            reach(LORD_SLAYER, world.getDefeatedColorCount(), why);
            reach(CAPITAL_GAINS, capitalsTaken(), why);
            for (Map.Entry<String, Pair<Integer, Integer>> e : player.getStatistic().getWinLossRecord().entrySet()) {
                if (e.getValue() == null || e.getValue().getLeft() == null || e.getValue().getLeft() <= 0)
                    continue;
                EnemyData enemy = WorldData.getEnemy(e.getKey());
                if (enemy != null && LegendSpawns.isMember(enemy))
                    addSeen(LEGEND_HUNTER, enemy.getName(), why);
            }
            if (Ascendance.isActive())
                reach(ASCENDANT, Ascendance.level(), why);
            reach(ASHES_TO_HEARTH, TownRestoration.countPlayerTowns(), why);
            if (TownRestoration.findCapitol() != null)
                earn(SEAT_OF_YOUR_OWN, why);
            if (TerritoryControl.playerVictoryReached(world))
                earn(RING_IS_WHOLE, why);
            System.out.println("[TFR-Achievement] back-fill (" + why + "): " + backfillEarned.size() + " new"
                    + (backfillEarned.isEmpty() ? "" : " - " + String.join(", ", backfillEarned)));
            if (!backfillEarned.isEmpty())
                GameHUD.getInstance().addNotification("Achievements earned from your deeds so far: "
                        + String.join(", ", backfillEarned) + ".");
        } catch (RuntimeException ex) {
            System.err.println("[TFR-Achievement] back-fill failed: " + ex);
            ex.printStackTrace();
        } finally {
            backfillEarned = null;
        }
    }

    // ---- the console's report ---------------------------------------------------------------------------------

    /** The {@code achievement} command: every entry, its best and tier. */
    public static String describe() {
        StringBuilder out = new StringBuilder(isEnabled() ? "" : "(TFR achievements are off on this plane)\n");
        for (Achievement a : get()) {
            TfrAchievement t = (TfrAchievement) a;
            out.append(t.getKey()).append(": best ").append(t.getBest())
                    .append(t.tierName() != null ? ", " + t.tierName() : ", not earned")
                    .append(t.seen().isEmpty() ? "" : ", " + t.seen().size() + " seen").append('\n');
        }
        return out.toString().trim();
    }

    /** The {@code achievement reset} command, for the agent game's profile: forget one entry, or every one. */
    public static String reset(String key) {
        int count = 0;
        for (Achievement a : get()) {
            if (key != null && !a.getKey().equals(key))
                continue;
            TfrAchievement t = (TfrAchievement) a;
            t.seen().clear();
            t.resetForTest();
            count++;
        }
        get().save();
        System.out.println("[TFR-Achievement] reset " + (key == null ? "all" : key) + " (" + count + ")");
        return "Reset " + count + " achievement(s)";
    }
}
