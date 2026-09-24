package forge.adventure.data;

/**
 * Backing class for the plane's "config tables/roaming_champions.json" (round 311) - hand-editable, no code change
 * needed to re-cast or retune. See RoamingChampions.java for how it is consumed.
 * <p>
 * {@code names} are the arena-only champions: enemies.json entries authored with {@code spawnRate} 0 that no other
 * route reaches (dev-tools/arena_champion_audit.py lists them). {@code share} is the fraction of a colour land's
 * ordinary spawn rolls they take between them. An absent file, an empty list or a share of 0 switches it off.
 */
public class RoamingChampionData {
    public float share;
    public String[] names;
}
