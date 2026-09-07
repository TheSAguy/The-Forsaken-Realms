package forge.adventure.data;

/**
 * Backing class for the plane's "config tables/war_champions.json" (user request 2026-09-07:
 * "I still feel like you could make some let's say 20% rare overworld spawns. You choose who.
 * Maybe have them only spawn when you are at War with the AI. So get 5 or so of each color for
 * this purpose."). Same dedicated-table-file pattern as SpawnTierWeightData for
 * "config tables/spawn_tier_weighting.json" - hand-editable, no code change needed to re-cast it.
 * See WarChampions.java for how it is consumed.
 * <p>
 * The five colour fields name enemies that ordinarily never roam ({@code spawnRate} 0 - the
 * arena-exclusive champions) and become eligible roaming encounters in that colour's own biome
 * ONLY while the player is at war with it. {@code share} is the fraction of that biome's spawn
 * rolls they take between them while the war lasts - 0.2 means one encounter in five is a
 * champion. Absent file, absent colour list or share <= 0 all leave the feature switched off.
 * <p>
 * Deliberately five named fields rather than a map: libGDX's Json reads a String[] field
 * directly, and the colour set is fixed by ColorReputation.COLORS anyway.
 */
public class WarChampionData {
    public float share;
    public String[] white;
    public String[] blue;
    public String[] black;
    public String[] red;
    public String[] green;

    /** The champion roster for one ColorReputation colour name, or null if that colour has none. */
    public String[] forColor(String color) {
        if (color == null)
            return null;
        switch (color) {
            case "white": return white;
            case "blue":  return blue;
            case "black": return black;
            case "red":   return red;
            case "green": return green;
            default:      return null;
        }
    }
}
