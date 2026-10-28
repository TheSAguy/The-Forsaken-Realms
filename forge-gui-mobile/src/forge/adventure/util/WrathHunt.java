package forge.adventure.util;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import forge.adventure.character.CharacterSprite;
import forge.adventure.character.EnemySprite;
import forge.adventure.character.PlayerSprite;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.SpawnTierWeightData;
import forge.adventure.data.TuningData;
import forge.adventure.data.WorldData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.card.CardRarity;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Round 813 (the user: "I want to implement something, when you built a temple. Where the current color that has been
 * 'killed' the most does something"; design notes/temple-wrath-design-813.md, approved 2026-10-28 with every
 * recommendation): the Temple's hunted color.
 * <ul>
 *   <li>When a week turns and the Capitol has a Temple, the closed week's most-killed color (TempleOfferings' ranking:
 *   multicolor kills split, ties in WUBRG order) is hunted for the new week. A week with no kills hunts nobody.</li>
 *   <li>Spawns ahead: roaming spawns on the hunted color's land - the biome roll (BiomeData.getEnemy) and the roamers its
 *   dungeons send (DungeonSources.byWeekTier) - roll on the NEXT rank bracket's row of spawn_tier_weighting.json
 *   (wrathAheadBrackets), not next week's: next week's row differs only in a bracket's last week. The last bracket
 *   has nothing above it. Everything else on the row (territory, difficulty, Notoriety's tilt, the win-ratio shift)
 *   applies as before; the lifetime-win rank gate is not touched. Off on Easy (wrathAheadOnDifficulty).</li>
 *   <li>The bounty: the Temple tally's duels (the player's own wins - no Arena, Inn, fixed-deck or guard fight) against
 *   an enemy with the hunted color pay wrathBountyGold / wrathBountyShards more gold and shards, rounded up. DuelScene
 *   marks the win (onDuelEnded), the two loot sites raise it (applyBounty) - Ascendance's pending-Power pattern.</li>
 *   <li>A destroyed Temple ends the hunt at once (onTempleGone). The state lives on the World (wrathColor, wrathWeek,
 *   wrathStatus) and holds only for the week it was set in, so a missed turn lets it lapse rather than run on.</li>
 * </ul>
 * Round 814 (the user: "It should not fly, but walk. It starting speed should match the players starting speed at the
 * time it was created. It should fade out at the end of the week as the new one is spawned"; "Speed is player +5%"): the
 * Wrath hunter.
 * <ul>
 *   <li>At the turn, a mono-color mage of the hunted color sets out from its castle (TerritoryControl.findCastle): a
 *   Master, an Archmage from wrathArchmageWeekByDifficulty (Normal 7, Hard and Insane 4), held under the week's
 *   spawn-tier bracket like a castle's attack mage. None on Easy, none for a color without a castle.</li>
 *   <li>It walks, never flies: WrathPath plans its route over the full tile grid on a worker thread; within
 *   wrathDirectTiles and in a clear line it walks straight at the player. It moves only while the world clock runs, as
 *   every world enemy does. No route (an island, the barrier): it holds at the nearest tile it reaches.</li>
 *   <li>Its speed: playerBaseSpeed x AdventurePlayer.equipmentSpeed() (gear, blessing, Swift Feet) when it set out,
 *   saved on the World, x wrathSpeedFactor (1.05), x the player's road and land rule at its own feet
 *   (WorldStage.terrainSpeedAt). Not Sprint, the Run key or the fog-dark speed-up.</li>
 *   <li>It waits wrathWaitTiles outside a place the player enters (onEnterPlace). The roaming guards ignore it (they
 *   engage town attackers and raiders only), Intimidating never makes it flee, the despawn clock and the spawn-crowd
 *   count leave it out.</li>
 *   <li>Beaten: a rare of its color from the Temple's pool (a mythic at wrathPrizeMythicChance), seeded by world, week and
 *   color so a reload brings the same card; wrathPrizeShardsByDifficulty shards; Ascendance's wrathFactor on the duel's
 *   Power; the Wrath Breaker trophy. It beats the player, or a draw: it goes home, no prize. Either way the hunt is
 *   over for the week - the bounty and spawns ahead hold. At the next turn it fades as the next one sets out.</li>
 *   <li>The sprite is not in WorldStage's saved roamers: the World keeps its enemy, speed and position, and keep()
 *   rebuilds the sprite from them every frame it is missing (a load, a return from a place).</li>
 * </ul>
 * Logs [TFR-Wrath]; console "wrath".
 */
public final class WrathHunt {
    private WrathHunt() {
    }

    /** World.wrathStatus: no hunt; a hunter is out (round 814); beaten; it won; no hunter this week (Easy, no castle). */
    public static final int NONE = 0, HUNTING = 1, BEATEN = 2, LOST = 3, NO_HUNTER = 4;

    /** The tint of the hunt's notices - round 594's player-town red. */
    private static final String TINT = "[#B3261E]";

    /** The duel just won, when it counts for the bounty - consumed by applyBounty at its loot screen. */
    private static EnemySprite pendingBounty;
    /** "seed|land|week|source" keys already logged by spawnWeek, so each is logged once. */
    private static final Set<String> loggedAhead = new HashSet<>();
    /** True while DungeonSources' ordinary roll runs - BiomeData then treats it as a roaming roll. */
    private static boolean dungeonSourceRoll;

    // Round 814: the hunter on the map, and its walk. Not saved - the World holds the hunter (keep() rebuilds it).
    private static EnemySprite hunter;
    private static WrathPath.Route route;
    private static int routeAt;
    private static boolean planning, holdLogged;
    private static float sincePlan, stuckClock, anchorX, anchorY, lastSpeedFactor = -1f;
    private static int stuckCount;

    /** WorldStage.clearCache(): a load or a new world. */
    public static void resetSessionState() {
        pendingBounty = null;
        hunter = null;
        forgetRoute();
    }

    private static void forgetRoute() {
        route = null;
        routeAt = 0;
        planning = false;
        holdLogged = false;
        sincePlan = stuckClock = 0f;
        stuckCount = 0;
        lastSpeedFactor = -1f;
    }

    /** The Wrath hunter on the map, or null (MapViewScene's marker). */
    public static EnemySprite hunter() {
        return hunter;
    }

    public static boolean isEnabled() {
        ConfigData config = Config.instance().getConfigData();
        return config != null && config.wrathHuntEnabled;
    }

    /** DungeonSources.pick: its ordinary roll (BiomeData.getEnemy without the injected spawns) is a dungeon's roamer. */
    public static <T> T dungeonSourceRoll(java.util.function.Supplier<T> roll) {
        dungeonSourceRoll = true;
        try {
            return roll.get();
        } finally {
            dungeonSourceRoll = false;
        }
    }

    public static boolean inDungeonSourceRoll() {
        return dungeonSourceRoll;
    }

    private static TuningData tuning() {
        return Config.instance().getTuningData();
    }

    private static World world() {
        WorldSave save = WorldSave.getCurrentSave();
        return save == null ? null : save.getWorld();
    }

    /** True when the difficulty's entry (Easy, Normal, Hard, Insane) of a per-difficulty switch is on. */
    private static boolean onFor(int[] perDifficulty) {
        int d = TerritoryControl.difficultyIndex();
        return perDifficulty != null && d >= 0 && d < perDifficulty.length && perDifficulty[d] > 0;
    }

    /** The hunted color ("red") this week, or null. */
    public static String huntedColor() {
        World world = world();
        if (!isEnabled() || world == null || world.getWrathColor() == null)
            return null;
        return world.getWrathWeek() == world.getCurrentWeek() ? world.getWrathColor() : null;
    }

    private static String letterOf(String color) {
        for (int c = 0; c < TempleOfferings.NAMES.length; c++)
            if (TempleOfferings.NAMES[c].equals(color))
                return TempleOfferings.LETTERS[c];
        return "";
    }

    private static String capitalized(String color) {
        return color.isEmpty() ? color : Character.toUpperCase(color.charAt(0)) + color.substring(1);
    }

    // ------------------------------------------------------------------------------------------------- the week turn

    /**
     * TempleOfferings.onDaysPassed: a new week began. {@code kills} is the closed week's tally (empty when there was
     * none), {@code closedWeek} its 0-based week (-1 when none was kept). The old hunt ends; a new one starts when the
     * Capitol has a Temple and someone was killed.
     */
    public static void onWeekTurn(float[] kills, int closedWeek, int newWeek) {
        World world = world();
        if (!isEnabled() || world == null)
            return;
        String before = world.getWrathColor();
        int beforeWeek = world.getWrathWeek();
        boolean wasHunting = world.getWrathStatus() == HUNTING;
        if (before != null && wasHunting) { // the user: "It should fade out at the end of the week as the new one is spawned"
            endHunter("fades - the week turned");
            GameHUD.getInstance().addNotification(TINT + "The Wrath of " + capitalized(before) + " fades as the week turns.",
                    true);
        }
        world.setWrath(null, -1, NONE);
        world.setWrathHunter("", 0f);
        if (before != null && beforeWeek >= 0 && beforeWeek < newWeek)
            System.out.println("[TFR-Wrath] week " + (newWeek + 1) + ": " + before + "'s hunt (week " + (beforeWeek + 1)
                    + ") is over");
        List<Integer> ranked = TempleOfferings.ranking(kills);
        if (ranked.isEmpty()) {
            System.out.println("[TFR-Wrath] week " + (newWeek + 1) + ": no kills in week " + (closedWeek + 1)
                    + " - nobody hunted");
            return;
        }
        if (!TempleOfferings.capitolHasTemple()) {
            System.out.println("[TFR-Wrath] week " + (newWeek + 1) + ": no Temple - no hunt");
            return;
        }
        String color = TempleOfferings.NAMES[ranked.get(0)];
        start(color, newWeek, "(" + TempleOfferings.LETTERS[ranked.get(0)] + " " + TempleOfferings.fmt(kills[ranked.get(0)])
                + " of " + TempleOfferings.fmt(total(kills)) + " kills, 1st)", color.equals(before) && wasHunting);
    }

    private static float total(float[] kills) {
        float sum = 0f;
        for (float k : kills)
            sum += k;
        return sum;
    }

    /** Sets the hunt for {@code week} and announces it. {@code why} is for the log; {@code again}: the same color as
     *  last week, its hunter just faded. */
    private static void start(String color, int week, String why, boolean again) {
        World world = world();
        TuningData t = tuning();
        if (world == null || t == null)
            return;
        boolean ahead = onFor(t.wrathAheadOnDifficulty) && t.wrathAheadBrackets > 0 && SpawnTierWeighting.isEnabled();
        boolean bounty = onFor(t.wrathBountyOnDifficulty) && (t.wrathBountyGold > 0f || t.wrathBountyShards > 0f);
        boolean hunterOn = onFor(t.wrathHunterOnDifficulty);
        String difficulty = Current.player().getDifficulty() == null ? "?" : Current.player().getDifficulty().name;
        if (!ahead && !bounty && !hunterOn) {
            System.out.println("[TFR-Wrath] week " + (week + 1) + ": " + color + " would be hunted " + why
                    + " - spawns ahead, bounty and hunter all off on " + difficulty);
            return;
        }
        if (hunter != null)
            endHunter("replaced (console)");
        world.setWrath(color, week, NO_HUNTER);
        world.setWrathHunter("", 0f);
        loggedAhead.clear();
        System.out.println("[TFR-Wrath] week " + (week + 1) + ": " + color + " is hunted " + why + " - hunter "
                + (hunterOn ? "yes" : "no") + ", spawns ahead "
                + (ahead ? "yes (+" + t.wrathAheadBrackets + " bracket)" : "no") + ", bounty "
                + (bounty ? "+" + pct(t.wrathBountyGold) + " gold / +" + pct(t.wrathBountyShards) + " shards" : "no")
                + " (" + difficulty + ")");
        String name = capitalized(color);
        String noHunter = hunterOn ? sendHunter(world, color, week) : "off on " + difficulty;
        String head = again ? name + " is still hunted: a new Wrath of " + name : name + " is hunted. The Wrath of " + name;
        if (noHunter == null)
            GameHUD.getInstance().addNotification(TINT + head + " has left its castle and is hunting you."
                    + (ahead ? " Its land sends stronger foes this week." : ""), true);
        else
            GameHUD.getInstance().addNotification(TINT + name + " is hunted"
                    + (hunterOn ? ", but has no castle to send its Wrath from" : "") + "."
                    + (ahead ? " Its land sends stronger foes this week." : ""), true);
        if (bounty)
            GameHUD.getInstance().addNotification("[BLACK]" + name + "'s bounty this week: " + color + " foes pay "
                    + bountyText(t) + ".", true);
    }

    private static String bountyText(TuningData t) {
        if (t.wrathBountyGold > 0f && t.wrathBountyShards > 0f && t.wrathBountyGold == t.wrathBountyShards)
            return "+" + pct(t.wrathBountyGold) + " [WHITE][+Gold][BLACK] and [WHITE][+Shards][BLACK]";
        StringBuilder sb = new StringBuilder();
        if (t.wrathBountyGold > 0f)
            sb.append("+").append(pct(t.wrathBountyGold)).append(" [WHITE][+Gold][BLACK]");
        if (t.wrathBountyShards > 0f)
            sb.append(sb.length() == 0 ? "" : " and ").append("+").append(pct(t.wrathBountyShards))
                    .append(" [WHITE][+Shards][BLACK]");
        return sb.toString();
    }

    private static String pct(float share) {
        return Math.round(share * 100f) + "%";
    }

    /** EconomyBuildings: the Capitol's Temple was destroyed - the hunt ends at once. */
    public static void onTempleGone() {
        World world = world();
        String color = huntedColor();
        if (world == null || color == null)
            return;
        boolean wasHunting = world.getWrathStatus() == HUNTING;
        if (wasHunting)
            endHunter("turns back - the Temple was destroyed");
        world.setWrath(null, -1, NONE);
        world.setWrathHunter("", 0f);
        System.out.println("[TFR-Wrath] the Temple was destroyed - " + color + "'s hunt ends (week "
                + (world.getCurrentWeek() + 1) + ")");
        GameHUD.getInstance().addNotification(TINT + "Without the Temple, " + (wasHunting ? "the Wrath of "
                + capitalized(color) + " turns back. " : "") + capitalized(color) + " is no longer hunted.", true);
    }

    // ------------------------------------------------------------------------------------------------- the hunter

    /** Picks the hunter and puts it at its castle's gate on the World; null when it set out, else why it did not. */
    private static String sendHunter(World world, String color, int week) {
        TuningData t = tuning();
        PointOfInterest castle = TerritoryControl.findCastle(world, color);
        if (castle == null) {
            System.out.println("[TFR-Wrath] no hunter: " + color + " has no castle");
            return "no castle";
        }
        EnemyData data = pickHunter(world, color, week);
        if (data == null) {
            System.out.println("[TFR-Wrath] no hunter: no " + color + " mage to send");
            return "no mage";
        }
        int ts = world.getTileSize();
        int cx = (int) (castle.getPosition().x / ts), cy = (int) (castle.getPosition().y / ts);
        int[] gate = WrathPath.nearestOpen(world, cx, cy, 16);
        if (gate == null) {
            System.out.println("[TFR-Wrath] no hunter: no open tile within 16 of " + castle.getDisplayName());
            return "walled in";
        }
        float base = Config.instance().getConfigData().playerBaseSpeed;
        float gear = Current.player().equipmentSpeed();
        world.setWrathHunter(EnemyTiers.saveName(data, data.name), base * gear);
        world.setWrathPosition((gate[0] + 0.5f) * ts, (gate[1] + 0.5f) * ts);
        world.setWrathStatus(HUNTING);
        forgetRoute();
        Vector2 player = WorldStage.getInstance().getPlayerSprite() == null ? null : WorldStage.getInstance().getPlayerSprite().pos();
        System.out.println("[TFR-Wrath] hunter set out: " + data.getName() + " (" + EnemyData.tierDisplayName(data.tier)
                + "; Archmage from week " + archmageWeek(t) + ") from " + castle.getDisplayName() + " (" + cx + "," + cy
                + ") -> start tile (" + gate[0] + "," + gate[1] + "); speed " + base + " x " + round2(gear)
                + " (gear, blessing, Swift Feet) = " + round2(base * gear) + " px/s, x" + round2(t.wrathSpeedFactor) + " = "
                + round2(base * gear * t.wrathSpeedFactor) + (player == null ? "" : "; player at (" + (int) (player.x / ts)
                + "," + (int) (player.y / ts) + "), " + (int) Math.hypot(player.x / ts - gate[0], player.y / ts - gate[1])
                + " tiles"));
        return null;
    }

    private static int archmageWeek(TuningData t) {
        int d = TerritoryControl.difficultyIndex();
        int[] weeks = t.wrathArchmageWeekByDifficulty;
        return weeks != null && d >= 0 && d < weeks.length ? weeks[d] : 0;
    }

    /** A Master before the Archmage week, an Archmage from it - stepped down while the week's bracket shuts the rank. */
    private static EnemyData pickHunter(World world, String color, int week) {
        TuningData t = tuning();
        int weekNo = week + 1, arch = archmageWeek(t);
        String[] tiers = SpawnTierWeighting.tiers();
        int rank = arch > 0 && weekNo >= arch ? 3 : 2;
        while (rank > 0 && SpawnTierWeighting.isEnabled()
                && SpawnTierWeighting.targetTierWeight(tiers[rank], weekNo, null) <= 0f)
            rank--;
        Random rand = new Random(world.getSeed() * 31L + week * 7919L + color.hashCode());
        if (rank == 3) {
            List<EnemyData> roster = TerritoryControl.grandmasterRoster(world, color);
            List<EnemyData> mono = new ArrayList<>();
            for (EnemyData e : roster)
                if (e.colors != null && e.colors.equalsIgnoreCase(letterOf(color)))
                    mono.add(e);
            List<EnemyData> pool = t.wrathMonoColorOnly && !mono.isEmpty() ? mono : roster;
            if (t.wrathMonoColorOnly && mono.isEmpty() && !roster.isEmpty())
                System.out.println("[TFR-Wrath] " + color + " has no mono-color Archmage - any of its " + roster.size());
            if (!pool.isEmpty())
                return pool.get(rand.nextInt(pool.size()));
            System.out.println("[TFR-Wrath] " + color + " has no Archmage - a Master instead");
            rank = 2;
        }
        String name = EnemyData.tierDisplayName(tiers[rank]) + " " + capitalized(color) + " Wizard";
        EnemyData data = WorldData.getEnemy(name);
        if (data == null)
            System.out.println("[TFR-Wrath] no enemy \"" + name + "\"");
        return data;
    }

    private static String round2(float v) {
        return String.valueOf(Math.round(v * 100f) / 100f);
    }

    /** WorldStage.onActing, every frame the world runs: the sprite follows the World - built when it is missing (a
     *  load, a new hunter), faded when the hunt it belongs to is over. */
    public static void keep(WorldStage stage) {
        World world = world();
        if (!isEnabled() || world == null)
            return;
        boolean out = huntedColor() != null && world.getWrathStatus() == HUNTING && !world.getWrathEnemy().isEmpty();
        if (!out) {
            if (hunter != null)
                endHunter("its hunt lapsed");
            return;
        }
        if (hunter != null && stage.hasEnemy(hunter))
            return;
        EnemyData data = EnemyTiers.resolveSaved(world.getWrathEnemy()); // "Name|Tier" for a family variant
        if (data == null)
            data = WorldData.getEnemy(world.getWrathEnemy()); // a plain name - WorldStage.load's order
        if (data == null) {
            System.out.println("[TFR-Wrath] the hunter \"" + world.getWrathEnemy() + "\" is not in the catalog - no hunter");
            world.setWrathStatus(NO_HUNTER);
            return;
        }
        EnemySprite sprite = new EnemySprite(data);
        sprite.wrathHunter = true;
        stage.spawnAt(sprite, new Vector2(world.getWrathX() - sprite.getWidth() / 2f, world.getWrathY()));
        hunter = sprite;
        forgetRoute();
        int ts = world.getTileSize();
        System.out.println("[TFR-Wrath] on the map: " + data.getName() + " at (" + (int) (world.getWrathX() / ts) + ","
                + (int) (world.getWrathY() / ts) + "), speed " + round2(world.getWrathSpeed()) + " x"
                + round2(tuning().wrathSpeedFactor) + ", " + huntedColor() + " week " + (world.getWrathWeek() + 1));
    }

    /** The hunter leaves the map (faded); the World's state is the caller's. */
    private static void endHunter(String why) {
        EnemySprite h = hunter;
        hunter = null;
        forgetRoute();
        if (h == null)
            return;
        System.out.println("[TFR-Wrath] fades: " + h.getName() + " at (" + tileOf(h.getX() + h.getWidth() / 2f) + ","
                + tileOf(h.getY()) + ") - " + why);
        h.wrathHunter = false; // its plate goes now, not after the fade (a new hunt may already name another color)
        WorldStage.getInstance().removeWrathHunter(h, true);
    }

    private static int tileOf(float px) {
        World world = world();
        return world == null ? 0 : (int) (px / world.getTileSize());
    }

    /**
     * WorldStage's enemy loop, each frame the world clock runs: the hunter's step toward the player - straight within
     * wrathDirectTiles in a clear line, else along WrathPath's route, re-planned as the player moves. Hide holds it.
     */
    public static void move(WorldStage stage, EnemySprite mob, PlayerSprite player, float delta, boolean hidden) {
        World world = world();
        TuningData t = tuning();
        if (world == null || t == null || mob != hunter)
            return;
        if (hidden) {
            mob.setAnimation(CharacterSprite.AnimationTypes.Idle);
            return;
        }
        int ts = world.getTileSize();
        float hx = mob.getX() + mob.getWidth() / 2f, hy = mob.getY();
        float px = player.getX() + player.getWidth() / 2f, py = player.getY();
        float terrain = stage.terrainSpeedAt(hx, hy);
        if (terrain != lastSpeedFactor) {
            lastSpeedFactor = terrain;
            float playerTerrain = stage.terrainSpeedAt(px, py);
            System.out.println("[TFR-Wrath] speed check: " + round2(world.getWrathSpeed()) + " x " + round2(t.wrathSpeedFactor)
                    + " x " + round2(terrain) + " = " + round2(world.getWrathSpeed() * t.wrathSpeedFactor * terrain)
                    + " px/s at (" + (int) (hx / ts) + "," + (int) (hy / ts) + "); player "
                    + round2(Config.instance().getConfigData().playerBaseSpeed * Current.player().equipmentSpeed())
                    + " x " + round2(playerTerrain) + " (before Sprint and the Run key)");
        }
        float step = world.getWrathSpeed() * t.wrathSpeedFactor * terrain * delta;
        int htx = (int) (hx / ts), hty = (int) (hy / ts), ptx = (int) (px / ts), pty = (int) (py / ts);
        float dist = (float) Math.hypot(px - hx, py - hy);
        sincePlan += delta;
        if (dist <= 1.5f * ts || (dist <= t.wrathDirectTiles * ts && WrathPath.clearLine(world, htx, hty, ptx, pty))) {
            stepToward(mob, hx, hy, px, py, step); // in sight: straight at the player
            route = null;
            noteProgress(mob, world, delta, true);
            return;
        }
        boolean goalMoved = route != null && Math.max(Math.abs(route.goalX - ptx), Math.abs(route.goalY - pty)) >= t.wrathReplanTiles;
        if (planning && sincePlan > 10f)
            planning = false; // a request that never came back (the world changed under it)
        if (!planning && (route == null || goalMoved || sincePlan >= t.wrathReplanSeconds))
            plan(world, htx, hty, ptx, pty);
        if (route == null || routeAt >= route.waypoints.size()) {
            mob.setAnimation(CharacterSprite.AnimationTypes.Idle); // waiting for a plan, or holding where the walk ends
            if (route != null && !route.reached && !holdLogged) {
                holdLogged = true;
                System.out.println("[TFR-Wrath] no walk to the player (island or barrier) - holds at (" + htx + "," + hty
                        + "), " + (int) Math.hypot(ptx - htx, pty - hty) + " tiles off; trying again every "
                        + round2(t.wrathReplanSeconds) + " s");
            }
            noteProgress(mob, world, delta, false);
            return;
        }
        float[] wp = route.waypoints.get(routeAt);
        float left = (float) Math.hypot(wp[0] - hx, wp[1] - hy);
        if (left <= step) {
            routeAt++;
            mob.moveBy(wp[0] - hx, wp[1] - hy);
        } else {
            stepToward(mob, hx, hy, wp[0], wp[1], step);
        }
        noteProgress(mob, world, delta, true);
    }

    private static void stepToward(EnemySprite mob, float fromX, float fromY, float toX, float toY, float step) {
        float dx = toX - fromX, dy = toY - fromY, len = (float) Math.hypot(dx, dy);
        if (len < 0.01f)
            return;
        float f = Math.min(1f, step / len);
        mob.moveBy(dx * f, dy * f);
    }

    private static void plan(World world, int htx, int hty, int ptx, int pty) {
        int[] from = WrathPath.nearestOpen(world, htx, hty, 6);
        int[] to = WrathPath.nearestOpen(world, ptx, pty, 4);
        if (from == null || to == null)
            return;
        planning = true;
        sincePlan = 0f;
        EnemySprite asked = hunter;
        WrathPath.request(world, from[0], from[1], to[0], to[1], r -> {
            planning = false;
            if (asked != hunter)
                return;
            route = r;
            routeAt = 0;
            holdLogged = false;
            System.out.println("[TFR-Wrath] path: (" + from[0] + "," + from[1] + ") -> (" + r.goalX + "," + r.goalY + "): "
                    + r.tiles + " tiles, " + r.waypoints.size() + " waypoints, " + r.nodes + " nodes, " + r.millis
                    + " ms (worker)" + (r.reached ? "" : " - no walk there; it ends at (" + r.endX + "," + r.endY + ")"));
        });
    }

    /** The World keeps the hunter's place; under a tile of progress in wrathStuckSeconds of moving is stuck. */
    private static void noteProgress(EnemySprite mob, World world, float delta, boolean moving) {
        TuningData t = tuning();
        float x = mob.getX() + mob.getWidth() / 2f, y = mob.getY();
        world.setWrathPosition(x, y);
        if (!moving) {
            stuckClock = 0f;
            anchorX = x;
            anchorY = y;
            return;
        }
        stuckClock += delta;
        if (stuckClock < t.wrathStuckSeconds)
            return;
        int ts = world.getTileSize();
        if (Math.hypot(x - anchorX, y - anchorY) < ts) {
            stuckCount++;
            System.out.println("[TFR-Wrath] stuck at (" + (int) (x / ts) + "," + (int) (y / ts) + ") for "
                    + round2(stuckClock) + " s - re-plan " + stuckCount + "/3");
            if (stuckCount >= 3 && route != null && routeAt < route.waypoints.size()) {
                float[] wp = route.waypoints.get(routeAt++);
                mob.moveBy(wp[0] - x, wp[1] - y);
                System.out.println("[TFR-Wrath] snapped to waypoint (" + (int) (wp[0] / ts) + "," + (int) (wp[1] / ts) + ")");
                stuckCount = 0;
            } else {
                route = null;
            }
        } else {
            stuckCount = 0;
        }
        stuckClock = 0f;
        anchorX = x;
        anchorY = y;
    }

    /** WorldStage.loadPOI: the player goes into a place - a hunter nearer than wrathWaitTiles steps back to wait. */
    public static void onEnterPlace(WorldStage stage, PointOfInterest poi) {
        World world = world();
        if (hunter == null || world == null || stage.getPlayerSprite() == null)
            return;
        int ts = world.getTileSize(), wait = tuning().wrathWaitTiles;
        float hx = hunter.getX() + hunter.getWidth() / 2f, hy = hunter.getY();
        float px = stage.getPlayerSprite().getX() + stage.getPlayerSprite().getWidth() / 2f, py = stage.getPlayerSprite().getY();
        float dist = (float) Math.hypot(hx - px, hy - py);
        if (dist >= wait * ts)
            return;
        float dx = dist < 1f ? 1f : (hx - px) / dist, dy = dist < 1f ? 0f : (hy - py) / dist;
        int tx = (int) ((px + dx * wait * ts) / ts), ty = (int) ((py + dy * wait * ts) / ts);
        int[] spot = WrathPath.nearestOpen(world, tx, ty, 4);
        if (spot == null)
            return;
        float nx = (spot[0] + 0.5f) * ts, ny = (spot[1] + 0.5f) * ts;
        hunter.moveBy(nx - hx, ny - hy);
        hunter.setAnimation(CharacterSprite.AnimationTypes.Idle);
        world.setWrathPosition(nx, ny);
        route = null;
        System.out.println("[TFR-Wrath] waits outside " + (poi == null ? "a place" : poi.getDisplayName()) + ": set back "
                + round2(dist / ts) + " -> " + round2((float) Math.hypot(nx - px, ny - py) / ts) + " tiles");
    }

    /** WorldStage.setWinner, a win: the Wrath's prize joins the loot, and the hunt is over for the week. */
    public static void onHunterBeaten(Array<Reward> loot, EnemySprite mob) {
        World world = world();
        String color = huntedColor();
        if (mob == null || !mob.wrathHunter || world == null || color == null)
            return;
        TuningData t = tuning();
        hunter = null;
        forgetRoute();
        world.setWrathStatus(BEATEN);
        int week = world.getWrathWeek();
        Random rand = new Random(world.getSeed() * 1_000_003L + week * 7919L + color.hashCode() * 31L);
        float roll = rand.nextFloat();
        boolean mythic = roll < t.wrathPrizeMythicChance;
        Set<String> unlocked = new HashSet<>(WorldSave.getCurrentSave().getPlayer().getUnlockedEditions());
        List<PaperCard> inSets = new ArrayList<>();
        for (PaperCard card : forge.model.FModel.getMagicDb().getCommonCards().getAllCards())
            if (unlocked.contains(card.getEdition()) && !card.getRules().getType().isLand())
                inSets.add(card);
        int c = java.util.Arrays.asList(TempleOfferings.NAMES).indexOf(color);
        CardRarity[] tries = mythic ? new CardRarity[]{CardRarity.MythicRare, CardRarity.Rare, CardRarity.Uncommon}
                : new CardRarity[]{CardRarity.Rare, CardRarity.Uncommon};
        PaperCard card = null;
        String rarity = "";
        int poolSize = 0;
        for (CardRarity r : tries) {
            List<PaperCard> pool = TempleOfferings.pool(inSets, TempleOfferings.BITS[c], r);
            if (!pool.isEmpty()) {
                pool.sort(java.util.Comparator.comparing(PaperCard::getName)); // a stable order for the seed
                card = pool.get(rand.nextInt(pool.size()));
                rarity = r.name();
                poolSize = pool.size();
                break;
            }
        }
        if (card != null)
            loot.add(new Reward(card));
        int d = TerritoryControl.difficultyIndex();
        int shards = t.wrathPrizeShardsByDifficulty != null && d >= 0 && d < t.wrathPrizeShardsByDifficulty.length
                ? t.wrathPrizeShardsByDifficulty[d] : 0;
        if (shards > 0)
            loot.add(new Reward(Reward.Type.Shards, shards));
        System.out.println("[TFR-Wrath] beaten: " + mob.getName() + " - prize " + (card == null ? "no card (empty pool)"
                : card.getName() + " (" + rarity + (mythic ? ", mythic roll " + round2(roll) : ", roll " + round2(roll))
                + ", pool " + poolSize + ")") + ", " + shards + " shards, Power x" + round2(Ascendance.wrathFactor())
                + ", seed " + world.getSeed() + "-" + (week + 1) + "-" + letterOf(color));
        TfrAchievements.onWrathBroken(color);
        GameHUD.getInstance().addNotification(TINT + "You broke the Wrath of " + capitalized(color)
                + "! Its prize is on the loot screen. " + capitalized(color) + "'s bounty holds to the week's end.", true);
    }

    /** WorldStage.setWinner, a loss: the Wrath goes home - no prize this week. */
    public static void onHunterWon(EnemySprite mob) {
        if (mob == null || !mob.wrathHunter)
            return;
        endOnFoot(mob, "won", "has beaten you and returns to its castle. No prize this week.");
    }

    /** WorldStage.setDrawn: the Wrath withdraws - no prize this week. */
    public static void onHunterDrawn(EnemySprite mob) {
        if (mob == null || !mob.wrathHunter)
            return;
        endOnFoot(mob, "drew", "withdraws from a drawn duel. No prize this week.");
    }

    private static void endOnFoot(EnemySprite mob, String result, String notice) {
        World world = world();
        String color = huntedColor();
        hunter = null;
        forgetRoute();
        if (world != null)
            world.setWrathStatus(LOST);
        System.out.println("[TFR-Wrath] duel " + result + " by " + mob.getName() + " - it returns to its castle, no prize");
        if (color != null)
            GameHUD.getInstance().addNotification(TINT + "The Wrath of " + capitalized(color) + " " + notice, true);
    }

    /** EnemySprite.draw: the hunter's plate, every frame (TfrPlates.queueWrath). */
    public static void queuePlate(EnemySprite mob, TfrPlates.WrathPlate plate, float cx, float foot) {
        World world = world();
        String color = huntedColor();
        if (world == null || color == null || mob.getData() == null)
            return;
        int day = world.getCurrentDay();
        TfrPlates.queueWrath(plate, color, mob.getData().tier, World.nextWeekBoundary(day) - day, cx, foot);
    }

    // ---------------------------------------------------------------------------------------------- spawns ahead

    /**
     * BiomeData.getEnemy / DungeonSources.byWeekTier: the week whose bracket a spawn on {@code land} rolls - the first
     * week of the bracket wrathAheadBrackets above this week's when the land is the hunted color's, else {@code week}.
     * {@code dungeonSource} marks DungeonSources' roll (wrathAheadDungeonSources).
     */
    public static int spawnWeek(String land, int week, boolean dungeonSource) {
        String color = huntedColor();
        if (color == null || !color.equals(land))
            return week;
        TuningData t = tuning();
        if (t == null || t.wrathAheadBrackets <= 0 || !onFor(t.wrathAheadOnDifficulty)
                || (dungeonSource && !t.wrathAheadDungeonSources))
            return week;
        SpawnTierWeightData data = Config.instance().getSpawnTierWeightData();
        if (data == null || data.weekBrackets == null || data.weekBrackets.length == 0)
            return week;
        int at = -1;
        for (int i = 0; i < data.weekBrackets.length; i++) {
            SpawnTierWeightData.WeekBracket b = data.weekBrackets[i];
            if (week >= b.weekMin && (b.weekMax == -1 || week <= b.weekMax)) {
                at = i;
                break;
            }
        }
        if (at < 0)
            return week;
        int to = Math.min(data.weekBrackets.length - 1, at + t.wrathAheadBrackets);
        SpawnTierWeightData.WeekBracket bracket = data.weekBrackets[to];
        int ahead = to == at ? week : Math.max(week, bracket.weekMin);
        World world = world();
        if (loggedAhead.add((world == null ? 0L : world.getSeed()) + "|" + land + "|" + week + "|" + dungeonSource)) {
            String source = dungeonSource ? " (its dungeons' roamers)" : "";
            if (to == at)
                System.out.println("[TFR-Wrath] spawn ahead: " + land + " land" + source + " is already at the last bracket"
                        + " (week " + week + ") - no change");
            else
                System.out.println("[TFR-Wrath] spawn ahead: " + land + " land" + source + " rolls week " + ahead
                        + "'s row (bracket " + bracket.weekMin + "-" + (bracket.weekMax == -1 ? "" : bracket.weekMax)
                        + ") in week " + week);
        }
        return ahead;
    }

    // ------------------------------------------------------------------------------------------------- the bounty

    /**
     * DuelScene: a duel ended. {@code counted} is the enemy when the win counts for the Temple tally (the player's own
     * win, no Arena, Inn, fixed-deck or guard fight), else null - which also clears an unused mark from an earlier duel.
     */
    public static void onDuelEnded(EnemySprite counted) {
        if (pendingBounty != null && pendingBounty != counted)
            System.out.println("[TFR-Wrath] bounty for " + pendingBounty.getName() + " dropped - it reached no loot screen");
        pendingBounty = null;
        if (counted == null || counted.getData() == null)
            return;
        String color = huntedColor();
        TuningData t = tuning();
        if (color == null || t == null || !onFor(t.wrathBountyOnDifficulty)
                || (t.wrathBountyGold <= 0f && t.wrathBountyShards <= 0f))
            return;
        String colors = counted.getData().colors == null ? "" : counted.getData().colors.toUpperCase();
        if (colors.contains(letterOf(color)))
            pendingBounty = counted;
    }

    /** WorldStage.setWinner / MapStage.getReward: the won duel's loot, before it shows - raises its gold and shards. */
    public static void applyBounty(Array<Reward> loot, EnemySprite mob) {
        if (pendingBounty == null)
            return;
        if (pendingBounty != mob || loot == null) {
            System.out.println("[TFR-Wrath] bounty for " + pendingBounty.getName() + " dropped - the loot was "
                    + (mob == null ? "nobody's" : mob.getName() + "'s"));
            pendingBounty = null;
            return;
        }
        pendingBounty = null;
        TuningData t = tuning();
        if (t == null)
            return;
        int goldBefore = 0, goldAfter = 0, shardsBefore = 0, shardsAfter = 0;
        for (int i = 0; i < loot.size; i++) {
            Reward r = loot.get(i);
            if (r == null)
                continue;
            if (r.getType() == Reward.Type.Gold && t.wrathBountyGold > 0f && r.getCount() > 0) {
                int raised = raised(r.getCount(), t.wrathBountyGold);
                goldBefore += r.getCount();
                goldAfter += raised;
                loot.set(i, r.isCardFallbackGold() ? Reward.cardFallbackGold(raised) : new Reward(raised));
            } else if (r.getType() == Reward.Type.Shards && t.wrathBountyShards > 0f && r.getCount() > 0) {
                int raised = raised(r.getCount(), t.wrathBountyShards);
                shardsBefore += r.getCount();
                shardsAfter += raised;
                loot.set(i, new Reward(Reward.Type.Shards, raised));
            }
        }
        EnemyData data = mob.getData();
        System.out.println("[TFR-Wrath] bounty: " + mob.getName() + " (" + (data == null ? "?" : data.colors) + ") gold "
                + goldBefore + " -> " + goldAfter + ", shards " + shardsBefore + " -> " + shardsAfter
                + (goldBefore == 0 && shardsBefore == 0 ? " (no gold or shards in this loot)" : ""));
    }

    /** count x (1 + share), rounded up - so any shards gain one at least. */
    private static int raised(int count, float share) {
        return (int) Math.ceil(count * (1f + share) - 0.0001f);
    }

    // ------------------------------------------------------------------------------------------------- the console

    /** The console "wrath": no argument reports; "start red" hunts red this week (needs the Temple, or "start red force");
     *  "stop" ends the hunt. */
    public static String console(String[] args) {
        World world = world();
        if (world == null)
            return "No world.";
        if (!isEnabled())
            return "The Wrath is off on this plane.";
        if (args.length < 1) {
            String color = huntedColor();
            return color == null ? "Nobody is hunted this week (week " + (world.getCurrentWeek() + 1) + ")."
                    : capitalized(color) + " is hunted this week (week " + (world.getCurrentWeek() + 1) + ", status "
                    + world.getWrathStatus() + "). Temple: " + (TempleOfferings.capitolHasTemple() ? "yes" : "no") + ".";
        }
        if ("near".equalsIgnoreCase(args[0]) || "at".equalsIgnoreCase(args[0])) {
            // "wrath near" puts the hunter 12 tiles from the player, "wrath at x,y" on a tile - for the path tests
            if (world.getWrathStatus() != HUNTING || WorldStage.getInstance().getPlayerSprite() == null)
                return "No hunter is out.";
            int ts = world.getTileSize();
            Vector2 p = WorldStage.getInstance().getPlayerSprite().pos();
            int tx = (int) (p.x / ts) + 12, ty = (int) (p.y / ts);
            if ("at".equalsIgnoreCase(args[0])) {
                if (args.length < 2 || !args[1].contains(","))
                    return "wrath at x,y";
                tx = Integer.parseInt(args[1].split(",")[0].trim());
                ty = Integer.parseInt(args[1].split(",")[1].trim());
            }
            int[] spot = WrathPath.nearestOpen(world, tx, ty, 8);
            if (spot == null)
                return "No open tile near " + tx + "," + ty + ".";
            world.setWrathPosition((spot[0] + 0.5f) * ts, (spot[1] + 0.5f) * ts);
            if (hunter != null)
                WorldStage.getInstance().removeWrathHunter(hunter, false);
            hunter = null;
            forgetRoute();
            System.out.println("[TFR-Wrath] console: the hunter moved to (" + spot[0] + "," + spot[1] + ")");
            return "The Wrath is at " + spot[0] + "," + spot[1] + ".";
        }
        if ("stop".equalsIgnoreCase(args[0])) {
            if (hunter != null)
                endHunter("console stop");
            world.setWrathHunter("", 0f);
            world.setWrath(null, -1, NONE);
            System.out.println("[TFR-Wrath] console: the hunt is stopped");
            return "The hunt is stopped.";
        }
        if ("start".equalsIgnoreCase(args[0]) && args.length >= 2) {
            String color = args[1].toLowerCase();
            if (letterOf(color).isEmpty())
                return "Give a color: white, blue, black, red or green.";
            boolean force = args.length >= 3 && "force".equalsIgnoreCase(args[2]);
            if (!force && !TempleOfferings.capitolHasTemple())
                return "The Capitol has no Temple - add \"force\" to hunt anyway.";
            start(color, world.getCurrentWeek(), "(console" + (force ? ", forced" : "") + ")", false);
            return huntedColor() == null ? "Nothing to hunt with on this difficulty - see forge.log [TFR-Wrath]."
                    : capitalized(color) + " is hunted this week.";
        }
        return "wrath | wrath start <color> [force] | wrath near | wrath at x,y | wrath stop";
    }
}
