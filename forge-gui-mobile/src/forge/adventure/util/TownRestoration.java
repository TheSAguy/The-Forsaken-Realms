package forge.adventure.util;

import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Array;
import forge.adventure.data.ConfigData;
import forge.adventure.data.DialogData;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.TileMapScene;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.WorldSave;

/**
 * Central Wasteland town reconstruction (MOD_SCOPE.md #2), first pass: towns in the colorless
 * "Wastes" biome (the existing stand-in for "the middle of the map" until the full territory
 * system exists) start destroyed. The Job Board must be restored before any of its shops can be
 * individually rebuilt. All state is stored as ordinary per-town map flags (the same mechanism
 * used by scripted maps like waste_town_abandoned.tmx), so it persists via the existing save
 * system with no new save-file fields needed.
 */
public class TownRestoration {
    public static final String TOWN_RESTORED_FLAG = "townRestored";
    // Functioning Neutral Towns (2026-08-24 user spec) - a per-instance exemption, deliberately
    // NOT the same flag as TOWN_RESTORED_FLAG. TOWN_RESTORED_FLAG is overloaded across the
    // codebase to mean both "not ruined" (ShopActor/QuestActor/OnCollide/MapStage) AND "this is
    // now the player's own territory" (TerritoryControl's many isTownRestored() ownership/
    // expansion checks) - reusing it here would silently grant the player ownership of these
    // towns, which is the opposite of "neutral". This flag only ever short-circuits
    // isWastelandTown()/getBrokenTownSprite() below (the "does this look/act ruined right now"
    // checks) - it is never read by EditionProgression or TerritoryControl, so shops still fall
    // through to the ordinary "no color match -> NEUTRAL" branch, and the town never counts as
    // player-owned. See TownRestoration.seedFunctioningNeutralTowns().
    public static final String NEUTRAL_SEEDED_FLAG = "neutralSeeded";
    // 2026-08-12 user cost table (multi-resource; see EconomyBuildings' cost helpers).
    // Wood component halved 2026-08-21 (v1.00 feedback round) - gold untouched.
    private static final int RESTORE_COST_GOLD = 200;
    private static final int RESTORE_COST_WOOD = 5;

    // Biome json ("colorless.json") whose name pool (town_names_waste.txt) names wasteland towns.
    private static final String WASTE_BIOME_NAME = "waste";

    /**
     * One-time repair for saves whose world generated while the town-name pool was drained (the
     * pre-2026-08-08 rerun-drain bug): any wasteland town still carrying its POI template's
     * generic name ("Waste Town Generic"/"Identity"/"Tribal") gets a fresh unique name from the
     * waste biome's pool. Idempotent - once every town has a real name this scans and does
     * nothing. Called from World.load(); inert unless townReconstructionEnabled (the
     * isWastelandTown() gate), so stock planes never reach the rename.
     * <p>
     * Deliberately NOT applied to quest text: quest strings bake their target's display name at
     * quest-generation time, so quests accepted before the repair keep mentioning the old generic
     * name while their map arrows still point at the right (now renamed) town. New quests pick up
     * the new names.
     */
    public static void migrateGenericTownNames(forge.adventure.world.World world) {
        java.util.HashSet<String> usedNames = new java.util.HashSet<>();
        java.util.List<PointOfInterest> needRename = new java.util.ArrayList<>();
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (!isWastelandTown(poi.getData()))
                continue;
            if (poi.getDisplayName().equals(poi.getData().name))
                needRename.add(poi);
            else
                usedNames.add(poi.getDisplayName());
        }
        if (needRename.isEmpty())
            return;
        forge.adventure.data.BiomeData wasteBiome = null;
        for (forge.adventure.data.BiomeData biome : world.getData().GetBiomes()) {
            if (WASTE_BIOME_NAME.equals(biome.name)) {
                wasteBiome = biome;
                break;
            }
        }
        if (wasteBiome == null) {
            System.out.println("[TownRestoration] name repair skipped - no '" + WASTE_BIOME_NAME + "' biome found");
            return;
        }
        int renamed = 0;
        for (PointOfInterest poi : needRename) {
            String newName = null;
            for (int attempt = 0; attempt < 500; attempt++) {
                newName = wasteBiome.getNewTownName();
                if (newName == null || !usedNames.contains(newName))
                    break;
            }
            if (newName == null) {
                System.out.println("[TownRestoration] name repair stopped - town name list unavailable/empty");
                break;
            }
            usedNames.add(newName);
            poi.setDisplayName(newName);
            renamed++;
        }
        if (renamed > 0)
            System.out.println("[TownRestoration] renamed " + renamed + " generic-named wasteland town(s)");
    }

    /**
     * Functioning Neutral Towns (2026-08-24 user spec: "10 out of the 60 neutral towns should
     * not be ruined, but actual functioning neutral towns... shops in these towns should all
     * still be gated to only sell cards from expansions neutral has access to"). Called once,
     * from World.generateNew() right after TerritoryControl.neutralizeAfterGeneration() (so the
     * final, full pool of neutral towns - including any AI-color towns just swept back to
     * neutral - is already known), gated by the caller on isFunctioningNeutralTownsEnabled().
     * <p>
     * Deliberately does NOT set TOWN_RESTORED_FLAG - that flag also means "this is the player's
     * own territory" everywhere TerritoryControl checks ownership, which would silently steal
     * these towns away from "neutral" into "player-owned". Setting only NEUTRAL_SEEDED_FLAG
     * (checked by isWastelandTown()/getBrokenTownSprite() above) makes the town render/act like
     * an ordinary functioning town everywhere else in the game, while EditionProgression's shop-
     * restriction code still falls through to its normal "no color name match -> NEUTRAL" branch
     * (colorOfTown() never matches a "Waste Town..." name), and TerritoryControl's ownership/
     * standings bucketing (all keyed off isTownRestored(), never isWastelandTown()) never sees it
     * as anything but neutral.
     */
    public static void seedFunctioningNeutralTowns(forge.adventure.world.World world) {
        java.util.List<PointOfInterest> candidates = new java.util.ArrayList<>();
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (isWastelandTown(poi.getData()))
                candidates.add(poi);
        }
        int target = Config.instance().getTuningData().functioningNeutralTownCount;
        java.util.Collections.shuffle(candidates, world.getRandom());
        // Center Towns (MOD_SCOPE #102, user spec 2026-09-03): the five star towns are ALWAYS functioning
        // neutral towns - moved to the front so the seeding loop below takes them first, and added to
        // the target so they do not eat into the ordinary random count.
        java.util.List<PointOfInterest> starFirst = new java.util.ArrayList<>();
        for (java.util.Iterator<PointOfInterest> it = candidates.iterator(); it.hasNext(); ) {
            PointOfInterest c = it.next();
            if (c.getData().name != null && c.getData().name.startsWith("Waste Town Center")) {
                starFirst.add(c);
                it.remove();
            }
        }
        candidates.addAll(0, starFirst);
        target += starFirst.size();
        int seeded = 0;
        int totalBroken = 0;
        for (int i = 0; i < candidates.size() && seeded < target; i++) {
            PointOfInterest poi = candidates.get(i);
            PointOfInterestChanges changes = WorldSave.getCurrentSave().getPointOfInterestChanges(poi.getID());
            changes.getMapFlags().put(NEUTRAL_SEEDED_FLAG, (byte) 1);
            // Randomly broken, unrepairable shop slots (2026-08-24 user spec: "randomly have some
            // of the shops be broken, and can't be repaired... randomly 1-5 broken shops/armory
            // per town"). 1-5 of the 9 fixed shop slots in player_town.tmx (see
            // PLAYER_TOWN_SHOP_OBJECT_IDS's own comment) get PERMANENTLY_BROKEN_SHOP_FLAG_PREFIX
            // set - checked by ShopActor.isDestroyed(), completely separate from the ordinary
            // shopRebuilt_<id> flag (that one means "still needs the player to pay to fix it";
            // these towns are never restored, so there's no repair dialog to route through at
            // all - this state has no path back to "fixed").
            java.util.List<Integer> shopIds = new java.util.ArrayList<>();
            for (int id : PLAYER_TOWN_SHOP_OBJECT_IDS) shopIds.add(id);
            java.util.Collections.shuffle(shopIds, world.getRandom());
            int brokenCount = 1 + world.getRandom().nextInt(5); // 1-5 inclusive
            for (int b = 0; b < brokenCount; b++)
                changes.getMapFlags().put(permanentlyBrokenShopFlag(shopIds.get(b)), (byte) 1);
            totalBroken += brokenCount;
            seeded++;
        }
        // [TFR-NeutralTowns] - diagnostic logging (2026-08-24 user request), same greppable-tag
        // convention as every other hard-to-observe/probabilistic mechanic this mod has added.
        // Logs the shortfall explicitly rather than silently seeding fewer than requested, since
        // "fewer neutral towns exist than the requested count" is exactly the kind of map-gen
        // interaction (see the placement-failure logging added alongside this) worth surfacing.
        System.out.println("[TFR-NeutralTowns] seeded " + seeded + "/" + target
                + " functioning neutral towns out of " + candidates.size() + " wasteland town candidate(s), "
                + totalBroken + " total permanently-broken shop slot(s) across them");
    }

    // The 9 shop slot object ids inside maps/map/towns/player_town.tmx - confirmed by direct
    // read: this is the SAME map file every Waste Town instance uses regardless of template
    // (Generic/Identity/Tribal all point at it), so these ids are stable across every functioning
    // neutral town. 8 ordinary card-shop slots (broad commonShopList/rareShopList/
    // uncommonShopList pool) + 1 dedicated Armory slot (id 48, the only one whose
    // commonShopList is the single value "Equipment") - matches the user's own framing exactly
    // ("8 possible shops and 1 armory").
    private static final int[] PLAYER_TOWN_SHOP_OBJECT_IDS = {41, 55, 57, 50, 51, 52, 53, 54, 48};

    private static String permanentlyBrokenShopFlag(int objectId) {
        return "permanentlyBrokenShop_" + objectId;
    }

    /** Used by ShopActor (rendering/interaction, has a live MapStage) - same access pattern as
     *  isShopRebuilt(MapStage, int). */
    public static boolean isPermanentlyBrokenShop(MapStage stage, int objectId) {
        return stage.checkQuestFlag(permanentlyBrokenShopFlag(objectId));
    }

    /**
     * The Armory's fixed object id inside player_town.tmx - the one slot of the 9 in
     * PLAYER_TOWN_SHOP_OBJECT_IDS whose commonShopList is the single value "Equipment"
     * (see that array's own comment: "8 possible shops and 1 armory").
     */
    public static final int ARMORY_SHOP_OBJECT_ID = 48;

    /**
     * Does this town still have a WORKING Armory - i.e. its Armory slot wasn't one of the 1-5
     * slots seedFunctioningNeutralTowns() permanently broke? (2026-08-29 user spec: a neutral
     * town's base defense gets a bonus "if they have a working Armory inside".)
     * <p>
     * Meaningful precisely because that breakage is random per town: every functioning neutral
     * town is rendered from the same player_town.tmx and therefore always HAS an Armory slot, so
     * mere presence would be a constant. Whether it survived seeding is the real variable -
     * roughly two thirds of towns keep it (1-5 of 9 slots broken, uniformly chosen).
     * Permanently-broken is terminal by design - these towns are never restored, so there is no
     * repair path back to working.
     */
    public static boolean hasWorkingArmory(PointOfInterestChanges changes) {
        return changes != null
                && changes.getMapFlags().get(permanentlyBrokenShopFlag(ARMORY_SHOP_OBJECT_ID)) == null;
    }

    // Overworld icon for a wasteland town the PLAYER has personally restored (2026-08-25 user
    // spec: "Currently we're using the Neutral one... since we now have neutral towns that work,
    // we need our own" - a restored player town and a functioning-neutral-seeded town previously
    // looked identical, both falling through to the shared default "WasteTown" sprite). Custom
    // art kept plane-local, same convention as the broken-town art below. Single static frame
    // (unlike the 16-variant broken art) - one consistent look for "this is my town" is the point.
    private static final String PLAYER_TOWN_ATLAS = "maps/tileset/playertown.atlas";
    private static final String PLAYER_TOWN_SPRITE = "PlayerTown";
    private static Sprite playerTownSprite;

    /**
     * The overworld icon for a player-restored wasteland town, or null if this isn't one (not a
     * Waste Town template, not restored, or a functioning-neutral-seeded town - those keep the
     * ordinary shared "WasteTown" look, they were never ruined and aren't the player's). Mirrors
     * getBrokenTownSprite()'s own gating below, just for the opposite (restored, not ruined) case.
     * <p>
     * Explicitly excludes the Capitol (2026-08-25 bug found via playtest: "Player's Capitol Icon
     * still looks like a player town, not capitol" on the main map) - isWastelandTown(data)
     * matches on type=="capital" too (by design, so a not-yet-upgraded ruin can still show broken
     * art before it becomes the Capitol), and the Capitol is always "restored" by definition, so
     * without this guard every Capitol matched both the wasteland-template AND restored checks
     * above and got the generic PlayerTown sprite instead of its own dedicated Capitol art.
     * getBrokenTownSprite() never had this problem (its restored-check runs the OPPOSITE
     * direction - it returns null once restored, and the Capitol is always restored), but this
     * method's restored-check runs the same direction as "is Capitol", so it needs its own guard.
     */
    public static TextureRegion getPlayerTownSprite(PointOfInterest point) {
        if (point == null || isNeutralSeeded(point.getID()) || !isWastelandTown(point.getData()))
            return null;
        if (CAPITOL_POI_NAME.equals(point.getData().name))
            return null;
        PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(point.getID());
        if (!isTownRestored(changes))
            return null;
        if (playerTownSprite == null)
            playerTownSprite = Config.instance().getAtlas(PLAYER_TOWN_ATLAS).createSprite(PLAYER_TOWN_SPRITE);
        return playerTownSprite;
    }

    // Overworld icon for a destroyed wasteland town, custom art kept plane-local so it can never
    // show up on Shandalar or any other stock plane. All 16 variants share one atlas region name
    // so Forge's existing PointOfInterest.spriteIndex machinery could pick among them the normal
    // way; we don't use that path here (see getBrokenTownSprite()) since spriteIndex was already
    // collapsed to a constant for "WasteTown" (whose real atlas only has 1 frame) before this art
    // existed, so a fresh, independently-seeded pick was needed instead.
    private static final String BROKEN_WASTETOWN_ATLAS = "maps/tileset/wastetown_broken.atlas";
    private static final String BROKEN_WASTETOWN_SPRITE = "WasteTownBroken";
    private static Array<Sprite> brokenWasteTownSprites;

    // Shared by isWastelandTown() and getBrokenTownSprite() below - both already have a concrete
    // PointOfInterest instance (not just the shared template PointOfInterestData every other
    // isWastelandTown(data) caller works with), so both can check this per-instance exemption.
    private static boolean isNeutralSeeded(String poiId) {
        if (poiId == null)
            return false;
        PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(poiId);
        return changes != null && changes.getMapFlags().get(NEUTRAL_SEEDED_FLAG) != null;
    }

    public static boolean isWastelandTown() {
        PointOfInterest point = TileMapScene.instance().rootPoint;
        if (point != null && isNeutralSeeded(point.getID()))
            return false;
        return point != null && isWastelandTown(point.getData());
    }

    /** Is the CURRENTLY-LOADED map built from the wasteland/player_town template - a question
     *  about the MAP FILE (does it have baked building art?), NOT about ruined state. Deliberately
     *  does NOT apply the NEUTRAL_SEEDED_FLAG exemption isWastelandTown() above does (2026-08-24
     *  bug found via playtest: "the shops are not showing up" in a functioning neutral town) - a
     *  functioning neutral town is STILL rendered from player_town.tmx, which has no Walls layer
     *  and almost no baked building tiles (confirmed: 5 non-empty cells total vs. ~144 for an
     *  ordinary color town), so ShopActor's fallback building icon (see its own isWastelandTown()
     *  gate) is still required, or the shop draws nothing at all - only its floating sign, which
     *  is exactly what got reported ("Elf tribal"/"Eldrazi" bulletin boards with no buildings
     *  underneath). Use this instead of isWastelandTown() for "does this map need a fallback
     *  building sprite drawn", never for "is this town ruined" (that's still isWastelandTown()). */
    public static boolean isWastelandTownTemplate() {
        PointOfInterest point = TileMapScene.instance().rootPoint;
        return point != null && isWastelandTown(point.getData());
    }

    public static boolean isWastelandTown(PointOfInterestData data) {
        // Opt-in per-plane via config.json ("townReconstructionEnabled": true), same pattern as
        // fog of war and the day/night cycle, so this never affects Shandalar or any other plane
        // that hasn't explicitly turned it on.
        ConfigData configData = Config.instance().getConfigData();
        if (configData == null || !configData.townReconstructionEnabled)
            return false;

        if (data == null || data.questTags == null)
            return false;
        // The colorless-biome tag alone isn't specific to towns - dungeons/caves placed in the
        // same biome share it too, which was incorrectly sweeping them into "destroyed" (rubble
        // overlay + broken overworld icon). Restrict to actual town-type POIs, same "town"/
        // "capital" check World.java's own generation code already uses to distinguish towns.
        if (data.type == null || !(data.type.equals("town") || data.type.equals("capital")))
            return false;
        boolean colorless = false;
        for (String tag : data.questTags) {
            if ("Spawn".equals(tag))
                return false; // the starting encampment/teleporter is type="town" and BiomeColorless
                              // in the data, but it's the player's always-safe home base, not a
                              // contestable settlement - never treat it as destroyed.
            if ("BiomeColorless".equals(tag))
                colorless = true;
        }
        return colorless;
    }

    public static boolean isTownRestored(MapStage stage) {
        return stage.checkQuestFlag(TOWN_RESTORED_FLAG);
    }

    public static boolean isTownRestored(PointOfInterestChanges changes) {
        return changes != null && changes.getMapFlags().get(TOWN_RESTORED_FLAG) != null;
    }

    /** Is this town one of the Functioning Neutral Towns seeded by seedFunctioningNeutralTowns()
     *  (NEUTRAL_SEEDED_FLAG set on its own PointOfInterestChanges)? Public counterpart to the
     *  private isNeutralSeeded(String poiId) above, for callers that already hold the town's
     *  `changes` object directly (e.g. EconomyBuildings' shop-generation post-processing) and
     *  don't need the peek-by-id lookup. */
    public static boolean isNeutralSeededTown(PointOfInterestChanges changes) {
        return changes != null && changes.getMapFlags().get(NEUTRAL_SEEDED_FLAG) != null;
    }

    // PROTOTYPE for MOD_SCOPE.md #7: hardcoded to always recolor "player" (was "green" - flipped
    // 2026-08-04 to test the new gold-tint Player biome) - real territory control will decide
    // the color dynamically (whichever castle's attack succeeds, or "player" once a town is
    // actually claimed), this is purely to validate that live terrain repainting works before
    // that system gets built. Called once, right after a town's Job Board is actually restored.
    private static final String TEST_RECOLOR_BIOME = "player";
    // Aliased to TerritoryControl's, not an independent 10 - a restored town's repaint radius,
    // its seeded territory radius, and its AI-capture protection cap must all be the same number
    // or they drift apart (the exact class of mismatch already caught once for the 20-vs-10 cap).
    private static final int RECOLOR_RADIUS = TerritoryControl.RECOLOR_RADIUS;

    /**
     * Town assault win (MOD_SCOPE #87, 2026-09-03 user spec): "When you win the fight, you should get
     * the city. It should be just like a newly restored ruined city - all buildings besides the inn
     * broken." The AI color town becomes the matching Waste Town (same name, same mechanism the
     * post-generation sweep and AI captures use), flagged TOWN_RESTORED so it is player-owned from
     * this moment, then gets the ownership side effects an AI capture gets (TerritoryControl.
     * onMageArrived): territory radius, terrain repaint, vision, road, town life bonus.
     */
    public static void captureTownForPlayer(forge.adventure.world.World world, PointOfInterest target, String fromColor) {
        PointOfInterestData wasteData = TerritoryControl.matchingWasteData(target.getData(), fromColor);
        if (wasteData == null) {
            System.out.println("[TFR-TownAssault] no Waste Town template matches " + target.getData().name + " - town not captured");
            return;
        }
        String shownName = target.getDisplayName();
        Integer oldRadius = world.getTownTerritoryRadius(target.getID());
        int repaintRadius = Math.max(TerritoryControl.RECOLOR_RADIUS, oldRadius != null ? oldRadius : TerritoryControl.RECOLOR_RADIUS);
        target.transformInto(wasteData, world.getRandom(), true); // ownership changes, the town keeps its name
        PointOfInterestChanges changes = WorldSave.getCurrentSave().getPointOfInterestChanges(target.getID());
        changes.getMapFlags().put(TOWN_RESTORED_FLAG, (byte) 1);
        world.setTownTerritoryRadius(target.getID(), repaintRadius);
        world.rebuildPlayerTownVision();
        world.repaintBiomeAroundTown(target, TEST_RECOLOR_BIOME, repaintRadius,
                WorldStage.getInstance()::refreshBackgroundTile,
                WorldStage.getInstance()::reloadBackgroundChunkObjects);
        world.revealArea((int) (target.getPosition().x / world.getTileSize()),
                (int) (target.getPosition().y / world.getTileSize()),
                repaintRadius, WorldStage.getInstance()::refreshBackgroundTile);
        TerritoryControl.connectCapturedTownByRoad(world, target, "player");
        updateTownLifeBonus(true);
        world.refreshWorldMapMarkers();
        ColorReputation.applyTownAssaultPenalty(fromColor, Config.instance().getTuningData().townCaptureReputationPenalty,
                "captured " + shownName);
        TerritoryControl.dispatchRetaliation(world, fromColor, shownName);
        System.out.println("[TFR-TownAssault] " + shownName + " captured from " + fromColor
                + " -> player-owned restored town (radius " + repaintRadius + "), buildings start broken except the inn");
        forge.adventure.stage.GameHUD.getInstance().addNotification(shownName + " is yours! Its people welcome you - the buildings will need rebuilding.", true);
    }

    public static void recolorTerrainForTesting() {
        PointOfInterest point = TileMapScene.instance().rootPoint;
        if (point == null)
            return;
        forge.adventure.world.World world = WorldSave.getCurrentSave().getWorld();
        // The restored town now grows its own territory (RECOLOR_RADIUS ->
        // TOWN_MAX_TERRITORY_RADIUS, see TerritoryControl.processTerritoryExpansion()) - a
        // restored town keeps its id (restoration is a flag, not a transformInto()), so seeding
        // here keys the same id every later lookup uses. Its area also counts as fog-of-war
        // Revealed from now on (user spec 2026-08-08). Radius seed + vision-cache rebuild run
        // BEFORE the repaint/reveal below, deliberately: their per-tile callbacks bake tiles into
        // the cached chunk textures through isCurrentlyVisible(), and rebuilding after would bake
        // the whole supposedly-Revealed circle HAZED using the stale cache (order bug found by the
        // pre-commit review - only a ~4-tile trail around the player would have rendered bright).
        world.setTownTerritoryRadius(point.getID(), TerritoryControl.RECOLOR_RADIUS);
        world.rebuildPlayerTownVision();
        world.repaintBiomeAroundTown(point, TEST_RECOLOR_BIOME, RECOLOR_RADIUS,
                WorldStage.getInstance()::refreshBackgroundTile,
                WorldStage.getInstance()::reloadBackgroundChunkObjects);
        world.revealArea((int) (point.getPosition().x / world.getTileSize()),
                (int) (point.getPosition().y / world.getTileSize()),
                TerritoryControl.RECOLOR_RADIUS, WorldStage.getInstance()::refreshBackgroundTile);
        // Every 5th owned town is +1 max life (user spec 2026-08-09), and the new holding gets a
        // road to the player's nearest other town, routed through any towns between.
        updateTownLifeBonus(true);
        TerritoryControl.connectCapturedTownByRoad(world, point, "player");
        // Main-quest hook (2026-08-26, "Raise the Banner" rework): publish the live restored-town
        // count as a quest flag so quest stages can gate "restore N towns" on one numeric
        // comparison. setQuestFlag (NOT advanceQuestFlag) deliberately - only the set* variant
        // fires the QUESTFLAG event the quest-objective machinery listens for. Byte-capped at 127.
        int restoredCount = Math.min(127, countPlayerTowns());
        Current.player().setQuestFlag("townsRestored", restoredCount);
        System.out.println("[TFR-MainQuest] townsRestored -> " + restoredCount);
    }

    /**
     * The overworld icon to show for this point of interest, or null if it should use its
     * normal/default sprite (not a wasteland town, or already restored). Picks one of the 16
     * broken-town variants deterministically from the POI's own id, so the same town always shows
     * the same variant without needing a new persisted field.
     */
    public static TextureRegion getBrokenTownSprite(PointOfInterest point) {
        if (point == null || isNeutralSeeded(point.getID()) || !isWastelandTown(point.getData()))
            return null;
        if (point.getData().name != null && point.getData().name.startsWith("Waste Town Center"))
            return null; // Center Towns keep their own castle art (MOD_SCOPE #102)
        // peek, not get - a pure read for every wasteland town icon drawn on the map; the
        // get-or-create accessor would materialize an empty changes entry per town just for
        // rendering (see WorldSave.peekPointOfInterestChanges()).
        PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(point.getID());
        if (isTownRestored(changes))
            return null;

        Array<Sprite> variants = getBrokenWasteTownSprites();
        if (variants == null || variants.size == 0)
            return null;
        int index = Math.floorMod(point.getID().hashCode(), variants.size);
        return variants.get(index);
    }

    private static Array<Sprite> getBrokenWasteTownSprites() {
        if (brokenWasteTownSprites == null)
            brokenWasteTownSprites = Config.instance().getAtlas(BROKEN_WASTETOWN_ATLAS).createSprites(BROKEN_WASTETOWN_SPRITE);
        return brokenWasteTownSprites;
    }

    // Same pattern as the broken-town overworld icon above, but for individual shops within a
    // town: 64 variants, one shared atlas region name, picked deterministically from the shop's
    // own Tiled object id (stable per shop instance, no new persisted field needed). Source art
    // is 32x32 (2x a shop's native 16x16 footprint) - drawn at native size, positioned to cover
    // the real building art baked into the town's tile layers, see ShopActor.drawCenteredOverFootprint().
    private static final String BROKEN_SHOP_ATLAS = "maps/tileset/shop_broken.atlas";
    private static final String BROKEN_SHOP_SPRITE = "ShopBroken";
    private static Array<Sprite> brokenShopSprites;

    // Capitol land-shop ruins (2026-08-13, user-provided art): the 6 fixed land shops in
    // player_capital.tmx (55 Plains/White, 77 Forest/Green, 78 Mountain/Red, 79 Swamp/Black,
    // 80 Island/Blue, 81 Land/Neutral - ids confirmed against the tmx's own commonShopList
    // properties) get their own color-matched 16x16 ruin instead of a random pick from the
    // generic 64-variant pool below - drawn at native 16x16 via the same drawOverFootprint()
    // call site (ShopActor already sizes off the region's own width/height, so a 16x16 region
    // sits flush with the shop's footprint instead of the generic ruins' 32x32 "looming" size).
    // Guarded on isCurrentTownCapitol(): these are raw Tiled object ids, and this exact bug
    // class already bit the generic picker below once (every town built from a shared .tmx
    // template reuses the same slot ids) - without this guard, an unrelated shop in some OTHER
    // town template that happens to reuse one of these 6 ids would wrongly pick up Capitol art.
    private static final java.util.Map<Integer, String> LAND_SHOP_RUIN_REGIONS = new java.util.HashMap<>();
    static {
        LAND_SHOP_RUIN_REGIONS.put(55, "White");
        LAND_SHOP_RUIN_REGIONS.put(77, "Green");
        LAND_SHOP_RUIN_REGIONS.put(78, "Red");
        LAND_SHOP_RUIN_REGIONS.put(79, "Black");
        LAND_SHOP_RUIN_REGIONS.put(80, "Blue");
        LAND_SHOP_RUIN_REGIONS.put(81, "Neutral");
    }
    private static final String LAND_SHOP_RUIN_ATLAS = "maps/tileset/land_shop_broken.atlas";

    private static TextureRegion getLandShopRuinSprite(int objectId) {
        String region = LAND_SHOP_RUIN_REGIONS.get(objectId);
        if (region == null || !isCurrentTownCapitol())
            return null;
        return Config.instance().getAtlasSprite(LAND_SHOP_RUIN_ATLAS, region);
    }

    public static TextureRegion getBrokenShopSprite(int objectId) {
        TextureRegion landShopRuin = getLandShopRuinSprite(objectId);
        if (landShopRuin != null)
            return landShopRuin;
        Array<Sprite> variants = getBrokenShopSprites();
        if (variants == null || variants.size == 0)
            return null;
        // Salted with the current town's own POI id, not just objectId (2026-08-11 bug fix - user
        // report: "the ruin images being used for the towns/capitol... hard-coded to be the same
        // set each time"). Every town is built from one of a small handful of shared .tmx
        // templates, and a shop slot's Tiled objectId is baked into that template - so picking by
        // objectId alone meant every "shop slot 3" on the whole map, across every town sharing that
        // template, showed the exact same ruin variant. Combining in rootPoint.getID() (already
        // proven unique per physical town instance for getBrokenTownSprite() above - it
        // incorporates the town's actual world position) makes the pick vary town-to-town while
        // staying stable for a given town/slot pair across visits, same as before. Falls back to
        // objectId alone if no town is currently loaded (shouldn't happen in practice - this is
        // only ever called while standing inside a town's own map - but avoids an NPE either way).
        PointOfInterest current = TileMapScene.instance().rootPoint;
        int salt = current != null ? current.getID().hashCode() : 0;
        int index = Math.floorMod(objectId * 31 + salt, variants.size);
        return variants.get(index);
    }

    private static Array<Sprite> getBrokenShopSprites() {
        if (brokenShopSprites == null)
            brokenShopSprites = Config.instance().getAtlas(BROKEN_SHOP_ATLAS).createSprites(BROKEN_SHOP_SPRITE);
        return brokenShopSprites;
    }

    public static boolean isShopRebuilt(MapStage stage, int objectId) {
        return stage.checkQuestFlag(shopRebuiltFlag(objectId));
    }

    private static String shopRebuiltFlag(int objectId) {
        return "shopRebuilt_" + objectId;
    }

    private static DialogData.ActionData setFlagAction(String key) {
        DialogData.ActionData.QuestFlag flag = new DialogData.ActionData.QuestFlag();
        flag.key = key;
        flag.val = 1;
        DialogData.ActionData action = new DialogData.ActionData();
        action.setMapFlag = flag;
        return action;
    }

    public static MapDialog buildRestoreTownDialog(MapStage stage, int objectId) {
        // Multi-resource cost (2026-08-12 user table: 200 gold + 10 wood), each component
        // difficulty-scaled inside EconomyBuildings' cost helpers - label, affordability, and
        // deduction all derive from the same base tuple.
        String label = EconomyBuildings.costLabel(RESTORE_COST_GOLD, RESTORE_COST_WOOD, 0, 0);
        DialogData root = new DialogData();
        root.text = "The Job Board lies buried in rubble. Restoring the town here will cost "
                + label + ".";

        DialogData yes = new DialogData();
        yes.name = "Restore town (" + label + ")";
        yes.isDisabled = !EconomyBuildings.canAffordCost(RESTORE_COST_GOLD, RESTORE_COST_WOOD, 0, 0);
        DialogData.ActionData refreshShops = new DialogData.ActionData();
        refreshShops.refreshShopRewardsTrigger = "town-restore";
        // Town Reputation (user spec 2026-08-17): restoring a town is worth +1 reputation there -
        // the first BUILDINGS_PER_REPUTATION build slots become available immediately, before the
        // player has done anything else to earn standing with this specific town.
        DialogData.ActionData addRep = new DialogData.ActionData();
        addRep.addMapReputation = 1;
        yes.action = new DialogData.ActionData[]{
                EconomyBuildings.spendCostAction(RESTORE_COST_GOLD, RESTORE_COST_WOOD, 0, 0),
                setFlagAction(TOWN_RESTORED_FLAG),
                addRep,
                refreshShops};

        DialogData no = new DialogData();
        no.name = "Not now";

        root.options = new DialogData[]{yes, no};
        return new MapDialog(root, stage, objectId, null);
    }

    public static MapDialog buildRebuildShopDialog(MapStage stage, int objectId) {
        // Plain shop rebuild: 100 gold + 10 wood (2026-08-12 user table).
        return buildRebuildShopDialog(stage, objectId, 100, 10, 0, 0, "Rebuild");
    }

    /** Custom-cost variant for non-shop gated buildings (the Arena's OnCollide passes its own
     *  cost/label through here - 2026-08-12 user table gives it a different price than a shop). */
    public static MapDialog buildRebuildShopDialog(MapStage stage, int objectId,
            int gold, int wood, int stone, int shards, String verb) {
        String label = EconomyBuildings.costLabel(gold, wood, stone, shards);
        DialogData root = new DialogData();
        // Cost dropped from this line (user request 2026-08-14: the button right below already
        // shows "<verb> (<cost>)" - repeating the figure here just made the body text wrap badly
        // for longer costs, e.g. the Arena's "Rebuilding it will cost 313 [+Gold]."). Shared by
        // every OnCollide-gated building (Arena, Spellsmith, Shard Trader today), so kept generic
        // rather than naming a specific building.
        root.text = "This building is buried in rubble. Want to repair it?";

        DialogData yes = new DialogData();
        yes.name = verb + " (" + label + ")";
        yes.isDisabled = !EconomyBuildings.canAffordCost(gold, wood, stone, shards);
        DialogData.ActionData refreshShops = new DialogData.ActionData();
        refreshShops.refreshShopRewardsTrigger = "shop-rebuild";
        yes.action = new DialogData.ActionData[]{
                EconomyBuildings.spendCostAction(gold, wood, stone, shards),
                setFlagAction(shopRebuiltFlag(objectId)),
                refreshShops};

        DialogData no = new DialogData();
        no.name = "Not now";

        root.options = new DialogData[]{yes, no};
        return new MapDialog(root, stage, objectId, null);
    }

    public static MapDialog buildShopLockedDialog(MapStage stage, int objectId) {
        DialogData root = new DialogData();
        root.text = "This shop can't be rebuilt until the town's Job Board has been restored.";

        DialogData ok = new DialogData();
        ok.name = "OK";

        root.options = new DialogData[]{ok};
        return new MapDialog(root, stage, objectId, null);
    }

    // Town Reputation building gate (user spec 2026-08-17): reputation with a town caps how many
    // of its buildings can be built/restored at once, reusing the existing PointOfInterestChanges
    // "map reputation" (id-0) slot rather than adding a new field - that slot already grants
    // shop/town price discounts, this just gives it real teeth. Sources: +1 on restoring the
    // town (buildRestoreTownDialog), +1 on upgrading to a Capitol (upgradeToCapitol, migrated
    // forward across the id change), +1 on defeating an attacking mage (DuelScene.afterGameEnd).
    // Losing the town resets this to 0 for free - transformInto() re-keys the POI id on capture,
    // same as the guard/building state above, so the new owner's PointOfInterestChanges is a
    // fresh entry with no reputation carried over (see TerritoryControl.onMageArrived()).
    // Reputation loss (e.g. a quest's negative addMapReputation) never tears down an
    // already-built building - it only blocks NEW ones until reputation climbs back above the
    // threshold, since this gate is checked only at build time, never retroactively. Destroying
    // a building immediately frees its slot back up (destroyBuilding() removes its shopRebuilt_
    // flag), independent of reputation - the two mechanics don't interact.
    public static final int BUILDINGS_PER_REPUTATION = 3;

    /** How many of this town's shop/building slots are currently built/restored - every one of
     *  them, plain shop or gated building or economy building alike, sets a "shopRebuilt_&lt;id&gt;"
     *  flag on completion (see setFlagAction() calls throughout this file and EconomyBuildings'
     *  own registerMigratedBuilding()/build dialogs), so counting that flag prefix covers every
     *  building type uniformly with no separate bookkeeping. */
    public static int countBuiltBuildings(PointOfInterestChanges changes) {
        int count = 0;
        for (String flagKey : changes.getMapFlags().keySet()) {
            if (flagKey.startsWith("shopRebuilt_"))
                count++;
        }
        return count;
    }

    /** Negative reputation (a quest can take it away) never REDUCES the slot count below what's
     *  already built - see hasReputationForAnotherBuilding()'s own doc. */
    public static int maxBuildableBuildings(PointOfInterestChanges changes) {
        return Math.max(0, changes.getMapReputation()) * BUILDINGS_PER_REPUTATION;
    }

    public static boolean hasReputationForAnotherBuilding(PointOfInterestChanges changes) {
        return countBuiltBuildings(changes) < maxBuildableBuildings(changes);
    }

    public static MapDialog buildReputationLockedDialog(MapStage stage, int objectId) {
        PointOfInterestChanges changes = stage.getChanges();
        DialogData root = new DialogData();
        root.text = String.format(
                "This town doesn't have enough reputation to support another building yet "
                + "(%d/%d built, %d reputation with this town). Help it prosper - completing "
                + "its quests raises your standing here.",
                countBuiltBuildings(changes), maxBuildableBuildings(changes), changes.getMapReputation());

        DialogData ok = new DialogData();
        ok.name = "OK";

        root.options = new DialogData[]{ok};
        return new MapDialog(root, stage, objectId, null);
    }

    // Capitol upgrade (MOD_SCOPE.md #13, first slice 2026-08-08): one player town may become the
    // Capitol "Orazca" - bigger castle-sized icon, its own 40x40 player_capital.tmx layout.
    // (The earlier Rename-town option was dropped the same day per user - names showing in
    // messages/map made it unnecessary.)
    public static final String CAPITOL_POI_NAME = "Player Capitol";
    // 2026-08-12 user cost table: 1000 gold + 100 stone + 100 wood + 50 shards.
    // Wood/Stone components halved 2026-08-21 (v1.00 feedback round) - gold/shards untouched.
    private static final int CAPITOL_COST_GOLD = 1000;
    private static final int CAPITOL_COST_WOOD = 100;
    private static final int CAPITOL_COST_STONE = 100;
    private static final int CAPITOL_COST_SHARDS = 50;
    private static final int CAPITOL_TOWNS_REQUIRED = 5;

    /**
     * Restored-town Job Board menu (user request 2026-08-08): instead of jumping straight into
     * the quest offer, the board first offers Browse quests / Upgrade to Capitol / Leave. Only
     * reachable for restored wasteland towns (QuestActor gates on isWastelandTown() +
     * isTownRestored()), so stock planes and stock towns keep the direct-to-quest behavior.
     * The upgrade option: needs CAPITOL_TOWNS_REQUIRED owned towns (shown disabled with the
     * requirement until then), costs the CAPITOL_COST_* resource tuple, and disappears once ANY Capitol
     * exists (only one allowed; the Capitol's own board never shows it - its data name IS the
     * capitol).
     */
    public static void openJobBoardMenu(MapStage stage, Runnable openQuestBoard) {
        PointOfInterest point = TileMapScene.instance().rootPoint;
        com.badlogic.gdx.scenes.scene2d.ui.Dialog dialog = stage.getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();
        com.github.tommyettinger.textra.TypingLabel label = Controls.newTypingLabel(
                "The " + (point != null ? point.getDisplayName() : "town") + " Job Board.");
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getContentTable().add(label).width(250f).row();
        dialog.getButtonTable().add(Controls.newTextButton("Browse quests", () -> {
            stage.hideDialog();
            openQuestBoard.run();
        })).width(240f).row();
        boolean isCapitolItself = point != null && CAPITOL_POI_NAME.equals(point.getData().name);
        if (!isCapitolItself && !capitolExists()) {
            int owned = countPlayerTowns();
            if (owned < CAPITOL_TOWNS_REQUIRED) {
                com.github.tommyettinger.textra.TextraButton needMore = Controls.newTextButton(
                        "Upgrade to Capitol (" + owned + "/" + CAPITOL_TOWNS_REQUIRED + " towns)", () -> {});
                needMore.setDisabled(true);
                dialog.getButtonTable().add(needMore).width(240f).row();
            } else {
                // Multi-resource cost (2026-08-12 user table, wood/stone halved 2026-08-21: 1000
                // gold + 100 stone + 100 wood + 50 shards); upgradeToCapitol() pays the same tuple
                // via EconomyBuildings.payCost().
                com.github.tommyettinger.textra.TextraButton upgrade = Controls.newTextButton(
                        "[%90]Upgrade to Capitol (" + EconomyBuildings.costLabel(CAPITOL_COST_GOLD,
                                CAPITOL_COST_WOOD, CAPITOL_COST_STONE, CAPITOL_COST_SHARDS) + ")", () -> {
                            stage.hideDialog();
                            upgradeToCapitol(stage);
                        });
                upgrade.setDisabled(!EconomyBuildings.canAffordCost(CAPITOL_COST_GOLD,
                        CAPITOL_COST_WOOD, CAPITOL_COST_STONE, CAPITOL_COST_SHARDS));
                dialog.getButtonTable().add(upgrade).width(240f).row();
            }
        }
        dialog.getButtonTable().add(Controls.newTextButton("Leave", stage::hideDialog)).width(240f).row();
        dialog.setKeepWithinStage(true);
        stage.showDialog();
    }

    public static boolean capitolExists() {
        return findCapitol() != null;
    }

    /** The player's Capitol POI ("Player Capitol", displayName "Orazca"), or null if none has
     *  been built yet - at most one ever exists. Identified by canonical data.name, same as every
     *  other capital-lookup in the mod, so it's unaffected by any future rename option. */
    public static PointOfInterest findCapitol() {
        for (PointOfInterest poi : WorldSave.getCurrentSave().getWorld().getAllPointOfInterest()) {
            if (CAPITOL_POI_NAME.equals(poi.getData().name))
                return poi;
        }
        return null;
    }

    /** Is the town the player is currently inside the Capitol itself? */
    public static boolean isCurrentTownCapitol() {
        PointOfInterest point = TileMapScene.instance().rootPoint;
        return point != null && CAPITOL_POI_NAME.equals(point.getData().name);
    }

    /** Is the CURRENTLY-LOADED town player-owned - an ordinary restored town (the passed
     *  `changes` is that town's own PointOfInterestChanges) or the player's own migrated Capitol
     *  (isCurrentTownCapitol() reads TileMapScene's rootPoint itself, no separate check needed).
     *  The gate for every mod economy-building action - guard hire/dismiss, Bank, Mines, Armory
     *  upgrade/reroll, shop-type reroll, Outlook, Teleporter, Archaeologist, Destroy Building -
     *  since "only the player can build/upgrade stuff" (user spec, 2026-08-13). Most of those
     *  are already structurally unreachable at AI towns via isWastelandTown()'s own gate (an AI
     *  town/capital is never wasteland, so its shops are never "rubble" and this whole dialog
     *  family never opens there) - RewardScene's Armory-family buttons (Upgrade/Guards/Re-roll
     *  Inventory/Re-roll Shop Type) were the one path that bypassed that gate entirely, live and
     *  exploitable at the 5 AI capitals' colored Equipment/Items shops (see MOD_CHANGELOG.md). */
    public static boolean isCurrentTownPlayerOwned(PointOfInterestChanges changes) {
        return isTownRestored(changes) || isCurrentTownCapitol();
    }

    /**
     * The Job Board menu only exists to offer the Capitol upgrade (user decision 2026-08-08 late:
     * rename was dropped, and once a Capitol exists - or you're standing in it - a
     * Browse-quests-or-Leave menu is a pointless extra click). Straight to quests otherwise.
     */
    public static boolean shouldShowJobBoardMenu() {
        return !isCurrentTownCapitol() && !capitolExists();
    }

    // Made public (2026-08-11, round 8) so TerritoryControl.maxActiveMagesPerColor() can reuse the
    // exact same count (previously only called from within this class, e.g. the Capitol-upgrade
    // gate and the life-bonus calc below).
    public static int countPlayerTowns() {
        int count = 0;
        for (PointOfInterest poi : WorldSave.getCurrentSave().getWorld().getAllPointOfInterest()) {
            if (isTownRestored(WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID())))
                count++;
        }
        return count;
    }

    /**
     * The upgrade itself. The tricky part is that transformInto() changes the POI's id (derived
     * from data.name), so the town's built state does NOT carry over automatically - and the
     * capital layout's object ids differ from the town layout's anyway. Migration is by COUNT and
     * TYPE, not id: every economy building (each type exists at most once per town) is re-homed
     * onto a capital shop slot, then as many further slots as the old town had plain rebuilt
     * shops are marked rebuilt, lowest object id first. Everything else starts as rubble on the
     * capital layout - rebuildable through the ordinary wasteland-shop flow (the capital's data
     * keeps the Town+BiomeColorless tags precisely so all of that machinery just applies).
     * Finally the player is kicked to the world map (the currently-loaded scene still shows the
     * old tmx; re-entering loads the capital layout fresh - simplest correct swap, per
     * discussion with the user).
     */
    private static void upgradeToCapitol(MapStage stage) {
        PointOfInterest point = TileMapScene.instance().rootPoint;
        if (point == null)
            return;
        PointOfInterestData capitolData = PointOfInterestData.getPointOfInterest(CAPITOL_POI_NAME);
        if (capitolData == null) {
            System.out.println("[TownRestoration] CRITICAL: \"" + CAPITOL_POI_NAME + "\" POI data missing, upgrade aborted");
            return;
        }
        forge.adventure.world.World world = WorldSave.getCurrentSave().getWorld();

        // Snapshot the old town's built state before the id changes.
        PointOfInterestChanges oldChanges = WorldSave.getCurrentSave().getPointOfInterestChanges(point.getID());
        int plainRebuiltShops = 0;
        java.util.List<Integer> economyTypes = new java.util.ArrayList<>(oldChanges.getEconomyBuildingObjectIds().keySet());
        java.util.Set<Integer> economyObjectIds = new java.util.HashSet<>(oldChanges.getEconomyBuildingObjectIds().values());
        Integer oldInnId = readInnObjectId(point.getData().map); // the OLD town layout's inn
        java.util.Set<Integer> plainRebuiltIds = new java.util.TreeSet<>(); // sorted - stable slot order
        for (String flagKey : oldChanges.getMapFlags().keySet()) {
            if (flagKey.startsWith("shopRebuilt_")) {
                int objectId = Integer.parseInt(flagKey.substring("shopRebuilt_".length()));
                if (oldInnId != null && objectId == oldInnId)
                    continue; // the inn migrates by type (auto-repaired below), not as a plain shop slot
                if (!economyObjectIds.contains(objectId)) {
                    plainRebuiltShops++; // economy buildings set the same flag - don't double-count them
                    plainRebuiltIds.add(objectId);
                }
            }
        }
        // The exact ShopData each rebuilt plain shop is currently showing, in objectId order -
        // the upgrade is only reachable while standing IN the town, so its live MapStage still
        // holds every rolled shop. Pinned onto the capital slots below so the Capitol keeps the
        // SAME shops (user report 2026-08-09: "I got a different set of shops in the capitol
        // from what I had in the town" - each map load re-rolls unpinned shop objects).
        java.util.Map<Integer, String> rebuiltShopNames = new java.util.HashMap<>();
        Integer oldArmoryId = null;
        for (forge.adventure.character.ShopActor shopActor : stage.getShopActors()) {
            if (plainRebuiltIds.contains(shopActor.getObjectId()) && shopActor.getShopData() != null)
                rebuiltShopNames.put(shopActor.getObjectId(), shopActor.getShopData().name);
        }
        // The Armory isn't tracked in economyObjectIds (it's not an EconomyBuildings.java type,
        // just a plain shop with a fixed Armory shopList), so without this it falls into the
        // generic plainRebuiltIds bucket and migrates onto an ordinary Capitol shop slot - while
        // the Capitol's own dedicated, noMigrate-reserved Armory slot (see isReservedSlot()) is
        // left separately buildable. Real bug, user-reported 2026-08-12 TWICE: first as a plain
        // duplicate, then again after the first fix because the town Armory had been upgraded and
        // its resolved name was "EquipmentL2" - which the old inline isArmoryShop() patterns
        // didn't match. Detection now runs over the captured NAMES via the single shared
        // isArmoryShopName() predicate (which strips the L2 suffix), and excludes EVERY match,
        // not just the last one seen.
        java.util.Iterator<java.util.Map.Entry<Integer, String>> nameIter = rebuiltShopNames.entrySet().iterator();
        while (nameIter.hasNext()) {
            java.util.Map.Entry<Integer, String> entry = nameIter.next();
            if (!EconomyBuildings.isArmoryShopName(entry.getValue()))
                continue;
            if (oldArmoryId == null)
                oldArmoryId = entry.getKey(); // first one carries its building level across below
            plainRebuiltIds.remove(entry.getKey());
            plainRebuiltShops--;
            nameIter.remove(); // never pinnable onto a regular capital slot
        }
        Integer oldRadius = world.getTownTerritoryRadius(point.getID());

        EconomyBuildings.payCost(CAPITOL_COST_GOLD, CAPITOL_COST_WOOD, CAPITOL_COST_STONE, CAPITOL_COST_SHARDS);
        point.transformInto(capitolData, world.getRandom()); // template name -> displayName "Orazca"

        PointOfInterestChanges newChanges = WorldSave.getCurrentSave().getPointOfInterestChanges(point.getID());
        newChanges.getMapFlags().put(TOWN_RESTORED_FLAG, (byte) 1);
        // Reputation migration (user spec 2026-08-17, bumped 2026-08-18: "if you upgrade a town
        // to a capitol you also get +2 reputation", raised from the original +1) - same id-remap
        // problem transformInto() creates for the guards/buildings migrated below: oldChanges is
        // keyed by the OLD id and would otherwise be silently orphaned, wiping the town's
        // accumulated reputation on its own upgrade. Carries the old total across, then adds the
        // Capitol's own +2 on top.
        newChanges.addMapReputation(oldChanges.getMapReputation() + 2);
        java.util.List<Integer> capitolShopSlots = readCapitolShopObjectIds(capitolData.map);
        int slotIndex = 0;
        for (int economyType : economyTypes) {
            if (slotIndex >= capitolShopSlots.size())
                break;
            int slot = capitolShopSlots.get(slotIndex++);
            // Sets the one-per-type economyBuilt flag too - without it the Capitol's build menu
            // offered a second mine of a type that had just migrated in (user-reported).
            EconomyBuildings.registerMigratedBuilding(newChanges, economyType, slot);
        }
        java.util.Iterator<Integer> rebuiltIdIter = plainRebuiltIds.iterator();
        for (int i = 0; i < plainRebuiltShops && slotIndex < capitolShopSlots.size(); i++) {
            int slot = capitolShopSlots.get(slotIndex++);
            newChanges.getMapFlags().put("shopRebuilt_" + slot, (byte) 1);
            // Pin the capital slot to the exact shop the source town's slot held (same order).
            if (rebuiltIdIter.hasNext()) {
                int oldId = rebuiltIdIter.next();
                String shopName = rebuiltShopNames.get(oldId);
                if (shopName != null)
                    newChanges.setPinnedShopName(slot, shopName);
                // Carry the building's upgrade level across too (user report 2026-08-11: an
                // upgraded Armory reverted to Level 1 after the Capitol upgrade) - same id-remap
                // pattern as the shop-name pin just above, since the slot's Tiled object id
                // changes across the migration and buildingLevels is keyed by that id.
                int level = oldChanges.getBuildingLevel(oldId);
                if (level > 1)
                    newChanges.setBuildingLevel(slot, level);
            }
        }
        // The Inn came with the town (a restored town's inn was already working) - it starts
        // repaired in the Capitol, always (user spec 2026-08-09).
        Integer capitolInnId = readInnObjectId(capitolData.map);
        if (capitolInnId != null)
            newChanges.getMapFlags().put("shopRebuilt_" + capitolInnId, (byte) 1);
        // Likewise, an Armory the old town already had maps onto the Capitol's own reserved
        // Armory slot directly, never a plain shop slot (see the oldArmoryId block above). No
        // shop-name pin needed - the reserved slot already carries its own fixed Armory shopList
        // properties in the tmx, and repairCapitolState() strips any pinned name from reserved
        // slots on load anyway (it would just be discarded).
        if (oldArmoryId != null) {
            Integer capitolArmoryId = readCapitolArmorySlotId(capitolData.map);
            if (capitolArmoryId != null) {
                newChanges.getMapFlags().put("shopRebuilt_" + capitolArmoryId, (byte) 1);
                int armoryLevel = oldChanges.getBuildingLevel(oldArmoryId);
                if (armoryLevel > 1)
                    newChanges.setBuildingLevel(capitolArmoryId, armoryLevel);
                System.out.println("[TownRestoration] Capitol migration: Armory (old object " + oldArmoryId
                        + ") mapped onto reserved Capitol Armory slot " + capitolArmoryId);
            } else {
                System.out.println("[TownRestoration] CRITICAL: old town had a built Armory but the Capitol "
                        + "template has no reserved Armory slot - Armory state lost");
            }
        }
        // Hired guards live on PointOfInterestChanges (guardTiers/guardLastPaidDay), which is
        // keyed by POI id - transformInto() re-keys the POI, so without this copy a town's
        // guards silently vanished on upgrade while their salary state was orphaned (2026-08-12
        // review finding). hireGuard()'s day parameter is stored as lastPaidDay, so passing the
        // old lastPaidDay carries it across correctly - since guard salary moved to a fixed
        // shared payday (2026-08-13, EconomyBuildings.processDaysPassed()'s nextPayday formula),
        // that value no longer needs to BE a multiple of 7 to work right: it just converges onto
        // the next 7/14/21/28 boundary the same way any inherited legacy value would. Bank
        // balance needs no equivalent: Bank/Exchange are Capitol-exclusive builds (see
        // EconomyBuildings' buildSimpleRepairDialog isCapitol gate), so a pre-upgrade town can
        // never hold one.
        for (int i = 0; i < oldChanges.getGuardCount(); i++)
            newChanges.hireGuard(oldChanges.getGuardTier(i), oldChanges.getGuardLastPaidDay(i));
        if (oldChanges.getGuardCount() > 0)
            System.out.println("[TownRestoration] Capitol migration: " + oldChanges.getGuardCount() + " guard(s) carried over");
        System.out.println("[TownRestoration] Capitol migration: " + economyTypes.size() + " economy building(s) + "
                + plainRebuiltShops + " rebuilt shop(s) mapped onto " + capitolShopSlots.size() + " capital slots");

        // Territory state re-keys to the new id, same as a mage capture does.
        world.setTownTerritoryRadius(point.getID(), oldRadius != null ? oldRadius : RECOLOR_RADIUS);
        world.rebuildPlayerTownVision();
        // Fog of war (2026-08-13 fix): the Capitol's fixed keep-radius vision circle
        // (getTownVisionRadiusTiles() -> CASTLE_KEEP_RADIUS_TILES, bigger than the old town's
        // smaller RECOLOR_RADIUS this ground was last baked at) was only ever force-revealed by
        // EconomyBuildings.onOutlookChanged() - which fires on Outlook build/destroy, never on the
        // Capitol upgrade itself. Without this, the ring between the old town's original repaint
        // radius and the new, larger Capitol keep radius stayed hazed forever (nothing else ever
        // repaints it), even though isPersistentlyRevealed() already says true for it via the
        // just-rebuilt vision cache above - same "cache updated, nothing repainted" bug class
        // onOutlookChanged()'s own doc comment describes, same fix pattern (revealArea() for any
        // not-yet-explored ring, refreshFogInRadius() to re-tier everything already explored).
        applyTownVisionReveal(world, point, newChanges);
        world.refreshWorldMapMarkers(); // the icon changed to the castle-sized capitol art
        updateTownLifeBonus(true); // the Capitol itself is worth +1 max life (user spec 2026-08-09)

        // Plain text - the bold [*] markup renders as smeared double-struck glyphs at this
        // pixel-font size (same issue as the old PLAYER OWNED TOWN warning, reported again here).
        forge.adventure.stage.GameHUD.getInstance().addNotification("Orazca rises! Return to your new Capitol to see it rebuilt.");
        System.out.println("[TownRestoration] town upgraded to Capitol \"Orazca\"");
        // Main-quest hook (2026-08-26, "Raise the Banner"): the existing TOWN_RESTORED_FLAG write
        // above goes DIRECTLY into the changes map (never through the event-firing setQuestFlag
        // path), so without this explicit character flag no quest objective could ever see the
        // upgrade happen.
        Current.player().setCharacterFlag("capitolBuilt", 1);
        System.out.println("[TFR-MainQuest] capitolBuilt -> 1");
        // Kick to the world map so re-entry loads the capital layout.
        stage.exitDungeon(false, false);
    }

    // A shop slot is "reserved" - excluded from the Capitol migration target pool entirely - if
    // it's either a fixedShop (the 6 land shops: no conversion menu, no icon, hut art baked into
    // the map) or noMigrate (2026-08-10 addition: the Armory and dedicated Booster slots - DO
    // still get a conversion-menu bypass and a real icon like any other special shop, just also
    // can never be claimed by a migrated economy building or a random re-roll). User report:
    // "if you don't build [Armory] first in the Town, a shop can take its place and you can't
    // build one" - because neither slot was excluded from the migration pool before this.
    private static boolean isReservedSlot(com.badlogic.gdx.utils.XmlReader.Element object) {
        return hasTrueProperty(object, "fixedShop") || hasTrueProperty(object, "noMigrate");
    }

    /**
     * The capital layout's shop slot ids, ascending, parsed straight from the tmx (root-level
     * object group only - the file also embeds a tileset whose tiles carry their own tiny
     * objectgroups, which must not be scanned). Parsing the real file instead of hardcoding ids
     * keeps this correct if the user re-edits the map in Tiled. Reserved slots (see
     * isReservedSlot()) are NOT migration targets - they must stay exactly what the tmx says
     * they are, so they're excluded here.
     */
    private static java.util.List<Integer> readCapitolShopObjectIds(String mapPath) {
        java.util.List<Integer> shopIds = new java.util.ArrayList<>();
        for (com.badlogic.gdx.utils.XmlReader.Element object : readMapObjects(mapPath)) {
            String template = object.getAttribute("template", "");
            if (template.endsWith("shop.tx") && !isReservedSlot(object))
                shopIds.add(object.getIntAttribute("id"));
        }
        java.util.Collections.sort(shopIds);
        return shopIds;
    }

    /** The capital layout's reserved shop ids (6 land shops + Armory + dedicated Booster shop),
     *  ascending - repairCapitolState() relocates any economy building wrongly parked on one. */
    private static java.util.List<Integer> readCapitolReservedShopObjectIds(String mapPath) {
        java.util.List<Integer> shopIds = new java.util.ArrayList<>();
        for (com.badlogic.gdx.utils.XmlReader.Element object : readMapObjects(mapPath)) {
            String template = object.getAttribute("template", "");
            if (template.endsWith("shop.tx") && isReservedSlot(object))
                shopIds.add(object.getIntAttribute("id"));
        }
        java.util.Collections.sort(shopIds);
        return shopIds;
    }

    /** Among the capital layout's reserved shop slots, the one that's specifically the Armory (as
     *  opposed to a land shop or the dedicated Booster shop) - matched via the ONE shared
     *  EconomyBuildings.isArmoryShopName() predicate (an earlier inline copy of its patterns here
     *  is exactly the kind of drift that let "EquipmentL2" slip through the migration). Read off
     *  the object's own baked-in commonShopList property, so no ShopData resolution is needed. */
    private static Integer readCapitolArmorySlotId(String mapPath) {
        for (com.badlogic.gdx.utils.XmlReader.Element object : readMapObjects(mapPath)) {
            if (!object.getAttribute("template", "").endsWith("shop.tx") || !isReservedSlot(object))
                continue;
            com.badlogic.gdx.utils.XmlReader.Element properties = object.getChildByName("properties");
            if (properties == null)
                continue;
            for (com.badlogic.gdx.utils.XmlReader.Element property : properties.getChildrenByName("property")) {
                if (!"commonShopList".equals(property.getAttribute("name", "")))
                    continue;
                if (EconomyBuildings.isArmoryShopName(property.getAttribute("value", "")))
                    return object.getIntAttribute("id");
            }
        }
        return null;
    }

    /** The capital layout's inn object id, or null if the map has none. */
    private static Integer readInnObjectId(String mapPath) {
        for (com.badlogic.gdx.utils.XmlReader.Element object : readMapObjects(mapPath)) {
            if (object.getAttribute("template", "").endsWith("inn.tx"))
                return object.getIntAttribute("id");
        }
        return null;
    }

    // Memoized per mapPath: the capital tmx is ~730 KB with 3000+ objects, and one Capitol
    // upgrade calls 4 different readers (inn/shop-slots/reserved/armory) while repairCapitolState
    // adds 3 more on EVERY save load - each was independently re-reading and re-DOM-parsing the
    // identical file (2026-08-12 review finding). Map files can't change within a game session,
    // so a process-lifetime cache is safe. Failed parses cache the empty list deliberately -
    // retrying a broken file every call would just repeat the same log spam.
    private static final java.util.Map<String, java.util.List<com.badlogic.gdx.utils.XmlReader.Element>> mapObjectsCache =
            new java.util.HashMap<>();

    private static java.util.List<com.badlogic.gdx.utils.XmlReader.Element> readMapObjects(String mapPath) {
        java.util.List<com.badlogic.gdx.utils.XmlReader.Element> cached = mapObjectsCache.get(mapPath);
        if (cached != null)
            return cached;
        java.util.List<com.badlogic.gdx.utils.XmlReader.Element> objects = new java.util.ArrayList<>();
        try {
            com.badlogic.gdx.utils.XmlReader.Element root = new com.badlogic.gdx.utils.XmlReader()
                    .parse(Config.instance().getFile(mapPath));
            for (com.badlogic.gdx.utils.XmlReader.Element group : root.getChildrenByName("objectgroup")) {
                for (com.badlogic.gdx.utils.XmlReader.Element object : group.getChildrenByName("object"))
                    objects.add(object);
            }
        } catch (Exception e) {
            System.out.println("[TownRestoration] could not parse capital map objects: " + e);
        }
        mapObjectsCache.put(mapPath, objects);
        return objects;
    }

    private static boolean hasTrueProperty(com.badlogic.gdx.utils.XmlReader.Element object, String propertyName) {
        com.badlogic.gdx.utils.XmlReader.Element properties = object.getChildByName("properties");
        if (properties == null)
            return false;
        for (com.badlogic.gdx.utils.XmlReader.Element property : properties.getChildrenByName("property")) {
            if (propertyName.equals(property.getAttribute("name", "")))
                return Boolean.parseBoolean(property.getAttribute("value", "false"));
        }
        return false;
    }

    /**
     * Load-time repair for the Capitol's per-building state (2026-08-09 user spec). Called from
     * WorldSave.load() AFTER pointOfInterestChanges has loaded (World.load() itself runs too
     * early - the changes it would see are the previous session's). Idempotent, inert without a
     * Capitol. Two repairs:
     * <ul>
     * <li>The Inn always starts repaired - it "came with the town" (the upgrade requires a
     * restored, functioning town, whose inn the player already had working).</li>
     * <li>Any economy building an older migration parked on a reserved slot (the 6 land shops,
     * Armory, or the dedicated Booster shop - see isReservedSlot()) is relocated to the first
     * free regular slot - none of those may ever be a Bank/Mine. Its shopRebuilt flag moves with
     * it; the reserved slot reverts to rubble, rebuildable as itself.</li>
     * <li>Any pinned plain-shop name an older migration left on a reserved slot is cleared, so
     * Armory/Booster fall back to their own tmx-defined shopList instead of showing whatever
     * shop had migrated in (user report 2026-08-09/10: "if you don't build [Armory] first in the
     * Town, a shop can take its place").</li>
     * </ul>
     */
    /**
     * One-time-safe fog-of-war reveal over a player-owned town's actual vision radius (works for
     * the Capitol or an ordinary town - getTownVisionRadiusTiles() already branches on isCapitol
     * internally) - see the call sites in upgradeToCapitol(), repairAllTownVisionReveal(), and
     * TerritoryControl.processTerritoryExpansion() (same package, calls this directly rather than
     * duplicating it) for why this exists. revealArea() no-ops per-tile on ground already explored
     * and refreshFogInRadius() is a pure re-derive, so calling this again on an already-correct
     * town (every load, via repairAllTownVisionReveal() below) is safe/cheap, not just safe once -
     * existing saves affected by the gap this closes self-heal on their next load instead of
     * needing a save-specific migration flag. Package-private (not private) specifically so
     * TerritoryControl can share it instead of reimplementing the same reveal+refresh pair.
     */
    static void applyTownVisionReveal(forge.adventure.world.World world, PointOfInterest poi,
                                       PointOfInterestChanges changes) {
        int centerX = (int) (poi.getPosition().x / world.getTileSize());
        int centerY = (int) (poi.getPosition().y / world.getTileSize());
        int radius = world.getTownVisionRadiusTiles(poi, changes);
        world.revealArea(centerX, centerY, radius, WorldStage.getInstance()::refreshBackgroundTile);
        world.refreshFogInRadius(centerX, centerY, radius + 2, WorldStage.getInstance()::refreshBackgroundTile);
    }

    /**
     * Self-heals fog-of-war for EVERY player-owned restored town, not just the Capitol (2026-08-13
     * fix - user report: standing on owned ordinary-town land still rendered Stage-1 black). The
     * Capitol gets covered again here too (redundant with repairCapitolState()'s own call below,
     * but applyTownVisionReveal() is idempotent - see its own doc comment). Fixes both an already-
     * broken existing save (this round's TerritoryControl.processTerritoryExpansion() fix only
     * prevents the gap from recurring going forward, it can't retroactively fix ground already
     * mis-revealed) and any future drift from a cause not yet found. Cheap early-return mirrors
     * repairCapitolState()'s own gate - isTownRestored() is never true on any plane without
     * reconstruction enabled anyway, this just avoids the pointless full-POI scan.
     */
    public static void repairAllTownVisionReveal(forge.adventure.world.World world) {
        ConfigData configData = Config.instance().getConfigData();
        if (configData == null || !configData.townReconstructionEnabled)
            return;
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
            if (!isTownRestored(changes))
                continue;
            applyTownVisionReveal(world, poi, changes);
            // Capitol-specific extra sweep (2026-08-13 fix, alongside the fixed-keep-radius
            // applyTownVisionReveal() call above): the Capitol's territory radius grows well
            // past its fixed vision-circle radius (up to MAX_TERRITORY_RADIUS=450 via
            // TerritoryControl's own daily expansion, tracked separately under
            // getColorTerritoryRadius("player"), not townTerritoryRadius). A save whose Capitol
            // already grew past the keep radius BEFORE today's TerritoryControl fix landed has a
            // large already-mis-revealed band that fix alone can't retroactively repair (it only
            // prevents the gap from recurring on FUTURE growth) - sweep the actual current radius
            // here so it self-heals immediately on this load instead of slowly re-covering ring
            // by ring as the (already-maxed-out) daily growth loop no longer has anything new to
            // claim.
            // 2026-08-13 fully-explored fix, part 3: this sweep used to revealArea() the whole
            // territory-radius DISC (up to 450 - ocean and rival land included) on EVERY save
            // load, retro-contaminating explored[][] far past what the player actually owns and
            // massively inflating the Stage-2 fully-explored counter. Now reveals only tiles the
            // player's biome bit actually covers within that radius - the same ownership test
            // isPersistentlyRevealed() renders Stage 3 from - so the self-heal ("owned ground
            // never sits under black fog") is preserved without gifting the aspirational disc.
            if (CAPITOL_POI_NAME.equals(poi.getData().name)) {
                Integer capitolRadius = world.getColorTerritoryRadius("player");
                if (capitolRadius != null && capitolRadius > world.getTownVisionRadiusTiles(poi, changes)) {
                    int centerX = (int) (poi.getPosition().x / world.getTileSize());
                    int centerY = (int) (poi.getPosition().y / world.getTileSize());
                    int revealed = world.revealPlayerOwnedTiles(centerX, centerY, capitolRadius,
                            WorldStage.getInstance()::refreshBackgroundTile);
                    if (revealed > 0)
                        System.out.println("[TFR-FoW] load-time Capitol sweep: revealed " + revealed
                                + " newly-covered OWNED tiles within radius " + capitolRadius);
                }
            }
        }
    }

    public static void repairCapitolState(forge.adventure.world.World world) {
        ConfigData configData = Config.instance().getConfigData();
        if (configData == null || !configData.townReconstructionEnabled)
            return;
        PointOfInterest capitol = null;
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (CAPITOL_POI_NAME.equals(poi.getData().name)) {
                capitol = poi;
                break;
            }
        }
        if (capitol == null)
            return;
        String mapPath = capitol.getData().map;
        PointOfInterestChanges changes = WorldSave.getCurrentSave().getPointOfInterestChanges(capitol.getID());

        // Self-heals any save whose Capitol upgrade ran before the 2026-08-13 fix above - see
        // applyTownVisionReveal()'s own doc comment for why repeating this every load is safe.
        applyTownVisionReveal(world, capitol, changes);

        Integer innId = readInnObjectId(mapPath);
        if (innId != null && changes.getMapFlags().putIfAbsent("shopRebuilt_" + innId, (byte) 1) == null)
            System.out.println("[TownRestoration] Capitol repair: inn (object " + innId + ") marked repaired");

        java.util.List<Integer> reservedSlots = readCapitolReservedShopObjectIds(mapPath);
        if (reservedSlots.isEmpty())
            return;
        // A save from before reserved slots were excluded from the migration pool may still carry
        // a pinned plain-shop name on Armory/Booster (see upgradeToCapitol()'s setPinnedShopName())
        // - strip it so MapStage falls back to the slot's own tmx shopList (Equipment/Booster)
        // instead of whatever shop had migrated in. No-op (Map.remove() on an absent key) for
        // saves that never had one.
        for (int reservedSlot : reservedSlots)
            changes.removePinnedShopName(reservedSlot);
        java.util.List<Integer> regularSlots = readCapitolShopObjectIds(mapPath);
        // The Armory may only ever exist on its reserved slot - strip any armory-family pinned
        // name a buggy earlier migration left on a REGULAR slot (the "EquipmentL2" escape,
        // 2026-08-12: an upgraded town Armory's L2 shop name slipped past the old matcher and got
        // pinned onto the first regular capital slot). The slot keeps its rebuilt flag and simply
        // re-rolls from its own tmx shopList; also repairs the user's already-affected save on
        // next load without any migration machinery.
        for (int slot : regularSlots) {
            String pinned = changes.getPinnedShopName(slot);
            if (pinned != null && EconomyBuildings.isArmoryShopName(pinned)) {
                changes.removePinnedShopName(slot);
                System.out.println("[TownRestoration] Capitol repair: stripped armory pin \"" + pinned
                        + "\" from regular slot " + slot);
            }
        }
        for (java.util.Map.Entry<Integer, Integer> entry : changes.getEconomyBuildingObjectIds().entrySet()) {
            int objectId = entry.getValue();
            if (!reservedSlots.contains(objectId))
                continue;
            Integer freeSlot = null;
            for (int slot : regularSlots) {
                if (!changes.getEconomyBuildingObjectIds().containsValue(slot)
                        && changes.getMapFlags().get("shopRebuilt_" + slot) == null) {
                    freeSlot = slot;
                    break;
                }
            }
            if (freeSlot == null) {
                System.out.println("[TownRestoration] Capitol repair: no free slot to move economy building type "
                        + entry.getKey() + " off reserved shop " + objectId);
                continue;
            }
            entry.setValue(freeSlot);
            changes.getMapFlags().remove("shopRebuilt_" + objectId);
            changes.getMapFlags().put("shopRebuilt_" + freeSlot, (byte) 1);
            System.out.println("[TownRestoration] Capitol repair: moved economy building type " + entry.getKey()
                    + " off reserved shop " + objectId + " to slot " + freeSlot);
        }
    }

    // Town-count life bonus (user spec 2026-08-09): +1 max life per 5 owned towns, +1 more for
    // the Capitol. Recomputed whenever ownership changes (restore, capture loss, Capitol upgrade)
    // and once at load; AdventurePlayer tracks the currently-applied bonus so only the DELTA is
    // ever added/removed - re-running this is always safe.
    private static final int TOWNS_PER_LIFE = 5;

    public static void updateTownLifeBonus(boolean notify) {
        int target = countPlayerTowns() / TOWNS_PER_LIFE + (capitolExists() ? 1 : 0);
        int delta = Current.player().applyTownLifeBonus(target);
        if (delta == 0)
            return;
        System.out.println("[TownRestoration] town life bonus now " + target + " (" + (delta > 0 ? "+" : "") + delta + ")");
        if (notify) {
            if (delta > 0)
                forge.adventure.stage.GameHUD.getInstance().addNotification("Your realm prospers! Max life +" + delta + ".");
            else
                forge.adventure.stage.GameHUD.getInstance().addNotification("Your realm shrinks... Max life " + delta + ".");
        }
    }
}
