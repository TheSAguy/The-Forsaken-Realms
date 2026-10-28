package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.SpawnTierWeightData;
import forge.adventure.data.TuningData;
import forge.adventure.stage.GameHUD;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Round 813 (the user: "I want to implement something, when you built a temple. Where the current color that has been
 * 'killed' the most does something"; design notes/temple-wrath-design-813.md, approved 2026-10-28 with every
 * recommendation): the Temple's hunted color.
 * <ul>
 *   <li>When a week turns and the Capitol has a Temple, the closed week's most-killed color (TempleOfferings' ranking:
 *   multicolor kills split, ties in WUBRG order) is hunted for the new week. A week with no kills hunts nobody.</li>
 *   <li>Spawns ahead: roaming spawns on the hunted color's land - the biome roll (BiomeData.getEnemy) and the roamers its
 *   dungeons send (DungeonSources.byWeekTier) - roll on the NEXT rank bracket's row of spawn_tier_weighting.json
 *   (wrathAheadBrackets), not next week's: next week's row differs only in a bracket's last week. The last bracket
 *   has nothing above it. Everything else on the row (territory, difficulty, Notoriety's tilt, the win-ratio shift)
 *   applies as before; the lifetime-win rank gate is not touched. Off on Easy (wrathAheadOnDifficulty).</li>
 *   <li>The bounty: the Temple tally's duels (the player's own wins - no Arena, Inn, fixed-deck or guard fight) against
 *   an enemy with the hunted color pay wrathBountyGold / wrathBountyShards more gold and shards, rounded up. DuelScene
 *   marks the win (onDuelEnded), the two loot sites raise it (applyBounty) - Ascendance's pending-Power pattern.</li>
 *   <li>A destroyed Temple ends the hunt at once (onTempleGone). The state lives on the World (wrathColor, wrathWeek,
 *   wrathStatus) and holds only for the week it was set in, so a missed turn lets it lapse rather than run on.</li>
 * </ul>
 * Round 814 adds the Wrath hunter to the same state. Logs [TFR-Wrath]; console "wrath".
 */
public final class WrathHunt {
    private WrathHunt() {
    }

    /** World.wrathStatus: no hunt; a hunter is out (round 814); beaten; it won; no hunter this week (Easy, no castle). */
    public static final int NONE = 0, HUNTING = 1, BEATEN = 2, LOST = 3, NO_HUNTER = 4;

    /** The tint of the hunt's notices - round 594's player-town red. */
    private static final String TINT = "[#B3261E]";

    /** The duel just won, when it counts for the bounty - consumed by applyBounty at its loot screen. */
    private static EnemySprite pendingBounty;
    /** "seed|land|week|source" keys already logged by spawnWeek, so each is logged once. */
    private static final Set<String> loggedAhead = new HashSet<>();
    /** True while DungeonSources' ordinary roll runs - BiomeData then treats it as a roaming roll. */
    private static boolean dungeonSourceRoll;

    public static boolean isEnabled() {
        ConfigData config = Config.instance().getConfigData();
        return config != null && config.wrathHuntEnabled;
    }

    /** DungeonSources.pick: its ordinary roll (BiomeData.getEnemy without the injected spawns) is a dungeon's roamer. */
    public static <T> T dungeonSourceRoll(java.util.function.Supplier<T> roll) {
        dungeonSourceRoll = true;
        try {
            return roll.get();
        } finally {
            dungeonSourceRoll = false;
        }
    }

    public static boolean inDungeonSourceRoll() {
        return dungeonSourceRoll;
    }

    private static TuningData tuning() {
        return Config.instance().getTuningData();
    }

    private static World world() {
        WorldSave save = WorldSave.getCurrentSave();
        return save == null ? null : save.getWorld();
    }

    /** True when the difficulty's entry (Easy, Normal, Hard, Insane) of a per-difficulty switch is on. */
    private static boolean onFor(int[] perDifficulty) {
        int d = TerritoryControl.difficultyIndex();
        return perDifficulty != null && d >= 0 && d < perDifficulty.length && perDifficulty[d] > 0;
    }

    /** The hunted color ("red") this week, or null. */
    public static String huntedColor() {
        World world = world();
        if (!isEnabled() || world == null || world.getWrathColor() == null)
            return null;
        return world.getWrathWeek() == world.getCurrentWeek() ? world.getWrathColor() : null;
    }

    private static String letterOf(String color) {
        for (int c = 0; c < TempleOfferings.NAMES.length; c++)
            if (TempleOfferings.NAMES[c].equals(color))
                return TempleOfferings.LETTERS[c];
        return "";
    }

    private static String capitalized(String color) {
        return color.isEmpty() ? color : Character.toUpperCase(color.charAt(0)) + color.substring(1);
    }

    // ------------------------------------------------------------------------------------------------- the week turn

    /**
     * TempleOfferings.onDaysPassed: a new week began. {@code kills} is the closed week's tally (empty when there was
     * none), {@code closedWeek} its 0-based week (-1 when none was kept). The old hunt ends; a new one starts when the
     * Capitol has a Temple and someone was killed.
     */
    public static void onWeekTurn(float[] kills, int closedWeek, int newWeek) {
        World world = world();
        if (!isEnabled() || world == null)
            return;
        String before = world.getWrathColor();
        int beforeWeek = world.getWrathWeek();
        world.setWrath(null, -1, NONE);
        if (before != null && beforeWeek >= 0 && beforeWeek < newWeek)
            System.out.println("[TFR-Wrath] week " + (newWeek + 1) + ": " + before + "'s hunt (week " + (beforeWeek + 1)
                    + ") is over");
        List<Integer> ranked = TempleOfferings.ranking(kills);
        if (ranked.isEmpty()) {
            System.out.println("[TFR-Wrath] week " + (newWeek + 1) + ": no kills in week " + (closedWeek + 1)
                    + " - nobody hunted");
            return;
        }
        if (!TempleOfferings.capitolHasTemple()) {
            System.out.println("[TFR-Wrath] week " + (newWeek + 1) + ": no Temple - no hunt");
            return;
        }
        start(TempleOfferings.NAMES[ranked.get(0)], newWeek,
                "(" + TempleOfferings.LETTERS[ranked.get(0)] + " " + TempleOfferings.fmt(kills[ranked.get(0)]) + " of "
                        + TempleOfferings.fmt(total(kills)) + " kills, 1st)");
    }

    private static float total(float[] kills) {
        float sum = 0f;
        for (float k : kills)
            sum += k;
        return sum;
    }

    /** Sets the hunt for {@code week} and announces it. {@code why} is for the log. */
    private static void start(String color, int week, String why) {
        World world = world();
        TuningData t = tuning();
        if (world == null || t == null)
            return;
        boolean ahead = onFor(t.wrathAheadOnDifficulty) && t.wrathAheadBrackets > 0 && SpawnTierWeighting.isEnabled();
        boolean bounty = onFor(t.wrathBountyOnDifficulty) && (t.wrathBountyGold > 0f || t.wrathBountyShards > 0f);
        String difficulty = Current.player().getDifficulty() == null ? "?" : Current.player().getDifficulty().name;
        if (!ahead && !bounty) {
            System.out.println("[TFR-Wrath] week " + (week + 1) + ": " + color + " would be hunted " + why
                    + " - spawns ahead and bounty both off on " + difficulty);
            return;
        }
        world.setWrath(color, week, NO_HUNTER); // round 814: HUNTING when a hunter sets out
        loggedAhead.clear();
        System.out.println("[TFR-Wrath] week " + (week + 1) + ": " + color + " is hunted " + why + " - spawns ahead "
                + (ahead ? "yes (+" + t.wrathAheadBrackets + " bracket)" : "no") + ", bounty "
                + (bounty ? "+" + pct(t.wrathBountyGold) + " gold / +" + pct(t.wrathBountyShards) + " shards" : "no")
                + " (" + difficulty + ")");
        String name = capitalized(color);
        GameHUD.getInstance().addNotification(TINT + name + " is hunted."
                + (ahead ? " Its land sends stronger foes this week." : ""), true);
        if (bounty)
            GameHUD.getInstance().addNotification("[BLACK]" + name + "'s bounty this week: " + color + " foes pay "
                    + bountyText(t) + ".", true);
    }

    private static String bountyText(TuningData t) {
        if (t.wrathBountyGold > 0f && t.wrathBountyShards > 0f && t.wrathBountyGold == t.wrathBountyShards)
            return "+" + pct(t.wrathBountyGold) + " [WHITE][+Gold][BLACK] and [WHITE][+Shards][BLACK]";
        StringBuilder sb = new StringBuilder();
        if (t.wrathBountyGold > 0f)
            sb.append("+").append(pct(t.wrathBountyGold)).append(" [WHITE][+Gold][BLACK]");
        if (t.wrathBountyShards > 0f)
            sb.append(sb.length() == 0 ? "" : " and ").append("+").append(pct(t.wrathBountyShards))
                    .append(" [WHITE][+Shards][BLACK]");
        return sb.toString();
    }

    private static String pct(float share) {
        return Math.round(share * 100f) + "%";
    }

    /** EconomyBuildings: the Capitol's Temple was destroyed - the hunt ends at once. */
    public static void onTempleGone() {
        World world = world();
        String color = huntedColor();
        if (world == null || color == null)
            return;
        world.setWrath(null, -1, NONE);
        System.out.println("[TFR-Wrath] the Temple was destroyed - " + color + "'s hunt ends (week "
                + (world.getCurrentWeek() + 1) + ")");
        GameHUD.getInstance().addNotification(TINT + "Without the Temple, " + capitalized(color) + " is no longer hunted.",
                true);
    }

    // ---------------------------------------------------------------------------------------------- spawns ahead

    /**
     * BiomeData.getEnemy / DungeonSources.byWeekTier: the week whose bracket a spawn on {@code land} rolls - the first
     * week of the bracket wrathAheadBrackets above this week's when the land is the hunted color's, else {@code week}.
     * {@code dungeonSource} marks DungeonSources' roll (wrathAheadDungeonSources).
     */
    public static int spawnWeek(String land, int week, boolean dungeonSource) {
        String color = huntedColor();
        if (color == null || !color.equals(land))
            return week;
        TuningData t = tuning();
        if (t == null || t.wrathAheadBrackets <= 0 || !onFor(t.wrathAheadOnDifficulty)
                || (dungeonSource && !t.wrathAheadDungeonSources))
            return week;
        SpawnTierWeightData data = Config.instance().getSpawnTierWeightData();
        if (data == null || data.weekBrackets == null || data.weekBrackets.length == 0)
            return week;
        int at = -1;
        for (int i = 0; i < data.weekBrackets.length; i++) {
            SpawnTierWeightData.WeekBracket b = data.weekBrackets[i];
            if (week >= b.weekMin && (b.weekMax == -1 || week <= b.weekMax)) {
                at = i;
                break;
            }
        }
        if (at < 0)
            return week;
        int to = Math.min(data.weekBrackets.length - 1, at + t.wrathAheadBrackets);
        SpawnTierWeightData.WeekBracket bracket = data.weekBrackets[to];
        int ahead = to == at ? week : Math.max(week, bracket.weekMin);
        World world = world();
        if (loggedAhead.add((world == null ? 0L : world.getSeed()) + "|" + land + "|" + week + "|" + dungeonSource)) {
            String source = dungeonSource ? " (its dungeons' roamers)" : "";
            if (to == at)
                System.out.println("[TFR-Wrath] spawn ahead: " + land + " land" + source + " is already at the last bracket"
                        + " (week " + week + ") - no change");
            else
                System.out.println("[TFR-Wrath] spawn ahead: " + land + " land" + source + " rolls week " + ahead
                        + "'s row (bracket " + bracket.weekMin + "-" + (bracket.weekMax == -1 ? "" : bracket.weekMax)
                        + ") in week " + week);
        }
        return ahead;
    }

    // ------------------------------------------------------------------------------------------------- the bounty

    /**
     * DuelScene: a duel ended. {@code counted} is the enemy when the win counts for the Temple tally (the player's own
     * win, no Arena, Inn, fixed-deck or guard fight), else null - which also clears an unused mark from an earlier duel.
     */
    public static void onDuelEnded(EnemySprite counted) {
        if (pendingBounty != null && pendingBounty != counted)
            System.out.println("[TFR-Wrath] bounty for " + pendingBounty.getName() + " dropped - it reached no loot screen");
        pendingBounty = null;
        if (counted == null || counted.getData() == null)
            return;
        String color = huntedColor();
        TuningData t = tuning();
        if (color == null || t == null || !onFor(t.wrathBountyOnDifficulty)
                || (t.wrathBountyGold <= 0f && t.wrathBountyShards <= 0f))
            return;
        String colors = counted.getData().colors == null ? "" : counted.getData().colors.toUpperCase();
        if (colors.contains(letterOf(color)))
            pendingBounty = counted;
    }

    /** WorldStage.setWinner / MapStage.getReward: the won duel's loot, before it shows - raises its gold and shards. */
    public static void applyBounty(Array<Reward> loot, EnemySprite mob) {
        if (pendingBounty == null)
            return;
        if (pendingBounty != mob || loot == null) {
            System.out.println("[TFR-Wrath] bounty for " + pendingBounty.getName() + " dropped - the loot was "
                    + (mob == null ? "nobody's" : mob.getName() + "'s"));
            pendingBounty = null;
            return;
        }
        pendingBounty = null;
        TuningData t = tuning();
        if (t == null)
            return;
        int goldBefore = 0, goldAfter = 0, shardsBefore = 0, shardsAfter = 0;
        for (int i = 0; i < loot.size; i++) {
            Reward r = loot.get(i);
            if (r == null)
                continue;
            if (r.getType() == Reward.Type.Gold && t.wrathBountyGold > 0f && r.getCount() > 0) {
                int raised = raised(r.getCount(), t.wrathBountyGold);
                goldBefore += r.getCount();
                goldAfter += raised;
                loot.set(i, r.isCardFallbackGold() ? Reward.cardFallbackGold(raised) : new Reward(raised));
            } else if (r.getType() == Reward.Type.Shards && t.wrathBountyShards > 0f && r.getCount() > 0) {
                int raised = raised(r.getCount(), t.wrathBountyShards);
                shardsBefore += r.getCount();
                shardsAfter += raised;
                loot.set(i, new Reward(Reward.Type.Shards, raised));
            }
        }
        EnemyData data = mob.getData();
        System.out.println("[TFR-Wrath] bounty: " + mob.getName() + " (" + (data == null ? "?" : data.colors) + ") gold "
                + goldBefore + " -> " + goldAfter + ", shards " + shardsBefore + " -> " + shardsAfter
                + (goldBefore == 0 && shardsBefore == 0 ? " (no gold or shards in this loot)" : ""));
    }

    /** count x (1 + share), rounded up - so any shards gain one at least. */
    private static int raised(int count, float share) {
        return (int) Math.ceil(count * (1f + share) - 0.0001f);
    }

    // ------------------------------------------------------------------------------------------------- the console

    /** The console "wrath": no argument reports; "start red" hunts red this week (needs the Temple, or "start red force");
     *  "stop" ends the hunt. */
    public static String console(String[] args) {
        World world = world();
        if (world == null)
            return "No world.";
        if (!isEnabled())
            return "The Wrath is off on this plane.";
        if (args.length < 1) {
            String color = huntedColor();
            return color == null ? "Nobody is hunted this week (week " + (world.getCurrentWeek() + 1) + ")."
                    : capitalized(color) + " is hunted this week (week " + (world.getCurrentWeek() + 1) + ", status "
                    + world.getWrathStatus() + "). Temple: " + (TempleOfferings.capitolHasTemple() ? "yes" : "no") + ".";
        }
        if ("stop".equalsIgnoreCase(args[0])) {
            world.setWrath(null, -1, NONE);
            System.out.println("[TFR-Wrath] console: the hunt is stopped");
            return "The hunt is stopped.";
        }
        if ("start".equalsIgnoreCase(args[0]) && args.length >= 2) {
            String color = args[1].toLowerCase();
            if (letterOf(color).isEmpty())
                return "Give a color: white, blue, black, red or green.";
            boolean force = args.length >= 3 && "force".equalsIgnoreCase(args[2]);
            if (!force && !TempleOfferings.capitolHasTemple())
                return "The Capitol has no Temple - add \"force\" to hunt anyway.";
            start(color, world.getCurrentWeek(), "(console" + (force ? ", forced" : "") + ")");
            return huntedColor() == null ? "Nothing to hunt with on this difficulty - see forge.log [TFR-Wrath]."
                    : capitalized(color) + " is hunted this week.";
        }
        return "wrath | wrath start <color> [force] | wrath stop";
    }
}
