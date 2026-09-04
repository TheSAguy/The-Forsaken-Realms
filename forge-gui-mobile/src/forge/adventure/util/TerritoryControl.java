package forge.adventure.util;

import com.badlogic.gdx.math.Vector2;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.BiomeData;
import forge.adventure.data.DifficultyData;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.data.WorldData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Dynamic Territory Control (MOD_SCOPE.md #7), first slice: independently for each of the 5 AI
 * colors, every random 2-5 in-game days its castle sends a mage toward one of its 3 nearest
 * still-neutral towns. Reaching the town converts it into a real instance of that color's own
 * town (see PointOfInterest.transformInto()) - not a reskin, a genuinely different POI - so every
 * other system (which map/shops load, whether it still counts as a capture target) just falls out
 * of that swap rather than needing its own tracking here. Losing the mage in combat before it
 * arrives simply prevents the capture - EnemySprite/WorldStage's existing defeat handling already
 * removes it, nothing extra needed on that path.
 */
public class TerritoryControl {
    /** Last [TFR-MageCap] line printed; identical repeats are suppressed (see attacking-mage cap). */
    private static String lastMageCapLine = null;

    // Public: World.java's placement pass (Territory Control #7 v2, spatially-aware) reads this
    // directly, rather than duplicating the list - it and this class must never disagree about
    // which biomes are "AI colors."
    public static final String[] COLORS = {"white", "blue", "black", "red", "green"};
    private static final Map<String, String> COLOR_TOWN_NOUN = new HashMap<>();
    static {
        COLOR_TOWN_NOUN.put("white", "Plains");
        COLOR_TOWN_NOUN.put("blue", "Island");
        COLOR_TOWN_NOUN.put("black", "Swamp");
        COLOR_TOWN_NOUN.put("red", "Mountain");
        COLOR_TOWN_NOUN.put("green", "Forest");
    }

    // Cross-color attack targeting (MOD_SCOPE.md #7, activated 2026-08-10) - the same standard MTG
    // color-pie wheel ColorReputation.java keeps its own copy of (see that class's comment for why
    // it's deliberately duplicated rather than shared: this class must keep working with
    // colorReputationEnabled off, same as that one must keep working with territoryControlEnabled
    // off). A color may only attack its two ENEMIES' towns, never an ally's or its own.
    private static final Map<String, String[]> ALLIES = new HashMap<>();
    private static final Map<String, String[]> ENEMIES = new HashMap<>();
    static {
        ALLIES.put("white", new String[]{"green", "blue"});
        ALLIES.put("blue", new String[]{"white", "black"});
        ALLIES.put("black", new String[]{"blue", "red"});
        ALLIES.put("red", new String[]{"black", "green"});
        ALLIES.put("green", new String[]{"red", "white"});

        ENEMIES.put("white", new String[]{"black", "red"});
        ENEMIES.put("blue", new String[]{"red", "green"});
        ENEMIES.put("black", new String[]{"green", "white"});
        ENEMIES.put("red", new String[]{"white", "blue"});
        ENEMIES.put("green", new String[]{"blue", "black"});
    }

    private static boolean isEnemyColor(String color, String other) {
        String[] enemies = ENEMIES.get(color);
        if (enemies == null || other == null)
            return false;
        for (String enemy : enemies)
            if (enemy.equals(other))
                return true;
        return false;
    }

    // Very-rare War-tier boss encounters (user request 2026-08-10): the 38 boss-flagged Shandalar
    // Old Border imports never got a dungeon home built for them (24 of their 34 source files
    // collide by name with content already imported elsewhere, and 9 are mid-chain rooms needing
    // their own preceding levels - a real, separately-scoped task, not attempted here). Surfaced
    // instead as an extremely rare roaming encounter, gated on the player being genuinely At War
    // with that boss's color - keyed by hand from each boss's real `colors` tag (a multicolor boss
    // appears under every color it contains, same "contains" convention the roaming-pool wiring
    // fix already used). Renamed entries reflect the cross-plane collision fixes from that same
    // round (e.g. "Karona (Boss)", not "Karona" - that bare name is Realm of Legends' own,
    // unrelated, non-boss version).
    private static final Map<String, String[]> WAR_TIER_BOSSES = new HashMap<>();
    static {
        WAR_TIER_BOSSES.put("white", new String[]{
                "Karona (Boss)", "Sorceress Queen Kaja", "King Kane Ferguson", "Elf Queen Guay",
                "The Sainted One", "Arzakon, Shandalar's Doom", "Baron Von Gant", "Baron Levilain",
                "Dark Ages Preacher", "Serra the Benevolent", "Bazaar Keeper", "Arcades Sabboth",
                "Chromium (Boss)", "Palladia-Mors (Boss)"});
        WAR_TIER_BOSSES.put("blue", new String[]{
                "Karona (Boss)", "Sorceress Queen Kaja", "Goblin King Phil", "King Kane Ferguson",
                "Elf Queen Guay", "The Astral Visionary", "Arzakon, Shandalar's Doom",
                "Urza Planeswalker", "Recaller of Ancestry", "Twister of Time", "Time Walker",
                "Bazaar Keeper", "Arcades Sabboth", "Chromium (Boss)", "Nicol Bolas (Boss)"});
        WAR_TIER_BOSSES.put("black", new String[]{
                "Karona (Boss)", "King Rohgahh", "Goblin King Phil", "King Kane Ferguson",
                "Valyx the Tormentor", "The Lichlord of Azar", "Arzakon, Shandalar's Doom",
                "Tibalt's Torturer", "Uncle Istvan", "Swamp Queen Tojira", "Chainer Dementia Master",
                "Cateran Overlord", "Twister of Time", "Bazaar Keeper", "Chromium (Boss)",
                "Nicol Bolas (Boss)", "Vaevictis Asmadi"});
        WAR_TIER_BOSSES.put("red", new String[]{
                "Karona (Boss)", "King Rohgahh", "Sorceress Queen Kaja", "Goblin King Phil",
                "King Kane Ferguson", "The Dragon Lord", "Arzakon, Shandalar's Doom",
                "Slivdrazi Monstrosity", "Tibalt's Torturer", "Chandler", "Joven", "Bazaar Keeper",
                "Nicol Bolas (Boss)", "Palladia-Mors (Boss)", "Vaevictis Asmadi"});
        WAR_TIER_BOSSES.put("green", new String[]{
                "Karona (Boss)", "King Kane Ferguson", "Elf Queen Guay", "The Great Druid",
                "Arzakon, Shandalar's Doom", "Gorilla Chief", "Slivdrazi Monstrosity",
                "Kogla (Boss)", "Gaea, the Worldsoul", "Recaller of Ancestry", "Bazaar Keeper",
                "Arcades Sabboth", "Palladia-Mors (Boss)", "Vaevictis Asmadi"});
    }

    // Base chance a WAR_TIER_BOSSES roll fires at all, checked by the caller only once it's
    // already confirmed War-tier standing with the roll's color - "very rare," per the user's own
    // words, layered on top of an already-rare condition (War tier itself, and whatever chance
    // brought this spawn roll to that color's territory in the first place).
    public static final float WAR_TIER_BOSS_CHANCE = 0.04f;

    /** A random War-tier boss for this color, or null if the color has none or the roll misses. */
    public static EnemyData rollWarTierBoss(String color, Random rand) {
        String[] pool = WAR_TIER_BOSSES.get(color);
        if (pool == null || pool.length == 0 || rand.nextFloat() >= WAR_TIER_BOSS_CHANCE)
            return null;
        return WorldData.getEnemy(pool[rand.nextInt(pool.length)]);
    }

    private static final int MIN_ATTACK_DAYS = 2;
    private static final int MAX_ATTACK_DAYS = 5;
    // 5 nearest neutral towns measured from ANY of the color's owned properties (castle + its
    // towns/capitals), per user request 2026-08-08 - was 3 nearest from the castle alone, which
    // meant a color's expansion frontier never widened as it captured towns.
    private static final int NEAREST_CANDIDATES = 5;
    // Public for the same reason CASTLE_KEEP_RADIUS_TILES is: World.claimWastelandRing() caps a
    // captured town's protection against AI expansion to this same radius, so it never protects a
    // larger area than repaintBiomeAroundTown() actually paints - the two must always agree, or a
    // captured town would end up guarding an invisible ring of plain-looking ground beyond its own
    // visibly-recolored area (a real, reported mismatch: this used to be capped to
    // CASTLE_KEEP_RADIUS_TILES, twice this value).
    public static final int RECOLOR_RADIUS = 10;
    // Public for the same reason COLORS is: World.java's placement pass must use the exact same
    // radius this class later uses to flip biomeMap ownership outside it, or content and ownership
    // would disagree at the boundary - see World.java's placement pass and
    // neutralizeTerritoryOutsideRadius() for why that's a real rendering bug, not just cosmetic.
    public static final int CASTLE_KEEP_RADIUS_TILES = 20; // first-guess constant, tune after testing - also the starting radius territory expansion grows from
    // Weighted-pull expansion model (2026-08-08 user redesign): a faction's pull on a tile is
    // min over its sources of dist*weight - lower weight projects further. A castle out-pulls a
    // capital, a capital out-pulls a captured town, and any forward holding bends the border
    // outward around itself. Spawn projects nothing at all anymore (its old protection bubble -
    // even the bounded one - left an unclaimable circle around the central teleporter; user:
    // "should be okay to cover").
    private static final float CASTLE_PULL_WEIGHT = 1.0f;
    private static final float CAPITAL_PULL_WEIGHT = 1.15f;
    private static final float TOWN_PULL_WEIGHT = 1.3f;
    private static final float PLAYER_TOWN_PULL_WEIGHT = 1.0f; // the player's few towns hold their ground like castles
    // AI castle strength buff (user request 2026-08-13: "increase the strength of the 5 AI
    // castles... a town very near black's castle should have pushed my territory away more").
    // Two levers, both applying to the FIVE AI CASTLES ONLY (the player Capitol keeps plain
    // castle-grade values - buffing the player too would cancel the intended asymmetry), both
    // consumed only by buildPullSources()'s castle source -> claimWastelandRing() daily
    // expansion/re-contest. (An earlier version of this fix ALSO skipped these tiles inside
    // repaintBiomeAroundTown()'s one-time town-capture/restore paint - reverted, adversarial
    // review 2026-08-13: that recorded a town's full territory radius via setTownTerritoryRadius()
    // BEFORE the skip-aware repaint ran, so a town captured/restored within ~22 tiles of a rival
    // castle got 0% of its ground actually painted while vision/fog/hard-protection all believed
    // the full disc was claimed - permanently, with no self-heal. claimWastelandRing() already
    // fully covers "the castle contests this ground" going forward via the castle's own
    // hard-protect radius below, which blocks every OTHER color from claiming there but never the
    // castle's own color - so a freshly captured/restored town's ground can still be immediately
    // recontested and won back by the castle on the very next sourcesChanged re-scan if its pull
    // is genuinely stronger there, without needing a second, buggy protection layer.):
    // - AI_CASTLE_PULL_WEIGHT < CASTLE_PULL_WEIGHT: an AI castle now out-pulls anything else at
    //   equal distance, bowing contested borders away from itself instead of settling on the
    //   plain perpendicular bisector (weight 1.0 vs 1.0 gave the castle zero push advantage
    //   against a player town - the exact reported symptom).
    // - AI_CASTLE_EXCLUSION_RADIUS_TILES > CASTLE_KEEP_RADIUS_TILES: rivals can't claim ANY tile
    //   within this radius of an AI castle (hard protection), while the castle's own color still
    //   claims there freely. Deliberately a separate constant - CASTLE_KEEP_RADIUS_TILES itself
    //   is load-bearing for world-gen placement/neutralize sweeps and must stay 20.
    private static final float AI_CASTLE_PULL_WEIGHT = 0.85f;
    private static final int AI_CASTLE_EXCLUSION_RADIUS_TILES = 32;
    // Territory pacing (2026-08-14, moved to the new tuning.json - see TuningData.java): all 5 of
    // these used to be hardcoded static final constants (3 -> 9 tiles/day per user 2026-08-08,
    // later split into separate Capitol/town/AI-castle rates 2026-08-14, with AI castles left at
    // the flat 9/day testing pace "not requested to change" at the time). The user has since
    // confirmed via real playtest log data that 9/day was still active for AI castles and asked
    // for 1/day max - now user-tunable via Config.instance().getTuningData() instead of requiring
    // a code change for every future rebalance. Each accessor is a thin wrapper so every existing
    // call site below reads exactly like it did as a constant.
    private static int expansionTilesPerDay() {
        return Config.instance().getTuningData().aiCastleExpansionTilesPerDay;
    }
    private static int capitolExpansionTilesPerDay() {
        return Config.instance().getTuningData().capitolExpansionTilesPerDay;
    }
    public static int maxTerritoryRadius() {
        return Config.instance().getTuningData().maxTerritoryRadius;
    }
    // Captured towns grow their own small territory too (user request 2026-08-08: "for captured
    // towns, let's have them expand to 15", raised +5 to 20 per user request 2026-08-14) - from
    // RECOLOR_RADIUS at capture up to this. Per-town current radius lives in
    // World.townTerritoryRadius, seeded at capture (onMageArrived() for AI, TownRestoration's
    // restore path for the player). A planned "outlook" building will later raise this further
    // per town.
    public static int townMaxTerritoryRadius() {
        return Config.instance().getTuningData().townMaxTerritoryRadius;
    }
    // Cap on the INPUT to the protected-core formula below (`radius / 2`), separate from the
    // growth cap above (2026-08-24 user spec) - lets townMaxTerritoryRadius grow the outer
    // territory disc further without also growing the inviolable core rivals can never touch.
    public static int townProtectedRadiusCap() {
        return Config.instance().getTuningData().townProtectedRadiusCap;
    }
    // Town growth pacing: 1 tile per N days (N tunable, was a hardcoded 7), down from the
    // 9-tiles/day rate towns previously shared with AI castles/the Capitol. A per-day rate can't
    // express "1 tile per week" as a whole number, so town growth tracks each town's own last-grew
    // day (World.townLastGrowthDay) instead of a flat per-tick multiply - see its use below.
    private static int capitolTargetCooldownDays() {
        return Config.instance().getTuningData().capitolTargetCooldownDays;
    }

    /**
     * May this color aim a mage at the player's Capitol today? (user spec 2026-08-31.)
     * <p>
     * Rolling window - "at least 7 days" - matching PointOfInterestChanges.canManuallyRerollShop()
     * rather than the calendar-week idiom rerollSurcharge() uses, under which a day-6 hit could be
     * followed by a day-8 hit and the player would feel two attacks in three days.
     */
    private static boolean capitolOffCooldown(World world, String color) {
        int cooldown = capitolTargetCooldownDays();
        if (cooldown <= 0)
            return true;
        Integer last = world.getCapitolTargetedDay(color);
        return last == null || world.getCurrentDay() - last >= cooldown;
    }

    private static int townExpansionDaysPerTile() {
        return Config.instance().getTuningData().townExpansionDaysPerTile;
    }

    // Roaming-spawn intrusion radius (user request 2026-08-10: "if a colored city is in the area,
    // that color might spawn in a certain radius"). Deliberately larger than CASTLE_KEEP_RADIUS_TILES
    // - a border town/capital should already start bleeding its color's monsters into the
    // surrounding land before the player is technically standing inside that color's own claimed
    // territory, not only once they cross the line.
    public static final int SPAWN_INTRUSION_RADIUS_TILES = 40;

    private TerritoryControl() {}

    /**
     * Called once from World.generateNew(), after world-gen has run to completion. By this point
     * every AI color's territory content is already correct - generateNew()'s own placement pass
     * (Territory Control #7 v2, spatially-aware) computed each tile using that color's real content
     * within CASTLE_KEEP_RADIUS_TILES of its real, already-placed castle, and colorless's own
     * content everywhere else in that color's claim, natively, the first time - no post-hoc
     * reskinning or reconstruction needed for ground content (unlike the whole-biome-swap approach
     * this replaced, which needed a two-pass sweep/restore/reclaim and still came out visibly less
     * dense inside the kept circle - see MOD_CHANGELOG.md). What's left here is ownership and POIs:
     * <ul>
     * <li>World.neutralizeTerritoryOutsideRadius() flips biomeMap's ownership bit from this color
     * to colorless outside CASTLE_KEEP_RADIUS_TILES of its real castle - content there is already
     * colorless-native, so this only touches biomeMap, the minimap pixel, and fog-of-war, not
     * terrainMap.</li>
     * <li>Any of that color's own out-of-radius Town/Capital POIs convert to their Waste equivalent
     * (PointOfInterest.transformInto(), the same mechanism a live capture uses, just run in reverse
     * and in bulk here).</li>
     * <li>ensureCapital() guarantees a capital survives inside the kept circle, and
     * setColorTerritoryRadius() seeds the radius daily territory expansion (processTerritoryExpansion()
     * below) grows from.</li>
     * </ul>
     * Deliberately leaves every *other* POI type (dungeons, caves, forts, boss encounters) exactly
     * where world-gen put them, keeping their original color-flavored identity - only towns/
     * capitals get swept, matching the request precisely and preserving content (e.g. Planeswalker
     * side-bosses) that an earlier, since-reverted approach was deleting outright.
     */
    public static void neutralizeAfterGeneration(World world) {
        if (!isEnabled())
            return;

        float keepRadiusWorld = CASTLE_KEEP_RADIUS_TILES * (float) world.getTileSize();

        for (String color : COLORS) {
            PointOfInterest castle = findCastle(world, color);
            if (castle == null) {
                System.out.println("[TerritoryControl] " + color + ": no castle found, skipping");
                continue;
            }
            Vector2 castlePosition = castle.getPosition();
            world.neutralizeTerritoryOutsideRadius(color, castlePosition, CASTLE_KEEP_RADIUS_TILES, null, null);

            int converted = 0;
            for (PointOfInterest poi : new ArrayList<>(world.getAllPointOfInterest())) {
                if (!isColorTownOrCapital(poi.getData(), color))
                    continue;
                if (poi.getPosition().dst(castlePosition) <= keepRadiusWorld)
                    continue;
                PointOfInterestData wasteData = matchingWasteData(poi.getData(), color);
                if (wasteData == null)
                    continue;
                poi.transformInto(wasteData, world.getRandom(), true); // keep the town's given name through the sweep
                converted++;
            }
            System.out.println("[TerritoryControl] " + color + ": neutralized territory outside castle, converted " + converted + " town(s) to neutral");

            ensureCapital(world, color, castle, keepRadiusWorld);
            world.setColorTerritoryRadius(color, CASTLE_KEEP_RADIUS_TILES);
        }
        // The player does NOT get a free starting circle here - per explicit user correction,
        // "the player should only start once he takes his first city." Spawn still participates
        // as a permanent rival anchor inside World.claimWastelandRing() itself (unconditional,
        // not tied to this method), which stops AI colors from claiming right up to Spawn - it
        // just never gets *painted* player-color until an actual town capture does that.

        // Doodad placement (generateNew()'s own "distribute small rocks and trees" pass, well
        // before this method runs) reads each tile's spriteNames catalog live, based on whichever
        // biome currently owns it - since that runs before the biomeMap bit-flip above, every tile
        // a color originally claimed (including what's now outside its kept circle) got doodads
        // from that color's own real catalog, not colorless's. This full-map call fixes that
        // mismatch for every tile that's now colorless - genuinely load-bearing under this
        // redesign (unlike doodads inside the kept circle, never touched, always correct, or ground
        // content, already spatially-aware from generateNew()'s own placement pass). See World.
        // regenerateDoodadsForBiome()'s own comment.
        world.regenerateDoodadsForBiome("waste");
    }

    /**
     * Load-time repair (2026-08-08): a world generated before the placement safeguards can be
     * missing a color's capital outright (twice observed: White). Rather than force a world
     * regeneration, re-run the same ensureCapital() promotion/placement pass on load - idempotent
     * (returns immediately for every color whose capital exists), inert when the feature flag is
     * off, and both of its repair paths (transformInto(), addPointOfInterestNear()) are already
     * exercised at runtime by mage captures and dungeon rotation respectively.
     */
    public static void repairMissingCapitals(World world) {
        if (!isEnabled())
            return;
        float keepRadiusWorld = CASTLE_KEEP_RADIUS_TILES * (float) world.getTileSize();
        for (String color : COLORS) {
            // Color Defeat (2026-08-14): a real, blocking bug caught by adversarial review - this
            // method runs unconditionally on EVERY load, so without this skip it would resurrect a
            // fresh capital next to a defeated color's still-standing castle every single time the
            // player reloads their save, directly undoing defeatColor()'s terrain sweep (which
            // deliberately leaves the castle itself in place as the sweep's own anchor tile,
            // meaning findCastle() below keeps succeeding for a defeated color forever).
            if (world.isColorDefeated(color))
                continue;
            PointOfInterest castle = findCastle(world, color);
            if (castle == null)
                continue; // no castle at all - nothing sane to anchor a capital to
            ensureCapital(world, color, castle, keepRadiusWorld);
        }
        // A Capitol upgraded before the migration set economyBuilt_<type> flags could offer
        // duplicate economy buildings - backfill the flags from the type->objectId map.
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (!TownRestoration.CAPITOL_POI_NAME.equals(poi.getData().name))
                continue;
            forge.adventure.pointofintrest.PointOfInterestChanges changes =
                    WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
            if (changes == null)
                continue;
            for (int type : changes.getEconomyBuildingObjectIds().keySet())
                changes.getMapFlags().putIfAbsent("economyBuilt_" + type, (byte) 1);
        }
    }

    // A color's own "<Noun> Capital" is placed by ordinary world-gen (same as any other town)
    // somewhere across its *original*, full-size territory - it's not guaranteed to land inside
    // the small area kept around the castle above, and gets swept to neutral just like any other
    // out-of-radius town if it doesn't. Rather than leave a color's kept territory without one,
    // promote the nearest surviving in-radius town to fill the role instead - per user request,
    // every color's small starting area should have a capital.
    private static void ensureCapital(World world, String color, PointOfInterest castle, float keepRadiusWorld) {
        String noun = COLOR_TOWN_NOUN.get(color);
        if (noun == null)
            return;
        String capitalName = noun + " Capital";
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (capitalName.equals(poi.getData().name))
                return; // already has one (survived the sweep, or was already within radius)
        }
        PointOfInterestData capitalData = PointOfInterestData.getPointOfInterest(capitalName);
        if (capitalData == null)
            return;

        PointOfInterest nearestTown = null;
        float nearestDist = Float.MAX_VALUE;
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (!isColorTownOrCapital(poi.getData(), color))
                continue;
            float dist = poi.getPosition().dst(castle.getPosition());
            if (dist <= keepRadiusWorld && dist < nearestDist) {
                nearestDist = dist;
                nearestTown = poi;
            }
        }
        if (nearestTown == null) {
            // Fallback (2026-08-08, after a generated world shipped with no White capital at
            // all): no town survived inside the keep radius, so there is nothing to promote -
            // place a brand-new capital POI near the castle instead. Every color's starting
            // area gets a capital, unconditionally.
            PointOfInterest placed = world.addPointOfInterestNear(capitalData, castle.getPosition(),
                    5, CASTLE_KEEP_RADIUS_TILES - 2);
            if (placed != null)
                System.out.println("[TerritoryControl] " + color + ": no in-radius town to promote - placed a fresh " + capitalName);
            else
                System.out.println("[TerritoryControl] CRITICAL: " + color + ": could not promote OR place a " + capitalName);
            return;
        }
        nearestTown.transformInto(capitalData, world.getRandom());
        System.out.println("[TerritoryControl] " + color + ": promoted a town to " + capitalName);
    }

    private static boolean isEnabled() {
        ConfigData configData = Config.instance().getConfigData();
        return configData != null && configData.territoryControlEnabled;
    }

    /** Called from WorldStage.onActing() whenever the in-game day counter advances. */
    public static void processDaysPassed(int daysPassed, int newDayCount) {
        updateAiTownGuardLevels(WorldSave.getCurrentSave().getWorld(), newDayCount); // AI guard dots (#87)
        if (daysPassed <= 0 || !isEnabled())
            return;
        World world = WorldSave.getCurrentSave().getWorld();
        for (String color : COLORS) {
            // Color Defeat (2026-08-14): a defeated color never attacks again - no timer upkeep,
            // no dispatch, nothing. buildPullSources() below has its own equivalent skip so a
            // defeated color's castle also stops contesting territory expansion.
            if (world.isColorDefeated(color))
                continue;
            Integer next = world.getColorNextAttackDay(color);
            if (next == null) {
                // First time this color's timer is touched (fresh world, or a save predating this
                // feature) - seed it rather than attacking immediately.
                int seeded = newDayCount + randomAttackDelay(world);
                world.setColorNextAttackDay(color, seeded);
                System.out.println("[TerritoryControl] " + color + ": timer seeded, next attack on day " + seeded);
                continue;
            }
            if (newDayCount >= next) {
                dispatch(world, color);
                world.setColorNextAttackDay(color, newDayCount + randomAttackDelay(world));
            }
        }
        processTerritoryExpansion(world, daysPassed);
    }

    private static int randomAttackDelay(World world) {
        return MIN_ATTACK_DAYS + world.getRandom().nextInt(MAX_ATTACK_DAYS - MIN_ATTACK_DAYS + 1);
    }

    // Last tick's pull-source fingerprint, PER OWNER (2026-08-26 perf fix - user report: a
    // day-end stutter that "kills the game" and gets worse over time, still present after two
    // earlier fixes. Root cause: this used to be ONE global fingerprint hashing ALL 6 owners'
    // (5 colors + player) combined sources together (see the caching comment in
    // processTerritoryExpansion) - so ANY town anywhere growing, which happens almost every day
    // once a game has more than a handful of active towns, invalidated the cache for EVERY owner
    // simultaneously, forcing a full O(radius^2) re-contest for all 6 owners every day, with
    // radius (and therefore cost) growing throughout the game. Confirmed via forge.log: 100% of
    // the last 20 daily territory ticks in a real session were "(full re-contest)" by radius ~65,
    // ~79,000 tile evaluations per day-tick and climbing. Tracking one fingerprint PER OWNER
    // instead means a change to one color's own towns only forces a re-contest for THAT color -
    // the other owners' borders are unaffected by it and correctly take the cheap "just the new
    // ring" path (used whenever an owner's own radius is still growing but its own sources
    // didn't change). Trade-off: an owner can miss an opportunistic reclaim from a rival's pull
    // weakening (e.g. a rival town's protection shrinking) on the day it happens - normally
    // self-corrects the next time that owner's OWN sources change (roughly every
    // townExpansionDaysPerTile() days per town it actually owns). A defeated color's territory is
    // swept to colorless immediately by defeatColor() itself, not dependent on any other color's
    // re-contest, so that specific case needs no special handling here.
    //
    // Bounded-staleness safety net (2026-08-26 review finding): the "self-corrects" claim above
    // breaks down for an owner that genuinely STALLS - every town it holds capped at
    // townMaxTerritoryRadius() and no new captures - since its own fingerprint then never changes
    // again, meaning it could otherwise NEVER re-contest a border tile against a rival that later
    // weakens, indefinitely. sourcesChangedFor() also forces one full re-contest per owner every
    // FORCE_RECONTEST_INTERVAL_DAYS regardless of its own fingerprint, capping the staleness at
    // that many days for a stalled owner instead of "forever," while still cutting the original
    // bug's near-100%-of-days full-recontest rate by roughly that same factor for an active one.
    private static final int FORCE_RECONTEST_INTERVAL_DAYS = 30;
    private static final Map<String, Long> lastPullSourcesFingerprint = new HashMap<>();
    private static final Map<String, Integer> lastFullRecontestDay = new HashMap<>();

    private static long pullSourcesFingerprint(List<float[]> source) {
        long hash = 17;
        for (float[] entry : source)
            for (float component : entry)
                hash = hash * 31 + Float.floatToIntBits(component);
        return hash;
    }

    // Stable per-owner phase offset so the 6 owners' periodic forced re-contests never all land
    // on the same day (2026-08-26): every owner's timer starts on the same first tick of a
    // session, so without an offset, day N+30 would run SIX full-disc re-contests in one frame -
    // a bigger one-day spike than the daily cost this whole caching layer exists to avoid.
    private static final String[] FORCED_RECONTEST_OWNER_ORDER = {"white", "blue", "black", "red", "green", "player"};

    private static int forcedRecontestStagger(String owner) {
        for (int i = 0; i < FORCED_RECONTEST_OWNER_ORDER.length; i++)
            if (FORCED_RECONTEST_OWNER_ORDER[i].equalsIgnoreCase(owner))
                return i * (FORCE_RECONTEST_INTERVAL_DAYS / FORCED_RECONTEST_OWNER_ORDER.length);
        return 0;
    }

    // True the first time this owner's fingerprint is computed (forces one full re-contest, same
    // as the old code's session-start behavior), whenever it actually changed since last tick, or
    // whenever this owner is overdue for its periodic forced re-contest (see the staleness
    // comment above).
    private static boolean sourcesChangedFor(String owner, Map<String, List<float[]>> pullSources, int currentDay) {
        long fingerprint = pullSourcesFingerprint(pullSources.get(owner));
        Long previousFingerprint = lastPullSourcesFingerprint.put(owner, fingerprint);
        boolean changed = previousFingerprint == null || previousFingerprint != fingerprint;
        Integer previousRecontestDay = lastFullRecontestDay.get(owner);
        boolean overdue = previousRecontestDay == null || currentDay - previousRecontestDay >= FORCE_RECONTEST_INTERVAL_DAYS;
        if (changed || overdue) {
            // First-seen initialization back-dates the timer by the owner's stagger offset so the
            // NEXT forced re-contest (and every one after) lands on that owner's own phase of the
            // 30-day cycle rather than all six owners sharing one.
            lastFullRecontestDay.put(owner, previousRecontestDay == null
                    ? currentDay - forcedRecontestStagger(owner) : currentDay);
            return true;
        }
        return false;
    }

    /**
     * Every faction's influence sources for World.claimWastelandRing()'s weighted-pull model,
     * keyed by color name plus "player". Each source: {tileX, tileY, weightMultiplier,
     * hardProtectRadiusTiles}. Castles pull strongest and keep their whole keep inviolable;
     * capitals and captured towns pull progressively weaker but bend the border outward around
     * themselves; every town's hard protection is HALF its current territory radius (user rule:
     * "a town can lose up to 50% of the territory around them" - never more).
     */
    private static Map<String, List<float[]>> buildPullSources(World world, Map<String, Vector2> castlePositions,
                                                               List<PointOfInterest> playerTowns) {
        float tileSize = world.getTileSize();
        Map<String, List<float[]>> sources = new LinkedHashMap<>();
        for (String color : COLORS) {
            List<float[]> list = new ArrayList<>();
            // Color Defeat (2026-08-14): a defeated color contests no territory at all - its
            // terrain was already fully swept to colorless by defeatColor(), and any towns/
            // capitals it still nominally "owns" per isColorTownOrCapital() (shouldn't exist post-
            // sweep, but defensive) shouldn't re-project a pull source either. Empty list, not a
            // missing key - keeps `sources` structurally consistent for every caller that assumes
            // every COLORS entry is present.
            if (world.isColorDefeated(color)) {
                sources.put(color, list);
                continue;
            }
            Vector2 castle = castlePositions.get(color);
            // AI castles get the buffed pull weight and the wider exclusion ring (2026-08-13,
            // see the constants' own comment) - the player Capitol below stays castle-grade.
            if (castle != null)
                list.add(new float[]{castle.x / tileSize, castle.y / tileSize, AI_CASTLE_PULL_WEIGHT, AI_CASTLE_EXCLUSION_RADIUS_TILES});
            for (PointOfInterest poi : world.getAllPointOfInterest()) {
                if (!isColorTownOrCapital(poi.getData(), color) || playerTowns.contains(poi))
                    continue;
                Integer radius = world.getTownTerritoryRadius(poi.getID());
                int protect = Math.max(1, Math.min(radius != null ? radius : RECOLOR_RADIUS, townProtectedRadiusCap()) / 2);
                boolean isCapital = poi.getData().name != null && poi.getData().name.endsWith("Capital");
                list.add(new float[]{poi.getPosition().x / tileSize, poi.getPosition().y / tileSize,
                        isCapital ? CAPITAL_PULL_WEIGHT : TOWN_PULL_WEIGHT, protect});
            }
            sources.put(color, list);
        }
        List<float[]> playerList = new ArrayList<>();
        for (PointOfInterest poi : playerTowns) {
            // The Capitol is the player's castle: castle-grade pull and a full inviolable keep,
            // exactly like the five AI castles (2026-08-08 late, "his terrain should also
            // spread, just like the AI's").
            if (TownRestoration.CAPITOL_POI_NAME.equals(poi.getData().name)) {
                playerList.add(new float[]{poi.getPosition().x / tileSize, poi.getPosition().y / tileSize,
                        CASTLE_PULL_WEIGHT, CASTLE_KEEP_RADIUS_TILES});
                continue;
            }
            Integer radius = world.getTownTerritoryRadius(poi.getID());
            int protect = Math.max(1, Math.min(radius != null ? radius : RECOLOR_RADIUS, townProtectedRadiusCap()) / 2);
            playerList.add(new float[]{poi.getPosition().x / tileSize, poi.getPosition().y / tileSize,
                    PLAYER_TOWN_PULL_WEIGHT, protect});
        }
        sources.put("player", playerList);
        return sources;
    }

    // Each color's circle slowly grows from its castle, claiming only currently-neutral wasteland
    // where its own castle is the *nearest* anchor among every other color's castle, the player's
    // Spawn, and every town the player currently owns (World.claimWastelandRing()'s nearest-anchor
    // check) - this is what keeps two colors' circles (or a color and the player's territory)
    // forming a clean border instead of overlapping or cutting a stray wedge through each other.
    // Every player-owned town counts, not just Spawn, per explicit user request - a color has one
    // fixed castle, but the player can end up owning several towns scattered across the map, and
    // only protecting Spawn let AI expansion grow right up against (and visually read as "creeping
    // over") a town the player had captured elsewhere, previously flagged as a known, deliberately
    // deferred gap (see MOD_CHANGELOG.md). A color with no surviving castle (shouldn't normally
    // happen post-neutralizeAfterGeneration, but a save could predate this feature) or no seeded
    // radius is skipped rather than guessed at.
    private static void processTerritoryExpansion(World world, int daysPassed) {
        Map<String, Vector2> castlePositions = new LinkedHashMap<>();
        for (String color : COLORS) {
            PointOfInterest castle = findCastle(world, color);
            if (castle != null)
                castlePositions.put(color, castle.getPosition());
        }
        // Same "is this town actually player-owned" check WorldStandingsScene's town count already
        // uses (TerritoryControl.getTownCounts()) - a town keeps its own name/color after the
        // player restores it (see TownRestoration.java), so this is the only reliable way to tell
        // "the player owns this one" apart from "this happens to still be a Waste Town" or "this
        // happens to already be some AI color's."
        List<PointOfInterest> playerTowns = new ArrayList<>();
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            // peek, not get - this loop queries EVERY POI on the map once per in-game day, and the
            // get-or-create accessor would materialize an empty PointOfInterestChanges entry for
            // each one, permanently bloating the save file for a pure read.
            if (TownRestoration.isTownRestored(WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID())))
                playerTowns.add(poi);
        }
        // Diagnostic only (MOD_SCOPE.md #7) - no way to otherwise tell from forge.log whether this
        // is finding the player's town(s) at all, given a report that AI expansion was still
        // visibly encroaching after this fix shipped.
        if (!playerTowns.isEmpty())
            System.out.println("[TerritoryControl] daily expansion: " + playerTowns.size() + " player-owned town(s) projecting pull");
        Map<String, List<float[]>> pullSources = buildPullSources(world, castlePositions, playerTowns);
        // Captured towns grow their own small territory, RECOLOR_RADIUS -> TOWN_MAX_TERRITORY_RADIUS
        // (user request 2026-08-08). Two kinds, same mechanism: player-restored towns claim as
        // "player", AI-captured towns (seeded into townTerritoryRadius by onMageArrived()) claim as
        // their own color. A town with no radius entry and no player owner never expands - that's a
        // world-gen original inside its color's castle circle, covered by the castle's own growth.
        // Towns grow BEFORE the castle loop below (pre-commit review finding): a castle sweeping
        // past a still-growing town the same day used to preempt the town's growth band - the
        // town's protection cap only covers its CURRENT radius, so the castle claimed the ring the
        // town was about to grow into, even where the town was strictly the nearer anchor.
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (TownRestoration.CAPITOL_POI_NAME.equals(poi.getData().name))
                continue; // the Capitol expands castle-style below, not through town growth
            boolean playerOwned = playerTowns.contains(poi);
            Integer townRadius = world.getTownTerritoryRadius(poi.getID());
            if (townRadius == null) {
                if (!playerOwned)
                    continue;
                townRadius = RECOLOR_RADIUS; // restored before per-town radius state existed - seed now
                world.setTownTerritoryRadius(poi.getID(), townRadius);
            }
            if (townRadius >= townMaxTerritoryRadius())
                continue;
            String ownerColor = null;
            if (playerOwned) {
                ownerColor = "player";
            } else {
                for (String color : COLORS) {
                    if (isColorTownOrCapital(poi.getData(), color)) {
                        ownerColor = color;
                        break;
                    }
                }
            }
            if (ownerColor == null)
                continue; // stale entry (e.g. the town was captured again under a new id) - skip
            // 1 tile / TOWN_EXPANSION_DAYS_PER_TILE days (2026-08-14 user spec), not a flat
            // per-day multiply - see World.getTownLastGrowthDay()'s own comment. First sight of
            // this town seeds "last grew today" rather than retroactively crediting elapsed days.
            int currentDay = world.getCurrentDay();
            Integer lastGrowthDay = world.getTownLastGrowthDay(poi.getID());
            if (lastGrowthDay == null) {
                lastGrowthDay = currentDay;
                world.setTownLastGrowthDay(poi.getID(), lastGrowthDay);
            }
            int tilesEarned = (currentDay - lastGrowthDay) / townExpansionDaysPerTile();
            if (tilesEarned <= 0)
                continue; // hasn't been a full week since this town's last growth tick
            int newTownRadius = Math.min(townRadius + tilesEarned, townMaxTerritoryRadius());
            // Radius + fog-of-war Revealed cache advance BEFORE the claim, so the claim's own
            // per-tile chunk re-bakes see the grown vision area (order-bug finding)...
            world.setTownTerritoryRadius(poi.getID(), newTownRadius);
            if (playerOwned)
                world.rebuildPlayerTownVision();
            int claimed = world.claimWastelandRing(ownerColor, poi.getPosition(), pullSources,
                    townRadius, newTownRadius,
                    WorldStage.getInstance()::refreshBackgroundTile,
                    WorldStage.getInstance()::reloadBackgroundChunkObjects);
            if (claimed > 0) {
                // Only spend the earned week(s) on an actual successful claim - a blocked attempt
                // (below) keeps its earned tile(s) banked and retries next tick, same spirit as
                // the per-day mechanism never permanently losing progress to a temporary block.
                world.setTownLastGrowthDay(poi.getID(), lastGrowthDay + tilesEarned * townExpansionDaysPerTile());
            } else {
                // REVERTED when the ring took no ground at all (fully blocked by an AI
                // color / rivals): advancing anyway would grow the town's protection cap and its
                // revealed circle over ground it visibly does not hold - the exact
                // "protection wider than visible ground" mismatch class already caught once.
                world.setTownTerritoryRadius(poi.getID(), townRadius);
                if (playerOwned)
                    world.rebuildPlayerTownVision();
                continue;
            }
            if (playerOwned) {
                // The grown ring is the player's own held ground now - mark it explored so it
                // doesn't sit under black fog (revealArea() no-ops for already-explored tiles and
                // when fog of war is off).
                // FoW Stage-3 reveal gap fix (2026-08-13, user report - player standing on owned
                // land still rendered Stage-1 black): this used to reveal only the raw territory
                // radius (newTownRadius), NOT the actual (Outlook-aware, up to 2x) vision circle
                // rebuildPlayerTownVision() just cached above - so a town with an Outlook had a
                // "Persistently Revealed"/Stage-3-eligible ring the fog-explored[][] array never
                // actually got marked explored for, permanently rendering Stage-1 black past the
                // raw radius (only the player's own live-vision sweep walking directly over those
                // tiles would ever clear them). Use the same Outlook-aware radius every other
                // reveal call site uses (EconomyBuildings.onOutlookChanged(),
                // TownRestoration.applyTownVisionReveal(), called directly here rather than
                // duplicated) instead of the raw growth radius.
                PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
                TownRestoration.applyTownVisionReveal(world, poi, changes);
            }
        }
        // Rebuild sources with the towns' POST-growth radii (their 50% hard-protection tracks it).
        pullSources = buildPullSources(world, castlePositions, playerTowns);

        // Caching layer (user report 2026-08-08: "day ticks at 100x started feeling choppy"):
        // the full-disc re-contest only matters when the pull LANDSCAPE changed - a source
        // appeared/moved/changed weight or protection (a town captured, a capital placed, a town
        // radius grown). While the sources are byte-identical to last tick's, every tile's winner
        // is provably unchanged too, so scanning only the newly-grown outer ring is exact, not an
        // approximation - and a color already at its radius cap skips scanning entirely. Any
        // source change (or the first tick of a session) triggers one full-disc re-contest day.
        // Per-owner now, not global - see sourcesChangedFor()'s own comment for why.

        for (String color : COLORS) {
            // Color Defeat (2026-08-14, adversarial review finding): without this skip, a defeated
            // color's radius (explicitly zeroed by defeatColor()) silently regrows every day toward
            // MAX_TERRITORY_RADIUS again - buildPullSources() already leaves it an empty pull-source
            // list so claimWastelandRing() correctly claims 0 tiles regardless, but the radius
            // bookkeeping and its diagnostic log line would otherwise run forever for no reason.
            if (world.isColorDefeated(color))
                continue;
            Integer currentRadius = world.getColorTerritoryRadius(color);
            if (currentRadius == null)
                continue;
            Vector2 castlePosition = castlePositions.get(color);
            if (castlePosition == null)
                continue;
            int newRadius = Math.min(currentRadius + expansionTilesPerDay() * daysPassed, maxTerritoryRadius());
            boolean sourcesChanged = sourcesChangedFor(color, pullSources, world.getCurrentDay());
            int innerRadius;
            if (sourcesChanged) {
                // Full-disc re-contest, KEEP outward (2026-08-08 pentagon-stall fix): tiles
                // skipped when their ring passed - or LOST to a rival whose pull has since
                // weakened - get (re)claimed instead of being gone forever.
                innerRadius = CASTLE_KEEP_RADIUS_TILES;
            } else if (newRadius > currentRadius) {
                innerRadius = Math.max(CASTLE_KEEP_RADIUS_TILES, currentRadius - 1); // just the new ring (1-tile overlap for rounding)
            } else {
                continue; // at cap, landscape unchanged - provably nothing to claim, skip the scan
            }
            int claimed = world.claimWastelandRing(color, castlePosition, pullSources,
                    innerRadius, newRadius,
                    WorldStage.getInstance()::refreshBackgroundTile,
                    WorldStage.getInstance()::reloadBackgroundChunkObjects);
            if (newRadius > currentRadius)
                world.setColorTerritoryRadius(color, newRadius);
            // Radius AND claimed-count in the log - "radius grows but the map never changes" is
            // exactly how the pentagon stall stayed invisible; a claimed-tile count can't hide.
            System.out.println("[TerritoryControl] " + color + ": territory radius now " + newRadius + "/" + maxTerritoryRadius()
                    + ", claimed " + claimed + " tile(s) this tick" + (sourcesChanged ? " (full re-contest)" : ""));
        }

        // Capitol expansion (2026-08-08 late, user: "once the player builds a Capitol, his
        // terrain should also spread, just like the AI's"): the player's territory grows from
        // Orazca at the same daily rate toward the same cap, painted as the "player" biome,
        // contested by the same pull rules. Radius state rides colorTerritoryRadius under the
        // "player" key.
        //
        // 2026-08-11 this block's own revealArea() call was REMOVED after the user reported "a
        // huge Stage 2 FoW circle" (~450 radius) appearing around the Capitol - the whole growing
        // disc was force-revealed regardless of whether the player ever set foot there, which
        // defeated the point of fog of war. 2026-08-13 the user's spec changed and now explicitly
        // supersedes that: "Where ever the player's lands 'spread' should all be revealed. If he
        // loses land... he should lose the vision." - i.e. Stage 3 should track CURRENT ownership
        // exactly, growing and shrinking with it, which is precisely what this block's own growth
        // was never wired to do (getTownVisionRadiusTiles() caps the Capitol's FoW reveal at the
        // fixed CASTLE_KEEP_RADIUS_TILES/x3-with-Outlook keep radius, never this ever-growing
        // territory radius - confirmed the one remaining un-revealed growth path in the codebase,
        // every other town/capture/upgrade path already pairs its radius growth with a reveal).
        // revealArea() is re-added below, this time keyed to THIS block's own newRadius (not
        // getTownVisionRadiusTiles()'s fixed radius) so it never reveals more than what was
        // actually just claimed - "lose land, lose vision" already works for free once this
        // reveal exists, since isPersistentlyRevealed() is a live per-render ownership check, not
        // a cached flag (confirmed via the ordinary-town capture-loss code path, which already
        // calls rebuildPlayerTownVision() on every ownership change with no separate fix needed).
        PointOfInterest capitol = null;
        for (PointOfInterest poi : playerTowns) {
            if (TownRestoration.CAPITOL_POI_NAME.equals(poi.getData().name)) {
                capitol = poi;
                break;
            }
        }
        if (capitol != null) {
            Integer currentRadius = world.getColorTerritoryRadius("player");
            if (currentRadius == null) {
                currentRadius = CASTLE_KEEP_RADIUS_TILES; // first tick after the upgrade
                world.setColorTerritoryRadius("player", currentRadius);
            }
            int newRadius = Math.min(currentRadius + capitolExpansionTilesPerDay() * daysPassed, maxTerritoryRadius());
            boolean sourcesChanged = sourcesChangedFor("player", pullSources, world.getCurrentDay());
            int innerRadius;
            if (sourcesChanged) {
                // Inner radius 1, not the keep: unlike an AI castle (whose keep was generated as
                // its own real content), the ground under the Capitol's keep is ordinary
                // player-painted-or-wasteland tiles - claiming from 1 outward fills any of it
                // still neutral (already-player tiles skip on the ownership check immediately).
                innerRadius = 1;
            } else if (newRadius > currentRadius) {
                innerRadius = Math.max(1, currentRadius - 1);
            } else {
                innerRadius = -1; // at cap, landscape unchanged - nothing to do
            }
            if (innerRadius >= 0) {
                java.util.Set<Long> claimedTiles = new java.util.HashSet<>();
                int claimed = world.claimWastelandRing("player", capitol.getPosition(), pullSources,
                        innerRadius, newRadius,
                        WorldStage.getInstance()::refreshBackgroundTile,
                        WorldStage.getInstance()::reloadBackgroundChunkObjects,
                        claimedTiles);
                boolean grew = newRadius > currentRadius;
                // 2026-08-13 fully-explored fix, part 1: only advance the radius when the grown
                // ring actually claimed something - mirrors the ordinary-town loop's own revert
                // rule above. Previously the radius marched to MAX_TERRITORY_RADIUS=450 forever
                // even after every ring claimed 0 tiles (real ownership stalls where rival pull
                // wins), and the reveal below then granted vision over that whole aspirational
                // disc. A blocked ring is simply retried on later ticks; a full re-contest after
                // rival losses can still unblock it.
                boolean advanced = grew && claimed > 0;
                if (advanced)
                    world.setColorTerritoryRadius("player", newRadius);
                world.rebuildPlayerTownVision();
                // 2026-08-13 fully-explored fix, part 2: reveal exactly the ground just CLAIMED
                // (plus a 1-tile sight margin per tile), not the whole geometric radius disc. The
                // old disc reveal marked ocean and rival-held land explored - combined with the
                // runaway radius above, it single-handedly pushed the Stage-2 fully-explored
                // counter past 80% while the rendered map still looked mostly dark (user report).
                // Claimed tiles already get their fog-pixmap + background repaint inside
                // claimWastelandRing() itself; the per-tile revealArea() here just flips
                // explored[][] for them and their immediate border. The old full-disc
                // refreshFogInRadius() call is gone entirely - per-tile updates cover everything
                // this block changes.
                for (long packed : claimedTiles) {
                    int tx = (int) (packed >> 32);
                    int ty = (int) packed;
                    world.revealArea(tx, ty, 1, WorldStage.getInstance()::refreshBackgroundTile);
                }
                System.out.println("[TerritoryControl] player: Capitol territory radius now "
                        + (advanced ? newRadius : currentRadius) + "/" + maxTerritoryRadius()
                        + ", claimed " + claimed + " tile(s) this tick"
                        + (grew && !advanced ? " (growth blocked, radius held)" : "")
                        + (sourcesChanged ? " (full re-contest)" : ""));
            }
        }
    }

    // Dispatched-mage tier variety (2026-08-14 user spec): was hardcoded to always "Adept
    // <Color> Wizard" - every attack, every color, forever, confirmed by direct code read before
    // this round (one call site, zero variation). Weighted roll instead: Apprentice 30% / Adept
    // 50% / Master 15% / Archmage 5%, same cumulative-boundary pattern as RewardData.
    // rollWeightedItemRarity(). Internal tier strings (Common/Uncommon/Rare/Mythic), matching
    // EnemyData.tier - display names are Apprentice/Adept/Master/Archmage (#58's rename, renamed
    // again 2026-08-25).
    private static final String[] DISPATCH_TIERS = {"Common", "Uncommon", "Rare", "Mythic"};
    private static final float[] DISPATCH_TIER_CUMULATIVE = {30f, 80f, 95f, 100f};
    // Color Defeat tier-shift (2026-08-14 user spec, stacking per additional defeat): per
    // defeated color, Adept -10 / Master +5 / Archmage +5, Apprentice untouched. Clamped so
    // Adept can't go negative - at 5 defeats (the max possible) it lands exactly on 0, so the
    // clamp is defensive, not load-bearing for the intended range.
    private static final float DISPATCH_TIER_SHIFT_PER_DEFEAT_ADEPT = 10f;
    private static final float DISPATCH_TIER_SHIFT_PER_DEFEAT_MASTER = 5f;
    private static final float DISPATCH_TIER_SHIFT_PER_DEFEAT_GRANDMASTER = 5f;

    private static float[] dispatchTierCumulative(World world) {
        int defeats = world == null ? 0 : world.getDefeatedColorCount();
        if (defeats <= 0)
            return DISPATCH_TIER_CUMULATIVE;
        float apprentice = DISPATCH_TIER_CUMULATIVE[0]; // 30, unshifted
        float adeptShare = Math.max(0f, (DISPATCH_TIER_CUMULATIVE[1] - DISPATCH_TIER_CUMULATIVE[0])
                - DISPATCH_TIER_SHIFT_PER_DEFEAT_ADEPT * defeats); // baseline 50
        float masterShare = (DISPATCH_TIER_CUMULATIVE[2] - DISPATCH_TIER_CUMULATIVE[1])
                + DISPATCH_TIER_SHIFT_PER_DEFEAT_MASTER * defeats; // baseline 15
        float grandmasterShare = (DISPATCH_TIER_CUMULATIVE[3] - DISPATCH_TIER_CUMULATIVE[2])
                + DISPATCH_TIER_SHIFT_PER_DEFEAT_GRANDMASTER * defeats; // baseline 5
        // Sums grandmasterShare in rather than hardcoding the final boundary to 100f (adversarial
        // review 2026-08-14: with the shipped constants these are numerically identical today -
        // ADEPT_SHIFT(10) == MASTER_SHIFT(5)+ARCHMAGE_SHIFT(5), so the three shares always summed
        // back to 100 anyway - but a hardcoded 100f made DISPATCH_TIER_SHIFT_PER_DEFEAT_GRANDMASTER
        // completely inert: tuning it alone would have silently changed nothing).
        return new float[]{apprentice, apprentice + adeptShare, apprentice + adeptShare + masterShare,
                apprentice + adeptShare + masterShare + grandmasterShare};
    }

    private static String rollDispatchMageTier(Random random, World world) {
        float[] cumulative = dispatchTierCumulative(world);
        float roll = random.nextFloat() * 100f;
        String rolled = "Mythic"; // unreachable fallback (last boundary is 100)
        for (int i = 0; i < cumulative.length; i++) {
            if (roll < cumulative[i]) {
                rolled = DISPATCH_TIERS[i];
                break;
            }
        }
        return clampDispatchTierToWeek(rolled, world);
    }

    /** Weekly tier cap on dispatched mages (2026-08-27, same playtest as the roaming-spawn
     *  isExempt fix): the baseline roll allows Master 15% / Archmage 5% from the very first
     *  dispatch (~day 4), ignoring the plane's spawn_tier_weighting week brackets entirely. Step
     *  the rolled tier down while the current week's target weight for it is zero, so a week-1
     *  "rare: 0 / mythic: 0" bracket means the earliest mages cap at Adept and the bracket table
     *  stays the single authority on early-game tier pacing. owningColor=null reads the bracket
     *  base with the all-zero NEUTRAL delta - the cap should not loosen or tighten with any one
     *  color's standing. No-op when the weighting feature is off. */
    private static String clampDispatchTierToWeek(String tier, World world) {
        if (!SpawnTierWeighting.isEnabled() || world == null)
            return tier;
        int week = SpawnTierWeighting.currentWeek(world);
        int idx = java.util.Arrays.asList(DISPATCH_TIERS).indexOf(tier);
        if (idx < 0)
            return tier;
        while (idx > 0 && SpawnTierWeighting.targetTierWeight(DISPATCH_TIERS[idx], week, null) <= 0f)
            idx--;
        if (!DISPATCH_TIERS[idx].equals(tier))
            System.out.println("[TerritoryControl] dispatch tier " + tier + " clamped to "
                    + DISPATCH_TIERS[idx] + " by week " + week + " spawn-tier bracket");
        return DISPATCH_TIERS[idx];
    }

    // No color has a Mythic-tier NAMED wizard ("Archmage <Color> Wizard" doesn't exist for any
    // color, confirmed 2026-08-14 - the hand-tuned wizard roster only ever had 3 tiers). Per user
    // decision: pick randomly from that color's own Mythic-tier roaming pool instead of inventing
    // a stand-in - a real, already-established threat for that color (17-26 candidates per color,
    // confirmed), just not literally named "Wizard". Same boss/quest-tag exclusion
    // EnemySprite.getRewards() already uses for the analogous edition-restriction exemption, and
    // getEnemyList() is already content-filter-table-aware (#41 Include=N exclusions respected
    // here for free). Returns null if that color's pool has no eligible Mythic entry at all.
    // Made public 2026-08-25: reused by ChestEvents (Chest loot spawn's "Dangerous Enemy" and
    // "Illegal Arena Match" events) for the same "strongest real roaming threat for this color"
    // pick - no behavior change, only widened visibility.
    /** Town assault (MOD_SCOPE #87, 2026-09-03): a random non-boss, non-quest roamer from this
     *  color's biome pool, any tier - "for now, just choose a random enemy from that AI's color
     *  pool" (user spec; a tier system comes later). Null if the pool is empty. */
    public static EnemyData pickRandomRoamer(World world, String color) {
        return pickRandomRoamer(world, color, null);
    }

    /** Tier-filtered variant ("Common".."Mythic"); falls back to any tier when the color pool has
     *  no eligible roamer of that tier, so an assault always finds a defender. */
    public static EnemyData pickRandomRoamer(World world, String color, String tier) {
        return pickRandomRoamer(world, color, tier, null);
    }
    /** Round 100: excludeName keeps a second defender different from the first whenever the pool allows it. */
    public static EnemyData pickRandomRoamer(World world, String color, String tier, String excludeName) {
        for (BiomeData biome : world.getData().GetBiomes()) {
            if (!color.equals(biome.name))
                continue;
            List<EnemyData> candidates = new ArrayList<>();
            List<EnemyData> anyTier = new ArrayList<>();
            for (EnemyData e : biome.getEnemyList()) {
                if (e == null || e.boss || (e.questTags != null && e.questTags.length > 0))
                    continue;
                anyTier.add(e);
                if (tier == null || tier.equals(e.tier))
                    candidates.add(e);
            }
            if (excludeName != null && candidates.size() > 1)
                candidates.removeIf(c -> excludeName.equals(c.name));
            if (candidates.isEmpty()) {
                if (anyTier.isEmpty())
                    return null;
                System.out.println("[TFR-TownAssault] no " + tier + "-tier roamer in the " + color + " pool - falling back to any tier");
                candidates = anyTier;
            }
            // Same kill-decay rule as overworld spawns (user spec 2026-09-03): an enemy the player has
            // killed k times permanently weighs 0.5^k, exactly like SpawnTierWeighting.rawSpawnWeight.
            float total = 0f;
            float[] weights = new float[candidates.size()];
            for (int i = 0; i < candidates.size(); i++) {
                weights[i] = (float) Math.pow(0.5, SpawnTierWeighting.getPermanentKillCount(candidates.get(i).name));
                total += weights[i];
            }
            float roll = forge.util.MyRandom.getRandom().nextFloat() * total;
            for (int i = 0; i < candidates.size(); i++) {
                roll -= weights[i];
                if (roll <= 0f)
                    return candidates.get(i);
            }
            return candidates.get(candidates.size() - 1);
        }
        return null;
    }

    /** 0 Easy, 1 Normal, 2 Hard, 3 Insane - the index of the player's difficulty in config.json's
     *  difficulties table (same derivation the attacking-mage cap uses). */
    public static int difficultyIndex() {
        DifficultyData playerDifficulty = Current.player().getDifficulty();
        DifficultyData[] allDifficulties = Config.instance().getConfigData().difficulties;
        if (playerDifficulty != null && playerDifficulty.name != null && allDifficulties != null)
            for (int i = 0; i < allDifficulties.length; i++)
                if (playerDifficulty.name.equals(allDifficulties[i].name))
                    return i;
        return 0;
    }

    /** A captured town's former owner answers at once with an attacking mage, using the standard
     *  dispatch/targeting logic (user spec 2026-09-03). */
    public static void dispatchRetaliation(World world, String color, String townName) {
        System.out.println("[TFR-TownAssault] " + color + " dispatches a mage in retaliation for the loss of " + townName);
        dispatch(world, color);
    }

    public static final int AI_GUARD_MAX_LEVEL = 4;

    /** Days until this town may be assaulted again (0 = now). Once a week per town (user spec
     *  2026-09-03), tunable via TuningData.aiTownAssaultCooldownDays. A captured town is re-keyed
     *  by transformInto, so its record is gone along with the AI's ownership. */
    public static int assaultCooldownDaysLeft(PointOfInterest poi, int today) {
        forge.adventure.pointofintrest.PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
        int last = changes == null ? -1 : changes.getAiLastAssaultDay();
        if (last < 0)
            return 0;
        int cooldown = Config.instance().getTuningData().aiTownAssaultCooldownDays;
        if (cooldown <= 0)
            cooldown = 7;
        return Math.max(0, cooldown - (today - last));
    }

    public static void recordAssault(PointOfInterest poi, int today) {
        WorldSave.getCurrentSave().getPointOfInterestChanges(poi.getID()).setAiLastAssaultDay(today);
    }

    /**
     * AI guard dots (MOD_SCOPE #87, user spec 2026-09-03). Once per day tick: every AI-held color
     * TOWN (capitals are fixed at two Archmage dots and stay unattackable) gains one level per
     * TuningData.aiTownGuardDaysPerLevel days of unbroken ownership, up to AI_GUARD_MAX_LEVEL.
     * The clock starts the first time a town is SEEN held - on save load or after a capture -
     * never retroactively from world generation (user decision: not a migration). Any change of
     * hands re-keys the POI (transformInto) and therefore restarts from a fresh entry.
     */
    static void updateAiTownGuardLevels(World world, int today) {
        int perLevel = Config.instance().getTuningData().aiTownGuardDaysPerLevel;
        if (perLevel <= 0)
            perLevel = 28;
        boolean changed = false;
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            PointOfInterestData data = poi.getData();
            if (data == null || !"town".equals(data.type) || ColorReputation.colorOfTown(data) == null)
                continue; // neutral / ruined / Waste towns and capitals: no upgrading dot (user spec)
            forge.adventure.pointofintrest.PointOfInterestChanges changes = WorldSave.getCurrentSave().getPointOfInterestChanges(poi.getID());
            if (TownRestoration.isTownRestored(changes))
                continue; // the player's now
            if (changes.getAiHeldSinceDay() < 0) {
                changes.setAiHeldSinceDay(today); // clock starts now
                continue;
            }
            int level = Math.min(AI_GUARD_MAX_LEVEL, (today - changes.getAiHeldSinceDay()) / perLevel);
            if (level != changes.getAiGuardLevel()) {
                System.out.println("[TFR-AiGuard] " + poi.getDisplayName() + " (" + ColorReputation.colorOfTown(data)
                        + ") guard level " + changes.getAiGuardLevel() + " -> " + level + " (held since day "
                        + changes.getAiHeldSinceDay() + ", day " + today + ", " + perLevel + " days/level)");
                changes.setAiGuardLevel(level);
                changed = true;
            }
        }
        if (changed)
            world.refreshWorldMapMarkers();
    }

    /** The basic land a town-assault defender starts with (tapped) for its color. */
    public static String basicLandFor(String color) {
        switch (color) {
            case "white": return "Plains";
            case "blue": return "Island";
            case "black": return "Swamp";
            case "red": return "Mountain";
            case "green": return "Forest";
            default: return null;
        }
    }

    public static EnemyData pickGrandmasterMage(World world, String color) {
        for (BiomeData biome : world.getData().GetBiomes()) {
            if (!color.equals(biome.name))
                continue;
            List<EnemyData> candidates = new ArrayList<>();
            for (EnemyData e : biome.getEnemyList()) {
                if (e == null || e.boss || (e.questTags != null && e.questTags.length > 0))
                    continue;
                if ("Mythic".equals(e.tier))
                    candidates.add(e);
            }
            if (candidates.isEmpty())
                return null;
            return candidates.get(world.getRandom().nextInt(candidates.size()));
        }
        return null;
    }

    // Every early-return below prints why, not just the success path - the only way to tell
    // "dispatch is quietly never firing" apart from "dispatch fires but something after it is
    // broken" without being able to run the game directly. Same reasoning behind the on-screen
    // notifications in dispatch()/onMageArrived() below - MOD_SCOPE.md #7 was reported as "ran a
    // week, saw zero mages" with no way to tell which stage of the pipeline that pointed at.
    private static void dispatch(World world, String color) {
        // TARGET selection is frontier-aware, but the LAUNCH is castle-only (user refinement
        // 2026-08-08, same day this briefly launched from the nearest owned property): candidates
        // are ranked by distance to the color's NEAREST owned property (castle + its towns/
        // capitals), so the attack frontier still widens as holdings grow - but the mage always
        // physically sets out from the castle, deliberately, so it has real travel distance the
        // player can see coming and intercept. No castle -> no attacks (also deliberate).
        PointOfInterest castle = findCastle(world, color);
        if (castle == null) {
            System.out.println("[TerritoryControl] " + color + ": no castle found, skipping dispatch");
            return;
        }
        // Difficulty-scaled cap on simultaneous in-flight mages per color (user request
        // 2026-08-08): 2 on Easy, +1 per difficulty step, 5 on Insane. A color at its cap skips
        // this dispatch entirely - its attack timer still resets in processDaysPassed(), so it
        // simply tries again on its next scheduled attack day.
        int activeMages = 0;
        for (EnemySprite mage : WorldStage.getInstance().getTerritoryMages())
            if (color.equals(mage.territoryColor))
                activeMages++;
        int cap = maxActiveMagesPerColor(world);
        if (world.isCapitolLost(color)) { // round 100 (user spec 2026-09-03): capital taken by the player - half the mages, rounded down
            int halved = cap / 2;
            System.out.println("[TFR-MageCap] " + color + ": capital lost to the player - active-mage cap " + cap + " -> " + halved);
            cap = halved;
        }
        if (activeMages >= cap) {
            System.out.println("[TerritoryControl] " + color + ": " + activeMages + " mage(s) already in flight (cap " + cap + "), skipping dispatch");
            return;
        }
        List<PointOfInterest> ownedSources = new ArrayList<>();
        ownedSources.add(castle);
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (isColorTownOrCapital(poi.getData(), color))
                ownedSources.add(poi);
        }
        List<PointOfInterest> attackable = findAttackableTowns(world, color);
        if (attackable.isEmpty())
            return; // nothing left to capture - the natural "done" state, quietly no-op forever
        // Ring Towns (round 99, user spec 2026-09-03): each AI color may target a given Ring Town at most
        // once per ringTownTargetCooldownDays - all five in one week is fine, the same one twice is not.
        int ringCooldown = Config.instance().getTuningData().ringTownTargetCooldownDays;
        if (ringCooldown > 0) {
            List<PointOfInterest> offCooldown = new ArrayList<>();
            List<String> held = new ArrayList<>();
            for (PointOfInterest candidate : attackable) {
                int last = isRingTown(candidate) ? world.ringTargetDay(color, ringTileX(world, candidate), ringTileY(world, candidate)) : Integer.MIN_VALUE;
                if (last != Integer.MIN_VALUE && world.getCurrentDay() - last < ringCooldown)
                    held.add(candidate.getDisplayName() + " (" + (ringCooldown - (world.getCurrentDay() - last)) + "d)");
                else
                    offCooldown.add(candidate);
            }
            if (!held.isEmpty()) {
                System.out.println("[TFR-RingCooldown] " + color + ": Ring Town(s) on targeting cooldown " + held
                        + (offCooldown.isEmpty() ? " - nothing else attackable, no dispatch" : ""));
                if (offCooldown.isEmpty())
                    return;
                attackable = offCooldown;
            }
        }

        // In-flight target exclusion (2026-08-26, user report: "I was black, send 3 mages, all
        // to the same town" - each dispatch independently rolled from the same nearest-5 pool
        // with zero memory of what this color's other mages were already flying toward). Towns
        // already claimed by one of THIS color's in-flight mages drop out of the candidate pool
        // before the roll; if that would leave zero candidates, the exclusion is waived and a
        // repeat target is allowed rather than silently skipping the attack.
        java.util.Set<String> inFlightTargetIds = new java.util.HashSet<>();
        for (EnemySprite mage : WorldStage.getInstance().getTerritoryMages())
            if (color.equals(mage.territoryColor) && mage.territoryTarget != null)
                inFlightTargetIds.add(mage.territoryTarget.getID());
        if (!inFlightTargetIds.isEmpty()) {
            List<PointOfInterest> untargeted = new ArrayList<>();
            for (PointOfInterest candidate : attackable)
                if (!inFlightTargetIds.contains(candidate.getID()))
                    untargeted.add(candidate);
            if (untargeted.isEmpty()) {
                System.out.println("[TFR-Targeting] " + color + ": every attackable town already has an in-flight mage - allowing a repeat target");
            } else {
                if (untargeted.size() < attackable.size())
                    System.out.println("[TFR-Targeting] " + color + ": excluded " + (attackable.size() - untargeted.size())
                            + " town(s) already targeted by this color's in-flight mage(s)");
                attackable = untargeted;
            }
        }

        // Capitol weekly lockout (user spec 2026-08-31) - filter site (a) of three.
        //
        // Deliberately AFTER the in-flight block above: that block self-waives when every
        // attackable town is already targeted ("allowing a repeat target"), restoring the
        // unfiltered list with the Capitol back in it. Filtering earlier would be undone there.
        //
        // The Capitol really is an ordinary distance-ranked candidate, despite what the comment
        // further down claims: findAttackableTowns() admits it via isWastelandTown(), which is
        // true for a "capital"-type POI carrying the BiomeColorless tag. So this filter is the
        // load-bearing one; the two below only close the paths that call findCapitol() directly.
        PointOfInterest lockedCapitol = TownRestoration.findCapitol();
        if (lockedCapitol != null && !capitolOffCooldown(world, color) && attackable.contains(lockedCapitol)) {
            List<PointOfInterest> withoutCapitol = new ArrayList<>(attackable);
            withoutCapitol.remove(lockedCapitol);
            if (withoutCapitol.isEmpty()) {
                // Nothing else on the whole map is attackable. Rather than silently break the
                // lockout, say so loudly - this should be unreachable while any wasteland town
                // exists anywhere.
                System.err.println("[TFR-CapitolCooldown] " + color + ": Capitol is the ONLY attackable target - "
                        + "allowing it despite the cooldown (day " + world.getCurrentDay()
                        + ", last targeted day " + world.getCapitolTargetedDay(color) + ")");
            } else {
                attackable = withoutCapitol;
                System.out.println("[TFR-CapitolCooldown] " + color + ": Capitol excluded - targeted on day "
                        + world.getCapitolTargetedDay(color) + ", today is day " + world.getCurrentDay()
                        + ", cooldown " + capitolTargetCooldownDays() + " day(s)");
            }
        }

        PointOfInterest target = null;
        // Color Defeat forced-targeting (2026-08-14 user spec): a one-shot flag armed when a
        // neighboring color falls (see defeatColor()) forces this color's NEXT dispatch to hit a
        // player-owned target with 100% probability, bypassing the weighted pick below entirely.
        // Searches the FULL attackable list (not just the nearest NEAREST_CANDIDATES) plus the
        // Capitol - "the next attack WILL be a player town" shouldn't depend on whether a player
        // town happens to be nearby right now. If the player owns nothing yet, the flag stays
        // ARMED (not consumed) and this dispatch falls through to the ordinary pick below -
        // there's nothing to force yet, but the guarantee still applies to a later dispatch once
        // the player does own something.
        if (world.hasForcedPlayerTarget(color)) {
            List<PointOfInterest> playerTargets = new ArrayList<>();
            for (PointOfInterest candidate : attackable) {
                if (TownRestoration.isTownRestored(WorldSave.getCurrentSave().peekPointOfInterestChanges(candidate.getID())))
                    playerTargets.add(candidate);
            }
            // The Capitol satisfies TownRestoration.isWastelandTown()/isTownRestored() the same as
            // any restored town (upgradeToCapitol() stamps the same TOWN_RESTORED_FLAG), so the
            // loop above may have already added it - guard against adding it twice (adversarial
            // review 2026-08-14 caught a real bug here: an unconditional add gave the Capitol ~2x
            // the intended uniform selection odds). Same dedup shape the pre-existing weighted-pick
            // Capitol handling below already uses via candidates.indexOf().
            PointOfInterest playerCapitol = TownRestoration.findCapitol();
            // Capitol weekly lockout - filter site (b). A forced attack does NOT punch through the
            // cooldown: the flag stays armed when playerTargets ends up empty (see the comment
            // above), so the guarantee is deferred rather than lost.
            if (playerCapitol != null && !capitolOffCooldown(world, color))
                playerTargets.remove(playerCapitol);
            else if (playerCapitol != null && !playerTargets.contains(playerCapitol)
                    && !inFlightTargetIds.contains(playerCapitol.getID()))
                playerTargets.add(playerCapitol);
            if (!playerTargets.isEmpty()) {
                target = playerTargets.get(world.getRandom().nextInt(playerTargets.size()));
                world.clearForcedPlayerTarget(color);
                System.out.println("[TFR-ColorDefeat] " + color + ": forced-next-attack consumed, targeting " + target.getDisplayName());
            }
        }

        // Hoisted above the forced-targeting/ordinary-pick branch below (2026-08-14) - the
        // [TFR-Targeting] diagnostic dump further down reads these unconditionally, and a
        // forced-target dispatch legitimately leaves them at empty/0 (there was no weighted pick
        // to show) rather than undefined.
        List<PointOfInterest> candidates = new ArrayList<>();
        List<Float> weights = new ArrayList<>();
        float originalRoll = 0f;
        float totalWeight = 0f;
        if (target == null) {
            attackable.sort(Comparator.comparingDouble(t -> distToNearestSource(t, ownedSources)));
            int candidateCount = Math.min(NEAREST_CANDIDATES, attackable.size());
            // Color reputation (MOD_SCOPE.md #1) consequence, the user's chosen meaning of "less/
            // more likely to be attacked": among the nearest candidates, a PLAYER-OWNED town's odds
            // of being picked scale with the player's standing with the dispatching color (Partner
            // x0.75 ... severe tier x1.25). Non-player towns keep weight 1.0, so with no player
            // towns in the candidate set this is exactly the old uniform pick. (This is the
            // reputation gate the original targeting design deferred - "eventually meant to be
            // gated by a reputation scale once #1 exists".)
            candidates.addAll(attackable.subList(0, candidateCount));
            for (PointOfInterest candidate : candidates) {
                boolean playerOwned = TownRestoration.isTownRestored(
                        WorldSave.getCurrentSave().peekPointOfInterestChanges(candidate.getID()));
                float weight = playerOwned ? ColorReputation.getPlayerTownAttackWeight(color) : 1f;
                // Functioning Neutral Towns are 15% less likely to be picked (user spec
                // 2026-08-29, same "keep neutral towns alive a little longer" goal as their new
                // base defense). Mutually exclusive with the player-owned branch above by
                // construction - isFunctioningNeutralTown() requires NOT restored - so the two
                // weightings can never compound on one town. Bare ruins keep weight 1.0, so this
                // also nudges expansion toward empty land rather than settled neutral towns.
                if (!playerOwned && isFunctioningNeutralTown(candidate))
                    weight *= NEUTRAL_TOWN_TARGET_WEIGHT;
                // Ring Towns (round 99): once among the five nearest, "they found their target" - x(1 + bonus)
                if (isRingTown(candidate))
                    weight *= 1f + Config.instance().getTuningData().ringTownTargetWeightBonus;
                weights.add(weight);
                totalWeight += weight;
            }
            // Color reputation (MOD_SCOPE.md #1) Capitol targeting (user request 2026-08-10): the
            // player's Capitol is never a normal candidate (it's neither neutral nor an enemy-color
            // town), and is fully exempt from this color's attacks at Partner/Happy. At War it becomes
            // attackable via a flat weight bonus equal to 5% of the pool's total - stacking with the
            // ordinary reputation multiplier above (user decision) - added as a 6th candidate, or ON
            // TOP of its existing weight if it already landed among the 5 nearest by distance (defensive;
            // in practice it never does, since "Player Capitol" matches neither isWastelandTown() nor
            // an enemy-color town check). Neutral/Unhappy leave the Capitol untouched - only War and
            // Partner/Happy have user-specified rules.
            PointOfInterest capitol = TownRestoration.findCapitol();
            // Same in-flight exclusion as the ordinary pool above - a Capitol already under
            // attack by one of this color's mages doesn't get a second one rolled onto it.
            if (capitol != null && inFlightTargetIds.contains(capitol.getID()))
                capitol = null;
            // Capitol weekly lockout - filter site (c). Site (a) already removed it from
            // `attackable`, so it cannot be among `candidates` by distance; this stops the War
            // tier adding it back as a 6th candidate.
            if (capitol != null && !capitolOffCooldown(world, color))
                capitol = null;
            if (capitol != null && ColorReputation.getStatus(color) == ColorReputation.Status.WAR) {
                float bonus = totalWeight / 19f; // solves bonus / (totalWeight + bonus) == 0.05
                int existingIndex = candidates.indexOf(capitol);
                if (existingIndex >= 0)
                    weights.set(existingIndex, weights.get(existingIndex) + bonus);
                else {
                    candidates.add(capitol);
                    weights.add(bonus);
                }
                totalWeight += bonus;
            }
            originalRoll = world.getRandom().nextFloat() * totalWeight;
            float roll = originalRoll;
            int pick = candidates.size() - 1;
            for (int i = 0; i < candidates.size(); i++) {
                roll -= weights.get(i);
                if (roll <= 0f) {
                    pick = i;
                    break;
                }
            }
            target = candidates.get(pick);
        }

        String dispatchTier = rollDispatchMageTier(world.getRandom(), world);
        EnemyData enemyData;
        String enemyName;
        if ("Mythic".equals(dispatchTier)) {
            enemyData = pickGrandmasterMage(world, color);
            enemyName = enemyData != null ? enemyData.getName() : "(no Mythic-tier " + color + " enemy available)";
        } else {
            enemyName = EnemyData.tierDisplayName(dispatchTier) + " " + capitalize(color) + " Wizard";
            enemyData = WorldData.getEnemy(enemyName);
        }
        System.out.println("[TerritoryControl] " + color + ": dispatch rolled tier " + dispatchTier
                + " (" + EnemyData.tierDisplayName(dispatchTier) + ") -> " + enemyName);
        if (enemyData == null) {
            System.out.println("[TerritoryControl] " + color + ": enemy \"" + enemyName + "\" not found, skipping dispatch");
            return;
        }
        // Mythic-tier dispatches draw from the color's roaming pool, which includes the
        // deliberately-oversized "Legends" commander sprites (scale 2x+, e.g. Commodore Guff) -
        // fine as a stationary boss, but marching across the overworld at double size it reads
        // as a rendering bug (user report 2026-08-26: "A large Enemy icon appeared"). Clone
        // (never mutate the shared JSON-loaded template) and normalize the SPRITE scale only -
        // deck/life/tier, i.e. the actual threat, are untouched.
        if (enemyData.scale != 1.0f) {
            enemyData = new EnemyData(enemyData);
            enemyData.scale = 1.0f;
        }
        EnemySprite mage = new EnemySprite(enemyData);
        mage.territoryTarget = target;
        mage.territoryColor = color;
        WorldStage.getInstance().spawnAt(mage, new Vector2(castle.getPosition()));

        // Capitol weekly lockout (user spec 2026-08-31): stamp the moment the mage is provably
        // launched. Placed AFTER the spawn, because dispatch() can still abort between choosing a
        // target and getting here (the enemyData == null return above, reachable when a Mythic
        // roll finds no grandmaster in this color's biome) - an aborted dispatch must not burn the
        // color's week. And placed at dispatch rather than at resolution, because the spec counts
        // the attack "regardless if the mage wins, loses, gets killed": the mage now walks to the
        // Capitol over several in-game days and can be duelled en route without being stopped, so
        // a resolution-time stamp would let this color re-target while its first mage was still
        // on the road.
        if (TownRestoration.CAPITOL_POI_NAME.equals(target.getData().name)) {
            world.setCapitolTargetedDay(color, world.getCurrentDay());
            System.out.println("[TFR-CapitolCooldown] " + color + ": dispatched at the Capitol on day "
                    + world.getCurrentDay() + " - locked out until day "
                    + (world.getCurrentDay() + capitolTargetCooldownDays()));
        }

        // Diagnostic logging standard (user request 2026-08-13) - the outcome line below only
        // ever shows the WINNING candidate; without this, the weighting/reputation math above
        // (and the mage's own speed/tier/life, otherwise unlogged anywhere for territory mages)
        // can't be verified from forge.log, only inferred from results over many dispatches.
        StringBuilder candidateDump = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) candidateDump.append(", ");
            candidateDump.append(candidates.get(i).getDisplayName()).append("=").append(weights.get(i));
        }
        if (isRingTown(target)) // Ring Towns: start this color's weekly targeting cooldown for THIS ring town
            world.recordRingTargeted(color, ringTileX(world, target), ringTileY(world, target), world.getCurrentDay());
        System.out.println("[TFR-Targeting] " + color + " mage (tier=" + enemyData.tier
                + ", speed=" + enemyData.speed + ", life=" + enemyData.life + ") candidates=["
                + candidateDump + "] roll=" + originalRoll + "/" + totalWeight + " -> picked "
                + target.getDisplayName());

        String message = capitalize(color) + " sends a mage toward " + target.getDisplayName() + "!";
        // Extra warning when the target is one of the PLAYER's towns (user request 2026-08-08) -
        // RED caps via the authored-markup overload (this string is fully self-authored, so the
        // white-tint path is safe here; the earlier bold-caps version rendered as smeared
        // double-struck glyphs at this pixel-font size, user report 2026-08-08).
        boolean targetPlayerOwned = TownRestoration.isTownRestored(
                WorldSave.getCurrentSave().peekPointOfInterestChanges(target.getID()));
        System.out.println("[TerritoryControl] " + message + (targetPlayerOwned ? " (Player Owned!)" : ""));
        if (targetPlayerOwned)
            GameHUD.getInstance().addNotification("[BLACK]" + message + " [RED]PLAYER OWNED TOWN!", true);
        else
            GameHUD.getInstance().addNotification(message);
    }

    // 2 simultaneous mages per color on Easy, +1 per difficulty step up (Easy/Normal/Hard/Insane
    // -> 2/3/4/5, matching the user's spec exactly for the shipped 4-difficulty list). Unknown or
    // missing difficulty falls back to the Easy cap rather than guessing high.
    private static int maxActiveMagesPerColor(World world) {
        DifficultyData playerDifficulty = Current.player().getDifficulty();
        DifficultyData[] allDifficulties = Config.instance().getConfigData().difficulties;
        int index = 0;
        if (playerDifficulty != null && playerDifficulty.name != null && allDifficulties != null) {
            for (int i = 0; i < allDifficulties.length; i++) {
                if (playerDifficulty.name.equals(allDifficulties[i].name)) {
                    index = i;
                    break;
                }
            }
        }
        // Player-town-count scaling (2026-08-11, round 8, user spec): "+1 attacking mage per 10
        // towns the player owns (count Capitol as a town)... add 1 town to easy difficulty, so
        // 11, and subtract 1 for hard and insane, so insane would be +1 attacker per 8 cities."
        // (11 - index) lands on exactly those 4 numbers - Easy 11, Normal 10, Hard 9, Insane 8 -
        // without needing a separate per-difficulty table. A rubber-band mechanic layered on top
        // of the flat difficulty base above, so a dominant player faces escalating pressure
        // regardless of difficulty. countPlayerTowns() itself doesn't count the Capitol (it's a
        // separate POI created via transformInto(), same reason the life-bonus calc elsewhere
        // adds capitolExists() ? 1 : 0 on top of it) - added here explicitly per the user's own
        // "count Capitol as a town" spec.
        int playerTowns = TownRestoration.countPlayerTowns() + (TownRestoration.capitolExists() ? 1 : 0);
        int townBonus = playerTowns / (11 - index);
        // Color Defeat (2026-08-14 user spec, stacking): "+1 to the number of attacking mages
        // [every remaining AI] can field" per additional color defeated - a shared/global cap
        // (this method takes no per-color input even before this change), so it applies equally
        // to every surviving color's dispatch() call, same as the difficulty/town-count terms
        // already do. Defeated colors never call dispatch() at all (see processDaysPassed()'s own
        // skip), so this term is simply moot for them.
        int defeatBonus = world != null ? world.getDefeatedColorCount() : 0;
        // Difficulty base made tunable 2026-08-20 (TuningData.baseAttackingMagesPerColor, Normal
        // base; fixed offsets Easy -1 / Hard +1 / Insane +2 per user spec). With the default base
        // of 3 this reproduces the old hardcoded 2+index ladder (2/3/4/5) exactly.
        int difficultyOffset = index == 0 ? -1 : index - 1;
        int base = Config.instance().getTuningData().baseAttackingMagesPerColor;
        int cap = base + difficultyOffset + townBonus + defeatBonus;
        // Diagnostic logging standard (user request 2026-08-13) - the town-count scaling term is
        // otherwise invisible: the caller only ever sees the final cap, with no way to tell how
        // much of it came from the flat difficulty base vs. this rubber-band bonus.
        // Printed only when the inputs or the result CHANGE (2026-09-02 log review: one idle
        // 139-day session wrote this identical line 194 times). The first call of a process
        // always prints, so a log still shows the cap in force for that session.
        String line = "[TFR-MageCap] base=" + base + " difficultyOffset=" + difficultyOffset
                + " playerTowns=" + playerTowns
                + " divisor=" + (11 - index) + " townBonus=" + townBonus + " defeatBonus=" + defeatBonus + " -> cap=" + cap;
        if (!line.equals(lastMageCapLine)) {
            System.out.println(line);
            lastMageCapLine = line;
        }
        return cap;
    }

    private static double distToNearestSource(PointOfInterest town, List<PointOfInterest> sources) {
        double best = Double.MAX_VALUE;
        for (PointOfInterest source : sources)
            best = Math.min(best, town.getPosition().dst2(source.getPosition()));
        return best;
    }

    // Public: World.java's placement pass (Territory Control #7 v2) calls this directly to find
    // each color's real castle position, rather than duplicating this exact-name+type lookup.
    public static PointOfInterest findCastle(World world, String color) {
        String castleName = capitalize(color) + " Castle";
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if ("castle".equals(poi.getData().type) && castleName.equals(poi.getData().name))
                return poi;
        }
        return null;
    }

    // Attacker's win chance when a mage capturing an enemy-color town resolves (onMageArrived()),
    // scaled by the mage's deck-rarity difficulty tier (EnemyData.tier, user request 2026-08-10 -
    // "we could use this to determine the chances to win a town fight"; replaces the original flat
    // 50/50 coin flip). A Common-tier mage reaching a town is a real but weak threat; a Mythic-tier
    // one should feel like a serious loss if it isn't intercepted first.
    private static float attackerWinChance(String tier) {
        if (tier == null)
            return 0.5f;
        switch (tier) {
            case "Common": return 0.10f;
            case "Uncommon": return 0.30f;
            case "Rare": return 0.70f;
            case "Mythic": return 0.90f;
            default: return 0.5f;
        }
    }

    // Town guard fight odds (MOD_SCOPE.md #22, Armory Guard hiring, 2026-08-11 user spec: "you
    // will have to calculate the odds, for all, i.e. an attacking Adept vs. defending Master").
    // Unlike attackerWinChance() above (single-tier vs. a fixed baseline), a guard fight needs a
    // genuine tier-vs-tier matchup on BOTH sides. Reuses the same 1/2/4/8 Common/Uncommon/Rare/
    // Mythic power weighting the Item Economy round already established for deck-rarity scoring
    // (MOD_CHANGELOG.md, "Weighting: Common=1, Uncommon=2, Rare=4, Mythic=8") rather than a new
    // scale - attackerPower / (attackerPower + defenderPower) gives same-tier a clean 50/50 and,
    // as a sanity check, a 3-tier gap (Common attacker vs Mythic defender) comes out to 11%, closely
    // matching attackerWinChance()'s own independently-chosen 10% for the equivalent single-tier
    // case above - the two systems agree without having been forced to.
    private static float tierPower(String tier) {
        if (tier == null)
            return 1f;
        switch (tier) {
            case "Common": return 1f;
            case "Uncommon": return 2f;
            case "Rare": return 4f;
            case "Mythic": return 8f;
            default: return 1f;
        }
    }

    public static float guardFightAttackerWinChance(String attackerTier, String defenderTier) {
        float attackerPower = tierPower(attackerTier);
        float defenderPower = tierPower(defenderTier);
        return attackerPower / (attackerPower + defenderPower);
    }

    // Guard-fight balance adjustment (user spec 2026-08-11): the base tier-vs-tier formula alone
    // felt too safe for the defender once compounded with the base town-capture roll afterward
    // (e.g. a Common attacker vs. a hired Archmage guard was ~11%, then another roll on top of
    // that) - a flat attacker bonus, partly countered by a new combat role for the Outlook
    // building (previously vision-radius only) if the town has one. Deliberately NOT applied to
    // guardFightAttackerWinChance() itself - that function stays the pure tier-math baseline,
    // these are combat-context modifiers layered on top only where an actual fight resolves.
    private static final float GUARD_FIGHT_ATTACKER_BONUS = 0.10f;
    // OUTLOOK_DEFENSE_BONUS (renamed from GUARD_FIGHT_-specific, 2026-08-11 same-day follow-up):
    // the user asked for Outlook to also defend the base town-capture roll, not just guard fights
    // - same -5% value, no separate attacker-side bonus added to the capture roll (only the guard
    // fight got the +10% attacker buff; extending that too wasn't asked for).
    private static final float OUTLOOK_DEFENSE_BONUS = 0.05f;

    // Functioning Neutral Town defense (user spec 2026-08-29: "Give Neutral towns a 15%
    // Natural/Base Defense and an additional 5% if they have a working Armory inside... This
    // should keep Neutral towns alive a little longer").
    //
    // Deliberately a FLAT repel chance, not a modifier on attackerWinChance(): these towns
    // previously fell with no roll whatsoever (100% capture), and the user's framing is a
    // property of the TOWN ("natural/base defense"), not a tier-vs-tier matchup. Subtracting 15%
    // from attackerWinChance() instead would have made a Common-tier mage's 10% go negative -
    // neutral towns would be outright immune to weak mages, which is far more than "a little
    // longer". So: the attacker takes the town on 85% (80% with a working Armory), regardless of
    // tier. Tunable here if that reads as too weak/strong in play.
    //
    // Scope (user decision, asked explicitly): FUNCTIONING neutral towns only - the seeded ones
    // with working shops. Bare ruined/unclaimed wasteland towns still fall with no roll at all,
    // so ordinary AI expansion into empty land is completely unchanged by this.
    private static final float NEUTRAL_TOWN_BASE_DEFENSE = 0.15f;
    private static final float NEUTRAL_TOWN_ARMORY_DEFENSE = 0.05f;
    // Same round/spec: "give Neutral towns a -15% less likely to be targeted by AI attacks".
    // Applied as a weight multiplier in dispatch()'s existing weighted candidate pick, alongside
    // the player-town reputation weighting that already lives there.
    private static final float NEUTRAL_TOWN_TARGET_WEIGHT = 0.85f;

    // Running tally so the log can answer "is the repel rate actually on target?" by itself,
    // instead of needing the lines grepped and the binomial worked out by hand (2026-08-30 user
    // request: "can we add more stuff to the log to nail this down"). Session-scoped and
    // deliberately NOT persisted - it is a diagnostic, and a per-session sample is exactly the
    // unit you want when comparing observed vs expected. Expected is accumulated per-roll rather
    // than assumed, because the per-town rate varies with whether that town kept its Armory.
    private static int neutralDefenseAttempts = 0;
    private static int neutralDefenseRepels = 0;
    private static float neutralDefenseExpectedRepels = 0f;

    /** Is this POI a seeded Functioning Neutral Town (shops, not a bare ruin)? */
    private static boolean isFunctioningNeutralTown(PointOfInterest poi) {
        PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
        return TownRestoration.isNeutralSeededTown(changes)
                && !TownRestoration.isTownRestored(changes);
    }

    private static boolean townHasOutlook(PointOfInterest target) {
        PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(target.getID());
        return changes != null && changes.hasEconomyBuildingOfType(EconomyBuildings.OUTLOOK);
    }

    /**
     * Resolves any active guards at target before an attack proceeds (user spec 2026-08-11,
     * refined after follow-up: strongest guard fights first; a win against one guard does NOT
     * weaken the attacker for the next - each fight is an independent fresh roll at full
     * strength; a Capitol's 2 guards are fought in sequence, both must fall before the attacker
     * reaches the Capitol duel itself). Returns true if the attacker broke through every guard
     * (or there were none), false if a guard repelled the attack - the caller must not proceed to
     * capture/duel in that case; the mage is simply spent.
     */
    private static boolean resolveGuardDefense(EnemySprite mage, PointOfInterest target) {
        PointOfInterestChanges changes = WorldSave.getCurrentSave().getPointOfInterestChanges(target.getID());
        if (changes == null)
            return true;
        boolean hasOutlook = changes.hasEconomyBuildingOfType(EconomyBuildings.OUTLOOK);
        while (changes.getGuardCount() > 0) {
            int strongestIndex = 0;
            for (int i = 1; i < changes.getGuardCount(); i++) {
                if (tierPower(changes.getGuardTier(i)) > tierPower(changes.getGuardTier(strongestIndex)))
                    strongestIndex = i;
            }
            String guardTier = changes.getGuardTier(strongestIndex);
            float baseChance = guardFightAttackerWinChance(mage.getData().tier, guardTier);
            float attackerChance = baseChance + GUARD_FIGHT_ATTACKER_BONUS - (hasOutlook ? OUTLOOK_DEFENSE_BONUS : 0f);
            attackerChance = Math.max(0f, Math.min(1f, attackerChance));
            boolean attackerWins = WorldSave.getCurrentSave().getWorld().getRandom().nextFloat() < attackerChance;
            // Diagnostic-only logging, same convention as [TFR-CaptureOdds] - greppable in forge.log.
            System.out.println("[TFR-GuardFight] " + mage.territoryColor + " mage (tier=" + mage.getData().tier
                    + ") vs " + EconomyBuildings.guardTierDisplayName(guardTier) + " guard at "
                    + target.getDisplayName() + " (chance=" + attackerChance + ") -> "
                    + (attackerWins ? "ATTACKER WINS, guard falls" : "GUARD WINS, attacker repelled"));
            if (!attackerWins) {
                GameHUD.getInstance().addNotification("[GREEN]Your " + EconomyBuildings.guardTierDisplayName(guardTier)
                        + " guard repelled " + capitalize(mage.territoryColor) + "'s attack at "
                        + target.getDisplayName() + "!", true);
                return false;
            }
            changes.removeGuardAt(strongestIndex);
            GameHUD.getInstance().addNotification("[RED]Your " + EconomyBuildings.guardTierDisplayName(guardTier)
                    + " guard fell defending " + target.getDisplayName() + "!", true);
        }
        return true;
    }

    // "Sacked" outcome (user spec 2026-08-11): even a successful capture doesn't guarantee the
    // attacker keeps the town - a separate post-win roll can instead revert it to a neutral ruin
    // ("they won the town, but sacked it"). Applied uniformly to every successful capture (both
    // player-owned town defense and AI-vs-AI captures), not scoped to player defense specifically
    // - my own call, not explicitly asked for either way: "sacking" reads as a general war
    // mechanic that should apply symmetrically, and it reuses the exact same revert-to-neutral
    // machinery the AI-vs-AI losing-roll case already has, rather than needing separate handling.
    // Flag this if the intent was player-town-only.
    private static final float ATTACKER_SACKS_TOWN_CHANCE = 0.20f;

    private static boolean attackerSacksInstead(World world) {
        return world.getRandom().nextFloat() < ATTACKER_SACKS_TOWN_CHANCE;
    }

    // Roaming-spawn intrusion (MOD_SCOPE.md #7 follow-up, user request 2026-08-10): the nearest
    // OTHER color's town/capital/castle within SPAWN_INTRUSION_RADIUS_TILES of pos, or null if
    // none. excludeColor lets the caller skip the biome's own color - standing in your own
    // color's land next to your own capital isn't an "intrusion." Player-owned towns never match
    // (their name no longer starts with any color noun once transformInto() renames them), so
    // they can't accidentally trigger this either - consistent with reputation treating
    // player-owned towns as colorless.
    public static String findNearbyForeignColor(World world, Vector2 pos, String excludeColor) {
        float radiusWorld = SPAWN_INTRUSION_RADIUS_TILES * (float) world.getTileSize();
        String nearestColor = null;
        float nearestDist = radiusWorld;
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            PointOfInterestData data = poi.getData();
            if (data.name == null)
                continue;
            String type = data.type;
            if (!"town".equals(type) && !"capital".equals(type) && !"castle".equals(type))
                continue;
            String color = colorOfPoiName(data.name, type);
            // Color Defeat (2026-08-14, adversarial review finding): a defeated color's castle is
            // deliberately left standing (it's defeatColor()'s own sweep anchor), so without this
            // check it would keep matching here forever - triggering hostile roaming-spawn
            // intrusion near its own ruins indefinitely, compounded by the flat -50 reputation
            // penalty that same defeat applies (likely pushing straight into War-tier's 2.5x
            // intrusion multiplier, the most hostile rate in the game, for a color that's supposed
            // to be permanently gone).
            if (color == null || color.equals(excludeColor) || world.isColorDefeated(color))
                continue;
            float dist = poi.getPosition().dst(pos);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestColor = color;
            }
        }
        return nearestColor;
    }

    // Content-level POI re-theme (MOD_SCOPE.md #7, user request 2026-08-10 - settles the
    // long-open "should special POIs change based on who controls the surrounding territory"
    // question from when Territory Control first shipped, in favor of re-theming rather than
    // leaving dungeons static forever). Which color's biome originally placed this POI at
    // world-gen - checked against each biome's raw pointsOfInterest[] name list, independent of
    // who owns the surrounding land NOW.
    private static String homeColorOfPoi(World world, String poiName) {
        if (poiName == null)
            return null;
        for (BiomeData biome : world.getData().GetBiomes()) {
            if (biome.pointsOfInterest == null)
                continue;
            for (String name : biome.pointsOfInterest) {
                if (poiName.equals(name))
                    return biome.name;
            }
        }
        return null;
    }

    // Current color of the land this POI sits on right now (may differ from homeColorOfPoi() once
    // territory has changed hands) - same tile-ownership lookup WorldStage's roaming spawner uses.
    // Made public (2026-08-13) so EditionProgression.restrictDungeonRewardsForCurrentPoi() can
    // reuse it for dungeon-chest loot restriction, the same "current territory color" a dungeon's
    // own roaming enemies are already implicitly placed by.
    public static String currentColorAtPoi(World world, PointOfInterest poi) {
        Vector2 pos = poi.getPosition();
        int biomeIndex = World.highestBiome(world.getBiome((int) pos.x / world.getTileSize(), (int) pos.y / world.getTileSize()));
        List<BiomeData> biomes = world.getData().GetBiomes();
        if (biomeIndex < 0 || biomeIndex >= biomes.size())
            return null;
        return biomes.get(biomeIndex).name;
    }

    /**
     * A same-difficulty-ceiling replacement enemy from the CURRENT owner of poi's territory, for
     * MapStage's hardcoded per-dungeon-object enemy placements - or null if the land hasn't
     * changed hands since world-gen (or nothing applies), meaning the caller should keep its
     * originally-authored enemy as-is. Deliberately doesn't check boss/quest status itself - only
     * the caller knows this specific encounter's own EnemyData, and boss/quest encounters are
     * often logic-critical or a scripted fight that shouldn't be silently swapped.
     */
    public static EnemyData reThemedEnemyFor(World world, PointOfInterest poi, float originalDifficultyCeiling) {
        if (!ColorReputation.isEnabled() || poi == null)
            return null;
        String homeColor = homeColorOfPoi(world, poi.getData().name);
        String currentColor = currentColorAtPoi(world, poi);
        if (homeColor == null || currentColor == null || homeColor.equals(currentColor))
            return null;
        for (BiomeData biome : world.getData().GetBiomes()) {
            if (currentColor.equals(biome.name)) {
                EnemyData result = biome.getEnemy(originalDifficultyCeiling);
                if (result != null) {
                    // Diagnostic-only logging (user request 2026-08-10, "hard to test in-game") -
                    // greppable in forge.log as "[TFR-ReTheme]".
                    System.out.println("[TFR-ReTheme] " + poi.getData().name + " (home=" + homeColor
                            + ", now=" + currentColor + ") -> " + result.getName());
                }
                return result;
            }
        }
        return null;
    }

    // "Plains Town X"/"Plains Capital"/"Plains Castle" -> white, etc. Castle names are an exact
    // "<Color> Castle" match (findCastle() above); town/capital names only need the color noun as
    // a prefix (matches ColorReputation.colorOfTown()'s equivalent town/capital check).
    private static String colorOfPoiName(String name, String type) {
        for (Map.Entry<String, String> entry : COLOR_TOWN_NOUN.entrySet()) {
            String noun = entry.getValue();
            if ("castle".equals(type) ? name.equals(noun + " Castle") : name.startsWith(noun))
                return entry.getKey();
        }
        return null;
    }

    // dispatch() candidates: every neutral town (incl. player-restored ones, deliberately - see
    // MOD_SCOPE.md #7) plus every ordinary TOWN (never a CAPITAL - a captured AI capital has no
    // defined consequence/equivalent in this design, so cross-color targeting is deliberately
    // scoped to towns only, matching how the pre-existing neutral-capture path already only ever
    // handles "Waste Town", not "Waste Capital") owned by one of `color`'s two enemies.
    private static List<PointOfInterest> findAttackableTowns(World world, String color) {
        List<PointOfInterest> towns = new ArrayList<>();
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            PointOfInterestData data = poi.getData();
            if (TownRestoration.isWastelandTown(data)) {
                towns.add(poi);
                continue;
            }
            String owner = colorOfOwnedTownForCombat(data);
            if (owner != null && isEnemyColor(color, owner))
                towns.add(poi);
        }
        return towns;
    }

    // Which of the 5 AI colors currently owns this TOWN (not capital - see findAttackableTowns()),
    // or null if it isn't recognizably any color's town right now.
    private static String colorOfOwnedTownForCombat(PointOfInterestData data) {
        if (data.name == null)
            return null;
        for (Map.Entry<String, String> entry : COLOR_TOWN_NOUN.entrySet()) {
            if (data.name.startsWith(entry.getValue() + " Town"))
                return entry.getKey();
        }
        return null;
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // The full set of rows anything showing this data needs to know about (currently
    // WorldStandingsScene, previously TownCountActor's HUD panel) - just used to zero-initialize
    // getTownCounts()'s map below, order doesn't matter here. See getSortedStandingsRows() for the
    // actual display order. "Colorless" means "still neutral", not one of the 5 AI colors.
    // Capitalized (not lowercase like COLORS above) since these double as the color_icons.atlas
    // region names, except "Player" - it has no color_icons.atlas region at all;
    // WorldStandingsScene special-cases it to render the player's own avatar instead.
    public static final String[] STANDINGS_ROWS = {"Green", "White", "Blue", "Black", "Red", "Colorless", "Player"};

    // Display order top-to-bottom: the 5 AI colors ranked by town count (most first), then
    // "Player" and "Colorless" pinned at the bottom in that order - per user request, so the
    // "still neutral" count reads as the bottom-line remainder rather than competing for attention
    // with the actual color standings above it.
    public static List<String> getSortedStandingsRows(Map<String, Integer> counts) {
        List<String> sorted = new ArrayList<>();
        for (String color : COLORS)
            sorted.add(capitalize(color));
        sorted.sort((a, b) -> counts.getOrDefault(b, 0) - counts.getOrDefault(a, 0));
        sorted.add("Player");
        sorted.add("Colorless");
        return sorted;
    }

    /**
     * Actual on-map town/capital count per STANDINGS_ROWS entry, for any UI that wants to show it.
     * "Player" is not a partition of the other 6 rows (a town keeps whatever name/color it already
     * had after the player restores it - restoring it doesn't rename/retransform the POI, only
     * recolors the surrounding terrain, see TownRestoration.java) - it's a separate count of how
     * many towns TownRestoration.isTownRestored() is true for, alongside whichever color bucket
     * that same town also counts toward by name.
     */
    public static Map<String, Integer> getTownCounts(World world) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String row : STANDINGS_ROWS)
            counts.put(row, 0);
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            String type = poi.getData().type;
            if (!"town".equals(type) && !"capital".equals(type))
                continue;
            if (TownRestoration.isTownRestored(WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID()))) // peek, not get - pure read, see processTerritoryExpansion()
                counts.merge("Player", 1, Integer::sum);
            String name = poi.getData().name;
            if (name == null)
                continue;
            if (name.startsWith("Waste Town")) {
                counts.merge("Colorless", 1, Integer::sum);
                continue;
            }
            for (Map.Entry<String, String> entry : COLOR_TOWN_NOUN.entrySet()) {
                if (name.startsWith(entry.getValue())) {
                    counts.merge(capitalize(entry.getKey()), 1, Integer::sum);
                    break;
                }
            }
        }
        return counts;
    }

    /**
     * Road follow-up to any capture (user spec 2026-08-09): connect the newly-taken town to its
     * owner's nearest existing holding by road - routed THROUGH whatever towns lie roughly
     * between the two rather than as one long straight line, so a road taken between two distant
     * holdings still reads as a natural chain of settlements. Mechanism: Dijkstra over the
     * complete graph of every town/capital POI (any allegiance - neutral and rival towns are
     * perfectly good waypoints), with edge cost = distance SQUARED. Squared cost makes a chain of
     * short hops always beat one long jump wherever a stop-over town exists roughly between the
     * endpoints (any B inside the circle whose diameter is AC satisfies |AB|²+|BC|² < |AC|²), and
     * the "closest" target holding falls out of the same search (cheapest-to-reach by path cost).
     * Re-drawing over segments the world-gen road network already built is nearly free -
     * World.buildRoad() skips already-road tiles. Owner is an AI color name, or "player" (owned =
     * restored towns, same isTownRestored() rule as everywhere else).
     */
    public static void connectCapturedTownByRoad(World world, PointOfInterest newTown, String owner) {
        if (!isEnabled() || newTown == null || owner == null)
            return;
        List<PointOfInterest> nodes = new ArrayList<>();
        int source = -1;
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            String type = poi.getData().type;
            if (!"town".equals(type) && !"capital".equals(type))
                continue;
            if (source < 0 && poi.getID().equals(newTown.getID()))
                source = nodes.size();
            nodes.add(poi);
        }
        if (source < 0)
            return;
        int n = nodes.size();
        boolean[] isTarget = new boolean[n];
        boolean anyTarget = false;
        // Round 99 (user spec 2026-09-03): the road goes to the closest town that is ALREADY road-connected
        // to the owner's seat (AI: its Capital; player: the Player Capitol), so every owner grows one network
        // from its seat instead of stray links between isolated holdings. Falls back to any owned town when
        // there is no seat yet or nothing is road-connected to it.
        PointOfInterest seat = "player".equals(owner) ? TownRestoration.findCapitol() : null;
        if (seat == null && !"player".equals(owner))
            for (PointOfInterest poi : nodes)
                if ("capital".equals(poi.getData().type) && isColorTownOrCapital(poi.getData(), owner)) { seat = poi; break; }
        long perfFlood = System.nanoTime();
        java.util.Set<String> connected = seat == null ? null
                : world.roadConnectedTownIds((int) (seat.getPosition().x / world.getTileSize()), (int) (seat.getPosition().y / world.getTileSize()));
        if (connected != null)
            System.out.println("[TFR-Perf] road flood fill from " + seat.getDisplayName() + " took " + (System.nanoTime() - perfFlood) / 1_000_000 + " ms");
        if (connected != null)
            connected.add(seat.getID());
        boolean[] owned = new boolean[n];
        boolean anyOwned = false;
        for (int i = 0; i < n; i++) {
            if (i == source)
                continue;
            PointOfInterest poi = nodes.get(i);
            if ("player".equals(owner))
                owned[i] = TownRestoration.isTownRestored(
                        WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID()));
            else
                owned[i] = isColorTownOrCapital(poi.getData(), owner);
            if (seat != null && poi.getID().equals(seat.getID()))
                owned[i] = true;
            anyOwned |= owned[i];
            isTarget[i] = owned[i] && (connected == null || connected.contains(poi.getID()));
            anyTarget |= isTarget[i];
        }
        if (!anyOwned)
            return; // first holding of this owner - nothing to connect to yet
        if (!anyTarget) {
            System.out.println("[TerritoryControl] road (" + owner + "): nothing is road-connected to the seat yet - linking to the nearest owned town instead");
            System.arraycopy(owned, 0, isTarget, 0, n);
        } else if (connected != null) {
            System.out.println("[TerritoryControl] road (" + owner + "): " + (connected.size() - 1) + " town(s) road-connected to " + seat.getDisplayName());
        }
        double[] best = new double[n];
        int[] prev = new int[n];
        boolean[] done = new boolean[n];
        java.util.Arrays.fill(best, Double.MAX_VALUE);
        java.util.Arrays.fill(prev, -1);
        best[source] = 0;
        int reached = -1;
        for (int iter = 0; iter < n; iter++) {
            int u = -1;
            double uBest = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (!done[i] && best[i] < uBest) {
                    uBest = best[i];
                    u = i;
                }
            }
            if (u < 0)
                break;
            done[u] = true;
            if (isTarget[u]) {
                reached = u;
                break;
            }
            for (int v = 0; v < n; v++) {
                if (done[v])
                    continue;
                double cost = best[u] + nodes.get(u).getPosition().dst2(nodes.get(v).getPosition());
                if (cost < best[v]) {
                    best[v] = cost;
                    prev[v] = u;
                }
            }
        }
        if (reached < 0)
            return;
        List<PointOfInterest> waypoints = new ArrayList<>();
        for (int i = reached; i >= 0; i = prev[i])
            waypoints.add(nodes.get(i));
        java.util.Collections.reverse(waypoints); // source -> ... -> reached (cosmetic; roads are undirected)
        long perfRoad = System.nanoTime();
        int tiles = world.buildRoad(waypoints, WorldStage.getInstance()::refreshBackgroundTile);
        System.out.println("[TFR-Perf] road build (" + owner + ") took " + (System.nanoTime() - perfRoad) / 1_000_000 + " ms for " + tiles + " new tile(s)");
        StringBuilder route = new StringBuilder();
        for (PointOfInterest poi : waypoints) {
            if (route.length() > 0)
                route.append(" -> ");
            route.append(poi.getDisplayName());
        }
        System.out.println("[TerritoryControl] road (" + owner + "): " + route + " (" + tiles + " new tile(s))");
    }

    // Capitol defense (MOD_SCOPE.md #7 forced duel, user request 2026-08-10): set by
    // onMageArrived() when the target IS the player's Capitol, instead of running the ordinary
    // capture flow below. Consumed by checkPendingCapitolDefense(), called from GameStage.act()
    // every frame - fires the actual forced duel at the next moment it's safe to interrupt
    // (not mid-dialog, mid-duel-transition, or paused), regardless of which of WorldStage/
    // MapStage the player is currently on. The mage sprite itself is removed from the map by
    // WorldStage's normal arrival handling right after this method returns, same as any other
    // capture - only its EnemyData/territoryColor need to survive that, which this reference does.
    private static EnemySprite pendingCapitolDefenseMage;

    /** Drop a queued Capitol-defense duel (WorldStage.clearCache(): Load / new world). 2026-09-02
     *  research re-verification: the field was only ever consumed, never reset, so a mage queued in
     *  one run could start its forced duel in the next save loaded in the same session. */
    public static void clearPendingCapitolDefense() {
        pendingCapitolDefenseMage = null;
    }

    /** Called every frame (GameStage.act(), both WorldStage and MapStage) once it's safe to
     *  interrupt whatever the player is doing. No-op unless a mage reached the Capitol since the
     *  last check. */
    public static void checkPendingCapitolDefense() {
        if (pendingCapitolDefenseMage == null)
            return;
        EnemySprite mage = pendingCapitolDefenseMage;
        pendingCapitolDefenseMage = null;
        WorldStage.getInstance().startForcedCapitolDuel(mage);
    }

    /** Called by WorldStage when a mage's territoryTarget position has been reached. */
    public static void onMageArrived(EnemySprite mage) {
        PointOfInterest target = mage.territoryTarget;
        if (target == null || mage.territoryColor == null)
            return;

        // Color Defeat (2026-08-14, found in real playtest log review - not caught by the earlier
        // adversarial code review): a mage dispatched BEFORE its color was defeated is already in
        // flight and keeps traveling toward its target regardless - without this check it still
        // successfully captured towns (or worse, could still trigger the Capitol's run-ending
        // forced duel below) for a color that's supposedly been wiped off the map. Confirmed from
        // forge.log: White captured 2 more towns and Black captured 1 more, ALL after their own
        // "[TFR-ColorDefeat] ... DEFEATED" log line, from mages dispatched earlier the same day.
        // Silent fizzle (mirrors the existing "ally already took it" fizzle a few lines below) -
        // the mage sprite itself is unconditionally removed by the caller (WorldStage.java) right
        // after this returns, regardless of what happens here, so nothing further is needed.
        if (WorldSave.getCurrentSave().getWorld().isColorDefeated(mage.territoryColor))
            return;

        // Capitol defense (see field comment above): a mage reaching the player's own Capitol
        // never goes through the ordinary capture flow below - it queues a forced last-chance
        // duel instead. Checked by canonical data.name (immune to the Capitol's "Orazca"
        // displayName), same identification pattern every capital lookup in this class uses.
        if (TownRestoration.CAPITOL_POI_NAME.equals(target.getData().name)) {
            // Guard defense (MOD_SCOPE.md #22, 2026-08-11): both of the Capitol's guards (if
            // hired) must fall before a mage reaches the forced duel at all - resolveGuardDefense()
            // already fights them strongest-first with no weakening between fights.
            if (!resolveGuardDefense(mage, target))
                return;
            pendingCapitolDefenseMage = mage;
            GameHUD.getInstance().addNotification("[RED]" + capitalize(mage.territoryColor) + "'s mage has reached your Capitol!", true);
            return;
        }

        World world = WorldSave.getCurrentSave().getWorld();
        boolean targetNeutral = TownRestoration.isWastelandTown(target.getData());
        String targetOwnerColor = targetNeutral ? null : colorOfOwnedTownForCombat(target.getData());

        PointOfInterestData newData;
        String repaintColor;
        boolean isRevert = false;
        String revertedFromColor = null;
        // "Sacked" (user spec 2026-08-11): distinct from isRevert above (which means the attacker
        // LOST the capture roll) - this means the attacker WON but a separate roll destroyed the
        // town instead of keeping it. Both end up colorless/neutral, but need different messaging.
        boolean isSacked = false;

        if (targetNeutral) {
            // Guard defense + a fair fight for player-owned towns (MOD_SCOPE.md #22, 2026-08-11 -
            // corrects a real gap this same day's research found: isWastelandTown() is a static
            // property of the town's ORIGINAL biome, true for player-owned wasteland-origin towns
            // too, so without this check every player town would fall through to the unconditional
            // flip below exactly like genuinely-unclaimed territory - no roll, no defense at all).
            // Truly-neutral/never-claimed towns are UNCHANGED - this only branches for towns the
            // player has actually restored.
            boolean playerOwnedNow = TownRestoration.isTownRestored(
                    WorldSave.getCurrentSave().peekPointOfInterestChanges(target.getID()));
            // Sacked (see attackerSacksInstead() comment) - only ever rolled below, where an
            // actual fight/roll happened; genuinely-unclaimed neutral land (no contest at all)
            // never sacks, it just claims normally, unchanged from before this feature.
            boolean sackedInstead = false;
            if (playerOwnedNow) {
                if (!resolveGuardDefense(mage, target))
                    return;
                // Outlook now also defends the base capture roll (user spec 2026-08-11 follow-up,
                // extending its guard-fight-only role from earlier the same day) - clamped same as
                // the guard-fight version, though a single -5% off attackerWinChance()'s existing
                // 10/30/70/90 range never actually risks going out of [0,1].
                float captureChance = attackerWinChance(mage.getData().tier);
                if (townHasOutlook(target))
                    captureChance = Math.max(0f, captureChance - OUTLOOK_DEFENSE_BONUS);
                boolean attackerWins = world.getRandom().nextFloat() < captureChance;
                System.out.println("[TFR-CaptureOdds] " + mage.territoryColor + " mage (tier=" + mage.getData().tier
                        + ", chance=" + captureChance + ") attacking player-owned " + target.getDisplayName()
                        + " -> " + (attackerWins ? "CAPTURED" : "REPELLED"));
                if (!attackerWins) {
                    GameHUD.getInstance().addNotification("[GREEN]You repelled " + capitalize(mage.territoryColor)
                            + "'s attack on " + target.getDisplayName() + "!", true);
                    return;
                }
                sackedInstead = !isRingTown(target) && attackerSacksInstead(world); // Ring Towns are captured, never sacked (round 99)
            } else if (isFunctioningNeutralTown(target)) {
                // Functioning Neutral Town defense (2026-08-29 user spec) - see the
                // NEUTRAL_TOWN_BASE_DEFENSE comment for why this is a flat repel chance rather
                // than a modifier on attackerWinChance(). Bare ruins never reach here: they fall
                // through unchanged to the unconditional claim below, exactly as before.
                PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(target.getID());
                boolean armory = TownRestoration.hasWorkingArmory(changes);
                float defense = NEUTRAL_TOWN_BASE_DEFENSE + (armory ? NEUTRAL_TOWN_ARMORY_DEFENSE : 0f);
                float captureChance = Math.max(0f, 1f - defense);
                boolean attackerWins = world.getRandom().nextFloat() < captureChance;
                neutralDefenseAttempts++;
                neutralDefenseExpectedRepels += defense;
                if (!attackerWins)
                    neutralDefenseRepels++;
                System.out.println("[TFR-CaptureOdds] " + mage.territoryColor + " mage (tier=" + mage.getData().tier
                        + ") attacking NEUTRAL " + target.getDisplayName() + " (defense=" + defense
                        + (armory ? ", working Armory" : ", no Armory") + ", chance=" + captureChance
                        + ") -> " + (attackerWins ? "CAPTURED" : "REPELLED")
                        + String.format(" | session repels %d/%d = %.1f%% (expected %.1f = %.1f%%)",
                                neutralDefenseRepels, neutralDefenseAttempts,
                                100f * neutralDefenseRepels / neutralDefenseAttempts,
                                neutralDefenseExpectedRepels,
                                100f * neutralDefenseExpectedRepels / neutralDefenseAttempts));
                if (!attackerWins)
                    return; // town holds; the mage is spent (caller removes the sprite regardless)
                // Deliberately NO attackerSacksInstead() roll here: sacking is an established
                // player-vs-AI war mechanic, and neutral towns were never sackable before this
                // round. Adding the defense roll shouldn't quietly also make them razeable - a
                // successful attack claims the town exactly as it always did.
            }
            if (sackedInstead) {
                // Player-owned towns are renamed only at the DISPLAY level by restoration - the
                // internal data.name (what getPointOfInterest() keys off) stays "Waste Town X"
                // the whole time, so this is already the correct ruin template, no lookup needed.
                newData = PointOfInterestData.getPointOfInterest(target.getData().name);
                repaintColor = "colorless";
                isSacked = true;
            } else {
                newData = matchingTownData(target.getData(), mage.territoryColor);
                repaintColor = mage.territoryColor;
            }
        } else if (targetOwnerColor == null || targetOwnerColor.equals(mage.territoryColor)) {
            // Race condition (documented in MOD_SCOPE.md #7): the target isn't recognizably any
            // color's town right now (already something else - e.g. a capital), or it's already
            // this mage's own color (another of its own mages, or this same one, got there
            // first). Just a state check, not a lock - whichever mage's arrival is processed
            // first wins, the loser's is a no-op.
            return;
        } else if (!isEnemyColor(mage.territoryColor, targetOwnerColor)) {
            // Cross-color targeting (MOD_SCOPE.md #7, user request 2026-08-10): an ALLY of the
            // attacker (or the target owner itself, handled above) took this town since the mage
            // set out - it's no longer a valid target for this color's wheel. Silent fizzle, no
            // capture, no notification (user request).
            return;
        } else {
            // Still a valid enemy-color target: tier-weighted flip-to-attacker or
            // revert-to-neutral (design from MOD_SCOPE.md #7, activated alongside cross-color
            // targeting; reweighted 2026-08-10 by the attacking mage's deck-rarity tier, once
            // mage tiers existed - replaces the original flat 50/50 coin flip).
            // Round 99 (user spec 2026-09-03): the defending AI town's guard dot fights first, exactly like a
            // hired player guard (tier power ratio + the same +10% attacker bonus); the winner then rolls the
            // ordinary capture. A beaten guard falls and the town's guard clock restarts at level 0.
            if (!resolveAiGuardDefense(world, mage, target))
                return; // repelled by the guard; the mage is spent (caller removes the sprite regardless)
            float captureChance = attackerWinChance(mage.getData().tier);
            boolean attackerWins = world.getRandom().nextFloat() < captureChance;
            // Diagnostic-only logging (user request 2026-08-10, "hard to test in-game") -
            // greppable in forge.log as "[TFR-CaptureOdds]".
            boolean sackedInstead = attackerWins && !isRingTown(target) && attackerSacksInstead(world); // Ring Towns: captured or repelled only (round 99)
            System.out.println("[TFR-CaptureOdds] " + mage.territoryColor + " mage (tier=" + mage.getData().tier
                    + ", chance=" + captureChance + ") attacking " + target.getDisplayName() + " (" + targetOwnerColor
                    + ") -> " + (sackedInstead ? "CAPTURED but SACKED" : attackerWins ? "CAPTURED" : "REVERTED to neutral"));
            if (sackedInstead) {
                // Attacker won the capture roll but the separate sack roll destroyed the town
                // instead of keeping it - reuses the SAME waste-template lookup the losing-roll
                // case below already needs (the town's current name still carries the DEFENDER's
                // color pattern at this point, transformInto() hasn't run yet).
                newData = matchingWasteData(target.getData(), targetOwnerColor);
                repaintColor = "colorless";
                isSacked = true;
            } else if (attackerWins) {
                newData = matchingTownData(target.getData(), mage.territoryColor);
                repaintColor = mage.territoryColor;
            } else {
                newData = matchingWasteData(target.getData(), targetOwnerColor);
                repaintColor = "colorless";
                isRevert = true;
                revertedFromColor = targetOwnerColor;
            }
        }
        if (newData == null)
            return;

        String displayName = target.getDisplayName();
        // Read while the OLD id is still valid (transformInto() re-keys the changes lookup) -
        // losing a restored town costs the player its share of the town-count life bonus.
        boolean wasPlayerOwned = TownRestoration.isTownRestored(
                WorldSave.getCurrentSave().peekPointOfInterestChanges(target.getID()));
        // The town's territory may have GROWN past RECOLOR_RADIUS (town expansion, up to
        // TOWN_MAX_TERRITORY_RADIUS) - read its radius under the OLD id, before transformInto()
        // changes it, and repaint the FULL held radius. Repainting only RECOLOR_RADIUS would
        // strand the grown annulus in the previous owner's color forever (verified: expansion only
        // ever claims wasteland, and a player-bit tile is never wasteland, so nothing could ever
        // reclaim it - an orphaned ring around an enemy town, found by the pre-commit review).
        Integer oldRadius = world.getTownTerritoryRadius(target.getID());
        int repaintRadius = Math.max(RECOLOR_RADIUS, oldRadius != null ? oldRadius : RECOLOR_RADIUS);
        target.transformInto(newData, world.getRandom(), true); // ownership changes, the town keeps its name
        if (newData.name != null && newData.name.startsWith("Waste Town Center")) // Center Towns revert to FUNCTIONING neutral towns
            WorldSave.getCurrentSave().getPointOfInterestChanges(target.getID()).getMapFlags().put(TownRestoration.NEUTRAL_SEEDED_FLAG, (byte) 1);
        // Seed the captured town's territory at everything the repaint below actually paints
        // (keyed on the NEW id - getID() derives from data.name, which the transform just
        // changed), and refresh the fog-of-war Revealed cache BEFORE the repaint: if this capture
        // took a town the PLAYER owned, the repaint's per-tile chunk re-bakes consult
        // isCurrentlyVisible(), and the stale cache would bake the lost area as still-bright
        // (order bug found by the pre-commit review).
        world.setTownTerritoryRadius(target.getID(), repaintRadius);
        world.rebuildPlayerTownVision();
        long perfRepaint = System.nanoTime();
        world.repaintBiomeAroundTown(target, repaintColor, repaintRadius,
                WorldStage.getInstance()::refreshBackgroundTile,
                WorldStage.getInstance()::reloadBackgroundChunkObjects);
        System.out.println("[TFR-Perf] AI capture repaint of " + target.getDisplayName() + " (radius " + repaintRadius + ") took "
                + (System.nanoTime() - perfRepaint) / 1_000_000 + " ms");
        // AFTER the repaint - repaint preserves road bits, and the road endpoints key off the
        // town's post-transform identity. Safe to call for a "colorless" revert too -
        // connectCapturedTownByRoad() no-ops cleanly (COLOR_TOWN_NOUN has no "colorless" entry,
        // so it finds no same-owner network to connect to).
        connectCapturedTownByRoad(world, target, repaintColor);
        if (wasPlayerOwned)
            TownRestoration.updateTownLifeBonus(true);
        if (isRingTown(target))
            TownRestoration.updateRingLifeBonus(true); // round 100: a Ring City's +1 life follows its owner

        String message;
        if (isSacked)
            message = displayName + " was sacked by " + capitalize(mage.territoryColor) + " and left in ruins!";
        else if (isRevert)
            message = displayName + " breaks free from " + capitalize(revertedFromColor) + " - reverts to neutral!";
        else
            message = displayName + " has fallen to " + capitalize(mage.territoryColor) + "!";
        System.out.println("[TerritoryControl] " + message);
        GameHUD.getInstance().addNotification(message);

        // New lose condition (2026-08-15 user request): "if there are no neutral towns left and
        // the player does not own any towns, they also lose" - covers a player who never built up
        // any territory while the 5 colors absorbed every neutral town. Checked ONLY here, after a
        // completed ownership change - every path that could make it true is an AI capture ending
        // in this common tail (towns never despawn via dungeon rotation, capital repair never
        // consumes neutrals, sacks/reverts CREATE neutrals, and the Capitol's own fall already
        // ends the run via the forced-duel path before ever reaching this tail). Count semantics
        // (see getTownCounts()'s own doc): a player-restored town counts in BOTH "Player" and
        // "Colorless" (restoration is a flag, the "Waste Town" name stays), so Colorless==0 &&
        // Player==0 is exactly "no neutrals AND player owns nothing" with no double-count risk;
        // the capitolExists() term is redundant with Player==0 but self-documenting.
        Map<String, Integer> loseCheckCounts = getTownCounts(world);
        if (loseCheckCounts.getOrDefault("Colorless", 0) == 0
                && loseCheckCounts.getOrDefault("Player", 0) == 0
                && !TownRestoration.capitolExists()) {
            System.out.println("[TFR-GameLost] no neutral towns remain and player owns nothing - run over");
            WorldStage.getInstance().triggerGameLost("[RED]The last free town has fallen![]\n"
                    + "Every town in the realm now flies an enemy banner, and none fly yours. "
                    + "With nothing left to liberate and nowhere to build, your cause is lost.");
        } else {
            checkStarTownLoss(world); // Center Towns (MOD_SCOPE #102) - else-branch so two run-over dialogs never stack
        }
    }

    /** Ring Towns (round 99): the five Center Towns, whatever their current owner. */
    public static boolean isRingTown(PointOfInterest poi) {
        return poi != null && poi.getData() != null && poi.getData().name != null && poi.getData().name.contains(" Town Center");
    }
    private static int ringTileX(World world, PointOfInterest poi) { return (int) (poi.getPosition().x / world.getTileSize()); }
    private static int ringTileY(World world, PointOfInterest poi) { return (int) (poi.getPosition().y / world.getTileSize()); }

    /**
     * Round 99 (user spec 2026-09-03): AI-vs-AI guard fight. Guard level -> tier (Apprentice = Common ...
     * Archmage = Mythic; level 4 = Archmage with two lands, Mythic power x aiGuardTwoLandPowerFactor), then
     * the hired-guard formula: attacker / (attacker + defender) + GUARD_FIGHT_ATTACKER_BONUS. Returns false
     * when the guard repels the attacker. When the guard falls the town's guard clock restarts (level 0,
     * held since today). Logged [TFR-AiGuardFight].
     */
    private static boolean resolveAiGuardDefense(World world, EnemySprite mage, PointOfInterest target) {
        if (!Config.instance().getTuningData().aiTownGuardDefenseEnabled)
            return true;
        PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(target.getID());
        int level = changes == null ? 0 : changes.getAiGuardLevel();
        if (level <= 0)
            return true;
        String[] tiers = EconomyBuildings.GUARD_TIERS_ASCENDING;
        String guardTier = tiers[Math.min(level, tiers.length - 1)];
        float twoLands = level >= AI_GUARD_MAX_LEVEL ? Math.max(1f, Config.instance().getTuningData().aiGuardTwoLandPowerFactor) : 1f;
        float attackerPower = tierPower(mage.getData().tier);
        float defenderPower = tierPower(guardTier) * twoLands;
        float chance = Math.max(0f, Math.min(1f, attackerPower / (attackerPower + defenderPower) + GUARD_FIGHT_ATTACKER_BONUS));
        boolean attackerWins = world.getRandom().nextFloat() < chance;
        System.out.println("[TFR-AiGuardFight] " + mage.territoryColor + " mage (tier=" + mage.getData().tier + ") vs level " + level
                + " " + EconomyBuildings.guardTierDisplayName(guardTier) + (twoLands > 1f ? " (two lands)" : "") + " guard at "
                + target.getDisplayName() + " (chance=" + chance + ") -> " + (attackerWins ? "GUARD FALLS" : "REPELLED"));
        if (attackerWins) {
            changes.setAiGuardLevel(0);
            changes.setAiHeldSinceDay(world.getCurrentDay());
        }
        return attackerWins;
    }

    /**
     * Round 100 (user spec 2026-09-03): the run is WON when the player holds all five Ring Cities and has
     * taken all five AI capitals. Checked after every player capture. Logged [TFR-Victory].
     */
    public static void checkPlayerVictory(World world) {
        java.util.List<int[]> tiles = world.getStarTownTiles();
        if (tiles == null || tiles.isEmpty())
            return;
        int held = 0;
        for (int[] tile : tiles)
            if (ringTownPlayerHeld(world, tile))
                held++;
        int capitals = world.getCapitolLostColors().size();
        System.out.println("[TFR-Victory] Ring Cities held " + held + "/" + tiles.size() + ", AI capitals taken " + capitals + "/" + COLOR_TOWN_NOUN.size());
        if (held >= tiles.size() && capitals >= COLOR_TOWN_NOUN.size())
            WorldStage.getInstance().triggerGameWon("[GREEN]The Ring is whole![]\n"
                    + "All five Ring Cities stand under your banner and every Lord's capital has fallen. "
                    + "The Ring's councils reach for their seals - and find you are no longer someone who can be sealed. "
                    + "The Forsaken Realms are yours to keep.");
    }
    private static boolean ringTownPlayerHeld(World world, int[] tile) {
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if ((int) (poi.getPosition().x / world.getTileSize()) != tile[0] || (int) (poi.getPosition().y / world.getTileSize()) != tile[1])
                continue;
            return TownRestoration.isTownRestored(WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID()));
        }
        return false;
    }

    /** Owner color of the star town at a recorded tile, or null (neutral / player / missing). */
    private static String starTownOwner(World world, int[] tile) {
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if ((int) (poi.getPosition().x / world.getTileSize()) != tile[0] || (int) (poi.getPosition().y / world.getTileSize()) != tile[1])
                continue;
            if (TownRestoration.isTownRestored(WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID())))
                return null;
            return ColorReputation.colorOfTown(poi.getData());
        }
        return null;
    }

    /**
     * Center Towns (MOD_SCOPE #102, user spec 2026-09-03): the run is lost when ONE AI color holds
     * TuningData.starTownsLossCount of the five star towns around the campfire. Runs from
     * onMageArrived()'s tail like the other loss path; inert on worlds without recorded star tiles.
     */
    private static void checkStarTownLoss(World world) {
        java.util.List<int[]> tiles = world.getStarTownTiles();
        if (tiles == null || tiles.isEmpty())
            return;
        int needed = Config.instance().getTuningData().starTownsLossCount;
        if (needed <= 0)
            needed = 3;
        Map<String, Integer> held = new HashMap<>();
        for (int[] tile : tiles) {
            String owner = starTownOwner(world, tile);
            if (owner != null)
                held.merge(owner, 1, Integer::sum);
        }
        System.out.println("[TFR-StarTowns] holdings " + held + " (loss at " + needed + " of " + tiles.size() + ")");
        for (Map.Entry<String, Integer> h : held.entrySet()) {
            String colorName = Character.toUpperCase(h.getKey().charAt(0)) + h.getKey().substring(1);
            if (h.getValue() >= needed) {
                System.out.println("[TFR-GameLost] " + h.getKey() + " holds " + h.getValue() + " Center Towns - run over");
                WorldStage.getInstance().triggerGameLost("[RED]The Star has fallen![]\n"
                        + colorName + " now holds " + h.getValue() + " of the " + tiles.size()
                        + " Center Towns around the campfire. With the heart of the realm in enemy hands, your cause is lost.");
                return;
            }
            if (h.getValue() == needed - 1)
                GameHUD.getInstance().addNotification("[RED]" + colorName + " holds " + h.getValue()
                        + " Center Towns - one more and the realm falls![]", true);
        }
    }

    // "Waste Town Identity" + "green" -> "Forest Town Identity" - keeps the same Generic/Identity/
    // Tribal sub-variant the source town already was, just re-themed to the capturing color.
    // Generalized 2026-08-10 for cross-color captures (was Waste-Town-only, matched by prefix) -
    // now matches "<AnyNoun> Town <suffix>" by locating " Town " directly, so it works whether the
    // source is neutral ("Waste Town X") or another color's town ("Swamp Town X"). Deliberately
    // TOWN-only, never CAPITAL (see findAttackableTowns()) - a captured capital has no cross-color
    // equivalent to swap to.
    private static PointOfInterestData matchingTownData(PointOfInterestData fromData, String color) {
        String noun = COLOR_TOWN_NOUN.get(color);
        if (noun == null || fromData.name == null)
            return null;
        int townIdx = fromData.name.indexOf(" Town ");
        if (townIdx < 0)
            return null;
        String suffix = fromData.name.substring(townIdx + " Town ".length());
        return PointOfInterestData.getPointOfInterest(noun + " Town " + suffix);
    }

    // True for a color's own "<Noun> Capital" or "<Noun> Town <Variant>" - the entries
    // neutralizeAfterGeneration() sweeps, mirroring isWastelandTown()'s equivalent check for the
    // opposite direction (a neutral town, not yet captured by anyone).
    private static boolean isColorTownOrCapital(PointOfInterestData data, String color) {
        String noun = COLOR_TOWN_NOUN.get(color);
        if (noun == null || data.name == null)
            return false;
        return data.name.equals(noun + " Capital") || data.name.startsWith(noun + " Town");
    }

    // Inverse of matchingTownData(): "Forest Town Identity" -> "Waste Town Identity". "Forest
    // Capital" has no direct Waste Town equivalent (colorless has no "capital" POI type at all) -
    // falls back to "Waste Town Generic" rather than being left as a color's own capital sitting
    // on now-neutral ground.
    static PointOfInterestData matchingWasteData(PointOfInterestData colorData, String color) { // package-private: TownRestoration.captureTownForPlayer()
        String noun = COLOR_TOWN_NOUN.get(color);
        if (noun == null || colorData.name == null)
            return null;
        if (colorData.name.equals(noun + " Capital"))
            return PointOfInterestData.getPointOfInterest("Waste Town Generic");
        if (colorData.name.startsWith(noun + " Town")) {
            String suffix = colorData.name.substring((noun + " Town").length()).trim();
            return PointOfInterestData.getPointOfInterest("Waste Town " + suffix);
        }
        return null;
    }

    // ==================== Color Defeat (MOD_SCOPE.md #61, user request 2026-08-14) ====================
    // Endgame consequence for beating one of the 5 colored castles: that color's whole holding -
    // every town, its capital, and all owned terrain, anywhere on the map - reverts to neutral
    // wasteland, and 4 escalating consequences fire for the survivors. See MOD_CHANGELOG.md for
    // the full design writeup; this block is the entire implementation.

    // Boss-defeat dialog action ({"setQuestFlag": {"key":"Ch1BlackCastleComplete", "val": 1}} in
    // each castle's own .tmx, confirmed by reading black_castle_f1.tmx directly) is the ONE real
    // call site that ever sets these 5 flags - AdventurePlayer.setQuestFlag() is hooked below (via
    // onCastleQuestFlagSet()) to catch it the moment it fires, regardless of whether the "Rescue
    // the Captive" quest STAGE that also reads this flag completes correctly (a separate, pre-
    // existing concern this feature doesn't depend on - only the flag write itself matters here).
    // (2026-08-15 review finding: this comment previously said "MapStage.setQuestFlag()" - that
    // method backs the differently-named "setMapFlag" JSON action and was the wrong hook point,
    // caught by the 2026-08-14 adversarial review; AdventurePlayer.setQuestFlag() is the real one.)
    private static final Map<String, String> CASTLE_COMPLETE_FLAG_TO_COLOR = new HashMap<>();
    static {
        CASTLE_COMPLETE_FLAG_TO_COLOR.put("Ch1BlackCastleComplete", "black");
        CASTLE_COMPLETE_FLAG_TO_COLOR.put("Ch1BlueCastleComplete", "blue");
        CASTLE_COMPLETE_FLAG_TO_COLOR.put("Ch1GreenCastleComplete", "green");
        CASTLE_COMPLETE_FLAG_TO_COLOR.put("Ch1RedCastleComplete", "red");
        CASTLE_COMPLETE_FLAG_TO_COLOR.put("Ch1WhiteCastleComplete", "white");
    }

    /** Called from MapStage.setQuestFlag() (real in-game trigger) and the "defeat castle" test
     *  console command (same call, just fired from outside the castle map) - public so both call
     *  sites, in different packages, can reach it. No-ops for any flag name that isn't one of the
     *  5 above, or a value < 1 (a flag being cleared, not set - shouldn't happen for these in
     *  practice, but defeat should never un-fire on a stray write). */
    public static void onCastleQuestFlagSet(String flagName, int value) {
        String color = CASTLE_COMPLETE_FLAG_TO_COLOR.get(flagName);
        if (color == null || value < 1)
            return;
        defeatColor(WorldSave.getCurrentSave().getWorld(), color);
    }

    /** Reverse of CASTLE_COMPLETE_FLAG_TO_COLOR's lookup direction, for the "defeat castle" test
     *  console command (ConsoleCommandInterpreter, a different package) to set the real flag on a
     *  castle's own POI before triggering the same consequence a real boss kill would. Reconstructs
     *  via the same capitalize() this file already uses everywhere else, rather than a second map
     *  to keep in sync with CASTLE_COMPLETE_FLAG_TO_COLOR's own values. */
    public static String castleCompleteFlagName(String color) {
        if (COLOR_TOWN_NOUN.get(color) == null)
            return null;
        return "Ch1" + capitalize(color) + "CastleComplete";
    }

    /**
     * The whole endgame consequence, idempotent (safe to call more than once - only the first call
     * per color does anything). Order: terrain/town revert first (so the notification/log below
     * can't ever fire without the actual world state change already applied), then the 4
     * consequences, per user spec:
     * <ul>
     * <li>Reputation: flat -50 to the defeated color, deliberately NOT zero-sum (ColorReputation's
     * net-zero invariant is for duel events - a color being wiped off the map isn't one, and
     * redistributing the negation to the 4 survivors would be inventing a rule never asked for).
     * The other 4 colors' reputation tracks are otherwise untouched and keep working normally
     * (user: "we will still keep the reputation system work of all 5 colors").</li>
     * <li>+1 simultaneous attacking-mage slot for every surviving color, stacking per additional
     * defeat (maxActiveMagesPerColor() reads World.getDefeatedColorCount() directly - nothing
     * further needed here beyond marking the color defeated).</li>
     * <li>Attacker tier distribution shifts toward Master/Archmage for every surviving color's
     * FUTURE dispatches, also stacking per defeat (rollDispatchMageTier() likewise reads
     * getDefeatedColorCount() directly).</li>
     * <li>The defeated color's 2 surviving allies each get a one-shot "next dispatch must target a
     * player town" flag (dispatch() consumes it - see that method's own comment for what happens
     * if the player owns nothing yet).</li>
     * </ul>
     */
    public static void defeatColor(World world, String color) {
        if (!isEnabled() || world == null || world.isColorDefeated(color))
            return;

        PointOfInterest castle = findCastle(world, color);
        if (castle == null) {
            // No sane anchor to sweep terrain from (a save predating this color ever placing a
            // castle, or a stale test call) - still apply every consequence below, just skip the
            // terrain/town revert since there's nothing to anchor it to.
            System.out.println("[TFR-ColorDefeat] " + color + ": no castle found, applying consequences without a terrain sweep");
        } else {
            // Full sweep, not neutralizeAfterGeneration()'s "outside a keep radius" version -
            // radius 0 means only the exact castle tile itself is close enough to "stay this
            // color" (dx*dx+dy*dy <= 0), so every OTHER tile this color currently owns anywhere on
            // the map (including ground far beyond any single town's radius, grown over many days
            // by processTerritoryExpansion()) flips to colorless. Reuses the exact primitive
            // world-gen already trusts for "revert everything a color owns" - see that method's
            // own doc comment for why a full-map scan is acceptable here (one-time, not per-frame).
            world.neutralizeTerritoryOutsideRadius(color, castle.getPosition(), 0,
                    WorldStage.getInstance()::refreshBackgroundTile,
                    WorldStage.getInstance()::reloadBackgroundChunkObjects);

            // Town/Capital POIs: the biomeMap flip above changes what the GROUND looks like, but
            // the town/capital POI objects themselves (their shops, their "this is a functioning
            // town" state) are separate game objects that need their own transformInto() - same
            // two-part pattern neutralizeAfterGeneration() uses at world-gen. isColorTownOrCapital()
            // matches on CURRENT data.name, which transformInto() keeps up to date on every
            // capture/recapture, so this correctly catches every town this color holds RIGHT NOW,
            // whether it's an original world-gen town or one captured from a rival color earlier
            // in this game.
            int converted = 0;
            for (PointOfInterest poi : new ArrayList<>(world.getAllPointOfInterest())) {
                if (!isColorTownOrCapital(poi.getData(), color))
                    continue;
                PointOfInterestData wasteData = matchingWasteData(poi.getData(), color);
                if (wasteData == null)
                    continue;
                poi.transformInto(wasteData, world.getRandom(), true); // keep the town's given name
                converted++;
            }
            world.setColorTerritoryRadius(color, 0);
            // Deliberately NOT calling world.regenerateDoodadsForBiome("waste") here (adversarial
            // review 2026-08-14 caught a real bug in an earlier version that did): unlike
            // neutralizeAfterGeneration()'s ONE-TIME world-gen call (after all 5 colors' sweeps are
            // already done), this fires per-defeat, mid-game - a full-map scan/re-placement over
            // EVERY tile currently classified "waste", not just this defeat's newly-converted ones,
            // using World's live shared Random. A 2nd/3rd/etc. defeat would silently strip and
            // re-randomize rocks/trees on ground an EARLIER defeat already settled (and the player
            // may have already explored), as an unrelated side effect - plus a real perf cost,
            // repeated per defeat instead of once. Trade-off accepted: this color's original
            // doodads linger cosmetically on the newly-neutral ground (rocks/trees, not buildings -
            // those already reskin correctly via neutralizeTerritoryOutsideRadius()) rather than
            // risk visibly rewriting ground the player has already seen.
            System.out.println("[TFR-ColorDefeat] " + color + ": terrain swept to neutral, " + converted + " town/capital POI(s) reverted");
        }

        world.setColorDefeated(color);
        // Discoverability (2026-08-15 review finding): WorldStandingsScene reads this to tag the
        // defeated color's row instead of showing a bare, indistinguishable-from-"hasn't expanded
        // yet" 0.
        world.setColorDefeatDay(color, world.getCurrentDay());
        // A forced-target flag armed FOR this color by an earlier defeat (it was one of that
        // color's surviving allies at the time) can never be consumed now - dispatch() is never
        // called again for a defeated color (adversarial review 2026-08-14: without this, the flag
        // is orphaned in the persisted Set forever, harmlessly but permanently).
        world.clearForcedPlayerTarget(color);

        if (ColorReputation.isEnabled())
            ColorReputation.applyColorDefeatPenalty(color);

        // Forced-next-attack (user spec): only the 2 colors ADJACENT to the defeated one on the
        // wheel (its allies, per the same ALLIES table reputation/targeting already share), and
        // only if that ally is itself still alive - a dead color never dispatches again, so arming
        // it would just be a flag nobody ever consumes. Worked example (corrected 2026-08-14 -
        // adversarial review caught the original text misreading its own ALLIES table): White's
        // allies are Green and Blue (ALLIES.put("white", {"green","blue"}), not Red - Red is
        // White's ENEMY). So if Green is already dead and White then falls, this arms only Blue
        // (Green's other neighbor is already gone) - no widening to White's enemies.
        List<String> armedAllies = new ArrayList<>();
        for (String ally : ALLIES.getOrDefault(color, new String[0])) {
            if (world.isColorDefeated(ally))
                continue;
            world.armForcedPlayerTarget(ally);
            armedAllies.add(ally);
        }

        int defeats = world.getDefeatedColorCount();
        System.out.println("[TFR-ColorDefeat] " + color + " DEFEATED (total defeated: " + defeats
                + ") - rep penalty applied=" + ColorReputation.isEnabled()
                + ", survivors' mage cap +" + defeats + ", forced-next-attack armed for " + armedAllies);

        String message = capitalize(color) + " has fallen! Its lands crumble to ruin.";
        GameHUD.getInstance().addNotification("[RED]" + message, true);
    }
}
