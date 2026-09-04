package forge.adventure.data;

import com.badlogic.gdx.utils.ObjectMap;

/**
 * Data class that will be used to read Json configuration files
 * BiomeData
 * contains general information about the game
 */
public class ConfigData {
    public int screenWidth;
    public int screenHeight;
    public String skin;
    public String font;
    public String fontColor;
    public int minDeckSize;
    public int maxNumberOfDecks;
    public float playerBaseSpeed;
    public String[] colorIds;
    public String[] colorIdNames;
    public String[] starterEditions;
    public String[] starterEditionNames;
    public ObjectMap<String, ObjectMap<String, String>> starterDecksByEdition;
    public DifficultyData[] difficulties;
    public RewardData legalCards;
    public String[] restrictedCards;
    public String[] restrictedEditions;
    public String[] restrictedBlocks;
    public String[] restrictedTokens;
    public String[] allowedEditions;
    public boolean vintageOnlyEditions = false;
    public String[] restrictedEvents;
    public String[] allowedEvents;
    public String[] allowedJumpstart;
    public String defaultBasicLandSet = "JMP";
    public boolean enableGeneticAI = true;
    public String chaosDeckFormat;
    public boolean usePriceListPrices = true;
    public boolean fogOfWarEnabled = false;
    // MOD_SCOPE #87 (2026-09-03): at War, entering an AI town offers Attack (a random roamer from
    // that color's pool, starting with its basic land tapped) instead of only being barred.
    public boolean warTownAssaultEnabled = false;
    // Round 101 (user spec 2026-09-03): the player starts with NOTHING; the five Ring Cities hand over the
    // difficulty's starting gold / shards / wood / stone / items during the tutorial (grantRingGift action).
    public boolean ringGiftStart = false;
    public boolean dayNightCycleEnabled = false;
    public boolean townReconstructionEnabled = false;
    public boolean territoryControlEnabled = false;
    public boolean colorReputationEnabled = false;
    public boolean resourceSpawnsEnabled = false;
    public boolean dungeonRotationEnabled = false;
    public boolean sideQuestTimerEnabled = false;
    public boolean resourceLootVarietyEnabled = false;
    // Time- and venue-gated Armory item rarity (user spec 2026-08-31) - see the plane's
    // "config tables/armory_rarity.json". Plane-opt-in like every other mod feature: stock planes
    // keep the flat Common 60 / Uncommon 30 / Rare 8 / Mythic 2 roll.
    public boolean armoryRarityGatingEnabled = false;
    // Roaming-spawn duplicate limiting (user report 2026-09-01: "I found 3 instances where there
    // were multiples of the exact same enemy. There was 3 Khenra Warriors close to each other").
    // Plane-opt-in: stock planes keep the unconstrained weighted pick, which really can hand out
    // the same enemy several rolls in a row. See WorldStage.pickNonClusteringEnemy().
    public boolean spawnDuplicateLimitEnabled = false;
    public boolean editionProgressionEnabled = false;
    // 2026-08-12 review: these three shipped without flags and leaked into stock planes
    // (Shandalar's Equipment/*Items shops matched isArmoryShop, common-town multi-name shop
    // lists exposed the type re-roll, and the common capitals' arena objects exposed the
    // upgrade economy). Same opt-in rule as every flag above: false here, true only in
    // "The Forsaken Realms"/config.json.
    public boolean armoryGuardsEnabled = false;
    public boolean shopTypeRerollEnabled = false;
    public boolean arenaUpgradesEnabled = false;
    // User-editable CSV content tables ("config tables/" in the plane folder) that can exclude
    // specific expansions/items/enemies from the game - see ContentFilterTables.java.
    public boolean contentFilterTablesEnabled = false;
    // Show each enemy's difficulty tier appended to its displayed name, e.g. "Red Wizard (Adept)"
    // (user spec 2026-08-13) - display-only, see EnemyData.getTieredDisplayName(). Same opt-in
    // rule as every flag above: false here, true only in "The Forsaken Realms"/config.json.
    public boolean showEnemyTierInName = false;
    // Per-race starting expansions (user spec 2026-08-12) - see RaceEditionData. When a race has
    // an entry here, it replaces the flat starterEditions first-N seeding; races without an
    // entry (and planes without this array) fall back to starterEditions.
    public RaceEditionData[] raceEditions;
    // Shop-type blueprints (user spec 2026-08-30): card shop TYPES must be unlocked before the
    // rebuild/re-assign chooser will offer them. Opt-in like every flag above - a plane leaving
    // this false behaves exactly as before, with every type freely available.
    public boolean shopBlueprintsEnabled = false;
    // Per-race starting shop types - see RaceShopData. Combined with the color trio derived from
    // the new game's chosen color, this forms the player's starting unlock set.
    public RaceShopData[] raceShops;
    // Which color-numbered shops the starting-color grant hands out (user decision 2026-08-30:
    // pick Black -> start with Black1/3/5, the COMMON tier trio; the even/uncommon trio and the
    // plain <Color> rare shop then become blueprint targets - a clean weak-to-strong ladder).
    // Data-driven so the ladder can be retuned without a rebuild. Each suffix is appended to the
    // capitalised color name.
    public String[] startingColorShopSuffixes = {"1", "3", "5"};
    // Blueprint purchase price by the shop's own tier, in SHARDS (user spec 2026-08-30).
    public int blueprintShardCostCommon = 20;
    public int blueprintShardCostUncommon = 40;
    public int blueprintShardCostRare = 100;
    // Standalone-game identity (MOD_SCOPE.md #89): the plane's own version string, appended to
    // the engine version on the start menu when set; and a one-time welcome popup shown on the
    // first map entry of a save (a new game starts inside the spawn dungeon, so new players see
    // it there). Both null/absent on stock planes - no behavior change for them.
    public String modVersion;
    public String welcomePopupText;
    // Optional URL rendered as a real "Join us on Discord" button on the welcome popup
    // (2026-08-26 user request: "Can the Discord link be an actual hyper link?") - opens the
    // system browser via Gdx.net.openURI, which also works on Android for the planned mobile
    // release. Null/absent on stock planes - no button.
    public String welcomePopupLink;
    // Start-menu version label overhaul (2026-08-22, user spec): the label now shows the last
    // upstream Forge snapshot merged (engineBuildVersion, e.g. "2.0.15-SNAPSHOT-08.19" - stays
    // static across TFR-only rounds, only bumped when a new engine merge happens) alongside
    // modVersion and modVersionDate (the TFR build's own version/date, bumped every round). Falls
    // back to Forge.getDeviceAdapter().getVersionString() if unset - see StartScene.java.
    public String engineBuildVersion;
    public String modVersionDate;
    // Weighted overworld-spawn tier system (user spec 2026-08-23): week-based progression toward
    // higher tiers, a territory/reputation modifier, and per-enemy kill-decay - see
    // SpawnTierWeighting.java. Same opt-in rule as every flag above: false here, true only in
    // "The Forsaken Realms"/config.json.
    public boolean weightedSpawnTiersEnabled = false;
    // Functioning Neutral Towns (user spec 2026-08-24): at world-gen, a handful of the neutral
    // ("Waste Town") POIs are seeded as already-functioning instead of ruined - real shops, no
    // rubble, gated to EditionProgression's existing NEUTRAL shard (not the player's unlocked
    // editions, unlike a player-paid town restoration). See TownRestoration.NEUTRAL_SEEDED_FLAG
    // and TownRestoration.seedFunctioningNeutralTowns(). Same opt-in rule as every flag above: false here,
    // true only in "The Forsaken Realms"/config.json.
    public boolean functioningNeutralTownsEnabled = false;

}
