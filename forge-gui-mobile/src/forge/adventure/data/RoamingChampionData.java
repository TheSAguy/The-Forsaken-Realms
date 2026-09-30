package forge.adventure.data;

/**
 * Backing class for the plane's "config tables/roaming_champions.json" (round 311) - hand-editable, no code change
 * needed to re-cast or retune. See RoamingChampions.java for how it is consumed.
 * <p>
 * {@code names} are the arena-only champions: enemies.json entries authored with {@code spawnRate} 0 that no other
 * route reaches (dev-tools/arena_champion_audit.py lists them). An absent file or an empty list leaves them out of the
 * legend table.
 */
public class RoamingChampionData {
    // Round 375: "share" is gone - the champions are sighted through the legend table (LegendSpawnData, legends.json).
    public String[] names;
}
