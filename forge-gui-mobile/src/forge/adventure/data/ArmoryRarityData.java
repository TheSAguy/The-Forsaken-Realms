package forge.adventure.data;

import com.badlogic.gdx.utils.ObjectMap;

/**
 * Armory item-rarity weights by venue and in-game week - see the plane's
 * "config tables/armory_rarity.json" for the table itself and the user spec behind it.
 * <p>
 * Deliberately shaped like SpawnTierWeightData: a per-key array of week brackets, read once at
 * Config load and never mutated. A missing or unparseable file leaves this null, and
 * {@link forge.adventure.util.ArmoryRarity} falls back to the historical flat
 * Common 60 / Uncommon 30 / Rare 8 / Mythic 2.
 */
public class ArmoryRarityData {

    public static class WeekBracket {
        public int weekMin = 1;
        /** -1 means open-ended: this row applies from weekMin onwards. */
        public int weekMax = -1;
        public float common = 60f;
        public float uncommon = 30f;
        public float rare = 8f;
        public float mythic = 2f;

        public boolean covers(int week) {
            return week >= weekMin && (weekMax < 0 || week <= weekMax);
        }

        public float total() {
            return common + uncommon + rare + mythic;
        }
    }

    /** Venue key ("CAPITOL" / "PLAYER_TOWN" / "NEUTRAL_TOWN") -> its week brackets. */
    public ObjectMap<String, WeekBracket[]> venueBrackets;
}
