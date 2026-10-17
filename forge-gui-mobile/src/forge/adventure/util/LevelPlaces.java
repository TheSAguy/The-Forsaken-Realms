package forge.adventure.util;

import com.badlogic.gdx.math.Vector2;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;

import java.util.ArrayList;
import java.util.List;

/**
 * Round 668 - places that come with the player's level. The user, of the three end-game legend dungeons brought over
 * from Realm of Legends (Hexhaven Tower, the Echoverse Overlay, the Theorist's Sanctum): "Since they are end-game, let's
 * not have them appear till the player reaches level 20+".
 * <ul>
 * <li>A place with PointOfInterestData.appearsAtLevel is never made by world generation (no land lists it).</li>
 * <li>Once the character's Ascendance level reaches it - or, for a character without Ascendance (an older save, or
 * leveling switched off in Settings), once the day reaches TuningData.levelPlacesFallbackDay for a level-20 place and
 * that share of it for a lower one (round 669: level 10, day 30) - it is put on the map in its appearsInBiome land, at
 * least KEEP_APART_TILES from the others.</li>
 * <li>Round 669 adds nine mid-game Realm of Legends lairs and stalls at levels 5, 10 and 15.</li>
 * <li>Round 682 (the user, of the "Word reaches you of places you have never seen" notice: "I don't think we need to
 * announce the new places. This and the other level place unlocks can be hidden"): a place arrives silently and under
 * the fog, like any place not yet found - no notice, no ground uncovered around it. It is found by exploring.</li>
 * <li>The check runs on the day tick and whenever the overworld is entered (after a duel, a level-up, a load), so a save
 * from before this round gets them too. A world that already has a place is left alone - each comes once.</li>
 * </ul>
 * [TFR-LevelPlaces] logs each placement. Console: "levelplaces info" / "levelplaces now".
 */
public final class LevelPlaces {
    private LevelPlaces() {
    }

    /** How far the places that appear together keep from each other. */
    private static final int KEEP_APART_TILES = 20;

    private static List<PointOfInterestData> levelPlaces;

    private static List<PointOfInterestData> levelPlaces() {
        if (levelPlaces == null) {
            levelPlaces = new ArrayList<>();
            for (PointOfInterestData d : PointOfInterestData.getAllPointOfInterest()) {
                if (d.appearsAtLevel > 0)
                    levelPlaces.add(d);
            }
        }
        return levelPlaces;
    }

    private static PointOfInterest onMap(World world, String name) {
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (poi.getData() != null && name.equals(poi.getData().name))
                return poi;
        }
        return null;
    }

    /** The level the fallback day stands for - a lower level comes that much sooner. */
    private static final int FALLBACK_LEVEL = 20;

    /** Without Ascendance: the day this place appears (0 = never). */
    private static int fallbackDay(PointOfInterestData d) {
        int day20 = Config.instance().getTuningData().levelPlacesFallbackDay;
        return day20 <= 0 ? 0 : Math.max(1, (day20 * d.appearsAtLevel + FALLBACK_LEVEL - 1) / FALLBACK_LEVEL);
    }

    /** Is this place's moment here for the current character? */
    private static boolean due(PointOfInterestData d, World world) {
        if (Ascendance.isActive())
            return Ascendance.level() >= d.appearsAtLevel;
        int day = fallbackDay(d);
        return day > 0 && world.getCurrentDay() >= day;
    }

    /** The day tick and WorldStage.enter(): put every place whose moment has come on the map. */
    public static void check(String why) {
        place(why, false);
    }

    /** Console "levelplaces info": each place, its level, and where it is (or why it is not there yet). */
    public static String info() {
        World world = WorldSave.getCurrentSave() == null ? null : WorldSave.getCurrentSave().getWorld();
        if (world == null)
            return "no world";
        StringBuilder sb = new StringBuilder(Ascendance.isActive() ? "Ascendance " + Ascendance.level()
                : "no Ascendance - day " + world.getCurrentDay());
        for (PointOfInterestData d : levelPlaces()) {
            PointOfInterest poi = onMap(world, d.name);
            sb.append("; ").append(d.name).append(" (level ").append(d.appearsAtLevel)
                    .append(Ascendance.isActive() ? "" : ", day " + fallbackDay(d)).append("): ");
            if (poi == null)
                sb.append(due(d, world) ? "due" : "not yet");
            else
                sb.append("tile ").append((int) (poi.getPosition().x / world.getTileSize())).append(",")
                        .append((int) (poi.getPosition().y / world.getTileSize()));
        }
        return sb.toString();
    }

    /** Places what is due (force: everything not yet on the map). Returns a summary for the console. */
    public static String place(String why, boolean force) {
        World world = WorldSave.getCurrentSave() == null ? null : WorldSave.getCurrentSave().getWorld();
        if (world == null || world.getData() == null || levelPlaces().isEmpty())
            return "nothing to place";
        List<PointOfInterestData> due = new ArrayList<>();
        List<Vector2> taken = new ArrayList<>();
        for (PointOfInterestData d : levelPlaces()) {
            PointOfInterest poi = onMap(world, d.name);
            if (poi != null)
                taken.add(poi.getPosition());
            else if (force || due(d, world))
                due.add(d);
        }
        if (due.isEmpty())
            return "nothing due";
        List<String> placedNames = new ArrayList<>(), ways = new ArrayList<>(); // for the console's answer
        for (PointOfInterestData d : due) {
            String land = d.appearsInBiome == null || d.appearsInBiome.isEmpty() ? "waste" : d.appearsInBiome;
            PointOfInterest placed = world.addPointOfInterestInBiome(d, land, 0.2f, 0.6f, taken, KEEP_APART_TILES);
            if (placed == null) // a crowded land: anywhere in it, closer together
                placed = world.addPointOfInterestInBiome(d, land, 0.05f, 0.9f, taken, KEEP_APART_TILES / 2);
            if (placed == null) {
                System.out.println("[TFR-LevelPlaces] " + d.name + ": no free spot in " + land + " - tried again later");
                continue;
            }
            taken.add(placed.getPosition());
            int tx = (int) (placed.getPosition().x / world.getTileSize());
            int ty = (int) (placed.getPosition().y / world.getTileSize());
            WorldStage stage = WorldStage.getInstance();
            // Round 682: no fog cleared around it - the place waits under the fog to be found.
            int chunk = world.getChunkSize();
            for (int cx = (tx - 4) / chunk; cx <= (tx + 4) / chunk; cx++) {
                for (int cy = (ty - 4) / chunk; cy <= (ty + 4) / chunk; cy++) {
                    stage.invalidateBackgroundChunkTexture(cx, cy); // clearTerrain changed the ground
                    stage.reloadBackgroundChunkObjects(cx, cy); // the new place's sprite, the cleared doodads
                }
            }
            String where = "";
            if (stage.getPlayerSprite() != null)
                where = WorldStage.compassDirection(placed.getPosition().x - stage.getPlayerSprite().getX(),
                        placed.getPosition().y - stage.getPlayerSprite().getY());
            placedNames.add(d.getDisplayName());
            ways.add(where);
            System.out.println("[TFR-LevelPlaces] " + d.name + " placed at tile " + tx + "," + ty + " in " + land
                    + (where.isEmpty() ? "" : " (" + where + " of the player)") + ", under the fog - "
                    + (force ? "forced" : Ascendance.isActive() ? "Ascendance " + Ascendance.level() + " of "
                    + d.appearsAtLevel : "no Ascendance, day " + world.getCurrentDay() + " of " + fallbackDay(d))
                    + " (" + why + ")");
        }
        if (placedNames.isEmpty())
            return "no spot found";
        // Round 682: no notice - the console's answer is the only place the list is told.
        return "placed " + listWithWays(placedNames, ways);
    }

    /** "A, B and C (all to the west)" when they share one direction; otherwise "A (west), B (north) and C (east)". */
    private static String listWithWays(List<String> names, List<String> ways) {
        boolean oneWay = !ways.get(0).isEmpty() && ways.stream().allMatch(ways.get(0)::equals);
        List<String> named = new ArrayList<>();
        for (int i = 0; i < names.size(); i++)
            named.add(names.get(i) + (oneWay || ways.get(i).isEmpty() ? "" : " (" + ways.get(i) + ")"));
        return join(named) + (oneWay ? (names.size() > 1 ? " (all to the " : " (to the ") + ways.get(0) + ")" : "");
    }

    private static String join(List<String> parts) {
        if (parts.size() == 1)
            return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }
}
