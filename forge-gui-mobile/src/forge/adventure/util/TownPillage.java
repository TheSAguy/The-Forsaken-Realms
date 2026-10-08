package forge.adventure.util;

import com.badlogic.gdx.math.Vector2;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.data.TuningData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Round 484 - a town being pillaged. The user: "I want to add a new 'Random Event' to the game. If one of your
 * towns/capitol or a neutral town, not a ruined town, that you have visited before. If there are 4+ dungeons in a radius
 * of 15 around the location, then there should be a 10% chance for a 'Town being pillaged.' event. have enemies spawn
 * around the location, 3 to 4 at a time. The play has 1 week to kill 5 to have the event stop. If he does he gets +1
 * reputation and 50 stone 50 wood from the location, if he does not, he loses 2 reputation with that town. Let's so no
 * more than 2 of these events per week can spawn." Then, on the recommendations given back: "Go ahead and build the
 * pillage event with those defaults."
 * <ul>
 * <li><b>The roll</b>: once a calendar week (World.weekOf) every town that qualifies rolls pillageChancePercent, and at
 * most pillageMaxPerWeek start. A town qualifies when it is the player's (the Capitol, Orazca, a restored or captured
 * town) or a working neutral town the player has entered; not ruined, not already pillaged, not the target of an AI
 * attack mage; and with pillageDungeonsNeeded hostile dungeons or caves on the map within pillageRadiusTiles.</li>
 * <li><b>The raiders</b> come out of those dungeons (DungeonSources.pick: the dungeon's inhabitants, or its land's
 * creatures) at the player's rank. Once the player is within pillageSpawnRangeTiles of the town, 3-4 of them stand
 * around it (pillageRaidersAtOnce, fixed per pillage), topped up as they fall, never more than are still to beat. They
 * are marked (EnemySprite.pillageTown, saved), chase like any roamer, never time out, and leave when the pillage
 * ends.</li>
 * <li><b>The end</b>: pillageKills beaten within pillageDays pays pillageRewardReputation with the town and the wood and
 * stone; the days running out costs pillageFailReputation. A reminder with 2 days left. A town that falls to an AI or
 * turns to ruin ends its pillage with neither.</li>
 * <li>Shown as rows in the quest log (QuestLogScene, like the legends) and a "Pillaged!" label on the world map's
 * Details (MapViewScene).</li>
 * </ul>
 * State: World.getPillages() (town id -> {start day, beaten, at once, reminded}) and World.getPillageWeek(), both saved.
 * [TFR-Pillage] logs every weekly roll, start, raider, win and end.
 */
public final class TownPillage {
    private TownPillage() {
    }

    private static final int P_START = 0, P_BEATEN = 1, P_AT_ONCE = 2, P_REMINDED = 3;
    private static final int REMIND_DAYS = 2;
    private static final Random RAND = new Random();
    private static int lastProcessedDay = Integer.MIN_VALUE;
    private static int frames = 0;

    public static boolean isEnabled() {
        ConfigData config = Config.instance().getConfigData();
        return config != null && config.pillageEnabled;
    }

    /** WorldStage.clearCache(): a load or a new world. */
    public static void resetSessionState() {
        lastProcessedDay = Integer.MIN_VALUE;
        frames = 0;
        poiCache.clear(); // round 490: a load or a new world brings new POI objects
    }

    private static TuningData tuning() {
        return Config.instance().getTuningData();
    }

    /** Called from WorldStage.onActing() while the clock runs, beside TreasureHunt.tick(). */
    public static void tick(World world, int currentDay) {
        if (!isEnabled() || world == null || WorldStage.getInstance().getPlayerSprite() == null)
            return;
        int week = World.weekOf(currentDay);
        if (world.getPillageWeek() != week) {
            world.setPillageWeek(week);
            rollWeek(world, currentDay);
        }
        if (currentDay != lastProcessedDay) {
            lastProcessedDay = currentDay;
            checkDays(world, currentDay);
        }
        if (++frames % 30 == 0) // twice a second is plenty for "is the player near a raided town"
            keepRaiders(world);
    }

    // ------------------------------------------------------------------------------------------------ the weekly roll

    private static void rollWeek(World world, int day) {
        TuningData t = tuning();
        List<PointOfInterest> dungeons = hostileDungeons(world);
        List<PointOfInterest> hits = new ArrayList<>();
        int qualifying = 0;
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (notEligibleReason(world, poi, dungeons) != null)
                continue;
            qualifying++;
            if (RAND.nextInt(100) < t.pillageChancePercent)
                hits.add(poi);
        }
        Collections.shuffle(hits, RAND);
        int started = 0;
        for (PointOfInterest poi : hits) {
            if (started >= t.pillageMaxPerWeek)
                break;
            start(world, poi, day);
            started++;
        }
        System.out.println("[TFR-Pillage] week " + World.weekOf(day) + " (day " + day + "): " + qualifying
                + " town(s) qualify, " + hits.size() + " rolled under " + t.pillageChancePercent + "%, " + started
                + " pillage(s) start (at most " + t.pillageMaxPerWeek + ")");
    }

    /** Why this POI cannot be pillaged now, or null when it can. */
    static String notEligibleReason(World world, PointOfInterest poi, List<PointOfInterest> dungeons) {
        PointOfInterestData data = poi.getData();
        if (data == null || !("town".equals(data.type) || "capital".equals(data.type)))
            return "not a town";
        if (!poi.getActive())
            return "not on the map";
        if (world.getPillages().containsKey(poi.getID()))
            return "already pillaged";
        String standing = standingReason(poi);
        if (standing != null)
            return standing;
        if (underAttack(poi))
            return "an AI mage is on its way to it";
        int near = dungeonsNear(world, poi, dungeons).size();
        if (near < tuning().pillageDungeonsNeeded)
            return near + " dungeon(s) near";
        return null;
    }

    /** The town's own standing: the player's, or a working neutral town the player has entered. Null when it may be
     *  pillaged - also the test that ends a running pillage quietly once the town falls or turns to ruin. */
    private static String standingReason(PointOfInterest poi) {
        PointOfInterestData data = poi.getData();
        if (data == null)
            return "no data";
        if (TownRestoration.CAPITOL_POI_NAME.equals(data.name) || TownRestoration.isOrazca(data))
            return null;
        PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
        if (TownRestoration.isTownRestored(changes))
            return null;
        if (!TownRestoration.isWastelandTown(data))
            return "an AI color's town";
        boolean working = TownRestoration.isNeutralSeededTown(changes)
                || (data.name != null && data.name.startsWith("Waste Town Center"));
        if (!working)
            return "ruined";
        if (changes == null || !changes.isVisited())
            return "never entered";
        return null;
    }

    private static boolean underAttack(PointOfInterest poi) {
        for (EnemySprite mage : WorldStage.getInstance().getTerritoryMages())
            if (mage.territoryTarget != null && poi.getID().equals(mage.territoryTarget.getID()))
                return true;
        return false;
    }

    /** Every hostile dungeon or cave on the map right now - a rotating one, a boss lair, or a leave-on-clear place. */
    private static List<PointOfInterest> hostileDungeons(World world) {
        List<PointOfInterest> out = new ArrayList<>();
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            PointOfInterestData data = poi.getData();
            if (data == null || !poi.getActive())
                continue;
            if (DungeonRotation.isRotatableData(data) || DungeonRotation.isVanishingLair(data)
                    || DungeonRotation.isClearReturner(data))
                out.add(poi);
        }
        return out;
    }

    private static List<PointOfInterest> dungeonsNear(World world, PointOfInterest town, List<PointOfInterest> dungeons) {
        float reach = tuning().pillageRadiusTiles * (float) world.getTileSize();
        List<PointOfInterest> out = new ArrayList<>();
        for (PointOfInterest d : dungeons)
            if (d.getCenter().dst2(town.getCenter()) <= reach * reach)
                out.add(d);
        return out;
    }

    private static void start(World world, PointOfInterest poi, int day) {
        int[] range = tuning().pillageRaidersAtOnce;
        int low = range != null && range.length > 0 ? Math.max(1, range[0]) : 3;
        int high = range != null && range.length > 1 ? Math.max(low, range[1]) : low;
        int atOnce = low + RAND.nextInt(high - low + 1);
        world.getPillages().put(poi.getID(), new int[]{day, 0, atOnce, 0});
        TuningData t = tuning();
        GameHUD.getInstance().addNotification("Raiders from the nearby dungeons are pillaging " + poi.getDisplayName()
                + "! Beat " + t.pillageKills + " of them within " + t.pillageDays + " days.");
        System.out.println("[TFR-Pillage] " + poi.getDisplayName() + " is pillaged from day " + day + " - "
                + dungeonsNear(world, poi, hostileDungeons(world)).size() + " dungeon(s) within "
                + t.pillageRadiusTiles + " tiles, " + atOnce + " raider(s) at a time, " + t.pillageKills + " to beat by day "
                + (day + t.pillageDays));
    }

    // ------------------------------------------------------------------------------------------------ the days

    private static void checkDays(World world, int day) {
        TuningData t = tuning();
        for (String id : new ArrayList<>(world.getPillages().keySet())) {
            int[] p = world.getPillages().get(id);
            PointOfInterest poi = findPoi(world, id);
            String standing = poi == null ? "gone from the map" : standingReason(poi);
            if (standing != null) {
                System.out.println("[TFR-Pillage] " + (poi == null ? id : poi.getDisplayName()) + ": the pillage ends - "
                        + standing + " (no reward, no penalty)");
                end(world, id);
                continue;
            }
            int deadline = p[P_START] + t.pillageDays;
            if (day >= deadline) {
                fail(world, poi);
                continue;
            }
            if (p[P_REMINDED] == 0 && deadline - day <= REMIND_DAYS) {
                p[P_REMINDED] = 1;
                GameHUD.getInstance().addNotification(poi.getDisplayName() + " is still being pillaged - "
                        + (deadline - day) + " day(s) left to beat the raiders (" + p[P_BEATEN] + " of " + t.pillageKills
                        + ").");
            }
        }
    }

    private static void fail(World world, PointOfInterest poi) {
        int loss = tuning().pillageFailReputation;
        WorldSave.getCurrentSave().getPointOfInterestChanges(poi.getID()).addMapReputation(-loss);
        GameHUD.getInstance().addNotification("The raiders were never driven from " + poi.getDisplayName()
                + " - its people think less of you (-" + loss + " reputation).");
        System.out.println("[TFR-Pillage] " + poi.getDisplayName() + ": time ran out at "
                + world.getPillages().get(poi.getID())[P_BEATEN] + " beaten - -" + loss + " reputation");
        end(world, poi.getID());
    }

    private static void end(World world, String id) {
        world.getPillages().remove(id);
        WorldStage.getInstance().removePillageRaiders(id);
    }

    // ------------------------------------------------------------------------------------------------ the raiders

    /** pillageSpawnRangeTiles in world pixels: how near the player - or, since round 490, a roaming guard helping there -
     *  must be for a pillaged town to have raiders out. */
    public static float spawnRange(World world) {
        return tuning().pillageSpawnRangeTiles * (float) world.getTileSize();
    }

    private static void keepRaiders(World world) {
        if (world.getPillages().isEmpty())
            return;
        TuningData t = tuning();
        Vector2 playerPos = WorldStage.getInstance().getPlayerSprite().pos();
        float range = spawnRange(world);
        for (Map.Entry<String, int[]> entry : world.getPillages().entrySet()) {
            int[] p = entry.getValue();
            int wanted = Math.min(p[P_AT_ONCE], t.pillageKills - p[P_BEATEN]);
            if (wanted <= 0)
                continue;
            PointOfInterest poi = findPoi(world, entry.getKey());
            if (poi == null)
                continue;
            // Round 490 (the user: "the raiders only spawn around the player. So this will need to be tweaked, so they
            // also spawn around the guards. If possible, only the raiders and not other enemies"): a guard on this
            // pillage counts as someone there. Only this method asks - WorldStage's ordinary spawns stay the player's.
            if (poi.getCenter().dst2(playerPos) > range * range
                    && !RoamingGuardRuntime.helpingAt(entry.getKey(), poi.getCenter(), range))
                continue;
            // The raider a guard is fighting right now is off the map until the result - not a gap to fill.
            int live = WorldStage.getInstance().getPillageRaiders(entry.getKey()).size()
                    + RoamingGuardRuntime.raidersInFight(entry.getKey());
            if (live < wanted)
                spawnRaider(world, poi); // one per check - they trickle out of the dungeons, not all in one frame
        }
    }

    /**
     * Round 490: where a raider heads this frame, read by WorldStage's enemy loop. Null when the player is within
     * spawnRange of the raider or of its town: it goes for the player as every roamer does (round 484's behavior).
     * Otherwise it does not cross the map after a distant player - the guard hunting at its town is nearer, so it goes
     * for the guard (RoamingGuardRuntime.hunterFor); with no guard it drifts back to the town's surroundings, and there
     * it holds its ground (its own position comes back: WorldStage idles it). With both about, the nearer of the two.
     */
    public static Vector2 raiderGoal(EnemySprite raider, Vector2 playerPos) {
        World world = Current.world();
        PointOfInterest town = world == null ? null : cachedPoi(world, raider.pillageTown);
        if (town == null)
            return null;
        float range = spawnRange(world);
        Vector2 here = raider.pos();
        Vector2 center = town.getCenter();
        float toPlayer2 = here.dst2(playerPos);
        boolean playerNear = toPlayer2 <= range * range || center.dst2(playerPos) <= range * range;
        Vector2 guard = RoamingGuardRuntime.hunterFor(raider, center, range);
        if (guard != null && (!playerNear || here.dst2(guard) < toPlayer2))
            return guard;
        if (playerNear)
            return null;
        float half = Math.max(town.getBoundingRectangle().width, town.getBoundingRectangle().height) / 2f;
        float hold = half + RAIDER_HOLD_TILES * world.getTileSize();
        if (here.dst2(center) > hold * hold)
            return new Vector2(center.x - raider.getWidth() / 2f, center.y - raider.getHeight() / 2f);
        return here;
    }

    /** Round 490: a raider left alone holds within this many tiles beyond the town's footprint (they spawn 3-7 out). */
    private static final int RAIDER_HOLD_TILES = 8;
    private static final java.util.Map<String, PointOfInterest> poiCache = new java.util.HashMap<>();

    /** findPoi for every raider every frame would walk the whole POI list each time; the ids never change in a world. */
    private static PointOfInterest cachedPoi(World world, String id) {
        PointOfInterest poi = poiCache.get(id);
        if (poi == null) {
            poi = findPoi(world, id);
            if (poi != null)
                poiCache.put(id, poi);
        }
        return poi;
    }

    private static void spawnRaider(World world, PointOfInterest town) {
        List<PointOfInterest> sources = dungeonsNear(world, town, hostileDungeons(world));
        Collections.shuffle(sources, RAND);
        float rank = SpawnTierWeighting.effectiveRank(Current.player().getStatistic().rank());
        for (PointOfInterest source : sources) {
            DungeonSources.Pick pick = DungeonSources.pick(world, source, rank, RAND);
            if (pick == null || pick.enemy == null)
                continue;
            EnemySprite raider = new EnemySprite(new EnemyData(pick.enemy));
            raider.pillageTown = town.getID();
            Vector2 at = spotNear(world, town, raider);
            if (at == null) {
                System.out.println("[TFR-Pillage] no open ground around " + town.getDisplayName() + " for "
                        + pick.enemy.getName() + " - tried again shortly");
                return;
            }
            WorldStage.getInstance().spawnAt(raider, at);
            System.out.println("[TFR-Pillage] " + pick.enemy.getName() + " (" + pick.enemy.tier + ") raids "
                    + town.getDisplayName() + " from " + source.getDisplayName() + " - " + pick.from);
            return;
        }
        System.out.println("[TFR-Pillage] none of the dungeons near " + town.getDisplayName() + " gave a raider");
    }

    /** Open ground 3-7 tiles outside the town's footprint (closer, and a player chasing one walked into the town), not on
     *  top of the player. */
    private static Vector2 spotNear(World world, PointOfInterest town, EnemySprite sprite) {
        int ts = world.getTileSize();
        Vector2 center = town.getCenter();
        float half = Math.max(town.getBoundingRectangle().width, town.getBoundingRectangle().height) / 2f;
        Vector2 playerPos = WorldStage.getInstance().getPlayerSprite().pos();
        float maxX = world.getWidthInTiles() * (float) ts, maxY = world.getHeightInTiles() * (float) ts;
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = RAND.nextDouble() * Math.PI * 2;
            float dist = half + ts * (3f + 4f * RAND.nextFloat());
            float x = center.x + (float) Math.cos(angle) * dist - sprite.getWidth() / 2f;
            float y = center.y + (float) Math.sin(angle) * dist - sprite.getHeight() / 2f;
            if (x < 0 || y < 0 || x + sprite.getWidth() > maxX || y + sprite.getHeight() > maxY)
                continue;
            if (Vector2.dst2(x, y, playerPos.x, playerPos.y) < 9f * ts * ts)
                continue;
            sprite.setX(x);
            sprite.setY(y);
            if (sprite.getData().flying || !world.collidingTile(sprite.boundingRect()))
                return new Vector2(x, y);
        }
        return null;
    }

    /** WorldStage.setWinner, a won world duel: a raider counts toward its town's pillage. */
    public static void onRaiderBeaten(EnemySprite mob) {
        if (mob == null || mob.pillageTown == null || !isEnabled())
            return;
        World world = Current.world();
        int[] p = world == null ? null : world.getPillages().get(mob.pillageTown);
        if (p == null)
            return; // its pillage already ended
        TuningData t = tuning();
        p[P_BEATEN]++;
        PointOfInterest poi = findPoi(world, mob.pillageTown);
        String town = poi == null ? "the town" : poi.getDisplayName();
        System.out.println("[TFR-Pillage] " + town + ": raider " + mob.getData().getName() + " beaten - " + p[P_BEATEN]
                + " of " + t.pillageKills);
        if (p[P_BEATEN] < t.pillageKills) {
            GameHUD.getInstance().addNotification("Raider beaten - " + p[P_BEATEN] + " of " + t.pillageKills + " at "
                    + town + ".");
            return;
        }
        if (poi != null)
            WorldSave.getCurrentSave().getPointOfInterestChanges(poi.getID()).addMapReputation(t.pillageRewardReputation);
        Current.player().addWood(t.pillageRewardWood);
        Current.player().addStone(t.pillageRewardStone);
        GameHUD.getInstance().addNotification("The raiders are driven from " + town + "! Its people thank you: +"
                + t.pillageRewardReputation + " reputation, +" + t.pillageRewardWood + " wood, +" + t.pillageRewardStone
                + " stone.");
        System.out.println("[TFR-Pillage] " + town + ": pillage stopped on day " + world.getCurrentDay() + " - +"
                + t.pillageRewardReputation + " reputation, +" + t.pillageRewardWood + " wood, +" + t.pillageRewardStone
                + " stone");
        Ascendance.onPillageStopped(town); // round 493
        end(world, mob.pillageTown);
    }

    // ------------------------------------------------------------------------------------------------ what the player sees

    /** The quest log's rows, one per pillaged town: where, how far along, how long is left. */
    public static List<String> questLogRows() {
        List<String> rows = new ArrayList<>();
        World world = Current.world();
        if (!isEnabled() || world == null)
            return rows;
        TuningData t = tuning();
        for (Map.Entry<String, int[]> entry : world.getPillages().entrySet()) {
            PointOfInterest poi = findPoi(world, entry.getKey());
            if (poi == null)
                continue;
            int[] p = entry.getValue();
            int left = Math.max(0, p[P_START] + t.pillageDays - world.getCurrentDay());
            rows.add("Pillaged: " + poi.getDisplayName() + " - beat the raiders (" + p[P_BEATEN] + " of " + t.pillageKills
                    + ") - " + WorldStage.getInstance().directionFromPlayer(poi.getCenter().x, poi.getCenter().y)
                    + " [%75](" + left + " day" + (left == 1 ? "" : "s") + " left)");
        }
        return rows;
    }

    /** The world map's Details: every pillaged town and its count so far. */
    public static List<Object[]> mapLabels() {
        List<Object[]> out = new ArrayList<>();
        World world = Current.world();
        if (!isEnabled() || world == null)
            return out;
        for (Map.Entry<String, int[]> entry : world.getPillages().entrySet()) {
            PointOfInterest poi = findPoi(world, entry.getKey());
            if (poi != null)
                out.add(new Object[]{poi, "Pillaged! " + entry.getValue()[P_BEATEN] + "/" + tuning().pillageKills});
        }
        return out;
    }

    private static PointOfInterest findPoi(World world, String id) {
        for (PointOfInterest poi : world.getAllPointOfInterest())
            if (poi.getID().equals(id))
                return poi;
        return null;
    }

    // ------------------------------------------------------------------------------------------------ test cheats

    /** Console "pillage start [town name]": the nearest town that could be pillaged (dungeon count waived), now. */
    public static String cheatStart(String name) {
        World world = Current.world();
        if (world == null)
            return "No world";
        List<PointOfInterest> dungeons = hostileDungeons(world);
        Vector2 at = WorldStage.getInstance().getPlayerSprite().pos();
        PointOfInterest best = null;
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            String why = notEligibleReason(world, poi, dungeons);
            if (why != null && !why.endsWith("dungeon(s) near"))
                continue;
            if (name != null && !name.isEmpty() && !poi.getDisplayName().equalsIgnoreCase(name))
                continue;
            if (best == null || poi.getCenter().dst2(at) < best.getCenter().dst2(at))
                best = poi;
        }
        if (best == null)
            return "No town that could be pillaged" + (name == null || name.isEmpty() ? "" : " named " + name);
        start(world, best, world.getCurrentDay());
        return best.getDisplayName() + " is now pillaged (" + dungeonsNear(world, best, dungeons).size() + " dungeon(s) near)";
    }

    /** Console "pillage beaten <n>": set every running pillage's count of raiders beaten (the next real win then ends
     *  it at pillageKills), for testing. */
    public static String cheatSetBeaten(int n) {
        World world = Current.world();
        if (world == null || world.getPillages().isEmpty())
            return "No town is being pillaged";
        int kills = tuning().pillageKills;
        for (int[] p : world.getPillages().values())
            p[P_BEATEN] = Math.max(0, Math.min(kills - 1, n));
        return world.getPillages().size() + " pillage(s) now at " + Math.max(0, Math.min(kills - 1, n)) + " of " + kills;
    }

    /** Console "pillage info": every running pillage, and why the nearest towns do or do not qualify. */
    public static String cheatInfo() {
        World world = Current.world();
        if (world == null)
            return "No world";
        StringBuilder sb = new StringBuilder("week roll done for week " + world.getPillageWeek() + ";");
        for (Map.Entry<String, int[]> entry : world.getPillages().entrySet()) {
            PointOfInterest poi = findPoi(world, entry.getKey());
            int[] p = entry.getValue();
            List<EnemySprite> live = WorldStage.getInstance().getPillageRaiders(entry.getKey());
            sb.append(" ").append(poi == null ? entry.getKey() : poi.getDisplayName()).append(": day ").append(p[P_START])
                    .append(", beaten ").append(p[P_BEATEN]).append(", at once ").append(p[P_AT_ONCE]).append(", live ")
                    .append(live.size());
            int ts = world.getTileSize();
            for (EnemySprite raider : live) // where each raider stands, as tiles - what a tester walks to
                sb.append(" [").append(raider.getData().getName()).append(" @").append((int) (raider.getX() / ts)).append(",")
                        .append((int) (raider.getY() / ts)).append("]");
            sb.append(";");
        }
        List<PointOfInterest> dungeons = hostileDungeons(world);
        Vector2 at = WorldStage.getInstance().getPlayerSprite().pos();
        List<PointOfInterest> towns = new ArrayList<>();
        for (PointOfInterest poi : world.getAllPointOfInterest())
            if (poi.getData() != null && ("town".equals(poi.getData().type) || "capital".equals(poi.getData().type)))
                towns.add(poi);
        towns.sort((a, b) -> Float.compare(a.getCenter().dst2(at), b.getCenter().dst2(at)));
        for (int i = 0; i < Math.min(5, towns.size()); i++) {
            String why = notEligibleReason(world, towns.get(i), dungeons);
            sb.append(" ").append(towns.get(i).getDisplayName()).append(" - ").append(why == null ? "qualifies" : why)
                    .append(";");
        }
        return sb.toString();
    }
}
