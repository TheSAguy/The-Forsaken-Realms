package forge.adventure.util;

import java.util.Map;

/**
 * Round 986: the plain arithmetic and bookkeeping behind the three loot perks (Bounty Hunter, Legend Slayer, Treasure
 * Seeker), kept free of the game's state so LootPerksTest can check it. The perks themselves are read through Ascendance;
 * StreakBounty, PlaceRewards and TreasureHunt call these with the numbers.
 */
public final class LootPerks {
    private LootPerks() {
    }

    /**
     * The Bounty in whole percent: points x share x factor, rounded half up, capped at cap + capBonus. A cap of 0 is "no
     * cap" (custom difficulty) and stays uncapped with the perk. 0 for no points or a share of 0 (the Bounty off).
     */
    public static int bountyPercent(int points, float share, float cap, float factor, float capBonus) {
        if (points <= 0 || share <= 0f)
            return 0;
        float raw = points * share * Math.max(1f, factor) * 100f;
        int pct = (int) Math.floor(raw + 0.5f + 1e-3f);
        if (cap > 0f)
            pct = Math.min(pct, (int) Math.floor((cap + Math.max(0f, capBonus)) * 100f + 1e-3f));
        return Math.max(0, pct);
    }

    /** A map's piece count after an obelisk touch that gives {@code perTouch} pieces (at least one, never past the whole). */
    public static int piecesAfterTouch(int pieces, int perTouch, int whole) {
        return Math.min(whole, Math.max(0, pieces) + Math.max(1, perTouch));
    }

    /** A treasure entry's count with Treasure Seeker II: {@code count} times {@code times} (1 = as written). */
    public static int timesCount(int count, int times) {
        return count * Math.max(1, times);
    }

    /** Legend Slayer's key in World.getOncePaidRewards(), beside round 302's "enemy|life|name". */
    public static String legendSlayerKey(String legendName) {
        return "enemy|legendSlayer|" + legendName;
    }

    /**
     * Legend Slayer: true the first time this legend is beaten with the perk - the key is written then (value: the day),
     * so every later win over the same legend returns false. The map is the world's saved once-paid map.
     */
    public static boolean claimLegendSlayer(Map<String, Integer> oncePaid, String legendName, int day) {
        if (oncePaid == null || legendName == null || legendName.isEmpty())
            return false;
        return oncePaid.putIfAbsent(legendSlayerKey(legendName), day) == null;
    }
}
