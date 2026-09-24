package forge.adventure.util;

import forge.adventure.data.EnemyData;
import forge.adventure.data.RoamingChampionData;
import forge.adventure.data.WorldData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Roaming champions (round 311, user: "Add all 'arena-only enemy' enemies as spawn able roaming champions on the
 * overworld"). The champions of the arena pools that no other route reaches - not placed on a map, not a cave
 * champion, not a frontier legend, not in the Chest's pools, not a war champion; config tables/roaming_champions.json
 * names them, dev-tools/arena_champion_audit.py derives the list - roam their own colours' land: a WUG champion in
 * white, blue and green, a colourless one in the wasteland. Between them they take {@link #share()} of a land's
 * ordinary spawn rolls, in every reputation state (a champion is not a hostile border's), and never in the player's
 * own land.
 * <p>
 * Like the war champions they keep {@code spawnRate} 0 in the data - so the tier weighting and the card budget leave
 * them alone, and their whole reward list pays when one is beaten out here (the arena pays one Rare from their deck
 * since this round). Like the frontier legends they respect the player's rank (their difficulty is 1 or 2), so a new
 * character does not meet an 80-life Karona on day one. They arrive as a legend sighting: announced, a gold dot on
 * the maps, the long legend lifetime ({@link #isLegend}). Within the group each champion's weight halves for every
 * time the player has beaten it out here, so the ones not yet met come round first.
 */
public class RoamingChampions {
    private RoamingChampions() {}

    private static final String PLAYER_BIOME = "player";
    private static final String WASTE_BIOME = "waste";

    private static RoamingChampionData cachedFor;
    private static Set<String> cachedNames = new HashSet<>();

    private static RoamingChampionData data() {
        return Config.instance().getRoamingChampionData();
    }

    /** The configured fraction of a colour land's spawn rolls the champions take, or 0 (off). */
    public static float share() {
        RoamingChampionData d = data();
        if (d == null || d.share <= 0f || d.names == null || d.names.length == 0)
            return 0f;
        // A share of 1 would leave nothing for the rest of the land and make shareWeight() divide by zero.
        return Math.min(d.share, 0.9f);
    }

    public static boolean isEnabled() {
        return share() > 0f;
    }

    private static Set<String> names() {
        RoamingChampionData d = data();
        if (d != cachedFor) {
            Set<String> fresh = new HashSet<>();
            if (d != null && d.names != null)
                for (String n : d.names)
                    if (n != null)
                        fresh.add(n);
            cachedNames = fresh;
            cachedFor = d;
        }
        return cachedNames;
    }

    /** Is this enemy one of the roaming champions? Matched on the catalog name - getName() is the display name, and
     *  "Karona (Boss)" shows as "Karona, False God". */
    public static boolean isChampion(EnemyData e) {
        return e != null && isEnabled() && names().contains(e.name);
    }

    /** Round 311: an enemy that arrives as a legend sighting - a frontier legend or a roaming champion. */
    public static boolean isLegend(EnemyData e) {
        return FrontierSpawns.isCandidate(e) || isChampion(e);
    }

    /**
     * Appends this land's eligible champions to a candidate list and returns what was added. Colour matching is per
     * letter, as for the frontier legends; the wasteland takes the colourless ones. {@code taken} are the names the
     * war champions and frontier legends already appended - BiomeData's index arithmetic needs the groups disjoint.
     */
    public static List<EnemyData> injectFor(String biomeName, List<EnemyData> candidates, Set<String> taken,
                                            float difficultyFactor) {
        List<EnemyData> added = new ArrayList<>();
        if (!isEnabled() || candidates == null || biomeName == null || PLAYER_BIOME.equals(biomeName))
            return added;
        String letter = colorLetterOf(biomeName);
        boolean waste = WASTE_BIOME.equals(biomeName);
        if (letter == null && !waste)
            return added;
        for (String name : names()) {
            if (taken != null && taken.contains(name))
                continue;
            EnemyData e = WorldData.getEnemy(name);
            if (e == null || e.difficulty > difficultyFactor || !ContentFilterTables.isEnemyIncluded(name))
                continue;
            String colors = e.colors == null ? "" : e.colors.toUpperCase();
            boolean colorless = colors.isEmpty() || colors.equals("C");
            if (!(waste ? colorless : colors.contains(letter)))
                continue;
            // The candidate list carries a weightless copy of every catalog enemy (BiomeData.getEnemyList()'s
            // quest boost) - drop it and append the champion, so it is counted once and sits at the tail.
            candidates.removeIf(d -> d != null && name.equals(d.name));
            candidates.add(e);
            added.add(e);
        }
        return added;
    }

    /** Same solve as the war champions: w / (w + rest) = share, so the share tracks the live pool. */
    public static float shareWeight(float otherWeightTotal) {
        float share = share();
        if (share <= 0f || otherWeightTotal <= 0f)
            return 0f;
        return otherWeightTotal * share / (1f - share);
    }

    /** Each champion's part of the group's weight: halved per time the player has beaten it, summing to 1. */
    public static float[] partsOf(List<EnemyData> champions) {
        float[] parts = new float[champions.size()];
        float sum = 0f;
        for (int i = 0; i < parts.length; i++) {
            parts[i] = (float) Math.pow(0.5, SpawnTierWeighting.getPermanentKillCount(champions.get(i).getName()));
            sum += parts[i];
        }
        for (int i = 0; i < parts.length; i++)
            parts[i] = sum > 0f ? parts[i] / sum : 1f / parts.length;
        return parts;
    }

    private static String colorLetterOf(String biome) {
        switch (biome) {
            case "white": return "W";
            case "blue":  return "U";
            case "black": return "B";
            case "red":   return "R";
            case "green": return "G";
            default:      return null;
        }
    }
}
