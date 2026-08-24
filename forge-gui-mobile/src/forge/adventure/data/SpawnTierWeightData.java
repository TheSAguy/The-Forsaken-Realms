package forge.adventure.data;

import com.badlogic.gdx.utils.ObjectMap;

/**
 * Backing class for the plane's "config tables/spawn_tier_weighting.json" (user request
 * 2026-08-23: week-based tier progression + territory/reputation modifier for overworld roaming
 * spawns). Same dedicated-table-file pattern as RestrictedCardsData for
 * "config tables/restricted_cards.json" - hand-editable, no code change needed to rebalance.
 * See SpawnTierWeighting.java for how this data is actually consumed.
 * <p>
 * weekBrackets: ordered list of {weekMin, weekMax, common, uncommon, rare, mythic} rows - the
 * four weights are target percentages (need not sum to exactly 100; SpawnTierWeighting clamps
 * and renormalizes defensively either way) for that week range. weekMax = -1 means open-ended
 * ("this bracket applies forever once reached") - the last row should use this as its ceiling.
 * <p>
 * territoryDeltas: percentage-point deltas added to the week bracket's row before renormalizing,
 * keyed by territory/reputation status - "PLAYER_OWNED", "PARTNER", "HAPPY", "NEUTRAL",
 * "UNHAPPY", "WAR" (PLAYER_OWNED is resolved separately from ColorReputation.Status; the other
 * five keys match ColorReputation.Status.name() exactly, including NEUTRAL as an explicit
 * all-zero row for clarity even though it's also the safe default when a key is missing).
 */
public class SpawnTierWeightData {
    public static class WeekBracket {
        public int weekMin;
        public int weekMax; // -1 = open-ended
        public float common;
        public float uncommon;
        public float rare;
        public float mythic;
    }

    public static class TierDelta {
        public float common;
        public float uncommon;
        public float rare;
        public float mythic;
    }

    public WeekBracket[] weekBrackets;
    public ObjectMap<String, TierDelta> territoryDeltas;
}
