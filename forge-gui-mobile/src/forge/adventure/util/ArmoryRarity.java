package forge.adventure.util;

import forge.adventure.data.ArmoryRarityData;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;

import java.util.Random;

/**
 * Time- and venue-gated Armory item rarity (user spec 2026-08-31).
 * <p>
 * The player's own Armories start mundane and open up over the first month: no Rare in week 1, no
 * Mythic until week 3 and then only in the Capitol, with player towns catching up at week 4 and the
 * Capitol's odds sharpening at the same time. Neutral towns never sell Mythics at all, which is the
 * pre-existing rule this table now expresses directly instead of stripping them after the fact.
 * <p>
 * A banned rarity is a zero weight rather than a post-generation filter, so the Armory always shows
 * its full complement of items - the old neutral-town Mythic strip left an empty slot behind.
 */
public final class ArmoryRarity {

    public static final String CAPITOL = "CAPITOL";
    public static final String PLAYER_TOWN = "PLAYER_TOWN";
    public static final String NEUTRAL_TOWN = "NEUTRAL_TOWN";

    private static final String[] TIERS = {"Common", "Uncommon", "Rare", "Mythic"};

    private ArmoryRarity() {}

    private static boolean enabled() {
        forge.adventure.data.ConfigData config = Config.instance().getConfigData();
        return config != null && config.armoryRarityGatingEnabled;
    }

    /**
     * Which venue's table governs the Armory in the town whose changes these are, or null for an
     * AI town (whose Armory-family shops use fixed item lists and never roll a rarity).
     * Capitol is tested first: it satisfies isTownRestored() too, so the order matters.
     */
    public static String classifyVenue(PointOfInterestChanges changes) {
        if (!enabled())
            return null;
        if (TownRestoration.isCurrentTownCapitol())
            return CAPITOL;
        if (TownRestoration.isTownRestored(changes))
            return PLAYER_TOWN;
        if (TownRestoration.isNeutralSeededTown(changes))
            return NEUTRAL_TOWN;
        return null;
    }

    /** The bracket governing this venue right now, or null to fall back to the flat odds. */
    private static ArmoryRarityData.WeekBracket bracketFor(String venue) {
        if (venue == null || !enabled())
            return null;
        ArmoryRarityData data = Config.instance().getArmoryRarityData();
        if (data == null || data.venueBrackets == null)
            return null;
        ArmoryRarityData.WeekBracket[] brackets = data.venueBrackets.get(venue);
        if (brackets == null)
            return null;
        WorldSave save = WorldSave.getCurrentSave();
        World world = save == null ? null : save.getWorld();
        if (world == null)
            return null;
        int week = SpawnTierWeighting.currentWeek(world);
        for (ArmoryRarityData.WeekBracket bracket : brackets) {
            if (bracket != null && bracket.covers(week))
                return bracket.total() > 0f ? bracket : null;
        }
        System.err.println("[TFR-ArmoryRarity] no bracket covers venue=" + venue + " week=" + week
                + " - falling back to the flat Common 60 / Uncommon 30 / Rare 8 / Mythic 2");
        return null;
    }

    /**
     * Rolls one slot's rarity for this venue, or returns null when no table applies - the caller
     * then uses its own historical flat odds. Consumes exactly one nextFloat() either way, so the
     * seeded weekly stock stays reproducible.
     */
    public static String roll(Random random, String venue) {
        ArmoryRarityData.WeekBracket bracket = bracketFor(venue);
        if (bracket == null)
            return null;
        float[] weights = {bracket.common, bracket.uncommon, bracket.rare, bracket.mythic};
        float roll = random.nextFloat() * bracket.total();
        float running = 0f;
        for (int i = 0; i < weights.length; i++) {
            running += weights[i];
            if (roll < running)
                return TIERS[i];
        }
        // Only reachable on floating-point drift at the very top of the range; fall back to the
        // highest tier that actually carries weight rather than blindly returning Mythic.
        for (int i = weights.length - 1; i >= 0; i--)
            if (weights[i] > 0f)
                return TIERS[i];
        return TIERS[0];
    }

    /** One-line description of the row in force, for the diagnostic log. */
    public static String describe(String venue) {
        ArmoryRarityData.WeekBracket b = bracketFor(venue);
        if (b == null)
            return "flat C60/U30/R8/M2";
        return "C" + (int) b.common + "/U" + (int) b.uncommon
                + "/R" + (int) b.rare + "/M" + (int) b.mythic;
    }
}
