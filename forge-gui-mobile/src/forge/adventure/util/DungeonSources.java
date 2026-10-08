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
 * <li><b>The zone:</b> round 383 - full strength within dungeonSourceRadiusTiles of the place's footprint, fading to
 * nothing at dungeonSourceReachTiles, times the escalation: + dungeonSourceEscalationPerWeek for each full week the place
 * has stood (World.dungeonAppearedDay), up to dungeonSourceEscalationMax x. The sources in reach pull together, their
 * presence saturating at one (totalWeight), up to dungeonSourceMaxPerRoll.</li>
 * <li><b>The budget:</b> round 383 - the dungeons carry most of the spawns and the land the rest; round 384 - the land's
 * share by land and standing (WorldStage.landShareOn: player 15%, waste 25%, a color's land 15-40% from Partner to War),
 * the dungeons 1 - that: each roll sends share x pull creatures from the sources in reach, the fraction a coin flip, so
 * an area whose dungeons are cleared keeps only the land's trickle. Quest creatures roll on their own.</li>
 * <li><b>What comes out:</b> one of a source's creatures, on its way out towards the player - before the
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
        return t != null && t.dungeonSourceShare > 0f;
    }

    /** A regular place (DungeonRotation's rotatable set) - the only kind that can be a source. */
    public static boolean isRegular(PointOfInterest poi) {
        return poi != null && DungeonRotation.isRotatableData(poi.getData());
    }

    /** A regular place on the map whose enemies are not cleared yet. */
    public static boolean isSource(World world, PointOfInterest poi) {
        return isRegular(poi) && poi.getActive() && !world.getDungeonClearedDay().containsKey(poi.getID());
    }

    /** One source within reach of the player, and how hard it pulls there. */
    public static final class Source {
        public final PointOfInterest poi;
        public final float distanceTiles;
        public final int ageDays;
        public final float escalation;
        /** Round 383: 1 within dungeonSourceRadiusTiles of the footprint, fading to 0 at dungeonSourceReachTiles. */
        public final float falloff;

        Source(PointOfInterest poi, float distanceTiles, int ageDays, float escalation, float falloff) {
            this.poi = poi;
            this.distanceTiles = distanceTiles;
            this.ageDays = ageDays;
            this.escalation = escalation;
            this.falloff = falloff;
        }

        /** Its pull on this spot: the per-source factor, times the escalation, times the falloff with distance. */
        public float weight() {
            TuningData t = tuning();
            float base = t == null ? 1f : Math.max(0f, t.dungeonSourceRateFactor);
            return base * escalation * falloff;
        }
    }

    /**
     * Round 383 (the user: "I want the dungeons on the map to contribute the most to overworld spawns. Let's say 85% of
     * spawns should come from dungeons and the remaining 15% from the land. So if you clear out all the dungeons in an
     * area, it will feel safe"). Every source whose footprint lies within dungeonSourceReachTiles of (px, py), world
     * units, strongest pull first - empty when none. Scans the chunks the reach can touch (a chunk is a screen wide,
     * 30 tiles).
     */
    public static List<Source> inReach(World world, float px, float py) {
        return inReach(world, px, py, true);
    }

    /** {@code record} false: a read-only look (the coverage log) - a source with no appearance day counts as new and
     *  keeps no date, so a diagnostic never starts every source's clock at once on an old save. */
    public static List<Source> inReach(World world, float px, float py, boolean record) {
        List<Source> out = new ArrayList<>();
        TuningData t = tuning();
        if (!isEnabled() || world == null || t == null)
            return out;
        int ts = world.getTileSize();
        int chunk = world.getChunkSize();
        if (ts <= 0 || chunk <= 0)
            return out;
        float full = Math.max(0f, t.dungeonSourceRadiusTiles);
        float reach = Math.max(full, t.dungeonSourceReachTiles);
        if (reach <= 0f)
            return out;
        int span = (int) Math.ceil(reach / chunk);
        int cx = (int) px / ts / chunk;
        int cy = (int) py / ts / chunk;
        for (int dx = -span; dx <= span; dx++) {
            for (int dy = -span; dy <= span; dy++) {
                for (PointOfInterest poi : world.getPointsOfInterest(cx + dx, cy + dy)) {
                    if (!isSource(world, poi))
                        continue;
                    float d = edgeDistanceTiles(poi, px, py, ts);
                    float falloff = falloff(d, full, reach);
                    if (falloff <= 0f)
                        continue;
                    int age = record ? ageDays(world, poi) : peekAgeDays(world, poi);
                    out.add(new Source(poi, d, age, escalation(age), falloff));
                }
            }
        }
        out.sort((a, b) -> Float.compare(b.weight(), a.weight()));
        return out;
    }

    /** The nearest source in reach of (px, py), or null - the strongest pull of inReach() is not always the nearest. */
    public static Source nearest(World world, float px, float py) {
        Source best = null;
        for (Source s : inReach(world, px, py))
            if (best == null || s.distanceTiles < best.distanceTiles)
                best = s;
        return best;
    }

    /** 1 up to {@code full} tiles from the footprint, then a straight fade to 0 at {@code reach}. */
    static float falloff(float distanceTiles, float full, float reach) {
        if (distanceTiles <= full)
            return 1f;
        if (distanceTiles >= reach || reach <= full)
            return 0f;
        return 1f - (distanceTiles - full) / (reach - full);
    }

    /**
     * Round 383: the whole pull on a spot. Presence saturates - the sources' falloffs summed, at most 1, so one young
     * dungeon close by is the full 0.85 and a crowd of them is not more (the agent world had 10-11 sources within 40
     * tiles of every spot, and summing them nearly doubled the old spawn rate) - times their escalation, averaged by
     * pull, so old dungeons still pour out more. Capped at dungeonSourceMaxPerRoll.
     */
    public static float totalWeight(List<Source> sources) {
        float presence = 0f, weight = 0f;
        for (Source s : sources) {
            presence += s.falloff;
            weight += s.weight();
        }
        if (presence <= 0f)
            return 0f;
        float perPresence = weight / presence; // the rate factor times the escalation, averaged by falloff
        TuningData t = tuning();
        float cap = t == null || t.dungeonSourceMaxPerRoll <= 0f ? Float.MAX_VALUE : t.dungeonSourceMaxPerRoll;
        return Math.min(Math.min(1f, presence) * perPresence, cap);
    }

    /** Round 383: one source drawn by its share of the pull - the dungeon a creature comes from. */
    public static Source draw(List<Source> sources, Random rand) {
        float sum = 0f;
        for (Source s : sources)
            sum += s.weight();
        float r = rand.nextFloat() * sum;
        for (Source s : sources) {
            r -= s.weight();
            if (r <= 0f)
                return s;
        }
        return sources.isEmpty() ? null : sources.get(sources.size() - 1);
    }

    /** Distance from (px, py) to the nearest edge of the place's footprint, in tiles - 0 on or inside it. */
    public static float edgeDistanceTiles(PointOfInterest poi, float px, float py, int ts) {
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

    /** Round 383: ageDays() without writing - an undated source is new. */
    static int peekAgeDays(World world, PointOfInterest poi) {
        Integer since = world.getDungeonAppearedDay().get(poi.getID());
        return since == null ? 0 : Math.max(0, world.getCurrentDay() - since);
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

    /** After the first visit one of the living inhabitants, by the week's tier odds (round 383b); before it - or with
     *  only special ones left, or none of the living due this week - an ordinary creature of the place's color. Null
     *  when neither yields one. */
    public static Pick pick(World world, PointOfInterest poi, float rank, Random rand) {
        List<EnemyData> living = livingInhabitants(poi);
        String color = colorOf(world, poi);
        if (!living.isEmpty()) {
            EnemyData out = byWeekTier(world, living, color, rand);
            if (out != null)
                return new Pick(out, "its inhabitants (" + living.size() + " living, week "
                        + SpawnTierWeighting.currentWeek(world) + "'s tier odds)");
        }
        BiomeData biome = biomeNamed(world, color);
        if (biome == null)
            return null;
        for (int attempt = 0; attempt < 6; attempt++) {
            EnemyData e = biome.getEnemy(rank, false, null); // the ordinary roll - no war champions, tiers as usual
            if (e != null && !isSpecial(e))
                return new Pick(e, color + (!living.isEmpty() ? " (its " + living.size() + " living not due this week)"
                        : " (not visited" + (hasRoster(poi) ? ", only special ones left" : "") + ")"));
        }
        return null;
    }

    /**
     * Round 383b (the user, on the dungeon spawns: "there is a chance that higher level monsters might spawn from
     * dungeons" early on). Before a visit a source's creatures come through the ordinary roll, with the week's tier odds
     * (SpawnTierWeighting); its inhabitants came out evenly, a Master on day 3 as likely as a rat. Now each tier present
     * takes the week's target for it in this land, shared among its living members - a tier at 0 this week stays
     * inside. Null when every tier present is at 0 (the caller falls back to the ordinary roll) or the weighting is off
     * (then an even draw, as before).
     */
    static EnemyData byWeekTier(World world, List<EnemyData> living, String color, Random rand) {
        if (!SpawnTierWeighting.isEnabled())
            return living.get(rand.nextInt(living.size()));
        int week = SpawnTierWeighting.currentWeek(world);
        Map<String, Integer> perTier = new java.util.HashMap<>();
        for (EnemyData e : living)
            perTier.merge(e.tier, 1, Integer::sum);
        float[] weights = new float[living.size()];
        float total = 0f;
        for (int i = 0; i < living.size(); i++) {
            EnemyData e = living.get(i);
            weights[i] = SpawnTierWeighting.targetTierWeight(e.tier, week, color) / perTier.get(e.tier);
            total += weights[i];
        }
        if (total <= 0f)
            return null;
        float r = rand.nextFloat() * total;
        for (int i = 0; i < living.size(); i++) {
            r -= weights[i];
            if (r <= 0f)
                return living.get(i);
        }
        return living.get(living.size() - 1);
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
        // Round 443 (the user's log: Scoured Gallery, Grey Warren and the Forgotten Hunting Lodge each paid twice): only a
        // place still on the map pays. A clear on the last kill despawned the place, the despawn (onHidden) wiped the
        // mark read above, and the walk-out paid again. MapStage's exit rules now skip a place already off the map.
        if (!poi.getActive()) {
            System.out.println("[TFR-DungeonSource] " + poi.getDisplayName() + " is already off the map - no second payment");
            return;
        }
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
        Ascendance.onPlaceCleared(poi); // round 493: once per incarnation, like the reputation above
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
