package forge.adventure.util;

import forge.adventure.data.EnemyData;
import forge.adventure.data.RoamingChampionData;

import java.util.HashSet;
import java.util.Set;

/**
 * Roaming champions (round 311, user: "Add all 'arena-only enemy' enemies as spawn able roaming champions on the
 * overworld"). The champions of the arena pools that no other route reaches - not placed on a map, not a cave
 * champion, not a frontier legend, not in the Chest's pools, not a war champion; config tables/roaming_champions.json
 * names them, dev-tools/arena_champion_audit.py derives the list.
 * <p>
 * Like the war champions they keep {@code spawnRate} 0 in the data - so the tier weighting and the card budget leave
 * them alone, and their whole reward list pays when one is beaten out here (the arena pays one Rare from their deck
 * since round 311). They arrive as a legend sighting: announced, a gold dot on the maps, the legend day clock. Round
 * 383: that list pays half its gold and 2/3 of its cards (PlaceRewards.applyLegendCut).
 * <p>
 * <b>Round 375:</b> only the membership is left here. Round 311 gave them a share of every colour land's ordinary roll
 * at any reputation - the way Elf Queen Guay met the user in friendly land at week 3. They belong to the legend table
 * now ({@link LegendSpawns}, config tables/legends.json) with the frontier legends: Unhappy or War land only, rare, the
 * least-sighted first. A legend is still matched on its catalog name.
 */
public class RoamingChampions {
    private RoamingChampions() {}

    private static RoamingChampionData cachedFor;
    private static Set<String> cachedNames = new HashSet<>();

    private static RoamingChampionData data() {
        return Config.instance().getRoamingChampionData();
    }

    /** Round 375: on while the list names anyone - there is no share to switch it off with any more. */
    public static boolean isEnabled() {
        return !names().isEmpty();
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
        return e != null && e.name != null && names().contains(e.name);
    }

    /** Round 311: an enemy that arrives as a legend sighting - a frontier legend or a roaming champion. Round 375: the
     *  legend table's membership (LegendSpawns.isMember). */
    public static boolean isLegend(EnemyData e) {
        return FrontierSpawns.isCandidate(e) || isChampion(e);
    }
}
