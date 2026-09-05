package forge.adventure.util;

import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.WorldData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.scene.RewardScene;
import forge.adventure.stage.GameHUD;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.sound.SoundEffectType;
import forge.sound.SoundSystem;

import java.util.Iterator;

/**
 * Random resource spawns (user request 2026-08-08): up to maxSpawns() walk-over pickups scattered
 * across the OVERWORLD only (they exist solely as WorldStage actors, so towns/dungeons - separate
 * MapStage scenes - can never contain one by construction). The world starts seeded with a full
 * pool; each spawn carries its own lifetime (2-10 in-game days) and is replaced by a fresh random
 * one when it expires. Pickups award directly: Gold 5-100, Shards/Wood/Stone 2-10.
 * <p>
 * Opt-in per-plane via config.json ("resourceSpawnsEnabled": true), defaulting to off like every
 * other mod feature - inert on Shandalar and any other stock plane.
 * <p>
 * State (the spawn list + the seeded flag) is persisted on World; this class is the logic plus a
 * per-frame tick driven by WorldStage.onActing(). The tick is cheap when nothing changed: one
 * enabled check, one day comparison, and a <=maxSpawns() pickup distance scan while the player is
 * actually moving.
 */
public class ResourceSpawns {
    // Was a flat constant (20) until 2026-08-17 (user request: "let's make that 30") - moved into
    // TuningData like every other world-balance number in this mod, so it's re-tunable without a
    // code change. maxSpawns() is the read; nothing else in the codebase referenced the old
    // constant except doc comments (World.java, WorldStage.java - narrated in prose only).
    public static int maxSpawns() {
        return Config.instance().getTuningData().maxResourceSpawns;
    }
    private static final int MIN_LIFETIME_DAYS = 2;
    private static final int MAX_LIFETIME_DAYS = 10;
    private static final int GOLD_MIN = 5, GOLD_MAX = 100;
    private static final int OTHER_MIN = 2, OTHER_MAX = 10;
    private static final int POI_CLEARANCE_TILES = 3; // don't spawn on/right next to a town/dungeon icon
    private static final int PLACEMENT_ATTEMPTS = 200; // per spawn - plenty for a mostly-open map
    private static final int NEAR_START_RADIUS_TILES = 12; // the guaranteed new-game spawn lands within this of the start
    // Pickup catch radius, in tiles from the spawn's center (2026-08-13, user report: "I feel
    // like I run over it a few times before it picks up"). 0.75 tiles - a little more forgiving
    // than the old effective ~0.5-tile-radius exact-tile check, without reaching into neighboring
    // tiles' worth of extra range.
    private static final float PICKUP_RADIUS_TILES = 0.75f;

    // Spawn entry layout: {tileX, tileY, type, value, expiryDay} in world tile space.
    // TYPE_MYSTERY (the diamond icon, user request 2026-08-08): contents decided at PICKUP, not
    // spawn - 5% an ambush by the mage of whichever color the player's reputation is worst with,
    // 95% an even split across the four ordinary resources.
    // TYPE_CHEST (2026-08-25 user spec): a 6th top-level type, alongside the original 5 - contents
    // ALSO decided at pickup, a uniform 1-of-6 pick among 6 new "loot event" outcomes (see
    // WorldStage.triggerChestEvent()), same "resolve at pickup, not spawn" idiom Mystery already
    // established. Deliberately its own type rather than folded into Mystery's own resolution -
    // the user asked for it as a visibly distinct chest icon on the map, not a Mystery sub-case.
    public static final int TYPE_GOLD = 0, TYPE_SHARDS = 1, TYPE_WOOD = 2, TYPE_STONE = 3, TYPE_MYSTERY = 4, TYPE_CHEST = 5;
    private static final float MYSTERY_AMBUSH_CHANCE = 0.05f;
    // Shop blueprint from a Mystery pickup (user spec 2026-08-30). Deliberately generous at 25%:
    // the AI-shop Buy Blueprint button is the real acquisition route, so a drop is only ever
    // filling in a type the player has not happened to walk past. Self-disabling once every type
    // is known - see grantRandomBlueprint().
    private static final float MYSTERY_BLUEPRINT_CHANCE = 0.25f;

    /**
     * Grants one random shop type the player does not already know. Returns false - so the caller
     * falls through to its normal reward - when blueprints are off for this plane, or when every
     * type in the game is already known. Shared by the Mystery pickup here and ChestEvents.
     * <p>
     * Draws from the union of every town shop-list pool rather than the whole shops.json catalog,
     * so it can never hand out an Armory/land/test shop that no chooser would ever offer.
     */
    public static boolean grantRandomBlueprint(String source) {
        if (!Config.instance().getConfigData().shopBlueprintsEnabled)
            return false;
        java.util.List<String> candidates = new java.util.ArrayList<>();
        for (String name : EconomyBuildings.allChooserShopNames())
            // isShopTypeUnlocked(), NOT the raw hasShopTypeUnlocked() set lookup (2026-09-01
            // release review, save-integrity). An EMPTY unlockedShopTypes means "legacy save -
            // everything is already available"; the raw lookup reads it as "nothing is unlocked",
            // so on a pre-blueprint save EVERY name looked like a valid drop candidate. Granting
            // one then made the set non-empty, which flipped isShopTypeUnlocked() out of its
            // legacy branch and left that single type as the player's ENTIRE unlocked list -
            // permanently, and for every v1.03 player who upgrades. Routing through the same
            // predicate the chooser uses means a legacy save yields no candidates at all and
            // falls through to an ordinary reward, exactly as it should.
            if (!EconomyBuildings.isShopTypeUnlocked(name))
                candidates.add(name);
        if (candidates.isEmpty()) {
            System.out.println("[TFR-Blueprint] " + source + ": every shop type already known - "
                    + "falling through to an ordinary reward");
            return false;
        }
        java.util.Collections.sort(candidates); // deterministic order before the seeded pick
        String picked = candidates.get(WorldSave.getCurrentSave().getWorld().getRandom().nextInt(candidates.size()));
        // Unlock FIRST, reveal second (2026-08-31). The reveal is a RewardScene card the player
        // turns over, and RewardScene's own grant happens on that click - granting here as well
        // means closing the screen without clicking still keeps the blueprint. AdventurePlayer's
        // Blueprint case is idempotent, so the click just re-learns something already known.
        AdventurePlayer.current().unlockShopType(picked, source);
        GameHUD.getInstance().addNotification("Blueprint found: "
                + EconomyBuildings.shopDisplayName(picked) + "! You can now build this shop type.");
        SoundSystem.instance.play(SoundEffectType.FlipCard, false);
        Array<Reward> reveal = new Array<>();
        reveal.add(Reward.blueprint(picked));
        // loadRewards() only fills the scene - it has to be switched to as well, same two-step
        // ChestEvents.triggerLostCard() uses for its card reveal.
        RewardScene.instance().loadRewards(reveal, RewardScene.Type.Loot, null);
        Forge.switchScene(RewardScene.instance());
        return true;
    }

    private static final String ITEMS_ATLAS = "sprites/items.atlas";
    private static final String RESOURCE_ICONS_ATLAS = "maps/tileset/resource_icons.atlas";
    private static final String CHEST_ATLAS = "sprites/chest.atlas";

    private static int lastProcessedDay = Integer.MIN_VALUE;
    private static boolean needsResync = true;

    private ResourceSpawns() {}

    private static boolean isEnabled() {
        ConfigData configData = Config.instance().getConfigData();
        return configData != null && configData.resourceSpawnsEnabled;
    }

    /**
     * Forces the next tick to rebuild the WorldStage actors from World's spawn list - called
     * whenever that list may have been replaced wholesale under the actors' feet (save load, new
     * world generation, WorldStage cache clear).
     */
    public static void forceResync() {
        needsResync = true;
        lastProcessedDay = Integer.MIN_VALUE;
    }

    /** Called every frame from WorldStage.onActing() while the clock is running. */
    public static void tick(World world, int currentDay) {
        if (!isEnabled())
            return;
        boolean changed = false;
        if (!world.isResourceSpawnsSeeded()) {
            // One guaranteed spawn near the player's start (user request 2026-08-08: with only 20
            // on the whole map, a fresh game had no findable example to verify the mechanic by).
            // Seeding runs on the first tick, so the player is still at/near the start position.
            changed |= spawnOneNearPlayer(world, currentDay, NEAR_START_RADIUS_TILES);
            for (int i = world.getResourceSpawns().size(); i < maxSpawns(); i++)
                changed |= spawnOne(world, currentDay);
            world.setResourceSpawnsSeeded(true);
            System.out.println("[ResourceSpawns] seeded " + world.getResourceSpawns().size() + " initial resource spawn(s)");
        }
        if (currentDay != lastProcessedDay) {
            lastProcessedDay = currentDay;
            changed |= processExpiry(world, currentDay);
        }
        changed |= checkPickup(world);
        if (changed || needsResync) {
            needsResync = false;
            WorldStage.getInstance().refreshResourceSpawnActors();
        }
    }

    // Expired spawns vanish and the pool tops back up to maxSpawns() with fresh random ones -
    // "each will have its own timer and disappear after 2-10 days and a new random resource will
    // appear." Pickups (removed elsewhere) are also replenished here, on the day tick.
    private static boolean processExpiry(World world, int currentDay) {
        boolean changed = false;
        int expiredCount = 0;
        Iterator<int[]> it = world.getResourceSpawns().iterator();
        while (it.hasNext()) {
            if (it.next()[4] <= currentDay) {
                it.remove();
                changed = true;
                expiredCount++;
            }
        }
        int refilled = 0;
        for (int i = world.getResourceSpawns().size(); i < maxSpawns(); i++) {
            if (spawnOne(world, currentDay))
                refilled++;
            changed = true;
        }
        // Diagnostic (2026-08-25, user report: "seeded 50 initial resource spawn(s)" logged once,
        // then nothing for the rest of a multi-week playtest - genuinely ambiguous from that log
        // alone whether the system silently died or was just working quietly, since a successful
        // spawn/refill never logged anything before this - only placement FAILURES did. This makes
        // the pool's actual size directly visible every day, so the next playtest settles it either
        // way instead of staying a mystery. Once a week (not every day) to avoid log spam.
        if (currentDay % 7 == 0) {
            System.out.println("[TFR-ResourceSpawns] day " + currentDay + ": pool=" + world.getResourceSpawns().size()
                    + "/" + maxSpawns() + " (expired " + expiredCount + ", refilled " + refilled + " today)");
        }
        return changed;
    }

    private static boolean spawnOne(World world, int currentDay) {
        return spawnInArea(world, currentDay, -1, -1, -1);
    }

    // Places one spawn within radiusTiles of the player's current tile (used for the guaranteed
    // new-game spawn and the "spawn resource" console command). Falls back to anywhere-on-map
    // placement if the neighborhood is too crowded to fit one.
    private static boolean spawnOneNearPlayer(World world, int currentDay, int radiusTiles) {
        WorldStage stage = WorldStage.getInstance();
        if (stage.getPlayerSprite() == null)
            return spawnOne(world, currentDay);
        int centerX = (int) (stage.getPlayerSprite().getX() / world.getTileSize());
        int centerY = (int) (stage.getPlayerSprite().getY() / world.getTileSize());
        if (spawnInArea(world, currentDay, centerX, centerY, radiusTiles))
            return true;
        System.out.println("[ResourceSpawns] no free tile within " + radiusTiles + " tiles of the player, placing anywhere");
        return spawnOne(world, currentDay);
    }

    // centerX < 0 means anywhere on the map; otherwise placement is restricted to the square of
    // +-radiusTiles around (centerX, centerY).
    private static boolean spawnInArea(World world, int currentDay, int centerX, int centerY, int radiusTiles) {
        int width = world.getWidthInTiles();
        int height = world.getHeightInTiles();
        for (int attempt = 0; attempt < PLACEMENT_ATTEMPTS; attempt++) {
            int wx, wy;
            if (centerX < 0) {
                wx = 1 + world.getRandom().nextInt(Math.max(1, width - 2));
                wy = 1 + world.getRandom().nextInt(Math.max(1, height - 2));
            } else {
                wx = centerX - radiusTiles + world.getRandom().nextInt(radiusTiles * 2 + 1);
                wy = centerY - radiusTiles + world.getRandom().nextInt(radiusTiles * 2 + 1);
                if (wx < 1 || wy < 1 || wx >= width - 1 || wy >= height - 1)
                    continue;
            }
            if (world.isColliding(wx, wy))
                continue; // water/mountains/structures - must be walkable to be walk-over collectable
            boolean blocked = false;
            for (int[] existing : world.getResourceSpawns()) {
                if (existing[0] == wx && existing[1] == wy) {
                    blocked = true;
                    break;
                }
            }
            if (blocked)
                continue;
            // Keep clear of POI icons - a pickup under a town/dungeon sprite would be invisible
            // and awkward to grab without entering the POI.
            for (PointOfInterest poi : world.getAllPointOfInterest()) {
                int px = (int) (poi.getPosition().x / world.getTileSize());
                int py = (int) (poi.getPosition().y / world.getTileSize());
                if (Math.abs(px - wx) <= POI_CLEARANCE_TILES && Math.abs(py - wy) <= POI_CLEARANCE_TILES) {
                    blocked = true;
                    break;
                }
            }
            if (blocked)
                continue;
            int type = world.getRandom().nextInt(6);
            // A mystery/chest spawn's value is rolled at pickup (award() decides what it even
            // IS); 0 here just keeps the entry layout uniform.
            int value = (type == TYPE_MYSTERY || type == TYPE_CHEST) ? 0
                    : type == TYPE_GOLD
                    ? GOLD_MIN + world.getRandom().nextInt(GOLD_MAX - GOLD_MIN + 1)
                    : OTHER_MIN + world.getRandom().nextInt(OTHER_MAX - OTHER_MIN + 1);
            int expiry = currentDay + MIN_LIFETIME_DAYS + world.getRandom().nextInt(MAX_LIFETIME_DAYS - MIN_LIFETIME_DAYS + 1);
            world.getResourceSpawns().add(new int[]{wx, wy, type, value, expiry});
            return true;
        }
        if (centerX < 0)
            System.out.println("[ResourceSpawns] no free tile found after " + PLACEMENT_ATTEMPTS + " attempts, skipping one spawn");
        return false;
    }

    /**
     * Console command hook ("spawn resource"): places one spawn right around the player so the
     * mechanic (icon, twinkle, walk-over pickup) can be tested on demand without hunting the map.
     * Allowed to exceed maxSpawns() - the overflow corrects itself at the next day tick.
     */
    public static String debugSpawnNearPlayer() {
        if (!isEnabled())
            return "Resource spawns are disabled on this plane";
        World world = WorldSave.getCurrentSave().getWorld();
        if (!spawnOneNearPlayer(world, world.getCurrentDay(), 4))
            return "No free tile found near the player";
        int[] spawn = world.getResourceSpawns().get(world.getResourceSpawns().size() - 1);
        WorldStage.getInstance().refreshResourceSpawnActors();
        return "Spawned " + spawn[3] + " " + typeName(spawn[2]) + " at tile " + spawn[0] + "," + spawn[1]
                + " (expires day " + spawn[4] + ")";
    }

    private static String typeName(int type) {
        switch (type) {
            case TYPE_GOLD: return "Gold";
            case TYPE_SHARDS: return "Shards";
            case TYPE_WOOD: return "Wood";
            case TYPE_STONE: return "Stone";
            case TYPE_MYSTERY: return "Mystery";
            case TYPE_CHEST: return "Chest";
            default: return "?";
        }
    }

    // Walk-over collection: picked up once the player's center comes within PICKUP_RADIUS_TILES
    // of the spawn tile's center. Was an exact-tile-equality check keyed off the player sprite's
    // raw getX()/getY() (its corner, not its visual center) - meaning the corner, not the
    // character the user actually sees standing on the icon, had to land on that exact tile,
    // which is why it often took a few passes. Switched to a real distance check off the
    // sprite's center, matching how WorldStage already computes it elsewhere (see navArrow
    // positioning), with a little extra tolerance on top per the user's report.
    private static boolean checkPickup(World world) {
        WorldStage stage = WorldStage.getInstance();
        forge.adventure.character.PlayerSprite playerSprite = stage.getPlayerSprite();
        if (playerSprite == null)
            return false;
        int tileSize = world.getTileSize();
        float playerCenterX = playerSprite.getX() + playerSprite.getWidth() / 2f;
        float playerCenterY = playerSprite.getY() + playerSprite.getHeight() / 2f;
        float pickupRadiusPx = PICKUP_RADIUS_TILES * tileSize;
        Iterator<int[]> it = world.getResourceSpawns().iterator();
        boolean changed = false;
        while (it.hasNext()) {
            int[] spawn = it.next();
            float spawnCenterX = spawn[0] * tileSize + tileSize / 2f;
            float spawnCenterY = spawn[1] * tileSize + tileSize / 2f;
            float dx = playerCenterX - spawnCenterX;
            float dy = playerCenterY - spawnCenterY;
            if (dx * dx + dy * dy > pickupRadiusPx * pickupRadiusPx)
                continue;
            award(world, spawn[2], spawn[3]);
            it.remove();
            changed = true;
        }
        return changed;
    }

    private static void award(World world, int type, int value) {
        if (type == TYPE_CHEST) {
            // Resolved entirely by ChestEvents (2026-08-25 user spec) - a uniform 1-of-6 pick
            // among 6 new loot events, several of which need a dialog/duel/reward-choice scene
            // that only WorldStage (not this util class) has the infrastructure to show.
            ChestEvents.trigger(world);
            return;
        }
        if (type == TYPE_MYSTERY) {
            if (world.getRandom().nextFloat() < MYSTERY_AMBUSH_CHANCE && spawnAmbush())
                return;
            // Shop blueprint (user spec 2026-08-30). Same short-circuit idiom as the ambush above:
            // grantRandomBlueprint() returns false when the feature is off or every type is
            // already known, and the pickup then falls through to an ordinary resource - so
            // "drop the outcome once they are all known" needs no extra bookkeeping and can never
            // produce a dud pickup.
            if (world.getRandom().nextFloat() < MYSTERY_BLUEPRINT_CHANCE && grantRandomBlueprint("Mystery drop"))
                return;
            // Otherwise it resolves into one of the four ordinary resources, value rolled now.
            type = world.getRandom().nextInt(4);
            value = type == TYPE_GOLD
                    ? GOLD_MIN + world.getRandom().nextInt(GOLD_MAX - GOLD_MIN + 1)
                    : OTHER_MIN + world.getRandom().nextInt(OTHER_MAX - OTHER_MIN + 1);
        }
        String what;
        switch (type) {
            case TYPE_GOLD:
                Current.player().giveGold(value); // plays CoinsDrop itself
                what = "Gold";
                break;
            case TYPE_SHARDS:
                Current.player().addShards(value); // plays TakeShard itself
                what = "Shards";
                break;
            case TYPE_WOOD:
                Current.player().addWood(value); // plays CoinsDrop itself since round 115
                what = "Wood";
                break;
            case TYPE_STONE:
                Current.player().addStone(value); // plays CoinsDrop itself since round 115
                what = "Stone";
                break;
            default:
                return;
        }
        String message = "You receive " + value + " " + what + "!";
        System.out.println("[ResourceSpawns] " + message);
        GameHUD.getInstance().addNotification(message);
    }

    // The mystery pickup's 5% outcome: the mage of whichever color the player's reputation is
    // worst with (ties: first in COLORS order) spawns right on top of the player - the ordinary
    // enemy-collision path takes it from there, so this IS an immediate fight, with the normal
    // flee/battle flow. "Adept <Color> Wizard" deliberately - a regular mage, not a boss (user
    // spec). Returns false (no ambush, caller falls through to a resource) if the enemy data or
    // the player sprite can't be found - never a dud pickup.
    private static boolean spawnAmbush() {
        WorldStage stage = WorldStage.getInstance();
        if (stage.getPlayerSprite() == null)
            return false;
        String worstColor = null;
        int worstScore = Integer.MAX_VALUE;
        for (String color : ColorReputation.COLORS) {
            int score = Current.player().getColorReputationHalfPoints(color);
            if (score < worstScore) {
                worstScore = score;
                worstColor = color;
            }
        }
        if (worstColor == null)
            return false;
        String enemyName = "Adept " + Character.toUpperCase(worstColor.charAt(0)) + worstColor.substring(1) + " Wizard";
        EnemyData enemyData = WorldData.getEnemy(enemyName);
        if (enemyData == null) {
            System.out.println("[ResourceSpawns] ambush enemy \"" + enemyName + "\" not found, awarding a resource instead");
            return false;
        }
        EnemySprite ambusher = new EnemySprite(enemyData);
        stage.spawnAt(ambusher, new Vector2(stage.getPlayerSprite().getX(), stage.getPlayerSprite().getY()));
        SoundSystem.instance.play(SoundEffectType.Damage, false);
        String message = "Ambush! A " + enemyName + " was lurking under the treasure!";
        System.out.println("[ResourceSpawns] " + message);
        // Plain text, no "[*]" bold markup - unclosed bold renders as smeared double-struck
        // glyphs at this pixel-font size (same bug as ChestEvents' Dangerous Enemy notification,
        // already fixed once before for "Orazca rises..."/"Camelot rises...").
        GameHUD.getInstance().addNotification(message);
        return true;
    }

    /** The overworld sprite for a spawn type - used by WorldStage's actor sync. */
    public static Sprite spriteFor(int type) {
        switch (type) {
            case TYPE_GOLD:
                // The gold-pile icon from the same buildings.png resource row Lumber/Stone came
                // from (336,272) - the diamond "Treasure" icon it used before now marks MYSTERY.
                return Config.instance().getAtlasSprite(RESOURCE_ICONS_ATLAS, "GoldPile");
            case TYPE_SHARDS:
                return Config.instance().getAtlasSprite(ITEMS_ATLAS, "Shards");
            case TYPE_WOOD:
                return Config.instance().getAtlasSprite(RESOURCE_ICONS_ATLAS, "Lumber");
            case TYPE_STONE:
                return Config.instance().getAtlasSprite(RESOURCE_ICONS_ATLAS, "Stone");
            case TYPE_MYSTERY:
                return Config.instance().getAtlasSprite(ITEMS_ATLAS, "Treasure");
            case TYPE_CHEST:
                return Config.instance().getAtlasSprite(CHEST_ATLAS, "Idle");
            default:
                return null;
        }
    }
}
