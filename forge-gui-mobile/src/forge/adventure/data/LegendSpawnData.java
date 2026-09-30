package forge.adventure.data;

/**
 * Backing class for the plane's "config tables/legends.json" (round 375) - the legend table's rules, hand-editable, no
 * code change needed to retune. See LegendSpawns.java for how it is consumed. Who IS a legend is not listed here: the
 * frontier legends are a predicate (FrontierSpawns.isCandidate, frontier_spawns.json's maxLife) and the roaming
 * champions a name list (roaming_champions.json). An absent file, or both chances at 0, switches sightings off.
 */
public class LegendSpawnData {
    /** Chance per spawn roll that a legend is sighted when the spot lies in land of a color that is UNHAPPY with the player. */
    public float unhappyChance;
    /** The same at WAR. */
    public float warChance;
    /** Whole days after a sighting before the next one can come. */
    public int cooldownDays;
    /** Legends alive on the overworld at once, at most. */
    public int maxAlive;
    /** A legend's weight is repeatWeight to the power of how many more times it has been sighted than the least-sighted
     *  legend of the same draw - 1 would ignore the sighting history altogether. */
    public float repeatWeight;
    /** How far from the player a legend is sighted, in tiles. */
    public float spawnMinTiles;
    public float spawnMaxTiles;
    /** A legend holds its ground until the player comes within chaseTiles, then gives chase until they are leashTiles away. */
    public float chaseTiles;
    public float leashTiles;
    /** Cards a sighted legend starts its duels with on the battlefield (the user: a Gemstone Mine). */
    public String[] startBattleWithCard;
}
