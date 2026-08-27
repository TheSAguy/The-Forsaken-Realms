package forge.adventure.util;

import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.SpawnTierWeightData;
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
     *  AdventureQuestController.getQuestSprites(), which never consult this weighting at all. */
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
        return baseline * (float) Math.pow(0.5, kills);
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
        if (data == null || !isEnabled() || isExempt(data))
            return;
        String enemyName = data.getName();
        World world = WorldSave.getCurrentSave().getWorld();
        int newCount = world.getEnemyPermanentKillCount().merge(enemyName, 1, Integer::sum);
        System.out.println("[TFR-KillDecay] " + enemyName + " permanent kill count now " + newCount
                + " (next baseline share multiplier=" + Math.pow(0.5, newCount) + ")");
    }

    /** All four tier names, in a stable order - used by BiomeData.getEnemy() to precompute each
     *  eligible tier's target weight once per roll rather than once per candidate enemy. */
    public static String[] tiers() {
        return TIERS;
    }
}
