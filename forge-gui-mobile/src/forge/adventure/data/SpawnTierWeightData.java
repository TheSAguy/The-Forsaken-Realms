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
 * keyed by territory/reputation status - "PLAYER_OWNED", "WASTELAND", "PARTNER", "HAPPY",
 * "NEUTRAL", "UNHAPPY", "WAR" (PLAYER_OWNED and WASTELAND are resolved separately from
 * ColorReputation.Status; the other five keys match ColorReputation.Status.name() exactly).
 * WASTELAND was added in round 183 - before it, ownerless land and an AI color you were merely
 * NEUTRAL with shared the NEUTRAL row, so walking into a rival's own territory was no more
 * dangerous than no-man's-land. A table with no WASTELAND row still falls back to NEUTRAL.
 * Each row may also carry the optional per-tier multipliers below.
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
        // Round 183: optional MULTIPLIERS applied after the deltas above (default 1 = no change, so every table
        // written before this round behaves exactly as it did). A delta is the wrong tool for a rank that has to
        // stay a FRACTION of the week's own target - "Partner land almost never sends an Archmage" has to mean
        // a tenth of whatever this week allows, not a flat -12 points that turns into a hard 0 in week 7 and into
        // nothing at all in week 21. Deltas move the low ranks, multipliers the high ones; see the JSON's comment.
        public float commonScale = 1f;
        public float uncommonScale = 1f;
        public float rareScale = 1f;
        public float mythicScale = 1f;
    }

    public WeekBracket[] weekBrackets;
    public ObjectMap<String, TierDelta> territoryDeltas;
}
