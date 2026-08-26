package forge.adventure.util;

/**
 * Defines for the hard coded paths
 */
public class Paths {
    public static final String ENEMIES = "world/enemies.json";
    public static final String SHOPS = "world/shops.json";
    public static final String WORLD = "world/world.json";
    public static final String HEROES = "world/heroes.json";
    public static final String POINTS_OF_INTEREST = "world/points_of_interest.json";
    public static final String ITEMS = "world/items.json";
    public static final String QUESTS = "world/quests.json";
    public static final String SKIN = "skin/ui_skin.json";
    public static final String ITEMS_EQUIP = "skin/equip.png";
    public static final String ITEMS_UNUSABLE = "skin/unusable.png";
    public static final String ITEMS_ATLAS = "sprites/items.atlas";
    public static final String GOLD_ATLAS = "sprites/gold.atlas";
    // Resource-pickup sparkle atlases (2026-08-13, user-provided art, replaces the alpha-twinkle
    // fallback for these 4 - see WorldStage.getSparkleAnimation()). GOLD_ATLAS above now also
    // resolves to this same new resource_drop.png sheet via the plane's own sprites/gold.atlas
    // (plane-first file resolution), replacing the stock treasure.png-based one it used before.
    public static final String WOOD_ATLAS = "sprites/wood.atlas";
    public static final String STONE_ATLAS = "sprites/stone.atlas";
    public static final String SHARDS_ATLAS = "sprites/shard.atlas";
    public static final String MYSTERY_ATLAS = "sprites/random.atlas";
    // Chest loot spawn (2026-08-25 user spec): top row of the stock treasure.png, 4 "Idle" frames,
    // same twinkle-animation shape as the other resource-drop atlases above.
    public static final String CHEST_ATLAS = "sprites/chest.atlas";
    public static final String PIXELMANA_ATLAS = "sprites/pixelmana.atlas";
    public static final String KEYS_ATLAS = "skin/keys.atlas";
    public static final String COLOR_FRAME_ATLAS = "ui/color_frames.atlas";
    public static final String ARENA_ATLAS = "ui/arena.atlas";
    public static final String MAP_MARKER = "sprites/map_marker.atlas";
    
    
    public static final String EFFECT_HEAL = "particle_effects/heal.p";
    public static final String EFFECT_KILL = "particle_effects/killed.p";
    public static final String TRIGGER_KILL = "particle_effects/kill.p";
    public static final String EFFECT_HIDE = "particle_effects/hide.p";
    public static final String EFFECT_SPRINT = "particle_effects/sprint.p";
    public static final String EFFECT_FLY = "particle_effects/fly.p";
    public static final String EFFECT_TELEPORT = "particle_effects/teleport.p";
    public static final String EFFECT_BLOOD = "particle_effects/blood.p";
    public static final String EFFECT_SPARKS = "particle_effects/sparks.p";
    public static final String CARD_PRICES = "world/cardprices.txt";
    public static final String CUSTOM_CARDS = "custom_cards";
    public static final String CUSTOM_CARDS_PICS = "custom_card_pics";
}
