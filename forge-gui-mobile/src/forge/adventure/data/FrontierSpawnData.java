package forge.adventure.data;

/**
 * Backing class for the plane's "config tables/frontier_spawns.json" (user spec 2026-09-07: the
 * enemies that were reachable nowhere "just have them be spawnable in Unhappy and War state
 * terrain. So the multi colour would spawn in multiple colour zones. Colourless - make those
 * spawnable in Neutral terrain."). Same dedicated-table-file pattern as WarChampionData and
 * SpawnTierWeightData - hand-editable, no code change needed to retune. See FrontierSpawns.java.
 * <p>
 * Unlike war_champions.json this table names no enemies: the set is defined by a predicate
 * (see {@link forge.adventure.util.FrontierSpawns#isCandidate}) so it tracks the catalog
 * automatically instead of going stale the first time an enemy is added or re-tiered.
 */
public class FrontierSpawnData {
    /** Share of a biome's spawn rolls these take while its colour is UNHAPPY with the player. */
    public float unhappyShare;
    /** Share while its colour is at WAR. Higher than unhappyShare by intent - war is the danger. */
    public float warShare;
    /** Share the colourless members take in terrain that is at NEUTRAL (including the wasteland,
     *  which has no owner to have an opinion). Small: only a handful of enemies qualify. */
    public float neutralColorlessShare;
    /** Life ceiling for a candidate. Keeps the hand-placed Eldrazi titans (70 life) out of the
     *  roaming pool while admitting everything that is genuinely unreachable (max 50). */
    public int maxLife;
}
