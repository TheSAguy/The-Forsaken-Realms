package forge.adventure.util;

import com.badlogic.gdx.math.Rectangle;
import forge.adventure.data.BiomeData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.data.TuningData;
import forge.adventure.data.WorldData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.stage.GameHUD;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Dungeons as sources of enemies (round 377). The user: <i>"I want to give the player more incentive to clear out
 * dungeons. Let's have the spawn rates around dungeons to be higher. Think of dungeons as a source of enemies and needs
 * to be removed. Let's have this only be for regular POI and not special POI... would be nice if the enemies that spawn
 * match the enemies in the dungeon. At first, maybe only matching the color of the dungeon and then, once visited and
 * the enemies inside are locked in, then those would spawn on the overworld."</i> - then, on the proposal: no messages
 * about it (the "Find a Dungeon" quest explains it), special monsters left out, and both the escalation and the payoff.
 * <ul>
 * <li><b>Which places:</b> the regular ones - DungeonRotation's rotatable set (hostile dungeons and caves; never a
 * story, quest or NoRotate map, a boss lair or a castle) - while active and not yet cleared.</li>
 * <li><b>The zone:</b> within dungeonSourceRadiusTiles of the place's footprint the spawn rolls come
 * dungeonSourceRateFactor x as fast, times the escalation: + dungeonSourceEscalationPerWeek for each full week the place
 * has stood (World.dungeonAppearedDay), up to dungeonSourceEscalationMax x.</li>
 * <li><b>What comes out:</b> dungeonSourceShare of those rolls send one of its creatures out of its door - before the
 * first visit an ordinary creature of its color (the land it stands on, else its Biome tag), after it one of its
 * LIVING inhabitants (round 201's fixed roster minus the defeated, per level). Never a special one: a boss, a quest
 * enemy, a legend or champion, the cave champion, a dialog NPC, a placement kept as authored
 * (PointOfInterestChanges.isRosterSpecial, marked by MapStage).</li>
 * <li><b>The payoff:</b> the moment its enemies are cleared (DungeonRotation.onDungeonCleared / onDungeonClear) it falls
 * quiet (World.dungeonClearedDay) and the nearest living town gains dungeonSourceClearReputation reputation.</li>
 * </ul>
 * Grep forge.log for [TFR-DungeonSource].
 */
public final class DungeonSources {
    private DungeonSources() {}

    private static TuningData tuning() {
        return Config.instance().getTuningData();
    }

    public static boolean isEnabled() {
        TuningData t = tuning();
        return t != null && (t.dungeonSourceShare > 0f || t.dungeonSourceRateFactor > 1f);
    }

    /** A regular place (DungeonRotation's rotatable set) - the only kind that can be a source. */
    public static boolean isRegular(PointOfInterest poi) {
        return poi != null && DungeonRotation.isRotatableData(poi.getData());
    }

    /** A regular place on the map whose enemies are not cleared yet. */
    public static boolean isSource(World world, PointOfInterest poi) {
        return isRegular(poi) && poi.getActive() && !world.getDungeonClearedDay().containsKey(poi.getID());
    }

    /** The source nearest the player within the zone radius, or null. */
    public static final class Source {
        public final PointOfInterest poi;
        public final float distanceTiles;
        public final int ageDays;
        public final float escalation;

        Source(PointOfInterest poi, float distanceTiles, int ageDays, float escalation) {
            this.poi = poi;
            this.distanceTiles = distanceTiles;
            this.ageDays = ageDays;
            this.escalation = escalation;
        }

        /** How much faster the rolls come here: the base factor times the escalation. */
        public float rateFactor() {
            TuningData t = tuning();
            float base = t == null ? 1f : Math.max(1f, t.dungeonSourceRateFactor);
            return base * escalation;
        }
    }

    /** The nearest source whose footprint lies within the zone radius of (px, py), world units. Scans the 3x3 chunks
     *  around the player - a chunk is a screen wide, far more than the radius. */
    public static Source nearest(World world, float px, float py) {
        TuningData t = tuning();
        if (!isEnabled() || world == null || t == null)
            return null;
        int ts = world.getTileSize();
        int chunk = world.getChunkSize();
        if (ts <= 0 || chunk <= 0)
            return null;
        float radius = Math.max(1f, t.dungeonSourceRadiusTiles);
        int cx = (int) px / ts / chunk;
        int cy = (int) py / ts / chunk;
        PointOfInterest best = null;
        float bestDist = Float.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (PointOfInterest poi : world.getPointsOfInterest(cx + dx, cy + dy)) {
                    if (!isSource(world, poi))
                        continue;
                    float d = edgeDistanceTiles(poi, px, py, ts);
                    if (d <= radius && d < bestDist) {
                        best = poi;
                        bestDist = d;
                    }
                }
            }
        }
        if (best == null)
            return null;
        int age = ageDays(world, best);
        return new Source(best, bestDist, age, escalation(age));
    }

    /** Distance from (px, py) to the nearest edge of the place's footprint, in tiles - 0 on or inside it. */
    static float edgeDistanceTiles(PointOfInterest poi, float px, float py, int ts) {
        Rectangle b = poi.getBoundingRectangle();
        float nx = Math.max(b.x, Math.min(px, b.x + b.width));
        float ny = Math.max(b.y, Math.min(py, b.y + b.height));
        float dx = (px - nx) / ts;
        float dy = (py - ny) / ts;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Whole days the place has stood. A save from before round 377 has no date: it counts from the day this first
     *  asks (logged once). */
    static int ageDays(World world, PointOfInterest poi) {
        int today = world.getCurrentDay();
        Integer since = world.getDungeonAppearedDay().get(poi.getID());
        if (since == null) {
            world.getDungeonAppearedDay().put(poi.getID(), today);
            System.out.println("[TFR-DungeonSource] " + poi.getDisplayName() + ": no appearance day on this save - its age"
                    + " counts from today (day " + today + ")");
            return 0;
        }
        return Math.max(0, today - since);
    }

    /** 1 + dungeonSourceEscalationPerWeek per full week, capped at dungeonSourceEscalationMax. */
    static float escalation(int ageDays) {
        TuningData t = tuning();
        if (t == null || t.dungeonSourceEscalationPerWeek <= 0f)
            return 1f;
        float max = Math.max(1f, t.dungeonSourceEscalationMax);
        return Math.min(max, 1f + t.dungeonSourceEscalationPerWeek * (ageDays / 7));
    }

    /** The creature a source sends out, with where it came from, for the log. */
    public static final class Pick {
        public final EnemyData enemy;
        public final String from;

        Pick(EnemyData enemy, String from) {
            this.enemy = enemy;
            this.from = from;
        }
    }

    /** After the first visit one of the living inhabitants; before it (or with only special ones left) an ordinary
     *  creature of the place's color. Null when neither yields one. */
    public static Pick pick(World world, PointOfInterest poi, float rank, Random rand) {
        List<EnemyData> living = livingInhabitants(poi);
        if (!living.isEmpty())
            return new Pick(living.get(rand.nextInt(living.size())), "its inhabitants (" + living.size() + " living)");
        String color = colorOf(world, poi);
        BiomeData biome = biomeNamed(world, color);
        if (biome == null)
            return null;
        for (int attempt = 0; attempt < 6; attempt++) {
            EnemyData e = biome.getEnemy(rank, false, null); // the ordinary roll - no war champions, tiers as usual
            if (e != null && !isSpecial(e))
                return new Pick(e, color + " (not visited" + (hasRoster(poi) ? ", only special ones left" : "") + ")");
        }
        return null;
    }

    private static boolean hasRoster(PointOfInterest poi) {
        for (PointOfInterestChanges changes : WorldSave.getCurrentSave().getPointOfInterestChangesTree(poi.getID()))
            if (changes.hasFixedRoster())
                return true;
        return false;
    }

    /** Every level's recorded roster minus the defeated, the special placements and special creatures. */
    static List<EnemyData> livingInhabitants(PointOfInterest poi) {
        List<EnemyData> out = new ArrayList<>();
        for (PointOfInterestChanges changes : WorldSave.getCurrentSave().getPointOfInterestChangesTree(poi.getID())) {
            for (Map.Entry<Integer, String> entry : changes.getFixedRoster().entrySet()) {
                if (changes.isObjectDeleted(entry.getKey()) || changes.isRosterSpecial(entry.getKey()))
                    continue;
                EnemyData e = WorldData.getEnemy(entry.getValue());
                if (e == null || isSpecial(e) || CaveChampions.isRecordedChampion(poi, e.getName()))
                    continue;
                out.add(e);
            }
        }
        return out;
    }

    /** The story markers MapStage's scripted-placement rule uses. An enemy's questTags are mostly DESCRIPTIVE ("Devil",
     *  "Humanoid", "IdentityRed", "BiomeRed") - only these four make one special. */
    private static final java.util.Set<String> STORY_TAGS = new java.util.HashSet<>(
            java.util.Arrays.asList("Boss", "Story", "Legendary", "Challenger"));

    /** A creature that never walks out: a boss or an arena/event fighter (spawnRate 0 - SpawnTierWeighting.isExempt,
     *  MapStage's scripted-placement rule), a story-tagged one, a legend or roaming champion, a war champion. */
    public static boolean isSpecial(EnemyData e) {
        if (e == null || SpawnTierWeighting.isExempt(e) || LegendSpawns.isMember(e))
            return true;
        if (e.questTags != null)
            for (String tag : e.questTags)
                if (tag != null && STORY_TAGS.contains(tag))
                    return true;
        for (String color : ColorReputation.COLORS)
            if (WarChampions.championNames(color).contains(e.getName()) || WarChampions.championNames(color).contains(e.name))
                return true;
        return false;
    }

    /** The color a place's creatures take before it is visited: the land it stands on (the re-theme's rule), else its
     *  Biome tag, else the wasteland's. */
    static String colorOf(World world, PointOfInterest poi) {
        List<BiomeData> biomes = world.getData().GetBiomes();
        Rectangle b = poi.getBoundingRectangle();
        int ts = world.getTileSize();
        int tx = (int) ((b.x + b.width / 2f) / ts);
        int ty = (int) ((b.y + b.height / 2f) / ts);
        if (tx >= 0 && ty >= 0 && tx < world.getWidthInTiles() && ty < world.getHeightInTiles()) {
            int index = World.highestBiome(world.getBiome(tx, ty));
            if (index >= 0 && index < biomes.size()) {
                String land = biomes.get(index).name;
                if ("waste".equals(land) || LegendSpawns.colorLetterOf(land) != null)
                    return land;
            }
        }
        PointOfInterestData data = poi.getData();
        if (data != null && data.questTags != null) {
            for (String tag : data.questTags) {
                if (tag == null)
                    continue;
                switch (tag) {
                    case "BiomeWhite": return "white";
                    case "BiomeBlue": return "blue";
                    case "BiomeBlack": return "black";
                    case "BiomeRed": return "red";
                    case "BiomeGreen": return "green";
                    case "BiomeColorless": return "waste";
                    default: break;
                }
            }
        }
        return "waste";
    }

    private static BiomeData biomeNamed(World world, String name) {
        for (BiomeData b : world.getData().GetBiomes())
            if (name.equals(b.name))
                return b;
        return null;
    }

    /** DungeonRotation: a place appeared (a new world, from the reserve, a quest's force-spawn) - its age starts. */
    public static void onAppeared(World world, PointOfInterest poi, int day) {
        if (world == null || !isRegular(poi))
            return;
        world.getDungeonAppearedDay().put(poi.getID(), day);
        world.getDungeonClearedDay().remove(poi.getID());
    }

    /** DungeonRotation.hidePoi: the incarnation is over - its age and its cleared mark go with it. */
    public static void onHidden(World world, PointOfInterest poi) {
        if (world == null || poi == null)
            return;
        world.getDungeonAppearedDay().remove(poi.getID());
        world.getDungeonClearedDay().remove(poi.getID());
    }

    /**
     * DungeonRotation.onDungeonCleared / onDungeonClear: its enemies are down. Once per incarnation it falls quiet and the
     * nearest living town gains dungeonSourceClearReputation - one line on the HUD, like an Inn tournament's +1.
     */
    public static void onCleared(PointOfInterest poi) {
        if (!isRegular(poi) || WorldSave.getCurrentSave() == null)
            return;
        World world = WorldSave.getCurrentSave().getWorld();
        String id = poi.getID();
        if (world.getDungeonClearedDay().containsKey(id))
            return;
        int today = world.getCurrentDay();
        world.getDungeonClearedDay().put(id, today);
        Integer since = world.getDungeonAppearedDay().get(id);
        int age = since == null ? 0 : Math.max(0, today - since);
        TuningData t = tuning();
        int rep = t == null ? 0 : t.dungeonSourceClearReputation;
        PointOfInterest town = rep == 0 ? null : nearestLivingTown(world, poi);
        String paid = "";
        if (town != null) {
            PointOfInterestChanges changes = WorldSave.getCurrentSave().getPointOfInterestChanges(town.getID());
            changes.addMapReputation(rep);
            paid = " - " + town.getDisplayName() + " reputation " + (rep > 0 ? "+" : "") + rep + " (now "
                    + changes.getMapReputation() + ")";
            GameHUD.getInstance().addNotification("[BLACK]" + poi.getDisplayName() + " cleared - " + town.getDisplayName()
                    + " is grateful. Local reputation " + (rep > 0 ? "+" : "") + rep + " (now " + changes.getMapReputation()
                    + ").", true);
        }
        System.out.println("[TFR-DungeonSource] " + poi.getDisplayName() + " cleared on day " + today + " after " + age
                + " day(s) (x" + escalation(age) + ") - it falls quiet" + paid);
    }

    /** The nearest town or capital with people in it - not a ruin still waiting for its restoration, not the start camp. */
    static PointOfInterest nearestLivingTown(World world, PointOfInterest from) {
        Rectangle fb = from.getBoundingRectangle();
        float fx = fb.x + fb.width / 2f;
        float fy = fb.y + fb.height / 2f;
        PointOfInterest best = null;
        float bestSq = Float.MAX_VALUE;
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (!isLivingTown(poi))
                continue;
            Rectangle b = poi.getBoundingRectangle();
            float dx = b.x + b.width / 2f - fx;
            float dy = b.y + b.height / 2f - fy;
            float sq = dx * dx + dy * dy;
            if (sq < bestSq) {
                best = poi;
                bestSq = sq;
            }
        }
        return best;
    }

    private static boolean isLivingTown(PointOfInterest poi) {
        PointOfInterestData data = poi == null ? null : poi.getData();
        if (data == null || data.type == null || !(data.type.equals("town") || data.type.equals("capital")))
            return false;
        if (data.questTags != null)
            for (String tag : data.questTags)
                if ("Spawn".equals(tag))
                    return false;
        if (TownRestoration.isWastelandTown(data)) {
            PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
            return TownRestoration.isTownRestored(changes) || TownRestoration.isNeutralSeededTown(changes);
        }
        return true;
    }
}
