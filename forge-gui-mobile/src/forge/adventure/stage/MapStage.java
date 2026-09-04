package forge.adventure.stage;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.maps.MapLayer;
import com.badlogic.gdx.maps.MapObject;
import com.badlogic.gdx.maps.MapProperties;
import com.badlogic.gdx.maps.objects.RectangleMapObject;
import com.badlogic.gdx.maps.tiled.TiledMap;
import com.badlogic.gdx.maps.tiled.TiledMapTileLayer;
import com.badlogic.gdx.maps.tiled.objects.TiledMapTileMapObject;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.*;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.*;
import com.badlogic.gdx.utils.Timer;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TypingAdapter;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.character.*;
import forge.adventure.data.*;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.*;
import forge.adventure.util.*;
import forge.adventure.util.pathfinding.NavigationMap;
import forge.adventure.util.pathfinding.NavigationVertex;
import forge.adventure.util.pathfinding.ProgressableGraphPath;
import forge.adventure.world.WorldSave;
import forge.gui.FThreads;
import forge.haptic.HapticEngine;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.screens.TransitionScreen;
import forge.sound.SoundEffectType;
import forge.sound.SoundSystem;
import forge.util.ScreenUtil;

import java.time.LocalDate;
import java.util.*;
import java.util.Queue;

/**
 * Stage to handle tiled maps for points of interests
 */
public class MapStage extends GameStage {
    public static MapStage instance;
    final Array<MapActor> actors = new Array<>();
    public com.badlogic.gdx.physics.box2d.World gdxWorld;
    public TiledMap tiledMap;
    public Array<Rectangle> collisionRect = new Array<>();
    public Map<Float, NavigationMap> navMaps = new HashMap<>();
    private boolean isInMap = false;
    MapLayer spriteLayer;
    private PointOfInterestChanges changes;
    private EnemySprite currentMob;
    Queue<Vector2> positions = new LinkedList<>();
    private boolean isLoadingMatch = false;
    private boolean isPlayerLeavingDungeon = false;
    //private HashMap<String, Byte> mapFlags = new HashMap<>(); //Stores local map flags. These aren't available outside this map.
    private boolean mustClearOnExit = false;

    //Map properties.
    //These maps are defined as embedded properties within the Tiled maps.
    private EffectData effect;             //"Dungeon Effect": Character Effect applied to all adversaries within the map.
    private boolean preventEscape = false; //Prevents player from escaping the dungeon by any means that aren't an exit.

    public InputEvent eventTouchDown, eventTouchUp;
    private boolean respawnEnemies;
    private boolean canFailDungeon = false;
    protected ArrayList<EnemySprite> enemies = new ArrayList<>();
    public Map<Integer, Vector2> waypoints = new HashMap<>();

    // A shop's own "shop" object is just its 16x16 doorstep footprint - the real building art is
    // baked directly into the town's tile layers (typically "Walls" for the body, "Overlay" for
    // the roof, drawn on a separate layer so it renders in front of the player) one or more tiles
    // above that footprint. There's no object-level way to hide a baked tile, so instead of
    // guessing a fixed pixel offset to draw an overlay over (which didn't hold across every town
    // map tried), this locates the actual tile(s) per shop at map-load time and lets ShopActor
    // hide/restore them directly - see findOverheadTiles()/setShopOverheadTilesHidden().
    private final Map<Integer, List<OverheadTile>> shopOverheadTiles = new HashMap<>();

    // Card Shop Type Re-Roll (2026-08-11, round 8, user spec: "add a re-roll card shop type for
    // 50 shards... randomly pick a new card shop type... change the little bulletin board").
    // shopCandidatePools: the raw comma-list of possible shop names this specific object could
    // ever roll from (captured once at load time, from the exact same possibleShops the initial
    // roll below already computes) - only populated for genuinely multi-choice, non-rotating shop
    // objects, which naturally excludes Armory/land shops (single-name commonShopList values) and
    // Rotating shops (their own date-seeded mechanism, not this one) without needing a separate
    // type check. shopSigns: the sign TextureSprite actually on screen for this object, so a
    // re-roll can swap its artwork live instead of requiring a fresh map reload to see the change.
    private final Map<Integer, Array<String>> shopCandidatePools = new HashMap<>();
    private final Map<Integer, TextureSprite> shopSigns = new HashMap<>();
    // The sign's COLOUR BAR (ShopData.overlaySprite - e.g. "Overlay6Blue"), tracked from
    // 2026-08-31 so a type change can swap it too. User playtest report: after buying three
    // "Cloaks of Invisibility" the signs looked right "but one had a colored bar on the side - it
    // was blue". setShopType() swapped only the base sign region and left the previous type's
    // overlay actor sitting on top of it, so the bar advertised the shop the slot USED to be.
    private final Map<Integer, ShopSignSprite> shopSignOverlays = new HashMap<>();
    // The sign anchor (already offset by signXOffset/signYOffset), kept so an overlay can be
    // CREATED later - a slot whose original type had no color bar still needs one the moment it
    // becomes a type that does.
    private final Map<Integer, Vector2> shopSignAnchors = new HashMap<>();

    // Card Shop Type CHOOSER (2026-08-30, user request: "when you build a card shop, you get a
    // drop-down menu of all types of card shops... so the player can choose what type they want").
    // Unlike shopCandidatePools above - which holds only the ONE tier list this slot happened to
    // roll into at load time - this keeps all three tiers separately, keyed tier -> names, so the
    // player can deliberately buy ACROSS tiers (the user's own pricing example goes Uncommon ->
    // Common). Without it a slot that rolled "common" could only ever become one of the 116 common
    // types, which the requested Common/Uncommon/Rare price ladder assumes it can escape.
    // Populated under the same guard as shopCandidatePools (non-rotating, real choice available).
    private final Map<Integer, Map<String, Array<String>>> shopTierPools = new HashMap<>();
    public static final String TIER_COMMON = "Common";
    public static final String TIER_UNCOMMON = "Uncommon";
    public static final String TIER_RARE = "Rare";

    /** tier -> shop names for this slot, or null if this object has no chooser-eligible pools. */
    public Map<String, Array<String>> getShopTierPools(int objectId) {
        return shopTierPools.get(objectId);
    }

    /** Object ids that have chooser tier pools on the currently-loaded map - lets the blueprint
     *  drops enumerate every shop type a chooser could ever offer. */
    public java.util.Set<Integer> getShopTierPoolObjectIds() {
        return shopTierPools.keySet();
    }

    /** Reads one tier's comma-list off a shop object's TMX properties, de-duplicated (the raw
     *  lists repeat names - player_town.tmx's commonShopList has "Colorless" twice, and a chooser
     *  must not show the same entry twice) and order-preserving. */
    private static void addTierPool(Map<String, Array<String>> into, String tier,
                                    MapProperties prop, String propertyName) {
        if (!prop.containsKey(propertyName))
            return;
        String raw = prop.get(propertyName).toString();
        if (raw.trim().isEmpty())
            return;
        Array<String> names = new Array<>();
        for (String name : raw.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty() && !names.contains(trimmed, false))
                names.add(trimmed);
        }
        if (names.size > 0)
            into.put(tier, names);
    }

    /**
     * Pins an EXPLICIT shop type on this slot (the chooser's counterpart to rerollShopType()'s
     * random pick) and swaps the on-screen sign to match. Returns the resolved ShopData, or null
     * if the name doesn't resolve - callers must treat null as "no change, charge nothing".
     */
    public ShopData setShopType(int objectId, String shopName) {
        if (shopName == null || shopName.isEmpty())
            return null;
        ShopData newData = null;
        for (ShopData candidate : new Array.ArrayIterator<>(WorldData.getShopList())) {
            if (candidate.name.equals(shopName)) {
                newData = candidate;
                break;
            }
        }
        if (newData == null)
            return null;
        applyShopType(objectId, newData, true, "chooser");
        return newData;
    }

    /**
     * Makes a slot BE the given shop type, everywhere it shows, without a map reload.
     * <p>
     * This exists because the 2026-08-30 chooser only did two of the six things that identity is
     * made of - it pinned the name and swapped the sign's base art - and left the live ShopActor
     * still holding the type the slot rolled at load time. The player's 2026-08-31 report is
     * exactly what that produces: "The shop I bought, does not match the shop that was built. In
     * the first town, I bought 3 'Cloaks of Invisibility'" but walking in gave Library of Lat-Nam,
     * Mistform Hive and Fresh Volunteers - three different shops, each with the wrong name, the
     * wrong inventory and the previous type's color bar still on its sign. Leaving and re-entering
     * the town fixed it because THAT path rebuilds the actor from the pin.
     * <p>
     * The six: the persisted pin, the actor's ShopData (name/description/restock price), the
     * inventory (regenerated under the new type's reward rules AND the town's edition
     * restrictions), the sign art, the sign's color-bar overlay, and the purchase history.
     *
     * @param freshSeed true to reroll the inventory seed and clear cardsBought - correct for a
     *                  deliberate type change, since the new identity should not inherit the old
     *                  shop's stock roll or what the player already bought from it.
     */
    public void applyShopType(int objectId, ShopData newData, boolean freshSeed, String trigger) {
        if (newData == null)
            return;
        PointOfInterestChanges changes = getChanges();
        changes.setPinnedShopName(objectId, newData.name);
        if (freshSeed)
            changes.generateNewShopSeed(objectId);

        forge.adventure.character.ShopActor actor = getShopActor(objectId);
        if (actor != null) {
            // Same stale-price correction map load applies to a pinned slot: ShopData instances are
            // SHARED between every slot resolving to that name, so restockPrice can be whatever
            // some unrelated town last wrote. Keep this slot's own tier price.
            int restockPrice = actor.getShopData() != null ? actor.getShopData().restockPrice : newData.restockPrice;
            newData.restockPrice = restockPrice;
            actor.setShopData(newData);
            Array<Reward> ret = new Array<>();
            WorldSave.getCurrentSave().getWorld().getRandom().setSeed(changes.getShopSeed(objectId));
            for (RewardData rdata : EditionProgression.restrictShopRewardsForCurrentTown(
                    new Array.ArrayIterator<>(newData.rewards), changes, newData.name, trigger)) {
                ret.addAll(rdata.generate(false, false));
            }
            EconomyBuildings.injectGuaranteedTorchIfOwed(ret, newData, changes);
            EconomyBuildings.excludeMythicItemsForNeutralArmory(ret, newData, changes);
            actor.setRewardData(ret);
        }
        String overlayOutcome = refreshShopSignArt(objectId, newData);
        // Reports what happened to the sign ACTORS, not just what the JSON says (2026-08-31).
        // The previous version printed newData.overlaySprite, which is a fact about shops.json and
        // is identical whether the swap worked or wrote to a detached orphan - which is exactly why
        // the colour-bar bug looked clean in the log.
        System.out.println("[TFR-ShopChooser] applied type " + newData.name + " to object " + objectId
                + " (trigger=" + trigger + ", actor=" + (actor != null ? "live" : "none")
                + ", overlay=" + overlayOutcome + ")");
    }

    /** Repoints a slot's sign art AND its color bar at the given type. Creates the overlay actor
     *  on demand (a slot whose first type had no color bar still needs one if it becomes a type
     *  that does) and suppresses it when the new type has none. */
    private String refreshShopSignArt(int objectId, ShopData newData) {
        TextureSprite sign = shopSigns.get(objectId);
        if (sign == null)
            return "no-sign"; // this slot has no sign at all (hasSign false) - nothing to repoint
        try {
            sign.setRegion(Config.instance().getAtlasSprite(newData.spriteAtlas, newData.sprite));
        } catch (Exception e) {
            System.err.println("[TFR-ShopChooser] no sign sprite for " + newData.name + ": " + e);
        }
        boolean hasOverlay = newData.overlaySprite != null && !newData.overlaySprite.isEmpty();
        ShopSignSprite overlay = shopSignOverlays.get(objectId);
        if (!hasOverlay) {
            if (overlay != null)
                overlay.setSuppressed(true);
            return "suppressed";
        }
        try {
            if (overlay == null) {
                Vector2 anchor = shopSignAnchors.get(objectId);
                if (anchor == null)
                    return "no-anchor";
                overlay = new ShopSignSprite(Config.instance().getAtlasSprite(newData.spriteAtlas, newData.overlaySprite), objectId);
                overlay.setX(anchor.x);
                overlay.setY(anchor.y);
                addMapActor(overlay);
                shopSignOverlays.put(objectId, overlay);
                overlay.setSuppressed(false);
                return "created:" + newData.overlaySprite;
            }
            overlay.setRegion(Config.instance().getAtlasSprite(newData.spriteAtlas, newData.overlaySprite));
            overlay.setSuppressed(false);
            return "swapped:" + newData.overlaySprite;
        } catch (Exception e) {
            System.err.println("[TFR-ShopChooser] no overlay sprite for " + newData.name + ": " + e);
            return "FAILED:" + newData.overlaySprite;
        }
    }

    public forge.adventure.character.ShopActor getShopActor(int objectId) {
        for (forge.adventure.character.ShopActor actor : getShopActors()) {
            if (actor.getObjectId() == objectId)
                return actor;
        }
        return null;
    }

    /** Names of the card-shop types actually STANDING in this town right now - used by the
     *  one-type-per-town rule in the chooser (user spec 2026-08-31). Rubble does not count: a
     *  ruined slot still carries whatever type it rolled at load, but nothing is built there. */
    public java.util.Set<String> getBuiltShopTypeNames() {
        java.util.Set<String> built = new java.util.HashSet<>();
        for (forge.adventure.character.ShopActor actor : getShopActors()) {
            if (actor.getShopData() == null || actor.isDestroyed())
                continue;
            if (EconomyBuildings.getBuildingType(getChanges(), actor.getObjectId()) != EconomyBuildings.NONE)
                continue; // slot is a Bank/Mine/etc now, not a card shop
            built.add(actor.getShopData().name);
        }
        return built;
    }

    /**
     * A shop's sign, or the color bar that sits on top of it. Both used to be anonymous
     * TextureSprite subclasses created inline at map load with copy-pasted visibility rules - and
     * the two copies had DRIFTED: the overlay's copy was missing the isPermanentlyBrokenShop()
     * clause the base sign had, which is why a ruined shop slot showed a naked color bar with no
     * sign under it (2026-08-31 user report: "I walked into a Neutral town and the shop color bars
     * for the broken/ruined shops were still showing"). One class, one rule, both actors.
     * <p>
     * {@code suppressed} exists because a type change can take a slot from a type WITH a color bar
     * to one without: the overlay actor stays alive (so it can be reused if a later type has one
     * again) but stops drawing.
     */
    private class ShopSignSprite extends TextureSprite {
        private final int shopId;
        private boolean suppressed;

        ShopSignSprite(com.badlogic.gdx.graphics.g2d.TextureRegion region, int shopId) {
            super(region);
            this.shopId = shopId;
        }

        void setSuppressed(boolean suppressed) {
            this.suppressed = suppressed;
        }

        @Override
        public void act(float delta) {
            super.act(delta);
            // While a wasteland shop is still rubble the sign would give away what it sells before
            // the player has rebuilt it; a permanently-broken slot never advertises anything at
            // all; and once the slot becomes an economy building (Bank/Mine/Exchange) the sign art
            // is keyed to a shop type that is no longer there.
            setVisible(!suppressed
                    && (!TownRestoration.isWastelandTown() || TownRestoration.isShopRebuilt(MapStage.this, shopId))
                    && !TownRestoration.isPermanentlyBrokenShop(MapStage.this, shopId)
                    && EconomyBuildings.getBuildingType(getChanges(), shopId) == EconomyBuildings.NONE);
        }
    }

    private static class OverheadTile {
        final TiledMapTileLayer layer;
        final int col, row;
        final TiledMapTileLayer.Cell originalCell;

        OverheadTile(TiledMapTileLayer layer, int col, int row, TiledMapTileLayer.Cell originalCell) {
            this.layer = layer;
            this.col = col;
            this.row = row;
            this.originalCell = originalCell;
        }
    }

    // Searches "Walls" then "Overlay" for the nearest non-empty cell at or above the shop's own
    // column/row. Two rounds of getting this wrong taught real lessons worth recording:
    //
    // 1) The shop's own row (`getCell(col, row)`, dr=0) is NOT empty - the Walls tile actually
    //    lives *there*, not one tile above it. Confirmed by decompiling libGDX's own
    //    BaseTmxMapLoader (javap -c on gdx-1.13.5.jar): TmxMapLoader.Parameters defaults
    //    flipY=true, so object x/y properties get computed as `heightInPixels - rawXmlY` (a
    //    continuous pixel-space flip), while loadTileLayer() computes a tile layer's row as
    //    `(heightInTiles - 1) - rawXmlRow` (a discrete tile-index flip, with its own "-1"). These
    //    two flips don't cancel out cleanly when converting between them via simple division -
    //    working an actual shop's numbers through both formulas side by side (not just reasoning
    //    about direction in the abstract, which is what went wrong the first two times) showed
    //    the Walls tile's true gdx row equals `actor.getY() / tileHeight` exactly, no offset.
    //    Previously started this search at dr=1, so it always skipped the one row that actually
    //    had the Walls tile.
    // 2) Overlay (the roof) is one row above that (dr=1 in gdx terms, matching Walls' raw-XML row
    //    minus one, i.e. one row closer to the top of the authored map).
    //
    // Searches dr=0..3 above first (where the evidence points), falling back to below in case a
    // particular town's authoring differs. Only ever called once per shop, at map-load time,
    // before any shop's tiles have been hidden - later shops searching past an earlier shop's
    // now-hidden cell would get a false miss.
    private List<OverheadTile> findOverheadTiles(int shopCol, int shopRow) {
        List<OverheadTile> found = new ArrayList<>();
        if (tiledMap == null)
            return found;
        for (String layerName : new String[]{"Walls", "Overlay"}) {
            MapLayer mapLayer = tiledMap.getLayers().get(layerName);
            if (!(mapLayer instanceof TiledMapTileLayer))
                continue;
            TiledMapTileLayer layer = (TiledMapTileLayer) mapLayer;
            TiledMapTileLayer.Cell hit = null;
            int hitRow = -1;
            for (int dr = 0; dr <= 3 && hit == null; dr++) {
                TiledMapTileLayer.Cell cell = layer.getCell(shopCol, shopRow + dr);
                if (cell != null) {
                    hit = cell;
                    hitRow = shopRow + dr;
                }
            }
            if (hit == null) {
                for (int dr = 1; dr <= 3 && hit == null; dr++) {
                    TiledMapTileLayer.Cell cell = layer.getCell(shopCol, shopRow - dr);
                    if (cell != null) {
                        hit = cell;
                        hitRow = shopRow - dr;
                    }
                }
            }
            if (hit != null)
                found.add(new OverheadTile(layer, shopCol, hitRow, hit));
        }
        return found;
    }

    /** Hides (or restores) the real building art found above this shop, live - safe to call every frame. */
    public void setShopOverheadTilesHidden(int shopId, boolean hidden) {
        List<OverheadTile> tiles = shopOverheadTiles.get(shopId);
        if (tiles == null)
            return;
        for (OverheadTile t : tiles)
            t.layer.setCell(t.col, t.row, hidden ? null : t.originalCell);
    }

    /** Whether this shop object was recorded with a genuine multi-choice candidate pool at load
     *  time - see shopCandidatePools' own comment. RewardScene uses this to decide whether to
     *  show its "Re-roll Shop Type" button at all. */
    public boolean isShopTypeRerollable(int objectId) {
        return shopCandidatePools.containsKey(objectId);
    }

    /** Re-rolls this shop's TYPE (2026-08-11, round 8, user spec: "add a re-roll card shop type
     *  for 50 shards... randomly pick a new card shop type... change the little bulletin board in
     *  front of the shop also on re-roll to match new shop type"). Picks a new ShopData from the
     *  SAME raw candidate pool this object's own tmx property offered at load time (excluding
     *  whatever it currently is, so a re-roll always actually changes something), pins it via the
     *  same PointOfInterestChanges.setPinnedShopName() mechanism the Capitol migration already
     *  uses for "lock this slot to an exact shop identity", and swaps the live sign sprite's
     *  artwork in place. Returns the new ShopData, or null if this object has no recorded
     *  candidate pool (see isShopTypeRerollable()) or the pool has nothing left to switch to. */
    public ShopData rerollShopType(int objectId, String currentShopName) {
        Array<String> candidates = shopCandidatePools.get(objectId);
        if (candidates == null || candidates.size == 0)
            return null;
        // Booster shops kept separate from ordinary card shops (2026-08-14 user request - found
        // mixed together in the 5 AI-capital towns' commonShopList property, e.g. "...,Instant6Green,
        // GreenBoosterPackShop,Elf,..." - meaning a plain card shop could reroll INTO a Booster shop
        // and vice versa). currentShopName.contains("Booster") mirrors EconomyBuildings.
        // isBoosterShop(ShopData)'s own check (name-based) without needing to resolve currentShopName
        // to a ShopData first - only the name is available here.
        boolean currentIsBooster = currentShopName != null && currentShopName.contains("Booster");
        // One type per town (user spec 2026-08-31). The chooser enforces this by greying built
        // types, but this random re-type is the OTHER way a slot changes identity - the same
        // destroy-and-rebuild bypass the blueprint filter below had to close. Without it, a
        // rebuild could hand the town a second copy of a type the chooser would have refused.
        java.util.Set<String> builtHere = getBuiltShopTypeNames();
        builtHere.remove(currentShopName); // this slot is the one being replaced
        Array<ShopData> matches = new Array<>();
        for (ShopData candidateData : new Array.ArrayIterator<>(WorldData.getShopList())) {
            if (candidates.contains(candidateData.name, false) && !candidateData.name.equals(currentShopName)
                    && EconomyBuildings.isBoosterShop(candidateData) == currentIsBooster
                    // Blueprint gate (2026-08-30): this random re-type is also what
                    // EconomyBuildings.destroyShopFromRewardScene() calls, so without this filter a
                    // player could reach any LOCKED shop type just by destroying and rebuilding -
                    // which would make the whole unlock system cosmetic.
                    && EconomyBuildings.isShopTypeUnlocked(candidateData.name)
                    && !builtHere.contains(candidateData.name))
                matches.add(candidateData);
        }
        if (matches.size == 0) {
            System.out.println("[TFR-Blueprint] rerollShopType(" + objectId + "): no UNLOCKED "
                    + "alternative to \"" + currentShopName + "\" in this slot's pool - keeping current type");
            return null;
        }
        ShopData newData = matches.get(WorldSave.getCurrentSave().getWorld().getRandom().nextInt(matches.size));
        // freshSeed=false: the only caller is destroyShopFromRewardScene(), which generates its own
        // new seed straight after - doing it twice would just burn an extra roll.
        applyShopType(objectId, newData, false, "reroll");
        return newData;
    }

    //todo: add additional graphs for other sprite sizes if desired. Current implementation
    // allows for mobs of any size to fit into 16x16 tiles for navigation purposes
    float collisionWidthMod = 0.4f;
    float defaultSpriteSize = 16f;
    float navMapSize =  defaultSpriteSize * collisionWidthMod;

    public boolean canEscape() {
        return !preventEscape;
    } //Check if escape is possible.

    public void clearIsInMap() {
        isInMap = false;
        effect = null; //Reset effect so battles outside the dungeon don't use the last visited dungeon's effects.
        preventEscape = false;
        GameHUD.getInstance().showHideMap(true);
    }

    public void draw(Batch batch) {
        //Camera camera = getCamera() ;
        //camera.update();
        //update camera after all layers got drawn
        if (!getRoot().isVisible()) return;
        getRoot().draw(batch, 1);
    }

    public MapLayer getSpriteLayer() {
        return spriteLayer;
    }

    public PointOfInterestChanges getChanges() {
        return TileMapScene.instance().getPointOfInterestChanges();
    }
    private boolean freezeAllEnemyBehaviors = false;

    protected MapStage() {
        disposeWorld();
        createNewWorld();
        eventTouchDown = new InputEvent();
        eventTouchDown.setPointer(-1);
        eventTouchDown.setType(InputEvent.Type.touchDown);
        eventTouchUp = new InputEvent();
        eventTouchUp.setPointer(-1);
        eventTouchUp.setType(InputEvent.Type.touchUp);
    }

    public static MapStage getInstance() {
        return instance == null ? instance = new MapStage() : instance;
    }

    @Override
    public void dispose() {
        disposeWorld();
    }

    public void disposeWorld() {
        if (gdxWorld != null) {
            try {
                gdxWorld.dispose();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void addMapActor(MapObject obj, MapActor newActor) {
        newActor.setWidth(Float.parseFloat(obj.getProperties().get("width").toString()));
        newActor.setHeight(Float.parseFloat(obj.getProperties().get("height").toString()));
        newActor.setX(Float.parseFloat(obj.getProperties().get("x").toString()));
        newActor.setY(Float.parseFloat(obj.getProperties().get("y").toString()));
        actors.add(newActor);
        foregroundSprites.addActor(newActor);
    }

    public void addMapActor(MapActor newActor) {
        actors.add(newActor);
        foregroundSprites.addActor(newActor);
    }

    /**
     * The currently-loaded map's live shop actors, with whatever ShopData each actually rolled
     * this load - the Capitol migration snapshots these to pin the upgraded town's exact shop
     * lineup onto the capital layout (see TownRestoration.upgradeToCapitol()).
     */
    public java.util.List<forge.adventure.character.ShopActor> getShopActors() {
        java.util.List<forge.adventure.character.ShopActor> shopActors = new java.util.ArrayList<>();
        for (MapActor actor : actors) {
            if (actor instanceof forge.adventure.character.ShopActor)
                shopActors.add((forge.adventure.character.ShopActor) actor);
        }
        return shopActors;
    }

    // Edition-restriction stale-bake-in fix (2026-08-13) - a shop's RewardData is normally only
    // (re)computed at the moments listed on EditionProgression.restrictShopRewardsForCurrentTown()'s
    // own doc comment (map load, restock, armory reroll/upgrade, shop-type reroll). None of those
    // fire when a wasteland town/shop transitions from AI-owned to player-owned via
    // TownRestoration.buildRestoreTownDialog()/buildRebuildShopDialog(),
    // EconomyBuildings.buildOption(NONE) (plain Card Shop rebuild), or
    // EconomyBuildings.buildSimpleRepairDialog() - those dialogs only spend the cost and set a
    // quest flag, so a freshly-restored shop kept showing whatever AI-color/neutral edition shard
    // it was born with (MapStage's very first build of it, necessarily before restoration) until
    // the player left and re-entered the town or paid for an unrelated restock/reroll. Called from
    // each of those four dialogs' "yes"/"repair" action right after the flag flips. Reuses the
    // shop's EXISTING seed (same as RewardScene.restockShop()) rather than rerolling one - this
    // only corrects which editions are eligible, it isn't a free extra reroll.
    //
    // trigger (adversarial review, 2026-08-13) - threaded through to [TFR-ShopEditions] so a
    // hardcoded label can't make every one of the town's shops log an identical, indistinguishable
    // trigger regardless of which of the 4 call sites actually fired.
    public void refreshAllShopRewards(String trigger) {
        PointOfInterestChanges changes = getChanges();
        for (forge.adventure.character.ShopActor shopActor : getShopActors()) {
            ShopData data = shopActor.getShopData();
            Array<Reward> ret = new Array<>();
            long shopSeed = changes.getShopSeed(shopActor.getObjectId());
            WorldSave.getCurrentSave().getWorld().getRandom().setSeed(shopSeed);
            for (RewardData rdata : EditionProgression.restrictShopRewardsForCurrentTown(
                    new Array.ArrayIterator<>(data.rewards), changes, data.name, trigger)) {
                ret.addAll(rdata.generate(false, false));
            }
            EconomyBuildings.injectGuaranteedTorchIfOwed(ret, data, changes);
            shopActor.setRewardData(ret);
        }
    }

    @Override
    public boolean isColliding(Rectangle adjustedBoundingRect) {
        for (Rectangle collision : collisionRect) {
            if (collision.overlaps(adjustedBoundingRect)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void prepareCollision(Vector2 pos, Vector2 direction, Rectangle boundingRect) {

    }

    Group collisionGroup;

    @Override
    public void debugCollision(boolean b) {
        if (collisionGroup == null) {
            collisionGroup = new Group();

            for (Rectangle rectangle : collisionRect) {
                MapActor collisionActor = new MapActor(0);
                collisionActor.setBoundDebug(true);
                collisionActor.setWidth(rectangle.width);
                collisionActor.setHeight(rectangle.height);
                collisionActor.setX(rectangle.x);
                collisionActor.setY(rectangle.y);
                collisionGroup.addActor(collisionActor);
            }

        }
        if (b) {
            addActor(collisionGroup);
        } else {
            collisionGroup.remove();
        }
        super.debugCollision(b);
    }

    Array<EntryActor> otherEntries = new Array<>();
    Array<EntryActor> spawnClassified = new Array<>();
    Array<EntryActor> sourceMapMatch = new Array<>();

    private void createNewWorld() {
        try {
            gdxWorld = new World(new Vector2(0, 0),false);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public void loadMap(TiledMap map, String sourceMap, String targetMap) {
        loadMap(map, sourceMap, targetMap, 0);
    }

    public void loadMap(TiledMap map, String sourceMap, String targetMap, int spawnTargetId) {
        disposeWorld();
        createNewWorld();
        isLoadingMatch = false;
        isInMap = true;
        GameHUD.getInstance().showHideMap(false);
        this.tiledMap = map;
        for (MapActor actor : new Array.ArrayIterator<>(actors)) {
            actor.remove();
            foregroundSprites.removeActor(actor);
        }
        positions.clear();
        actors.clear();
        collisionRect.clear();
        waypoints.clear();
        shopOverheadTiles.clear();
        // Per-map shop registries (2026-08-31 fix). MapStage is a PROCESS SINGLETON, and these
        // five maps are keyed by tmx object id - a number that is reused by every town in the
        // plane. The loop above detaches every actor from the stage, but these maps kept holding
        // the detached corpses and the previous town's data, so a fresh town inherited whatever
        // the last one left behind under the same id.
        //
        // That is the whole of the user's 2026-08-31 report ("the small lines on the card shop
        // sign did not refresh till I left the town and came back in"): refreshShopSignArt()
        // tests `overlay == null` as its ONLY liveness check, so a stale key looked live, it took
        // the else-branch and called setRegion() on an actor that is no longer in the scene graph.
        // Silent - no exception, nothing in the log. The base sign always refreshed because
        // shopSigns.put() runs for EVERY signed slot at load, so its stale entry is always
        // overwritten; shopSignOverlays.put() only runs when the slot's rolled type happens to
        // have a colour bar (161 of 293 types do), so roughly half the slots kept an orphan.
        //
        // The same leak also let isShopTypeRerollable() answer with a DIFFERENT town's candidate
        // pool, which is how a Buy Blueprint button appeared on a Cartographer's Guild - a
        // single-name land slot that has no candidate pool of its own at all.
        shopSigns.clear();
        shopSignOverlays.clear();
        shopSignAnchors.clear();
        shopCandidatePools.clear();
        shopTierPools.clear();
        // The blueprint drop universe is DERIVED from those pools, so its cache has to go too
        // (2026-09-01 release review). invalidateChooserShopNames() existed and documented itself
        // as "dropped when a new map loads" - but nothing had ever called it, so the universe
        // stayed frozen at whatever the first pickup of the session happened to see.
        EconomyBuildings.invalidateChooserShopNames();

        if (collisionGroup != null)
            collisionGroup.remove();
        collisionGroup = null;

        float width = Float.parseFloat(map.getProperties().get("width").toString());
        float height = Float.parseFloat(map.getProperties().get("height").toString());
        float tileHeight = Float.parseFloat(map.getProperties().get("tileheight").toString());
        float tileWidth = Float.parseFloat(map.getProperties().get("tilewidth").toString());
        setBounds(width * tileWidth, height * tileHeight);
        //collision = new Array[(int) width][(int) height];

        //Load dungeon effects.
        MapProperties MP = map.getProperties();

        if (MP.get("dungeonEffect") != null && !MP.get("dungeonEffect").toString().isEmpty()) {
            effect = JSONStringLoader.parse(EffectData.class, map.getProperties().get("dungeonEffect").toString(), "");
        }
        if (MP.get("respawnEnemies") != null && MP.get("respawnEnemies") instanceof Boolean && (Boolean) MP.get("respawnEnemies")) {
            respawnEnemies = true;
        } else {
            respawnEnemies = false;
        }
        if (MP.get("canFailDungeon") != null && MP.get("canFailDungeon") instanceof Boolean && (Boolean) MP.get("canFailDungeon")) {
            canFailDungeon = true;
        } else {
            canFailDungeon = false;
        }
        if (MP.get("preventEscape") != null) preventEscape = (boolean) MP.get("preventEscape");

        if (MP.get("music") != null && !MP.get("music").toString().isEmpty()) {
            //TODO: Add a way to play a music file directly without using a playlist.
        }

        getPlayerSprite().stop();
        spriteLayer = null;
        otherEntries.clear();
        spawnClassified.clear();
        sourceMapMatch.clear();
        enemies.clear();
        localInnID = -1;
        for (MapLayer layer : map.getLayers()) {
            if (layer.getProperties().containsKey("spriteLayer") && layer.getProperties().get("spriteLayer", boolean.class)) {
                spriteLayer = layer;
            }
            if (layer instanceof TiledMapTileLayer) {
                loadCollision((TiledMapTileLayer) layer);
            } else {
                loadObjects(layer, sourceMap, targetMap);
            }
        }
        // Blueprint tier-fallback drift check (#92, 2026-09-01). AFTER the layer loop, so
        // shopTierPools holds the whole map: FLAT_TOWN_SHOP_TIERS is a union over an entire file
        // resolved lowest-tier-first, so a per-slot comparison would report drift that the next
        // slot in the same file contradicts. No-ops unless this map is player_town.tmx or
        // player_capital.tmx, the two templates the table is derived from.
        EconomyBuildings.auditFlatTownTierFallback(shopTierPools.values(), targetMap);
        spawn(spawnTargetId);

        if (effect != null && enemies.size() > 0) {
            effectDialog(effect);
        }

        //reduce geometry in collision rectangles
        int oldSize;
        do {
            oldSize = collisionRect.size;
            for (int i = 0; i < collisionRect.size; i++) {
                Rectangle r1 = collisionRect.get(i);
                for (int j = i + 1; j < collisionRect.size; j++) {
                    Rectangle r2 = collisionRect.get(j);
                    if ((Math.abs(r1.x - (r2.x + r2.width)) < 1 && Math.abs(r1.y - r2.y) < 1 && Math.abs(r1.height - r2.height) < 1)//left edge is the same as right edge

                            || (Math.abs((r1.x + r1.width) - r2.x) < 1 && Math.abs(r1.y - r2.y) < 1 && Math.abs(r1.height - r2.height) < 1)//right edge is the same as left edge

                            || (Math.abs(r1.x - r2.x) < 1 && Math.abs((r1.y + r1.height) - r2.y) < 1 && Math.abs(r1.width - r2.width) < 1)//top edge is the same as bottom edge

                            || (Math.abs(r1.x - r2.x) < 1 && Math.abs(r1.y - (r2.y + r2.height)) < 1 && Math.abs(r1.width - r2.width) < 1)//bottom edge is the same as left edge

                            || containsOrEquals(r1, r2) || containsOrEquals(r2, r1)
                    ) {
                        r1.merge(r2);
                        collisionRect.removeIndex(j);
                        i--;
                        break;
                    }
                }
            }
        } while (oldSize != collisionRect.size);
        if (spriteLayer == null) System.err.print("Warning: No spriteLayer present in map.\n");

        navMaps.clear();
        navMaps.put(navMapSize, new NavigationMap(navMapSize));
        navMaps.get(navMapSize).initializeGeometryGraph();
        getPlayerSprite().stop();
    }

    public void spawn(int targetId){
        stop(); //Prevent player from unintentionally going back through entrance again when holding input
        boolean hasSpawned = false;
        if (targetId > 0){
            for (int i = 0; i < actors.size; i++) {
                if (actors.get(i).getObjectId() == targetId) {
                    if (actors.get(i) instanceof EntryActor) {
                        ((EntryActor)(actors.get(i))).spawn();
                        hasSpawned = true;
                    }
                }
            }
        }
        if (!hasSpawned){
            if (!spawnClassified.isEmpty())
                spawnClassified.first().spawn();
            else if (!sourceMapMatch.isEmpty())
                sourceMapMatch.first().spawn();
            else if (!otherEntries.isEmpty())
                otherEntries.first().spawn();
        }
    }

    static public boolean containsOrEquals(Rectangle r1, Rectangle r2) {
        float xmi = r2.x;
        float xma = xmi + r2.width;
        float ymi = r2.y;
        float yma = ymi + r2.height;
        return xmi >= r1.x && xmi <= r1.x + r1.width && xma >= r1.x && xma <= r1.x + r1.width && ymi >= r1.y && ymi <= r1.y + r1.height && yma >= r1.y && yma <= r1.y + r1.height;
    }

    private void loadCollision(TiledMapTileLayer layer) {
        for (int x = 0; x < layer.getWidth(); x++) {
            for (int y = 0; y < layer.getHeight(); y++) {
                TiledMapTileLayer.Cell cell = layer.getCell(x, y);
                if (cell == null)
                    continue;
                for (MapObject collision : cell.getTile().getObjects()) {
                    if (collision instanceof RectangleMapObject) {
                        Rectangle r = ((RectangleMapObject) collision).getRectangle();
                        collisionRect.add(new Rectangle(((layer.getTileWidth() * x) + r.x), ((layer.getTileHeight() * y) + r.y), Math.round(r.width), Math.round(r.height)));
                    }
                }
            }
        }
    }

    private boolean canSpawn(MapProperties prop) {
        DifficultyData difficultyData = Current.player().getDifficulty();
        boolean spawnEasy = prop.get("spawn.Easy", Boolean.class);
        boolean spawnNorm = prop.get("spawn.Normal", Boolean.class);
        boolean spawnHard = prop.get("spawn.Hard", Boolean.class);
        boolean spawnInsane = prop.get("spawn.Insane", Boolean.class);
        if (difficultyData.spawnRank == 3 && !spawnInsane) return false;
        if (difficultyData.spawnRank == 2 && !spawnHard) return false;
        if (difficultyData.spawnRank == 1 && !spawnNorm) return false;
        if (difficultyData.spawnRank == 0 && !spawnEasy) return false;
        if (prop.containsKey("spawnCondition") && !prop.get("spawnCondition").toString().isEmpty()){

        }

        return true;
    }

    private void loadObjects(MapLayer layer, String sourceMap, String currentMap) {
        player.setMoveModifier(2);
        Array<String> shopsAlreadyPresent = new Array<>();
        for (MapObject obj : layer.getObjects()) {
            MapProperties prop = obj.getProperties();
            String type = prop.get("type", String.class);
            if (type != null) {
                int id = prop.get("id", int.class);
                if (changes.isObjectDeleted(id))
                    continue;

                boolean hidden = !obj.isVisible(); //Check if the object is invisible.

                String rotatingShop = "";

                switch (type) {
                    case "collision":
                        float cX = Float.parseFloat(prop.get("x").toString());
                        float cY = Float.parseFloat(prop.get("y").toString());
                        float cW = Float.parseFloat(prop.get("width").toString());
                        float cH = Float.parseFloat(prop.get("height").toString());
                        collisionRect.add(new Rectangle(cX, cY, cW, cH));
                        break;
                    case "waypoint":
                        waypoints.put(id, new Vector2(Float.parseFloat(prop.get("x").toString()), Float.parseFloat(prop.get("y").toString())));
                        break;
                    case "entry":
                        float x = Float.parseFloat(prop.get("x").toString());
                        float y = Float.parseFloat(prop.get("y").toString());
                        float w = Float.parseFloat(prop.get("width").toString());
                        float h = Float.parseFloat(prop.get("height").toString());

                        String targetMap = prop.containsKey("teleport")?prop.get("teleport").toString():"";
                        String direction = prop.containsKey("direction")?prop.get("direction").toString():"";
                        boolean canStillSpawnPlayerThere = (targetMap == null || targetMap.isEmpty() && sourceMap.isEmpty()) ||//if target is null and "from world"
                                !sourceMap.isEmpty() && targetMap.equals(sourceMap);

                        int entryTargetId = (!prop.containsKey("teleportObjectId") || prop.get("teleportObjectId") ==null || prop.get("teleportObjectId").toString().isEmpty())? 0: Integer.parseInt(prop.get("teleportObjectId").toString());

                        EntryActor entry = new EntryActor(this, id, targetMap, x, y, w, h, direction, currentMap, entryTargetId);
                        if (prop.containsKey("spawn") && prop.get("spawn").toString().equals("true")) {
                            spawnClassified.add(entry);
                        } else if (canStillSpawnPlayerThere) {
                            sourceMapMatch.add(entry);
                        } else {
                            otherEntries.add(entry);
                        }
                        if (!prop.containsKey("noExit") || prop.get("noExit").toString().equals("false"))
                            addMapActor(obj, entry);
                        break;
                    case "portal":
                        float px = Float.parseFloat(prop.get("x").toString());
                        float py = Float.parseFloat(prop.get("y").toString());
                        float pw = Float.parseFloat(prop.get("width").toString());
                        float ph = Float.parseFloat(prop.get("height").toString());

                        Object portalSpriteProvided = prop.get("sprite");
                        String portalSpriteToUse;
                        portalSpriteToUse = "sprites/portal.atlas";
                        if (portalSpriteProvided != null && !portalSpriteProvided.toString().isEmpty()) portalSpriteToUse = portalSpriteProvided.toString();
                        else
                            System.err.printf("No sprite defined for portal (ID:%s), defaulting to \"sprites/portal.atlas\"", id);

                        String portalTargetMap = prop.get("teleport").toString();
                        boolean validSpawnPoint = (portalTargetMap == null || portalTargetMap.isEmpty() && sourceMap.isEmpty()) ||//if target is null and "from world"
                                !sourceMap.isEmpty() && portalTargetMap.equals(sourceMap);

                        int portalTargetId = (!prop.containsKey("teleportObjectId") || prop.get("teleportObjectId") ==null || prop.get("teleportObjectId").toString().isEmpty())? 0: Integer.parseInt(prop.get("teleportObjectId").toString());

                        PortalActor portal = new PortalActor(this, id, prop.get("teleport").toString(), px, py, pw, ph, prop.get("direction").toString(), currentMap, portalTargetId, portalSpriteToUse);

                        if (prop.containsKey("activeQuestFlag") && Current.player().checkQuestFlag(prop.get("activeQuestFlag").toString())){
                            portal.setAnimation("active");
                        }
                        else if (prop.containsKey("inactiveQuestFlag") && Current.player().checkQuestFlag(prop.get("inactiveQuestFlag").toString())){
                            portal.setAnimation("inactive");
                        }
                        else if (prop.containsKey("closedQuestFlag") && Current.player().checkQuestFlag(prop.get("closedQuestFlag").toString())){
                            portal.setAnimation("closed");
                        }
                        else if (prop.containsKey("portalState")) {
                            portal.setAnimation(prop.get("portalState").toString());
                        }
                        if (prop.containsKey("spawn") && prop.get("spawn").toString().equals("true")) {
                            spawnClassified.add(portal);
                        } else if (validSpawnPoint) {
                            sourceMapMatch.add(portal);
                        } else {
                            otherEntries.add(portal);
                        }
                        addMapActor(obj, portal);
                        break;
                    case "reward":
                        if (!canSpawn(prop)) break;
                        Object R = prop.get("reward");
                        if (R != null && !R.toString().isEmpty()) {
                            Object S = prop.get("sprite");
                            String Sp;
                            Sp = "sprites/treasure.atlas";
                            if (S != null && !S.toString().isEmpty()) Sp = S.toString();
                            else
                                System.err.printf("No sprite defined for reward (ID:%s), defaulting to \"sprites/treasure.atlas\"", id);
                            RewardSprite RW = new RewardSprite(id, R.toString(), Sp);
                            RW.hidden = hidden;
                            addMapActor(obj, RW);
                        }
                        break;
                    case "enemy":
                        if (!canSpawn(prop)) break;
                        Object enemy = prop.get("enemy");
                        if (enemy != null && !enemy.toString().isEmpty()) {
                            EnemyData EN = WorldData.getEnemy(enemy.toString());
                            if (EN == null) {
                                System.err.printf("Enemy \"%s\" not found, choosing a random one for current biome\n", enemy);
                                forge.adventure.world.World world = Current.world();
                                Vector2 poiPos = AdventureQuestController.instance().mostRecentPOI.getPosition();
                                int currentBiome = forge.adventure.world.World.highestBiome(world.getBiome((int) poiPos.x / world.getTileSize(), (int) poiPos.y / world.getTileSize()));
                                EN = world.getData().GetBiomes().get(currentBiome).getEnemy(Current.player().getStatistic().rank());
                            } else if (!EN.boss && EN.questTags.length == 0) {
                                // Content filter tables (user spec 2026-08-12): an Include=N
                                // enemy is skipped from ordinary dungeon population. Same
                                // ordinary-encounter test the re-theme below already uses -
                                // bosses and quest-tagged enemies are protected by design (a
                                // missing boss/quest target would break dungeons and quests).
                                if (!ContentFilterTables.isEnemyIncluded(EN.getName()))
                                    break;
                                // Content-level POI re-theme (MOD_SCOPE.md #7, user request
                                // 2026-08-10): if this dungeon's land has changed hands since
                                // world-gen, swap ordinary (non-boss, non-quest) encounters for a
                                // same-difficulty-ceiling pick from the CURRENT owner's roster
                                // instead of whatever color originally authored this placement.
                                PointOfInterest mostRecentPOI = AdventureQuestController.instance().mostRecentPOI;
                                if (mostRecentPOI != null) {
                                    EnemyData reThemed = TerritoryControl.reThemedEnemyFor(Current.world(), mostRecentPOI, EN.difficulty);
                                    if (reThemed != null)
                                        EN = reThemed;
                                }
                            }
                            EnemySprite mob = new EnemySprite(id, EN);
                            Object dialogObject = prop.get("dialog"); //Check if the enemy has a dialogue attached to it.
                            if (dialogObject != null && !dialogObject.toString().isEmpty()) {
                                mob.dialog = new MapDialog(dialogObject.toString(), this, mob.getId());
                            }
                            dialogObject = prop.get("defeatDialog"); //Check if the enemy has a defeat dialogue attached to it.
                            if (dialogObject != null && !dialogObject.toString().isEmpty()) {
                                mob.defeatDialog = new MapDialog(dialogObject.toString(), this, mob.getId());
                            }
                            dialogObject = prop.get("displayNameOverride"); //Check for name override.
                            if (dialogObject != null && !dialogObject.toString().isEmpty()) {
                                mob.nameOverride = dialogObject.toString();
                            }
                            dialogObject = prop.get("effect"); //Check for special effects.
                            if (dialogObject != null && !dialogObject.toString().isEmpty()) {
                                mob.effect = JSONStringLoader.parse(EffectData.class, dialogObject.toString(), "");
                            }
                            dialogObject = prop.get("ignoreDungeonEffect"); //Check for special effects.
                            if (dialogObject != null && !dialogObject.toString().isEmpty()) {
                                mob.ignoreDungeonEffect = Boolean.parseBoolean(dialogObject.toString());
                            }
                            dialogObject = prop.get("reward"); //Check for additional rewards.
                            if (dialogObject != null && !dialogObject.toString().isEmpty()) {
                                mob.rewards = JSONStringLoader.parse(RewardData[].class, dialogObject.toString(), "[]");
                            }
                            if (prop.containsKey("threatRange")) //Check for threat range.
                            {
                                mob.threatRange = Float.parseFloat(prop.get("threatRange").toString());
                            }
                            if (prop.containsKey("threatRange")) //Check for threat range.
                            {
                                mob.pursueRange = Float.parseFloat(prop.get("pursueRange").toString());
                            }
                            if (prop.containsKey("fleeRange")) //Check for flee range.
                            {
                                mob.fleeRange = Float.parseFloat(prop.get("fleeRange").toString());
                            }
                            if (prop.containsKey("speed")) //Check for flee range.
                            {
                                mob.getData().speed = Float.parseFloat(prop.get("speed").toString());
                            }
                            if (prop.containsKey("flying"))
                            {
                                mob.getData().flying = Boolean.parseBoolean(prop.get("flying").toString());
                            }
                            if (prop.containsKey("hidden"))
                            {
                                hidden = Boolean.parseBoolean(prop.get("hidden").toString());
                            }
                            if (prop.containsKey("inactive"))
                            {
                                mob.inactive = Boolean.parseBoolean(prop.get("inactive").toString());
                                if (mob.inactive) mob.clearCollisionHeight();
                            }
                            dialogObject = prop.get("deckOverride");
                            if (dialogObject != null && !dialogObject.toString().isEmpty())
                            {
                                mob.overrideDeck(dialogObject.toString());
                            }
                            if (hidden){
                                mob.hidden = hidden; //Evil.
                                mob.setAnimation(CharacterSprite.AnimationTypes.Hidden);
                            }
                            dialogObject = prop.get("waypoints");
                            if (dialogObject != null && !dialogObject.toString().isEmpty()) {
                                mob.parseWaypoints(dialogObject.toString());
                            }
                            if (prop.containsKey("speedModifier")) //Increase or decrease default speed for this mob
                            {
                                mob.speedModifier = Float.parseFloat(prop.get("speedModifier").toString());
                            }

                            enemies.add(mob);
                            addMapActor(obj, mob);
                        }
                        break;
                    case "dummy": //Does nothing. Mostly obstacles to be removed by ID by switches or such.
                        TiledMapTileMapObject obj2 = (TiledMapTileMapObject) obj;
                        DummySprite D = new DummySprite(id, obj2.getTextureRegion(), this);
                        if (prop.containsKey("blocking")){
                            D.blocking = Boolean.parseBoolean(prop.get("blocking").toString());
                        }
                        if (prop.containsKey("hidden")){
                            D.setVisible(!Boolean.parseBoolean(prop.get("hidden").toString()));
                        }
                        addMapActor(obj, D);
                        //TODO: Ability to toggle their solid state.
                        //TODO: Ability to move them (using a sequence such as "UULU" for up, up, left, up).
                        break;
                    case "inn":
                        localInnID = id;
                        // Ungated on purpose (user decision 2026-08-09, reversing the earlier
                        // wasteland-rubble gating): the Inn always works from the start, in
                        // destroyed towns and the Capitol alike - single-arg OnCollide, never
                        // shows as rubble, never needs repair.
                        addMapActor(obj, new OnCollide(() -> Forge.switchScene(InnScene.instance(TileMapScene.instance(), TileMapScene.instance().rootPoint.getID(), changes, id))));
                        break;
                    case "spellsmith":
                        addMapActor(obj, new OnCollide(() -> {
                            // Reputation gate (2026-08-14 user spec): an AI-color town/capital's
                            // Spellsmith only deals with the player at Happy-or-better standing
                            // with that color - stricter than the general War-only capital entry
                            // toll (ColorReputation.isEntryBarred()), and specific to this
                            // building, not the whole town. Player-owned towns/Capitol and
                            // neutral/colorless towns are exempt entirely (colorOfTown() is null
                            // for both, same check EditionProgression's shop restriction uses).
                            PointOfInterest point = TileMapScene.instance().rootPoint;
                            String townColor = (point != null && !TownRestoration.isCurrentTownCapitol()
                                    && !TownRestoration.isTownRestored(changes))
                                    ? ColorReputation.colorOfTown(point.getData()) : null;
                            if (townColor != null && !ColorReputation.isSpellsmithAccessible(townColor)) {
                                String displayColor = Character.toUpperCase(townColor.charAt(0)) + townColor.substring(1);
                                DialogData blocked = new DialogData();
                                blocked.text = "The " + displayColor + " Spellsmith won't deal with you - "
                                        + "your standing with " + displayColor + " needs to be Happy or better.";
                                DialogData ok = new DialogData();
                                ok.name = "OK";
                                blocked.options = new DialogData[]{ok};
                                if (new MapDialog(blocked, this, id, null).activate())
                                    showDialog();
                                return;
                            }
                            Forge.switchScene(SpellSmithScene.instance());
                        }, id, this).withRebuiltIcon(EconomyBuildings::getSpellsmithSprite));
                        break;
                    case "shardtrader":
                        MapActor shardTraderActor = new OnCollide(() -> Forge.switchScene(ShardTraderScene.instance()), id, this);
                        addMapActor(obj, shardTraderActor);
                        if (prop.containsKey("hasSign") && Boolean.parseBoolean(prop.get("hasSign").toString()) && prop.containsKey("signYOffset") && prop.containsKey("signXOffset")) {
                            try {
                                TextureSprite sprite = new TextureSprite(Config.instance().getAtlasSprite(ShardTraderScene.spriteAtlas, ShardTraderScene.sprite));
                                sprite.setX(shardTraderActor.getX() + Float.parseFloat(prop.get("signXOffset").toString()));
                                sprite.setY(shardTraderActor.getY() + Float.parseFloat(prop.get("signYOffset").toString()));
                                addMapActor(sprite);

                            } catch (Exception e) {
                                System.err.print("Can not create Texture for Shard Trader");
                            }
                        }
                        break;
                    case "arena":
                        // Gated 3-arg OnCollide like inn/spellsmith (2026-08-08, Player Capitol
                        // round): in a wasteland town/capital the arena starts as rubble and must
                        // be rebuilt like any other building; outside wasteland towns the gate is
                        // inert and this behaves exactly as before. Straight into ArenaScene on
                        // collision (2026-08-11, user request) - the old pre-entry MapStage dialog
                        // (Enter Arena/Enter Challenge Arena/Upgrade) is gone; ArenaScene now owns
                        // its own Upgrade + Normal/Challenging toggle buttons instead.
                        addMapActor(obj, new OnCollide(() -> {
                            String challengeJson = prop.containsKey("arenaChallenge") ? prop.get("arenaChallenge").toString() : null;
                            ArenaScene.instance().enterArenaBuilding(this, id, prop.get("arena").toString(), challengeJson);
                            Forge.switchScene(ArenaScene.instance());
                        }, id, this).withRebuiltIcon(() -> EconomyBuildings.getArenaSprite(changes.getBuildingLevel(id)))
                                // 2026-08-12 cost table: Arena rebuild is 250 gold (vs the plain
                                // shop default this gate would otherwise charge).
                                .withRebuildCost(250, 0, 0, 0, "Rebuild Arena"));
                        break;
                    case "researchlab":
                        // Progressive Set Unlocks (MOD_SCOPE.md #4, user spec 2026-08-12): the
                        // Lab is a pre-existing decorative building already baked into this map's
                        // Ground2/Overlay tile layers (not a rubble-gated economy building like
                        // Arena/Spellsmith) - ungated single-arg OnCollide, same "always works"
                        // pattern as the Inn, and no withRebuiltIcon() since there's no icon of
                        // its own to draw (the art is already on the map regardless of this
                        // object's presence).
                        addMapActor(obj, new OnCollide(() -> Forge.switchScene(ResearchScene.instance())));
                        break;
                    case "exit":
                        addMapActor(obj, new OnCollide(() -> MapStage.this.exitDungeon(false, false)));
                        break;
                    case "dialog":
                        if (obj instanceof TiledMapTileMapObject) {
                            TiledMapTileMapObject tiledObj = (TiledMapTileMapObject) obj;
                            DialogActor dialog;
                            if (prop.containsKey("sprite"))
                                dialog = new DialogActor(this, id, prop.get("dialog").toString(), prop.get("sprite").toString());
                            else {
                                dialog = new DialogActor(this, id, prop.get("dialog").toString(), tiledObj.getTextureRegion());
                            }
                            if (prop.containsKey("hidden") && Boolean.parseBoolean(prop.get("hidden").toString()))
                            {
                                dialog.setVisible(false);
                            }
                            addMapActor(obj, dialog);
                        }
                        break;
                    case "quest":
                        if (prop.containsKey("questtype")) {
                            String questOrigin = prop.containsKey("questtype") ? prop.get("questtype").toString() : "";
                            DialogActor questActor = new QuestActor(TileMapScene.instance().rootPoint.getID(),changes,questOrigin, this, id);
                            questActor.setVisible(false);
                            addMapActor(obj, questActor);
                        }
                        break;

                    case "Rotating":
                        String rotation = "";
                        if (prop.containsKey("rotation")) {
                            rotation = prop.get("rotation").toString();
                        }

                        Array<String> possibleShops = new Array<>(rotation.split(","));

                        if (possibleShops.size > 0) {
                            long rotatingRandomSeed = WorldSave.getCurrentSave().getWorld().getRandom().nextLong() + LocalDate.now().toEpochDay();
                            Random rotatingShopRandom = new Random(rotatingRandomSeed);
                            rotatingShop = possibleShops.get(rotatingShopRandom.nextInt(possibleShops.size));
                            changes.setRotatingShopSeed(id, rotatingRandomSeed);
                        }

                        //Intentionally not breaking here.
                        //Flow continues into "shop" case with above data overriding base logic.

                    case "shop":
                        int restockPrice = 0;
                        String shopList = "";

                        boolean isRotatingShop = !rotatingShop.isEmpty();

                        if (isRotatingShop) {
                            shopList = rotatingShop;
                            restockPrice = 7;
                        } else {
                            int rarity = WorldSave.getCurrentSave().getWorld().getRandom().nextInt(100);
                            // Item economy (2026-08-10): per-shop rarity-mix override - every other
                            // shop keeps the original global split (thresholds 95/85/55, roughly
                            // common/uncommon/rare/mythic 56/30/10/4) unless THIS shop object sets
                            // its own via these optional TMX properties (e.g. the Capitol Armory's
                            // 30/60/8/2 - solve backward: mythic >2% -> threshold 97, rare cumulative
                            // >10% -> 89, uncommon cumulative >70% -> 29).
                            int mythicThreshold = prop.containsKey("mythicThreshold") ? Integer.parseInt(prop.get("mythicThreshold").toString()) : 95;
                            int rareThreshold = prop.containsKey("rareThreshold") ? Integer.parseInt(prop.get("rareThreshold").toString()) : 85;
                            int uncommonThreshold = prop.containsKey("uncommonThreshold") ? Integer.parseInt(prop.get("uncommonThreshold").toString()) : 55;
                            if (rarity > mythicThreshold & prop.containsKey("mythicShopList")) {
                                shopList = prop.get("mythicShopList").toString();
                                restockPrice = 5;
                            }
                            if (shopList.isEmpty() && (rarity > rareThreshold & prop.containsKey("rareShopList"))) {
                                shopList = prop.get("rareShopList").toString();
                                restockPrice = 4;
                            }
                            if (shopList.isEmpty() && (rarity > uncommonThreshold & prop.containsKey("uncommonShopList"))) {
                                shopList = prop.get("uncommonShopList").toString();
                                restockPrice = 3;
                            }
                            if (shopList.isEmpty() && prop.containsKey("commonShopList")) {
                                shopList = prop.get("commonShopList").toString();
                                restockPrice = 2;
                            }
                            if (shopList.trim().isEmpty() && prop.containsKey("shopList")) {
                                shopList = prop.get("shopList").toString(); //removed but included to not break existing custom planes
                                restockPrice = 0; //Tied to restock button
                            }
                            shopList = shopList.replaceAll("\\s", "");

                            // Armory level-based slot count (2026-08-11, round 8, user spec: "Lvl
                            // 1 has 6 and level 2 has 8. Regardless of where they are") - redirects
                            // to a "L2"-suffixed shops.json variant (same item pool, more slots)
                            // once this Armory is Level 2, covering both the Town's "Equipment"
                            // shop and the Capitol's "ArmoryCommon"/"Uncommon"/"Rare"/"Mythic"
                            // tiers with one check. Name-string match mirrors
                            // EconomyBuildings.isArmoryShop()'s own logic - that method takes an
                            // already-resolved ShopData, not available yet at this point.
                            boolean isArmoryShopList = !shopList.isEmpty()
                                    && (shopList.endsWith("Equipment") || shopList.startsWith("Armory"));
                            if (isArmoryShopList && changes.getBuildingLevel(id) >= 2) {
                                shopList = shopList + "L2";
                            }
                        }

                        // Item economy (2026-08-10): a noRestock shop reseeds automatically once a
                        // week - see the weekly-seed branch at this method's shop-seed selection
                        // below. Whether it ALSO keeps a player-paid restock button now depends on
                        // what kind of shop it is (2026-08-15 user correction): the Armory family
                        // and the fixed land shops stay button-less (their weekly refresh is the
                        // only refresh, restockPrice forced to 0 as before), but the widened
                        // ordinary card shops keep their tier-based restock price so the small
                        // "[+Refresh]" button (base price + weekly-escalating surcharge, exactly
                        // what the old Rotating shops showed) is available as a manual override on
                        // top of the automatic weekly reseed.
                        boolean noRestock = prop.containsKey("noRestock") && (boolean) prop.get("noRestock");
                        boolean fixedShopProp = prop.containsKey("fixedShop") && Boolean.parseBoolean(prop.get("fixedShop").toString());
                        if (noRestock && (EconomyBuildings.isArmoryShopName(shopList) || fixedShopProp)) {
                            restockPrice = 0;
                        }

                        possibleShops = new Array<String>(shopList.split(","));
                        // Card Shop Type Re-Roll (round 8): record the raw, unfiltered candidate
                        // list for this object id, but only when there's genuinely a choice to
                        // re-roll among (>1 name) and this isn't a Rotating shop (own separate
                        // mechanism, not this one) - see the field's own comment.
                        if (!isRotatingShop && possibleShops.size > 1)
                            shopCandidatePools.put(id, possibleShops);
                        // Card Shop Type chooser (2026-08-30) - capture ALL tier lists, not just
                        // the one the rarity roll above happened to land on, so the chooser can
                        // offer (and price) across tiers. Read straight off the same TMX
                        // properties that roll consulted; deliberately does NOT include
                        // mythicShopList (no price tier was specified for it, and it is not part
                        // of the requested Common/Uncommon/Rare ladder).
                        if (!isRotatingShop) {
                            Map<String, Array<String>> tierPools = new java.util.LinkedHashMap<>();
                            addTierPool(tierPools, TIER_COMMON, prop, "commonShopList");
                            addTierPool(tierPools, TIER_UNCOMMON, prop, "uncommonShopList");
                            addTierPool(tierPools, TIER_RARE, prop, "rareShopList");
                            if (!tierPools.isEmpty()) {
                                shopTierPools.put(id, tierPools);
                                // Feed the process-wide name -> tier map too (2026-08-31): the AI
                                // capitals declare a flat shopList and have no tier pools of their
                                // own, so blueprint pricing and the reputation ladder there depend
                                // entirely on what other maps have taught us.
                                EconomyBuildings.registerShopTiers(tierPools);
                            }
                        }
                        Array<String> filteredPossibleShops = new Array<>();
                        if (!isRotatingShop) {
                            for (String candidate : possibleShops) {
                                if (!shopsAlreadyPresent.contains(candidate, false))
                                    filteredPossibleShops.add(candidate);
                            }
                        }
                        if (filteredPossibleShops.isEmpty()) {
                            filteredPossibleShops = possibleShops;
                        }
                        Array<ShopData> shops;
                        if (filteredPossibleShops.size == 0 || shopList.isEmpty())
                            shops = WorldData.getShopList();
                        else {
                            shops = new Array<>();
                            for (ShopData data : new Array.ArrayIterator<>(WorldData.getShopList())) {
                                if (filteredPossibleShops.contains(data.name, false)) {
                                    data.restockPrice = restockPrice;
                                    shops.add(data);
                                }
                            }
                        }
                        if (shops.size == 0) continue;

                        ShopData data = shops.get(WorldSave.getCurrentSave().getWorld().getRandom().nextInt(shops.size));
                        // A pinned slot ignores the roll above (still executed so the shared world
                        // RNG advances identically either way) and becomes exactly the recorded
                        // shop - how the Capitol migration keeps the source town's actual shops
                        // (see PointOfInterestChanges.pinnedShopNames).
                        String pinnedName = changes.getPinnedShopName(id);
                        if (pinnedName != null) {
                            for (ShopData candidate : new Array.ArrayIterator<>(WorldData.getShopList())) {
                                if (pinnedName.equals(candidate.name)) {
                                    data = candidate;
                                    break;
                                }
                            }
                        }
                        // Stale-price fix (2026-08-15 review finding): a pinned name (Capitol
                        // migration, MapStage.rerollShopType()) can resolve to a ShopData outside
                        // THIS slot's own filteredPossibleShops - the only place restockPrice gets
                        // written above (line ~1007) - leaving the shared/cached instance holding
                        // whatever price some unrelated slot last wrote (or JSON's default 0).
                        // Apply this slot's own computed price unconditionally so it's never stale.
                        data.restockPrice = restockPrice;
                        shopsAlreadyPresent.add(data.name);
                        Array<Reward> ret = new Array<>();
                        // noRestock shops (Armory, land shops) reseed automatically once a week
                        // instead of being frozen on their first-ever roll - see
                        // PointOfInterestChanges.getWeeklyShopSeed().
                        long shopSeed = noRestock
                                ? changes.getWeeklyShopSeed(id, WorldSave.getCurrentSave().getWorld().getCurrentDay())
                                : changes.getShopSeed(id);
                        WorldSave.getCurrentSave().getWorld().getRandom().setSeed(shopSeed);
                        // Progressive Set Unlocks (MOD_SCOPE.md #4): restrict this shop's card
                        // rolls to whichever edition list applies to whoever owns this town - the
                        // player's own unlockedEditions (grown by research) in the Capitol/an
                        // owned town, or a permanent color/neutral shard everywhere else. Clones
                        // each RewardData rather than mutating data.rewards directly - those
                        // RewardData objects are the SAME shared instances every other town
                        // resolving to this shop name also uses.
                        Iterable<RewardData> shopRewardSource = EditionProgression.restrictShopRewardsForCurrentTown(
                                new Array.ArrayIterator<>(data.rewards), changes, data.name, "init");
                        for (RewardData rdata : shopRewardSource) {
                            ret.addAll(rdata.generate(false, false));
                        }
                        EconomyBuildings.injectGuaranteedTorchIfOwed(ret, data, changes);
                        EconomyBuildings.excludeMythicItemsForNeutralArmory(ret, data, changes);
                        ShopActor actor = new ShopActor(this, id, ret, data);
                        // Capitol land shops: fixed identity, simple repair, no overlay icon once
                        // rebuilt (hut art baked into the map) - see ShopActor.fixedShop.
                        if (prop.containsKey("fixedShop") && (boolean) prop.get("fixedShop"))
                            actor.setFixedShop(true);
                        // RewardScene's "Inventory will refresh weekly" note keys off this flag
                        // (2026-08-15) - it used to infer weekly refresh from !canRestock(), which
                        // broke the moment noRestock ordinary card shops got their restock button
                        // back (both facts are now true at once for those shops).
                        actor.setWeeklyRefresh(noRestock);
                        addMapActor(obj, actor);
                        // Locate the real building art baked above this shop's own footprint (see
                        // findOverheadTiles()'s own doc comment) once, right after the actor's
                        // final position is set, before any shop's tiles get hidden this session.
                        // Math.round(), not a truncating cast - some shop instances aren't placed
                        // perfectly tile-aligned (a couple of pixels of authoring slop), which a
                        // plain (int) cast would round toward zero and land on the wrong row for.
                        int shopCol = Math.round(actor.getX() / actor.getWidth());
                        int shopRow = Math.round(actor.getY() / actor.getHeight());
                        shopOverheadTiles.put(id, findOverheadTiles(shopCol, shopRow));
                        // While a wasteland shop is still rubble, the sign would give away what it
                        // sells before the player has rebuilt it. Signs used to only be created at
                        // all when the shop was already rebuilt at map-load time, which meant a
                        // sign wouldn't appear until the player left and re-entered the town after
                        // rebuilding - now always created, but with a live act()-driven visibility
                        // check (same "still wasteland and not this shop's rebuilt flag" condition
                        // ShopActor.isDestroyed() itself uses), so it appears the instant the shop
                        // is rebuilt without needing a fresh map load. Also hidden once the shop
                        // becomes an economy building (Bank/Mine/Exchange) - the sign art is keyed
                        // to this shop's original random type (e.g. a Card Shop sign) which no
                        // longer matches what got built, and there's no per-building-type sign art
                        // yet (MOD_SCOPE.md wishlist item) - better to show no sign than a wrong one.
                        if (prop.containsKey("hasSign") && (boolean) prop.get("hasSign") && prop.containsKey("signYOffset") && prop.containsKey("signXOffset")) {
                            final int shopId = id;
                            try {
                                float signX = actor.getX() + Float.parseFloat(prop.get("signXOffset").toString());
                                float signY = actor.getY() + Float.parseFloat(prop.get("signYOffset").toString());
                                ShopSignSprite sprite = new ShopSignSprite(Config.instance().getAtlasSprite(data.spriteAtlas, data.sprite), shopId);
                                sprite.setX(signX);
                                sprite.setY(signY);
                                addMapActor(sprite);
                                shopSigns.put(id, sprite); // Card Shop Type Re-Roll (round 8) - see rerollShopType()
                                shopSignAnchors.put(id, new Vector2(signX, signY)); // so an overlay can be added later

                                if (!(data.overlaySprite == null || data.overlaySprite.isEmpty())) {
                                    ShopSignSprite overlay = new ShopSignSprite(Config.instance().getAtlasSprite(data.spriteAtlas, data.overlaySprite), shopId);
                                    overlay.setX(signX);
                                    overlay.setY(signY);
                                    addMapActor(overlay);
                                    shopSignOverlays.put(id, overlay);
                                }
                            } catch (Exception e) {
                                System.err.print("Can not create Texture for " + data.sprite + " Obj:" + data);
                            }
                        }
                        break;
                    default:
                        System.err.println("Unexpected value: " + type);
                }
            }
        }
    }

    //We could track MapObject IDs more generally but for now this is the only one we might need.
    private int localInnID = -1;
    public InnScene findLocalInn() {
        if(localInnID == -1)
            return null;
        return InnScene.instance(TileMapScene.instance(), TileMapScene.instance().rootPoint.getID(), changes, localInnID);
    }

    /**
     * Fires DungeonRotation.onDungeonClear() if the dungeon the player is leaving has nothing
     * left in it - no live enemies AND no uncollected reward objects. See exitDungeon()'s own
     * comment for why the combat-win trigger alone wasn't enough.
     */
    private void clearDungeonIfEmptied() {
        PointOfInterest root = TileMapScene.instance().rootPoint;
        // No isEnabled() check here - it is private, and onDungeonClear() already gates on it
        // (plus rotatable/story) as its very first act, so calling in unconditionally is correct
        // and keeps the rules in exactly one place.
        if (root == null)
            return;
        for (EnemySprite enemy : enemies) {
            // Same "still actually on the map" test updateQuestsWin() uses, and the same
            // defeatDialog exemption: an enemy that can't be removed by defeating it must not
            // hold the dungeon open forever.
            if (enemy != null && enemy.getStage() != null && enemy.defeatDialog == null)
                return;
        }
        for (MapActor actor : new Array.ArrayIterator<>(actors)) {
            if (actor instanceof RewardSprite && actor.getStage() != null)
                return; // loot still sitting there - not emptied
        }
        System.out.println("[TFR-DungeonClear] " + root.getDisplayName()
                + " left with no enemies and no loot remaining - despawning via onDungeonClear");
        DungeonRotation.onDungeonClear(root);
    }

    public boolean exitDungeon(boolean defeated, boolean defeatedByBoss) {
        // Dungeon rotation's defeat hook is NOT here (an earlier version was, keyed on the
        // `defeated` parameter - it never fired in practice: a match loss with life remaining
        // routes through dungeonFailedDialog() -> exitDungeon(false, ...), and conceding likewise,
        // so `defeated` is only true when life actually hit zero). The hook lives at the match-loss
        // handler itself - see the loss branch below (the one that calls updateQuestsLose()).
        if (mustClearOnExit) {
            mustClearOnExit = false;

            this.resetMapRecursive(forge.adventure.scene.TileMapScene.resolveMapPath(AdventureQuestController.instance().mostRecentPOI), new HashSet<>()); // round 106
        }

        // Despawn-on-exit for a dungeon that is now genuinely empty (2026-08-30 user report:
        // "This cave did not disappear, even though I emptied out all the loot in it").
        // DungeonRotation.onDungeonClear() previously had exactly ONE trigger - the combat win in
        // AdventureQuestController.updateQuestsWin() where the killed enemy was the last one. That
        // misses every other way a dungeon ends up empty: enemies killed on an EARLIER visit and
        // only the loot collected on this one, enemies that were walked past, or a map whose
        // remaining content was taken rather than fought. In all of those the win event either
        // never happens or happened on a visit when loot still remained, so the despawn hook never
        // rides along and the emptied dungeon sits on the map forever, occupying a rotation slot.
        // Requires BOTH no enemies and no reward objects left, deliberately: "no enemies" alone
        // would despawn a loot-only cave the moment the player first walked in and out, stranding
        // its loot. onDungeonClear() is safe to call while still inside (see its own doc and
        // DialogData.triggerDungeonClear's comment - it only touches World-level POI bookkeeping,
        // not the loaded MapStage) and self-gates on rotatable/story/rotation-enabled, so a
        // non-rotatable or story dungeon is unaffected.
        if (!defeated && !defeatedByBoss)
            clearDungeonIfEmptied();
        AdventureQuestController.instance().updateQuestsLeave();
        clearIsInMap();
        AdventureQuestController.instance().showQuestDialogs(this);
        isLoadingMatch = false;
        effect = null; //Reset dungeon effects.
        if (defeated)
            WorldStage.getInstance().resetPlayerLocation();
        else if (defeatedByBoss)
            WorldStage.getInstance().defeatedFromBoss();
        Forge.switchScene(GameScene.instance());
        isPlayerLeavingDungeon = false;
        dialogOnlyInput = false;
        return true;
    }

    /**
     * Recursively clears map objects for the current map and all connected maps to reset the state of the dungeon when exiting from it.
     * @param currentMap The filename of the map to clear and check for connected maps from.
     * @param clearedMaps A set of maps that have already been cleared to avoid infinite recursion from circular connections between maps.
     */
    private void resetMapRecursive(String currentMap, HashSet<String> clearedMaps) {
        if (clearedMaps.contains(currentMap)) {
            return;
        }

        clearedMaps.add(currentMap);

        // Clear the current map's deleted objects.
        TileMapScene.instance().getPointOfInterestChanges(currentMap).clearDeletedObjects();
        
        TiledMap currentTiledMap = loadMapFile(currentMap);
        
        for (MapLayer layer : currentTiledMap.getLayers()) {
            if (layer.getProperties().containsKey("spriteLayer") || layer instanceof TiledMapTileLayer) {
                continue;
            }
            
            // Attemps to find connected maps through "entry" type MapObjects and recursively clear them as well.
            for (MapObject obj : layer.getObjects()) {
                MapProperties prop = obj.getProperties();
                String type = prop.get("type", String.class);
                
                if (type != null && type.equals("entry")) {
                    String targetMap = prop.containsKey("teleport") ? prop.get("teleport").toString() : "";
                    
                    if (targetMap != null && !targetMap.isEmpty()) {
                        resetMapRecursive(targetMap, clearedMaps);
                    }
                }
            }
        }
    }

    private TiledMap loadMapFile(String mapName) {
        return new TemplateTmxMapLoader().load(Config.instance().getCommonFilePath(mapName));
    }

    @Override
    public void setWinner(boolean playerWins, boolean isArena) {
        isLoadingMatch = false;
        freezeAllEnemyBehaviors = true;
        if (playerWins) {
            currentMob.clearCollisionHeight();
            Current.player().win();
            player.setAnimation(CharacterSprite.AnimationTypes.Attack);
            float attackDuration = Math.max(1f,
                    player.getActionAnimationDuration(CharacterSprite.AnimationTypes.Attack, 1f));
            currentMob.playEffect(Paths.EFFECT_BLOOD, 0.5f);
            Timer.schedule(new Timer.Task() {
                @Override
                public void run() {
                    currentMob.setAnimation(CharacterSprite.AnimationTypes.Death);
                    currentMob.resetCollisionHeight();
                    float deathDuration = currentMob.getActionAnimationDuration(CharacterSprite.AnimationTypes.Death, 0.3f);
                    startPause(deathDuration, () -> {
                        MapStage.this.getReward();
                        AdventureQuestController.instance().updateQuestsWin(currentMob,enemies);
                        AdventureQuestController.instance().showQuestDialogs(MapStage.this);
                        currentMob = null;
                    });
                    player.setAnimation(CharacterSprite.AnimationTypes.Idle);
                }
            }, attackDuration);
        } else {
            currentMob.clearCollisionHeight();
            player.setAnimation(CharacterSprite.AnimationTypes.Hit);
            currentMob.setAnimation(CharacterSprite.AnimationTypes.Attack);
            float resultAnimationDuration = Math.max(
                    player.getActionAnimationDuration(CharacterSprite.AnimationTypes.Hit, 0.3f),
                    currentMob.getActionAnimationDuration(CharacterSprite.AnimationTypes.Attack, 0.3f));
            startPause(resultAnimationDuration, () -> {
                player.setAnimation(CharacterSprite.AnimationTypes.Idle);
                currentMob.setAnimation(CharacterSprite.AnimationTypes.Idle);
                currentMob.resetCollisionHeight();
                if (positions.peek() != null) {
                    player.setPosition(positions.peek());
                }
                currentMob.freezeMovement();
                // Dungeon rotation (MOD_SCOPE.md #15): THE match-loss moment - every way to lose
                // inside a dungeon funnels here (life hitting zero, losing with life remaining,
                // conceding), unlike exitDungeon()'s `defeated` parameter, which is only true when
                // life actually reached zero (an earlier hook keyed on it missed concedes/ordinary
                // losses entirely - real, reported: "I entered several dungeons and they remained
                // after I conceded/lost"). BEFORE updateQuestsLose() so the 3-attempts rule still
                // sees the protecting quest active. No-op for towns/story dungeons/bosses and any
                // non-rotatable POI.
                DungeonRotation.onDungeonDefeat(TileMapScene.instance().rootPoint);
                AdventureQuestController.instance().updateQuestsLose(currentMob);
                AdventureQuestController.instance().showQuestDialogs(MapStage.this);
                boolean defeated = Current.player().defeated();
                //If hardcore mode is added, check and redirect to game over screen here
                if (canFailDungeon && !defeated)
                    dungeonFailedDialog(true, currentMob.getData().boss && !isArena);
                else
                    exitDungeon(defeated, currentMob.getData().boss && !isArena);
                MapStage.this.stop();
                currentMob = null;
            });
        }
    }

    // Standalone welcome popup (MOD_SCOPE.md #89): plain OK-dialog with the plane's
    // config.json welcomePopupText; TileMapScene shows it once per save on first map entry.
    public void showWelcomePopup(String text) {
        dialog.getButtonTable().clear();
        dialog.getContentTable().clear();
        dialog.clearListeners();
        TextraButton okButton = Controls.newTextButton("OK", this::hideDialog);
        TypingLabel label = Controls.newTypingLabel(text);
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getButtonTable().add(okButton).width(240f);
        dialog.getContentTable().add(label).width(250f);
        dialog.setKeepWithinStage(true);
        showDialog();
    }

    private void dungeonFailedDialog(boolean exit, boolean defeatedByBoss) {
        dialog.getButtonTable().clear();
        dialog.getContentTable().clear();
        dialog.clearListeners();
        TextraButton ok = Controls.newTextButton("OK", this::hideDialog);
        ok.setVisible(false);
        TypingLabel L = Controls.newTypingLabel("{GRADIENT=RED;WHITE;1;1}" + Forge.getLocalizer().getMessage("lblDefeatedDescription"));
        L.setWrap(true);
        L.setTypingListener(new TypingAdapter() {
            @Override
            public void end() {
                ok.setVisible(true);
            }
        });
        dialog.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                L.skipToTheEnd();
                super.clicked(event, x, y);
                if (exit)
                    exitDungeon(false, defeatedByBoss);
            }
        });
        dialog.getButtonTable().add(ok).width(240f);
        dialog.getContentTable().add(L).width(250f);
        dialog.setKeepWithinStage(true);
        showDialog();
    }

    public boolean deleteObject(int id) {
        changes.deleteObject(id);
        for (int i = 0; i < actors.size; i++) {
            if (actors.get(i).getObjectId() == id && id > 0) {
                if (actors.get(i).getClass().equals(EnemySprite.class)) {
                    enemies.remove((EnemySprite) actors.get(i));
                }
                actors.get(i).remove();
                actors.removeIndex(i);
                return true;
            }
        }
        return false;
    }

    public boolean activateMapObject(int id){
        if (changes.isObjectDeleted(id)){
            return false;
        }
        for (int i = 0; i < actors.size; i++) {
            if (actors.get(i).getObjectId() == id && id > 0) {
                if (actors.get(i) instanceof EnemySprite) {
                    ((EnemySprite)(actors.get(i))).inactive = false;
                    (actors.get(i)).resetCollisionHeight();
                    return true;
                }
                else if (actors.get(i) instanceof PortalActor) {
                    PortalActor thisPortal = (PortalActor)(actors.get(i));

                    if (thisPortal.getAnimation().equals("active"))
                        thisPortal.setAnimation("closed");
                    else
                        thisPortal.setAnimation("active");
                    return true;
                }
            }
        }
        return false;
    }

    public boolean lookForID(int id) { //Search actor by ID.

        for (MapActor A : new Array.ArrayIterator<>(actors)) {
            if (A.getId() == id)
                return true;
        }
        return false;
    }

    public EnemySprite getEnemyByID(int id) { //Search actor by ID, enemies only.
        for (MapActor A : new Array.ArrayIterator<>(actors)) {
            if (A instanceof EnemySprite && A.getId() == id)
                return ((EnemySprite) A);
        }
        return null;
    }

    public int getRemainingEnemyCount() {
        int count = 0;
        for (EnemySprite enemy : enemies) {
            if (enemy.getStage() != null && enemy.defeatDialog == null) {
                count++;
            }
        }
        return count;
    }

    public Actor getByID(int id) { //Search actor by ID.
        for (MapActor A : new Array.ArrayIterator<>(actors)) {
            if (A.getId() == id)
                return A;
        }
        return null;
    }

    protected void getReward() {
        isLoadingMatch = false;
        Array<Reward> loot = currentMob.getRewards();
        // Bronze Coin ransom reclaim as a visible loot tile (user request 2026-09-01) - the
        // dungeon/town twin of WorldStage.setWinner's call. See
        // AdventurePlayer.appendCoinRansomReward.
        Current.player().appendCoinRansomReward(loot, currentMob.getName());
        RewardScene.instance().loadRewards(loot, RewardScene.Type.Loot, null);
        Forge.switchScene(RewardScene.instance());
        if (currentMob.defeatDialog == null) {
            currentMob.remove();
            actors.removeValue(currentMob, true);
            if (!respawnEnemies || currentMob.getData().boss)
                changes.deleteObject(currentMob.getId());
                enemies.remove(currentMob);
        } else {
            currentMob.defeatDialog.activate();
            player.setAnimation(CharacterSprite.AnimationTypes.Idle);
            currentMob.setAnimation(CharacterSprite.AnimationTypes.Idle);
        }
    }

    public void removeAllEnemies() {
        Array<Integer> idsToRemove = new Array<>();
        for (MapActor actor : new Array.ArrayIterator<>(actors)) {
            if (actor instanceof EnemySprite) {
                idsToRemove.add(actor.getObjectId());
            }
        }
        for (Integer i : idsToRemove) deleteObject(i);
    }

    @Override
    protected void onActing(float delta) {
        if (isPaused() || isDialogOnlyInput() || Forge.advFreezePlayerControls || isPlayerLeavingDungeon)
            return;

        Iterator<EnemySprite> it = enemies.iterator();

        if (freezeAllEnemyBehaviors) {
            if (!positions.contains(player.pos())) {
                freezeAllEnemyBehaviors = false;
            }
            else return;
        }
        float mobSize = navMapSize; //todo: replace with actual size if multiple nav maps implemented
        ArrayList<NavigationVertex> verticesNearPlayer = new ArrayList<>(navMaps.get(mobSize).navGraph.getNodes());
        verticesNearPlayer.sort(Comparator.comparingInt(o -> Math.round((o.pos.x - player.pos().x) * (o.pos.x - player.pos().x) + (o.pos.y - player.pos().y) * (o.pos.y - player.pos().y))));

        if (!freezeAllEnemyBehaviors) {
            while (it.hasNext()) {
                EnemySprite mob = it.next();
                if (mob.inactive){
                    continue;
                }
                mob.updatePositon();

                ProgressableGraphPath<NavigationVertex> navPath = new ProgressableGraphPath<>(0);
                if (mob.getData().flying) {
                    navPath.add(new NavigationVertex(mob.getTargetVector(player, null,delta)));
                } else {
                    Vector2 destination = mob.getTargetVector(player, verticesNearPlayer, delta);

                    if (mob.isFrozen() || (destination.epsilonEquals(mob.pos()) && !mob.aggro)) {
                        mob.setAnimation(CharacterSprite.AnimationTypes.Idle);
                        continue;
                    }

                    if (destination.equals(mob.targetVector) && mob.getNavPath() != null)
                        navPath = mob.getNavPath();

                    if (navPath.nodes.size == 0 || !destination.equals(mob.targetVector)) {
                        mob.targetVector = destination;
                        navPath = navMaps.get(mobSize).findShortestPath(mobSize, mob.pos(), mob.targetVector);
                    }

                    if (mob.aggro) {
                        navPath.add(new NavigationVertex(player.pos()));
                    }
                }

                if (navPath == null || navPath.getCount() == 0 || navPath.get(0) == null) {
                        mob.setAnimation(CharacterSprite.AnimationTypes.Idle);
                        continue;
                }
                Vector2 currentVector = null;

                while (navPath.getCount() > 0 && navPath.get(0) != null && (navPath.get(0).pos == null || navPath.get(0).pos.dst(mob.pos()) < 0.5f)) {

                    navPath.remove(0);

                }
                if (navPath.getCount() != 0) {
                    currentVector = new Vector2(navPath.get(0).pos).sub(mob.pos());
                }
                mob.setNavPath(navPath);
                mob.clearActions();
                if (currentVector == null || (currentVector.x == 0.0f && currentVector.y == 0.0f)) {
                    mob.setAnimation(CharacterSprite.AnimationTypes.Idle);
                    continue;
                }
                mob.steer(currentVector);
                mob.update(delta);
            }
        }

        float sprintingMod = currentModifications.containsKey(PlayerModification.Sprint) ? 2 : 1;
        player.setMoveModifier(2 * sprintingMod);

        positions.add(player.pos());
        if (positions.size() > 4)
            positions.remove();

        for (MapActor actor : new Array.ArrayIterator<>(actors)) {
            if (actor.collideWithPlayer(player)) {
                if (actor instanceof EnemySprite) {
                    EnemySprite mob = (EnemySprite) actor;
                    currentMob = mob;
                    resetPosition();
                    if (mob.dialog != null && mob.dialog.canShow()) { //This enemy has something to say. Display a dialog like if it was a DialogActor but only if dialogue is possible.
                        mob.dialog.activate();
                    } else { //Duel the enemy.
                        beginDuel(mob);
                    }
                    break;
                } else if (actor instanceof RewardSprite) {
                    freezeAllEnemyBehaviors = true;
                    HapticEngine.vibrate(FPref.UI_VIBRATE_ON_ADVENTURE_REWARD, 100);
                    RewardSprite RS = (RewardSprite) actor;
                    Array<Reward> rewards = RS.getRewards();

                    if (rewards.size == 1) {
                        Reward reward = rewards.get(0);
                        switch (reward.getType()) {
                            case Life:
                            case Shards:
                            case Gold:
                            case Stone:
                            case Wood:
                                String message = Forge.getLocalizer().getMessageorUseDefault("lbl" + reward.getType().name(), reward.getType().name());
                                // Stone/Wood have no font-registered [+Stone]/[+Wood] bracket icon
                                // (same constraint as the Exchange dialog's Lumber/Stone rows and
                                // the combat gold-variance status popup - never registered, risked
                                // a null-FileHandle crash on other planes) - pass no icon rather
                                // than show a broken glyph; Life/Shards/Gold keep theirs.
                                String icon = (reward.getType() == Reward.Type.Stone || reward.getType() == Reward.Type.Wood) ? null : reward.getType().name();
                                AdventurePlayer.current().addStatusMessage(icon, message, reward.getCount(), actor.getX(), actor.getY() + player.getHeight());
                                AdventurePlayer.current().addReward(reward);
                                break;
                            default:
                                showRewardScene(rewards);
                                break;
                        }
                    } else {
                        showRewardScene(rewards);
                    }
                    RS.remove();
                    actors.removeValue(RS, true);
                    changes.deleteObject(RS.getId());
                    break;
                }
            }
        }
    }

    private void showRewardScene(Array<Reward> rewards) {
        startPause(0.1f, () -> {
            RewardScene.instance().loadRewards(rewards, RewardScene.Type.Loot, null);
            Forge.switchScene(RewardScene.instance());
        });
    }

    boolean started = false;
    
    public void beginDuel(EnemySprite mob) {
        if (mob == null) return;
        mob.clearCollisionHeight();
        currentMob = mob;
        player.setAnimation(CharacterSprite.AnimationTypes.Attack);
        player.playEffect(Paths.EFFECT_SPARKS, 0.5f);
        mob.setAnimation(CharacterSprite.AnimationTypes.Attack);
        SoundSystem.instance.play(SoundEffectType.Block, false);
        HapticEngine.vibrate(FPref.UI_VIBRATE_ON_ENEMY_ENCOUNTER, mob.getData().boss ? 400 : 200);
        Forge.advFreezePlayerControls = true;
        player.clearCollisionHeight();
        float attackDuration = Math.max(
                player.getActionAnimationDuration(CharacterSprite.AnimationTypes.Attack, 0.8f),
                mob.getActionAnimationDuration(CharacterSprite.AnimationTypes.Attack, 0.8f));
        startPause(attackDuration, () -> {
            if (started)
                return;
            started = true;
            Forge.setCursor(null, Forge.magnifyToggle ? "1" : "2");
            SoundSystem.instance.play(SoundEffectType.ManaBurn, false);
            DuelScene duelScene = DuelScene.instance();
            FThreads.invokeInEdtNowOrLater(() -> {
                if (!isLoadingMatch) {
                    isLoadingMatch = true;
                    Forge.setTransitionScreen(new TransitionScreen(() -> {
                        started = false;
                        duelScene.initDuels(player, mob);
                        if (isInMap && effect != null && !mob.ignoreDungeonEffect)
                            duelScene.setDungeonEffect(effect);
                        Forge.switchScene(duelScene);
                    }, ScreenUtil.getInstance().takeScreenshot(), true, false, false, false, "", Current.player().avatar(), mob.getAtlasPath(), Current.player().getName(), mob.getTieredDisplayName())
                            .withEnemyStatKey(mob.getName()));
                }
            });
        });
    }

    public void setPointOfInterest(PointOfInterestChanges change) {
        changes = change;
    }

    public boolean isInMap() {
        return isInMap;
    }

    public void onBeginLeavingDungeon() {
        isPlayerLeavingDungeon = true;
    }

    @Override
    public void showDialog() {
        // Used to fully duplicate GameStage.showDialog()'s body instead of calling it - which
        // meant the "halt in-flight movement" fix added there (2026-08-08, "Player kept walking
        // behind dialogs") silently never ran for ANY shop/building/quest interaction, since
        // those all go through MapStage's override, not the base class directly (user report
        // 2026-08-09: fix "did not take" - this override is why). Delegating to super() means any
        // future GameStage.showDialog() fix automatically reaches MapStage too.
        super.showDialog();
        freezeAllEnemyBehaviors = true;
    }



    public void resetPosition() {
        if (positions.peek() != null){
            player.setPosition(positions.peek());
        }
        stop();
    }

    public void setQuestFlag(String key, int value) {
        changes.getMapFlags().put(key, (byte) value);

        AdventureQuestController.instance().updateQuestsMapFlag(key,value);
        AdventureQuestController.instance().showQuestDialogs(this);
        // Color Defeat's real trigger hook does NOT live here (adversarial review 2026-08-14
        // caught this - a real, blocking bug): this method backs the JSON dialog-action key
        // "setMapFlag", but each castle's boss-defeat dialog action uses the DIFFERENTLY-NAMED
        // "setQuestFlag" key (confusingly similar name, completely different code path -
        // MapDialog.java routes "setQuestFlag" to Current.player().setQuestFlag(), i.e.
        // AdventurePlayer.setQuestFlag(), never through here). See that method for the real hook.
    }

    public void advanceQuestFlag(String key) {
        changes.getMapFlags().merge(key, (byte)1, (a, b) -> (byte)(a + b));

        AdventureQuestController.instance().updateQuestsMapFlag(key,changes.getMapFlags().get(key));
        AdventureQuestController.instance().showQuestDialogs(this);
    }

    public boolean checkQuestFlag(String key) {
        return changes.getMapFlags().get(key) != null;
    }

    public int getQuestFlag(String key) {
        return (int) changes.getMapFlags().getOrDefault(key, (byte) 0);
    }

    public void resetQuestFlags() {
        changes.getMapFlags().clear();
    }

    public boolean dialogInput(int keycode) {
        if (dialogOnlyInput) {
            if (KeyBinding.Up.isPressed(keycode)) {
                selectPreviousDialogButton();
            }
            if (KeyBinding.Down.isPressed(keycode)) {
                selectNextDialogButton();
            }
            if (KeyBinding.Use.isPressed(keycode)) {
                performTouch(dialogStage.getKeyboardFocus());
            }
        }
        return true;
    }

    public void performTouch(Actor actor) {
        if (actor == null)
            return;
        actor.fire(eventTouchDown);
        Timer.schedule(new Timer.Task() {
            @Override
            public void run() {
                actor.fire(eventTouchUp);
            }
        }, 0.10f);
    }

    private void selectNextDialogButton() {
        if (dialogButtonMap.size < 2)
            return;
        if (!(dialogStage.getKeyboardFocus() instanceof Button)) {
            dialogStage.setKeyboardFocus(dialogButtonMap.first());
            return;
        }
        for (int i = 0; i < dialogButtonMap.size; i++) {
            if (dialogStage.getKeyboardFocus() == dialogButtonMap.get(i)) {
                i += 1;
                i %= dialogButtonMap.size;
                dialogStage.setKeyboardFocus(dialogButtonMap.get(i));
                return;
            }
        }
    }

    private void selectPreviousDialogButton() {
        if (dialogButtonMap.size < 2)
            return;
        if (!(dialogStage.getKeyboardFocus() instanceof Button)) {
            dialogStage.setKeyboardFocus(dialogButtonMap.first());
            return;
        }
        for (int i = 0; i < dialogButtonMap.size; i++) {
            if (dialogStage.getKeyboardFocus() == dialogButtonMap.get(i)) {
                i -= 1;
                if (i < 0)
                    i = dialogButtonMap.size - 1;
                dialogStage.setKeyboardFocus(dialogButtonMap.get(i));
                return;
            }
        }
    }

    public void clearOnExit() {
        mustClearOnExit = true;
    }
}
