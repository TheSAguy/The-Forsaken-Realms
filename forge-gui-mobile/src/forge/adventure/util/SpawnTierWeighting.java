package forge.adventure.util;

import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.SpawnTierWeightData;
import forge.adventure.data.TuningData;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;

import java.util.Arrays;

/**
 * 3-layer weighted overworld-spawn tier system (user spec 2026-08-23, following directly from the
 * enemy-tier recompute round and the Cloaker/Hulking Brute/Recruiter Sliver playtest finding;
 * Layer 3 redesigned 2026-08-25, see below):
 * <ol>
 * <li>Week progression - the tier mix (Common/Uncommon/Rare/Mythic target percentages) shifts
 * toward higher tiers as real in-game weeks pass, per a hand-editable bracket table.</li>
 * <li>Territory/reputation modifier - player-owned terrain and the player's relationship with
 * whichever AI color owns the current tile shift that same mix further, via percentage-point
 * deltas on top of the week bracket's row.</li>
 * <li>Per-enemy kill-decay - once a tier is chosen, the specific enemy within it is picked from a
 * PERFECTLY UNIFORM baseline (not spawnRate-weighted), permanently halved once per confirmed kill
 * of that exact enemy name - see rawSpawnWeight(). Redesigned 2026-08-25 (user spec) to replace an
 * earlier time-decaying, capped-stack version: the user's requirement was that defeating every
 * candidate in a pool an equal number of times must renormalize the pool back to perfectly
 * uniform, which only holds for a stateless "recompute each weight fresh from its own permanent
 * kill count, then renormalize the whole pool" formula - a sequential "halve current value, split
 * the freed share among the others" process is path-dependent and does NOT return to uniform once
 * more than one distinct enemy has been reduced (verified algebraically before this rewrite).</li>
 * </ol>
 * Folds into BiomeData.getEnemy()'s existing weighted pick as each candidate's effective weight -
 * deliberately does NOT change that method's signature or either of its existing fallback guards,
 * so TerritoryControl.reThemedEnemyFor() (a second, separate caller of the exact same 1-arg
 * method, used when territory ownership flips to re-theme a hardcoded encounter) keeps compiling
 * and behaving unchanged, just inheriting this system's math the same way ordinary roaming spawns
 * do - a deliberate choice, not an oversight, since a re-themed encounter is drawn from the new
 * owner's own pool and should reflect that owner's own current week/reputation situation.
 * <p>
 * Boss and quest-tagged enemies are exempt from every layer here - same "story/scripted encounters
 * aren't silently reshaped" idiom already used by EnemySprite.java's loot edition-restriction and
 * MapStage.java's territory re-theming (both explicitly skip boss/quest-tagged enemies). A quest's
 * own "Defeat" objective target is additionally guaranteed extra spawn weight on top of this
 * exemption via AdventureQuestController.getExtraQuestSpawns()/getBoostedSpawns() - neither of
 * those is touched by this class at all, so an active kill-quest's spawns are never suppressed by
 * anything here (2026-08-25 user confirmation). This also correctly leaves TerritoryControl's own
 * WAR_TIER_BOSSES roll (a separate, pre-existing mechanic - a flat 4% chance at War status to
 * spawn a named legendary boss via a direct name lookup, never touching BiomeData.getEnemy() at
 * all) completely untouched: those bosses are boss=true, so even if one is also findable in the
 * ordinary roaming pool, it never gets suppressed by Layer 3 just for being fought via that
 * separate mechanic.
 * <p>
 * Opt-in via ConfigData.weightedSpawnTiersEnabled (false by default, on only in this plane's
 * config.json) - completely inert on every other plane, matching every other mod feature's
 * standing convention. When disabled, isEnabled() short-circuits and BiomeData.getEnemy() falls
 * straight back to its original pure-spawnRate behavior.
 */
public class SpawnTierWeighting {
    private static final String[] TIERS = {"Common", "Uncommon", "Rare", "Mythic"};

    private SpawnTierWeighting() {}

    public static boolean isEnabled() {
        ConfigData config = Config.instance().getConfigData();
        return config != null && config.weightedSpawnTiersEnabled;
    }

    /** Bosses are exempt from tier-weighting AND kill-decay - they keep their original
     *  spawnRate-only weighting untouched, same as with this whole system off.
     *  Also exempts spawnRate<=0 enemies (2026-08-25 bug found via playtest: oversized "Legends"/
     *  commander-flavor enemies like Kothophed and Yargle and Multani, authored with spawnRate:0
     *  and no boss/questTags flag, started appearing as ordinary roaming encounters at roughly 2x
     *  the normal visual size). rawSpawnWeight()'s uniform-baseline formula deliberately ignores
     *  an enemy's own spawnRate by design (ordinary candidates should be equally likely regardless
     *  of that stat) - but that dropped the pre-existing "spawnRate 0 = never spawns on its own"
     *  invariant BiomeData.getEnemyList()'s quest-boost clone mechanism (and any data entry
     *  authored with spawnRate 0 for the same reason) depended on. Restoring the exemption here
     *  means BiomeData.getEnemy()'s exempt branch gives these candidates their own (zero) weight
     *  again, matching pre-Layer-3 / feature-disabled behavior, without touching the weighting
     *  math for any enemy that legitimately has spawnRate > 0.
     *  <p>
     *  The original questTags exemption was REMOVED 2026-08-27 (playtest: two "(Master)"-shown
     *  Rares roaming on day 1). It was meant to shield scripted quest enemies, but this plane
     *  uses questTags as generic metadata ("BiomeGreen", "Human", "IdentityGreen", ...) - 1398
     *  of 1520 enemies.json entries carried tags, so ~97% of the day-1 pool kept raw spawnRate
     *  weighting and week 1's "rare: 0" bracket bound almost nothing. The clause also protected
     *  nothing real: actual quest spawns route through BiomeData.getExtraSpawnEnemy()/
     *  AdventureQuestController.getQuestSprites(), which never consult this weighting at all.
     *  <p>
     *  Round 180: EnemySprite.getRewards() uses this same rule for the Progressive Set Unlocks
     *  loot restriction (exempt = dedicated rewards), so a change here changes loot too. */
    public static boolean isExempt(EnemyData data) {
        return data != null && (data.boss || data.spawnRate <= 0f);
    }

    /** Current week number, 1-indexed (week 1 = days 1-7) - reuses the identical day/7 boundary
     *  math World.recordStandingsHistoryIfNewWeek() already uses for its own weekly snapshots,
     *  just re-based so this class and its config table can talk in "week 1" terms. */
    public static int currentWeek(World world) {
        return 1 + (world.getCurrentDay() - 1) / 7;
    }

    /**
     * Layers 1+2 combined: the TARGET percentage for one tier at the given week, adjusted by
     * whichever territory/reputation situation owningColor resolves to. Returns a raw
     * percentage-ish number on the same scale as the config table (not yet divided by a tier's
     * "natural" spawnRate weight - the caller, BiomeData.getEnemy(), does that once it has
     * tallied each eligible tier's own natural weight for this specific roll).
     */
    public static float targetTierWeight(String tier, int week, String owningColor) {
        SpawnTierWeightData data = Config.instance().getSpawnTierWeightData();
        if (data == null || data.weekBrackets == null || data.weekBrackets.length == 0)
            return 0f;
        SpawnTierWeightData.WeekBracket bracket = findBracket(data.weekBrackets, week);
        if (bracket == null)
            return 0f;
        String key = resolveKey(data, owningColor);
        SpawnTierWeightData.TierDelta delta = data.territoryDeltas == null ? null : data.territoryDeltas.get(key);
        String difficulty = difficultyName();
        SpawnTierWeightData.TierDelta factor = difficultyFactor(data, difficulty);
        SpawnTierWeightData.TierDelta tilt = notorietyTilt(data); // round 447
        logRowOnce(bracket, delta, factor, tilt, key, week, difficulty);
        float shift = winRatioShift(); // round 613: last, on the finished row
        if (delta == null && factor == null && tilt == null) {
            if (shift == 0f)
                return Math.max(0f, baseFor(bracket, tier));
            float[] row = new float[TIERS.length];
            for (int i = 0; i < TIERS.length; i++)
                row[i] = Math.max(0f, baseFor(bracket, TIERS[i]));
            return shifted(row, shift, tier);
        }
        float adjusted = rowValue(bracket, delta, factor, tilt, tier);
        // Round 183: renormalize the row back to the week bracket's OWN total. The four adjusted numbers describe a
        // MIX, and every territory row before this round summed to zero change by hand (+10/+2/-4/-8 etc.) for a
        // reason: BiomeData.getEnemy() divides by the whole pool, and an EXEMPT entry (a boss, an arena-only fighter)
        // contributes its own fixed spawnRate to that pool rather than a tier target. Letting a row total drift -
        // War's multipliers come to 187 against the plateau's 100 - would quietly make bosses half as likely at war
        // and commoner on your own land, which is a change nobody asked for. Scaling the row back to the bracket's
        // total keeps the tilt and leaves the ordinary-vs-exempt balance exactly where it was.
        float rowTotal = 0f;
        for (String t : TIERS)
            rowTotal += rowValue(bracket, delta, factor, tilt, t);
        if (rowTotal <= 0f)
            return adjusted;
        float bracketTotal = bracket.common + bracket.uncommon + bracket.rare + bracket.mythic;
        if (shift == 0f)
            return adjusted * (bracketTotal / rowTotal);
        float[] row = new float[TIERS.length];
        for (int i = 0; i < TIERS.length; i++)
            row[i] = rowValue(bracket, delta, factor, tilt, TIERS[i]) * (bracketTotal / rowTotal);
        return shifted(row, shift, tier);
    }

    // ------------------------------------------------------------------------------------------------ round 613
    // The win-ratio tilt (the user: "Based on your win ratio, I want to increase, or decrease the spawn levels on enemies.
    // This should be in addition to the weekly increase we have"). The record is AdventurePlayer.recentDuels(): the last
    // winRatioWindow duels notoriety counts. Shift = maxShift x (ratio - 0.5) / 0.3 at the defaults, held to +-maxShift -
    // full at 20/25 and 40/50, -full at 5/25 and 10/50, none at an even record. Points of the row's own total move out of
    // Common into the higher ranks in their proportions (+), or back into Common (-): a rank the week has closed stays shut.

    /** The tier mix of {@code row} (Common, Uncommon, Rare, Mythic; summing to the bracket's total) with {@code shift}
     *  percentage points moved; the value for {@code tier}. */
    private static float shifted(float[] row, float shift, String tier) {
        shiftRow(row, shift);
        for (int i = 0; i < TIERS.length; i++)
            if (TIERS[i].equals(tier == null ? "Common" : tier))
                return Math.max(0f, row[i]);
        return Math.max(0f, row[0]);
    }

    /** The shift applied to the whole row, in place. */
    private static void shiftRow(float[] row, float shift) {
        float total = row[0] + row[1] + row[2] + row[3];
        float higher = row[1] + row[2] + row[3];
        float move = Math.abs(shift) * total / 100f;
        if (shift > 0f && higher > 0f) {
            move = Math.min(move, row[0]);
            for (int i = 1; i < 4; i++)
                row[i] += move * row[i] / higher;
            row[0] -= move;
        } else if (shift < 0f && higher > 0f) {
            move = Math.min(move, higher);
            for (int i = 1; i < 4; i++)
                row[i] -= move * row[i] / higher;
            row[0] += move;
        }
    }

    public static int winRatioWindow() {
        SpawnTierWeightData data = Config.instance().getSpawnTierWeightData();
        return data == null ? 50 : Math.max(1, data.winRatioWindow);
    }

    private static String record() {
        try {
            forge.adventure.player.AdventurePlayer player = forge.adventure.player.AdventurePlayer.current();
            return player == null ? "" : player.recentDuels();
        } catch (RuntimeException e) {
            return ""; // no game loaded yet
        }
    }

    /** The shift in percentage points (+ harder, - easier) for the current record; 0 under the first tier's count. */
    public static float winRatioShift() {
        return winRatioShift(record());
    }

    static float winRatioShift(String record) {
        SpawnTierWeightData data = Config.instance().getSpawnTierWeightData();
        if (data == null || record == null || data.winRatioMinDuels == null || data.winRatioMaxShift == null)
            return 0f;
        int n = record.length();
        float cap = 0f;
        for (int i = 0; i < Math.min(data.winRatioMinDuels.length, data.winRatioMaxShift.length); i++)
            if (n >= data.winRatioMinDuels[i])
                cap = data.winRatioMaxShift[i];
        float half = (data.winRatioHigh - data.winRatioLow) / 2f;
        if (cap <= 0f || n == 0 || half <= 0f)
            return 0f;
        int wins = 0;
        for (int i = 0; i < n; i++)
            if (record.charAt(i) == 'W')
                wins++;
        float ratio = wins / (float) n;
        float mid = (data.winRatioHigh + data.winRatioLow) / 2f;
        return cap * Math.max(-1f, Math.min(1f, (ratio - mid) / half));
    }

    /** W wins and L losses spread evenly over a record (oldest first) - for seeding and the console. */
    public static String spread(int wins, int losses) {
        StringBuilder sb = new StringBuilder();
        int n = wins + losses;
        int placed = 0;
        for (int i = 0; i < n; i++) {
            boolean win = Math.round((i + 1) * wins / (float) n) > placed;
            if (win)
                placed++;
            sb.append(win ? 'W' : 'L');
        }
        return sb.toString();
    }

    /** A save from before round 613: its lifetime wins and losses, scaled into the window at the same ratio. */
    public static String seedRecentDuels(int wins, int losses) {
        int total = Math.max(0, wins) + Math.max(0, losses);
        if (total == 0)
            return "";
        int n = Math.min(total, winRatioWindow());
        int w = Math.round(n * Math.max(0, wins) / (float) total);
        String seeded = spread(w, n - w);
        System.out.println("[TFR-WinRatio] an older save: the record starts from its lifetime " + wins + "-" + losses
                + " as " + w + "-" + (n - w) + " - " + describe(seeded));
        return seeded;
    }

    public static String describeWinRatio() {
        return describe(record());
    }

    private static String describe(String record) {
        int wins = 0;
        for (int i = 0; i < record.length(); i++)
            if (record.charAt(i) == 'W')
                wins++;
        float shift = winRatioShift(record);
        return "last " + record.length() + " duels " + wins + "-" + (record.length() - wins)
                + (record.isEmpty() ? "" : " (" + Math.round(100f * wins / record.length()) + "% won)")
                + ", spawn shift " + (shift > 0 ? "+" : "") + String.format(java.util.Locale.ROOT, "%.1f", shift) + " points";
    }

    /** Round 620: the token scripts the enemy starts with for the player's record (empty under winRatioHelpMinDuels). */
    public static String[] winRatioEnemyTokens() {
        SpawnTierWeightData data = Config.instance().getSpawnTierWeightData();
        return data == null ? new String[0] : ratioTokens(data, data.winRatioEnemyAtLeast, data.winRatioEnemyTokens, true);
    }

    /** Round 620: the token scripts the player starts with for their record. */
    public static String[] winRatioPlayerTokens() {
        SpawnTierWeightData data = Config.instance().getSpawnTierWeightData();
        return data == null ? new String[0] : ratioTokens(data, data.winRatioPlayerAtMost, data.winRatioPlayerTokens, false);
    }

    private static String[] ratioTokens(SpawnTierWeightData data, float[] thresholds, String[] tokens, boolean atLeast) {
        String record = record();
        if (thresholds == null || tokens == null || record.length() < Math.max(1, data.winRatioHelpMinDuels))
            return new String[0];
        int wins = 0;
        for (int i = 0; i < record.length(); i++)
            if (record.charAt(i) == 'W')
                wins++;
        float ratio = wins / (float) record.length();
        for (int i = 0; i < Math.min(thresholds.length, tokens.length); i++) {
            boolean met = atLeast ? ratio >= thresholds[i] - 1e-6f : ratio <= thresholds[i] + 1e-6f;
            if (met && tokens[i] != null && !tokens[i].trim().isEmpty())
                return tokens[i].trim().split("\\s*,\\s*");
        }
        return new String[0];
    }

    /** [TFR-WinRatio] after each counted duel. */
    public static void logWinRatio(String what) {
        System.out.println("[TFR-WinRatio] " + what + " - " + describeWinRatio());
    }

    /** One tier's target before the row is renormalized: the territory row (if any), then the difficulty's multiplier,
     *  then the round-447 win-streak tilt. */
    private static float rowValue(SpawnTierWeightData.WeekBracket bracket, SpawnTierWeightData.TierDelta delta,
                                  SpawnTierWeightData.TierDelta factor, SpawnTierWeightData.TierDelta tilt, String tier) {
        float value = delta == null ? Math.max(0f, baseFor(bracket, tier)) : adjustedFor(bracket, delta, tier);
        if (factor != null)
            value *= Math.max(0f, scaleFor(factor, tier));
        return tilt == null ? value : value * Math.max(0f, scaleFor(tilt, tier));
    }

    /**
     * Round 447 (the user: "gradually increase the spawn chances of higher level enemies as the player's win streak ...
     * goes up"): the win-streak level - wins in a row / notorietyWinsPerLevel, capped at notorietyMaxLevel (45 and 50
     * wins are levels 9 and 10, the bonus Walls'). 0 with no table, no game or no streak.
     */
    public static int notorietyTiltLevel() {
        SpawnTierWeightData data = Config.instance().getSpawnTierWeightData();
        if (data == null || data.notorietyPerLevel == null || data.notorietyWinsPerLevel <= 0)
            return 0;
        try {
            forge.adventure.player.AdventurePlayer player = forge.adventure.player.AdventurePlayer.current();
            if (player == null || forge.adventure.player.AdventurePlayer.notorietyDisabled()) // round 772: off in Settings
                return 0;
            return Math.max(0, Math.min(data.notorietyMaxLevel, player.notorietyStreak() / data.notorietyWinsPerLevel));
        } catch (RuntimeException e) {
            return 0; // no game loaded yet
        }
    }

    /** Round 447: the tilt's multipliers at the current level (1 + perLevel x level per tier), or null at level 0. A
     *  multiplier, like a difficulty factor: it cannot open a rank the week bracket closed. */
    private static SpawnTierWeightData.TierDelta notorietyTilt(SpawnTierWeightData data) {
        int level = notorietyTiltLevel();
        if (level <= 0)
            return null;
        SpawnTierWeightData.TierDelta per = data.notorietyPerLevel;
        SpawnTierWeightData.TierDelta tilt = new SpawnTierWeightData.TierDelta();
        tilt.commonScale = Math.max(0f, 1f + per.commonScale * level);
        tilt.uncommonScale = Math.max(0f, 1f + per.uncommonScale * level);
        tilt.rareScale = Math.max(0f, 1f + per.rareScale * level);
        tilt.mythicScale = Math.max(0f, 1f + per.mythicScale * level);
        return tilt;
    }

    /** PlayerStatistic.rank()'s steps (lifetime wins: under 20, 20-59, 60-150, over 150). */
    private static final float[] RANK_STEPS = {0.5f, 1f, 2f, 10f};

    /**
     * Round 447 (the user agreed: let the streak open the lifetime-win gate one step): the difficulty ceiling a spawn
     * rolls against - the lifetime-win rank, one step higher while the wins in a row reach
     * TuningData.notorietyRankStepWins (0.5 -> 1 -> 2 -> 10; 10 stays). Without it a player under 150 lifetime wins
     * never meets a roaming Archmage, whatever the tilt asks for.
     */
    public static float effectiveRank(float lifetimeRank) {
        TuningData tuning = Config.instance().getTuningData();
        if (tuning == null || tuning.notorietyRankStepWins <= 0)
            return lifetimeRank;
        try {
            forge.adventure.player.AdventurePlayer player = forge.adventure.player.AdventurePlayer.current();
            if (player == null || player.notorietyStreak() < tuning.notorietyRankStepWins
                    || forge.adventure.player.AdventurePlayer.notorietyDisabled()) // round 772: off in Settings
                return lifetimeRank;
        } catch (RuntimeException e) {
            return lifetimeRank;
        }
        for (float step : RANK_STEPS)
            if (step > lifetimeRank)
                return step;
        return lifetimeRank;
    }

    /**
     * Round 418 (the user: "It sounds like we need to up the chances of Adept+ appearing early in Hard and Insane"). The
     * rank mix used to ignore the difficulty: Insane in week 3 met 78% Apprentices, like Easy. spawn_tier_weighting.json
     * difficultyFactors multiplies the row the same way a territory row does - multipliers only, so a week that closes a
     * rank (week 1 has no Masters) keeps it closed on every difficulty - and the row is renormalized like a territory row,
     * so bosses and other exempt spawns keep their share. Easy and Normal have no row and play the table as written.
     */
    private static SpawnTierWeightData.TierDelta difficultyFactor(SpawnTierWeightData data, String difficulty) {
        if (data.difficultyFactors == null || difficulty == null)
            return null;
        for (com.badlogic.gdx.utils.ObjectMap.Entry<String, SpawnTierWeightData.TierDelta> e : data.difficultyFactors)
            if (e.key != null && e.key.equalsIgnoreCase(difficulty))
                return e.value;
        return null;
    }

    private static String difficultyName() {
        try {
            forge.adventure.player.AdventurePlayer player = forge.adventure.player.AdventurePlayer.current();
            return player == null || player.getDifficulty() == null ? null : player.getDifficulty().name;
        } catch (RuntimeException e) {
            return null; // no game loaded yet - the table as written
        }
    }

    /** One tier's adjusted target before the row is renormalized: the delta moves it, the multiplier scales it.
     *  A multiplier cannot open a rank the week bracket closed (0 x anything is 0), which is what keeps the week
     *  brackets the single authority on early-game pacing. */
    private static float adjustedFor(SpawnTierWeightData.WeekBracket bracket, SpawnTierWeightData.TierDelta delta,
                                     String tier) {
        return Math.max(0f, baseFor(bracket, tier) + deltaFor(delta, tier)) * scaleFor(delta, tier);
    }

    private static SpawnTierWeightData.WeekBracket findBracket(SpawnTierWeightData.WeekBracket[] brackets, int week) {
        for (SpawnTierWeightData.WeekBracket b : brackets) {
            if (week >= b.weekMin && (b.weekMax == -1 || week <= b.weekMax))
                return b;
        }
        // No bracket matched (a gap in a hand-edited table) - fall back to the last bracket rather
        // than silently returning an all-zero tier mix, which would make getEnemy() fall through
        // to its own "totalDistribution <= 0" uniform-random guard for every enemy, tier-blind.
        return brackets.length > 0 ? brackets[brackets.length - 1] : null;
    }

    private static float baseFor(SpawnTierWeightData.WeekBracket bracket, String tier) {
        switch (tier == null ? "Common" : tier) {
            case "Uncommon": return bracket.uncommon;
            case "Rare":     return bracket.rare;
            case "Mythic":   return bracket.mythic;
            default:         return bracket.common;
        }
    }

    private static float deltaFor(SpawnTierWeightData.TierDelta delta, String tier) {
        switch (tier == null ? "Common" : tier) {
            case "Uncommon": return delta.uncommon;
            case "Rare":     return delta.rare;
            case "Mythic":   return delta.mythic;
            default:         return delta.common;
        }
    }

    private static float scaleFor(SpawnTierWeightData.TierDelta delta, String tier) {
        switch (tier == null ? "Common" : tier) {
            case "Uncommon": return delta.uncommonScale;
            case "Rare":     return delta.rareScale;
            case "Mythic":   return delta.mythicScale;
            default:         return delta.commonScale;
        }
    }

    /** Resolves which territoryDeltas row applies: "player" biome ownership -> PLAYER_OWNED;
     *  an AI color with ColorReputation enabled -> that color's current Status name; anything
     *  else -> WASTELAND.
     *  <p>
     *  Round 183 (user: "Neutral is somewhere in the middle. AI should probably be a little more hostile than
     *  Neutral on Neutral Reputation"): the ownerless Wasteland and an AI color you are merely NEUTRAL with used
     *  to share one row. They are different places now - WASTELAND is the middle of the ladder, NEUTRAL is a
     *  color's own land. A table without a WASTELAND row falls back to NEUTRAL, as before. */
    private static String resolveKey(SpawnTierWeightData data, String owningColor) {
        if ("player".equals(owningColor))
            return "PLAYER_OWNED";
        if (owningColor != null && ColorReputation.isEnabled() && Arrays.asList(ColorReputation.COLORS).contains(owningColor))
            return ColorReputation.getStatus(owningColor).name();
        if (data.territoryDeltas != null && data.territoryDeltas.containsKey("WASTELAND"))
            return "WASTELAND";
        return "NEUTRAL";
    }

    private static float round2(float v) {
        return Math.round(v * 100f) / 100f;
    }

    /** MTG letter for one of ColorReputation.COLORS ("black" -> "B"), for the skew read-out below. */
    private static String colorLetter(String color) {
        switch (color) {
            case "white": return "W";
            case "blue":  return "U";
            case "black": return "B";
            case "red":   return "R";
            case "green": return "G";
            default:      return "";
        }
    }

    /** Round 183: one line whenever the land under the player - or the week - changes the tier mix, because
     *  nothing else in the log said which of the seven rows a spawn was rolled against. Identical repeats are
     *  suppressed (this runs four times per spawn roll), so stepping across a border prints exactly one line. */
    private static String lastLoggedRow = null;

    private static void logRowOnce(SpawnTierWeightData.WeekBracket bracket, SpawnTierWeightData.TierDelta delta,
                                   SpawnTierWeightData.TierDelta factor, SpawnTierWeightData.TierDelta tilt, String key,
                                   int week, String difficulty) {
        StringBuilder line = new StringBuilder("[TFR-SpawnTier] week ").append(week).append(" on ").append(key)
                .append(delta == null ? " (no row - week bracket as written)" : "")
                .append(factor != null ? ", " + difficulty + " x" + factor.commonScale + "/" + factor.uncommonScale + "/"
                        + factor.rareScale + "/" + factor.mythicScale : "") // round 418
                .append(tilt != null ? ", notoriety level " + notorietyTiltLevel() + " x" + round2(tilt.commonScale) + "/"
                        + round2(tilt.uncommonScale) + "/" + round2(tilt.rareScale) + "/" + round2(tilt.mythicScale) : "") // round 447
                .append(":");
        float rowTotal = 0f;
        float[] adjusted = new float[TIERS.length];
        for (int i = 0; i < TIERS.length; i++) {
            adjusted[i] = rowValue(bracket, delta, factor, tilt, TIERS[i]);
            rowTotal += adjusted[i];
        }
        for (int i = 0; i < TIERS.length; i++) {
            float share = rowTotal > 0f ? 100f * adjusted[i] / rowTotal : 0f;
            line.append(' ').append(EnemyData.tierDisplayName(TIERS[i])).append(' ')
                    .append(Math.round(share * 10f) / 10f).append('%');
        }
        // Round 613: the win-ratio shift and the row it leaves.
        float ratioShift = winRatioShift();
        if (ratioShift != 0f && rowTotal > 0f) {
            float[] pct = new float[TIERS.length];
            for (int i = 0; i < TIERS.length; i++)
                pct[i] = 100f * adjusted[i] / rowTotal;
            shiftRow(pct, ratioShift);
            line.append(" | win ratio ").append(ratioShift > 0 ? "+" : "").append(Math.round(ratioShift * 10f) / 10f)
                    .append(" ->");
            for (int i = 0; i < TIERS.length; i++)
                line.append(' ').append(EnemyData.tierDisplayName(TIERS[i])).append(' ')
                        .append(Math.round(pct[i] * 10f) / 10f).append('%');
        }
        // Round 183: the color skew belongs on the same line - it is the other half of "what am I about to meet",
        // and a per-candidate log of it would print once per enemy per roll.
        if (ColorReputation.isEnabled()) {
            line.append(" | color skew");
            for (String color : ColorReputation.COLORS) {
                // The MTG letter, not the English name's first letter - "blue" starts with a B, which printed
                // two B columns next to black's and made the read-out ambiguous (caught in the round-183 smoke test).
                String letter = colorLetter(color);
                line.append(' ').append(letter)
                        .append(Math.round(ColorReputation.getSpawnColorSkew(letter) * 100f) / 100f);
            }
        }
        String text = line.toString();
        if (!text.equals(lastLoggedRow)) {
            System.out.println(text);
            lastLoggedRow = text;
        }
    }

    /**
     * Layer 3 (redesigned 2026-08-25): this enemy's raw, pre-normalization within-tier weight.
     * Every non-exempt candidate in a tier starts at the same uniform baseline (1 / how many
     * candidates are in that tier) regardless of its own spawnRate stat, then that baseline is
     * permanently halved once per confirmed kill of this exact enemy name - repeat kills keep
     * halving (5%, 2.5%, 1.25%, ...), and it never recovers over time. The caller (BiomeData.
     * getEnemy()) sums this across every candidate in the tier and divides each one's raw weight
     * by that sum to get its actual share, then scales by the tier's own target percentage - a
     * fresh renormalization every roll, which is what makes "defeat every candidate in the pool
     * an equal number of times" land back on a perfectly uniform pool (see class javadoc).
     * Exempt (boss/quest) enemies never reach this method - BiomeData.getEnemy() gives them their
     * original spawnRate weight directly instead.
     */
    public static float rawSpawnWeight(EnemyData data, int candidateCountInTier) {
        if (data == null || candidateCountInTier <= 0)
            return 0f;
        float baseline = 1f / candidateCountInTier;
        int kills = getPermanentKillCount(data.getName());
        // Round 183 (user: "the color reputation skew ... at war x3 probability and partner 1/3"). Applied here,
        // inside the same within-tier weight the kill decay uses, so it is renormalized with everything else: it
        // moves WHICH colors you meet, never how many enemies spawn or which ranks they are.
        return baseline * (float) Math.pow(0.5, kills) * ColorReputation.getSpawnColorSkew(data.colors);
    }

    /** How many times this exact enemy name has been confirmed-defeated in roaming combat, ever.
     *  0 if never (or if the world/save isn't available). */
    public static int getPermanentKillCount(String enemyName) {
        World world = WorldSave.getCurrentSave().getWorld();
        if (world == null || enemyName == null)
            return 0;
        Integer count = world.getEnemyPermanentKillCount().get(enemyName);
        return count == null ? 0 : count;
    }

    /**
     * Called once per confirmed roaming-combat win (see DuelScene.afterGameEnd()'s endRunnable,
     * same guarded funnel PlayerStatistic.setResult() already uses). No-op when disabled or for
     * exempt (boss/quest) enemies - repeatedly beating a scripted boss never suppresses it, and an
     * active kill-quest's own target is separately guaranteed extra weight elsewhere (see class
     * javadoc), never suppressed by this.
     */
    public static void registerKill(EnemyData data) {
        // Round 311: a roaming champion is exempt from the tier weighting but still counts its defeats. Round 375: the
        // legend table goes by sightings instead (LegendSpawns); the count stays for the log and the kill history.
        if (data == null || !isEnabled() || (isExempt(data) && !RoamingChampions.isChampion(data)))
            return;
        String enemyName = data.getName();
        World world = WorldSave.getCurrentSave().getWorld();
        int newCount = world.getEnemyPermanentKillCount().merge(enemyName, 1, Integer::sum);
        System.out.println("[TFR-KillDecay] " + enemyName + " permanent kill count now " + newCount
                + " (next baseline share multiplier=" + Math.pow(0.5, newCount) + ")");
    }

    /** All four tier names, in a stable order - used by BiomeData.getEnemy() to precompute each
     *  eligible tier's target weight once per roll rather than once per candidate enemy. */
    /** Round 338: the fog zone at the spawn tile tilts the tier row - spawn_tier_weighting.json fogZoneFactors "lit" /
     *  "dark", the *Scale fields as multipliers (1 when the table or the key is missing). */
    public static float fogZoneScale(String tier, boolean lit) {
        SpawnTierWeightData data = Config.instance().getSpawnTierWeightData();
        if (data == null || data.fogZoneFactors == null)
            return 1f;
        SpawnTierWeightData.TierDelta f = data.fogZoneFactors.get(lit ? "lit" : "dark");
        if (f == null)
            return 1f;
        switch (tier == null ? "" : tier) {
            case "Common": return f.commonScale;
            case "Uncommon": return f.uncommonScale;
            case "Rare": return f.rareScale;
            case "Mythic": return f.mythicScale;
            default: return 1f;
        }
    }

    public static String[] tiers() {
        return TIERS;
    }
}
