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
 * enemy-tier recompute round and the Cloaker/Hulking Brute/Recruiter Sliver playtest finding):
 * <ol>
 * <li>Week progression - the tier mix (Common/Uncommon/Rare/Mythic target percentages) shifts
 * toward higher tiers as real in-game weeks pass, per a hand-editable bracket table.</li>
 * <li>Territory/reputation modifier - player-owned terrain and the player's relationship with
 * whichever AI color owns the current tile shift that same mix further, via percentage-point
 * deltas on top of the week bracket's row.</li>
 * <li>Per-enemy kill-decay - repeatedly killing the exact same named enemy suppresses its own
 * future spawn weight a little, recovering over time, so a distinct individual doesn't keep
 * reappearing for the player to grind.</li>
 * </ol>
 * Folds into BiomeData.getEnemy()'s existing spawnRate-weighted pick as a per-enemy multiplier -
 * deliberately does NOT change that method's signature or either of its existing fallback guards,
 * so TerritoryControl.reThemedEnemyFor() (a second, separate caller of the exact same 1-arg
 * method, used when territory ownership flips to re-theme a hardcoded encounter) keeps compiling
 * and behaving unchanged, just inheriting this system's math the same way ordinary roaming spawns
 * do - a deliberate choice, not an oversight, since a re-themed encounter is drawn from the new
 * owner's own pool and should reflect that owner's own current week/reputation situation.
 * <p>
 * Boss and quest-tagged enemies are exempt from every layer here - same "story/scripted encounters
 * aren't silently reshaped" idiom already used by EnemySprite.java's loot edition-restriction and
 * MapStage.java's territory re-theming (both explicitly skip boss/quest-tagged enemies). This also
 * correctly leaves TerritoryControl's own WAR_TIER_BOSSES roll (a separate, pre-existing mechanic -
 * a flat 4% chance at War status to spawn a named legendary boss via a direct name lookup, never
 * touching BiomeData.getEnemy() at all) completely untouched: those bosses are boss=true, so even
 * if one is also findable in the ordinary roaming pool, it never gets suppressed by Layer 3 just
 * for being fought via that separate mechanic.
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

    /** Bosses and quest-tagged enemies are exempt from tier-weighting AND kill-decay - they keep
     *  their original spawnRate-only weighting untouched, same as with this whole system off. */
    public static boolean isExempt(EnemyData data) {
        return data != null && (data.boss || (data.questTags != null && data.questTags.length > 0));
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
        float base = baseFor(bracket, tier);
        SpawnTierWeightData.TierDelta delta = resolveDelta(data, owningColor);
        float deltaVal = delta == null ? 0f : deltaFor(delta, tier);
        return Math.max(0f, base + deltaVal);
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

    /** Resolves which territoryDeltas row applies: "player" biome ownership -> PLAYER_OWNED;
     *  an AI color with ColorReputation enabled -> that color's current Status name; anything
     *  else (colorless/wasteland, reputation off, unrecognized name) -> NEUTRAL (an explicit
     *  all-zero row, same effect as no delta at all). */
    private static SpawnTierWeightData.TierDelta resolveDelta(SpawnTierWeightData data, String owningColor) {
        if (data.territoryDeltas == null)
            return null;
        String key;
        if ("player".equals(owningColor)) {
            key = "PLAYER_OWNED";
        } else if (owningColor != null && ColorReputation.isEnabled() && Arrays.asList(ColorReputation.COLORS).contains(owningColor)) {
            key = ColorReputation.getStatus(owningColor).name();
        } else {
            key = "NEUTRAL";
        }
        return data.territoryDeltas.get(key);
    }

    /**
     * Layer 3: this enemy's own kill-decay multiplier (1.0 = no suppression). Exempt (boss/quest)
     * enemies always return 1.0. Lazy decay - nothing ticks on a timer; the stored stack count is
     * decayed against elapsed days only when actually read, same "absolute day, evaluated on read"
     * idiom DungeonRotation's own respawn-cooldown maps already use.
     */
    public static float killDecayMultiplier(EnemyData data) {
        if (data == null || !isEnabled() || isExempt(data))
            return 1.0f;
        World world = WorldSave.getCurrentSave().getWorld();
        int effectiveStacks = effectiveStacks(world, data.getName(), world.getCurrentDay());
        if (effectiveStacks <= 0)
            return 1.0f;
        TuningData tuning = Config.instance().getTuningData();
        float perStack = tuning.killDecaySuppressionPerStack;
        return (float) Math.pow(1.0 - perStack, effectiveStacks);
    }

    private static int effectiveStacks(World world, String enemyName, int currentDay) {
        Integer stacks = world.getEnemyKillStacks().get(enemyName);
        if (stacks == null || stacks <= 0)
            return 0;
        Integer lastKillDay = world.getEnemyLastKillDay().get(enemyName);
        int daysSince = lastKillDay == null ? 0 : Math.max(0, currentDay - lastKillDay);
        TuningData tuning = Config.instance().getTuningData();
        int recoveryDays = Math.max(1, tuning.killDecayRecoveryDaysPerStack);
        int decayedStacks = daysSince / recoveryDays;
        return Math.max(0, stacks - decayedStacks);
    }

    /**
     * Called once per confirmed roaming-combat win (see DuelScene.afterGameEnd()'s endRunnable,
     * same guarded funnel PlayerStatistic.setResult() already uses). No-op when disabled or for
     * exempt (boss/quest) enemies - repeatedly beating a scripted boss never suppresses it.
     */
    public static void registerKill(EnemyData data) {
        if (data == null || !isEnabled() || isExempt(data))
            return;
        String enemyName = data.getName();
        World world = WorldSave.getCurrentSave().getWorld();
        TuningData tuning = Config.instance().getTuningData();
        int currentDay = world.getCurrentDay();
        int effective = effectiveStacks(world, enemyName, currentDay);
        int maxStacks = Math.max(1, tuning.killDecayMaxStacks);
        int newStacks = Math.min(effective + 1, maxStacks);
        world.getEnemyKillStacks().put(enemyName, newStacks);
        world.getEnemyLastKillDay().put(enemyName, currentDay);
        System.out.println("[TFR-KillDecay] " + enemyName + " stacks " + effective + " -> " + newStacks
                + " (multiplier=" + Math.pow(1.0 - tuning.killDecaySuppressionPerStack, newStacks)
                + ", day=" + currentDay + ")");
    }

    /** All four tier names, in a stable order - used by BiomeData.getEnemy() to precompute each
     *  eligible tier's target weight once per roll rather than once per candidate enemy. */
    public static String[] tiers() {
        return TIERS;
    }
}
