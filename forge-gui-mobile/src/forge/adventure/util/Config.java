package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter;
import com.badlogic.gdx.utils.ObjectMap;
import forge.CardStorageReader;
import forge.Forge;
import forge.ImageKeys;
import forge.adventure.data.*;
import forge.card.*;
import forge.deck.Deck;
import forge.deck.DeckProxy;
import forge.deck.DeckgenUtil;
import forge.game.GameType;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgeProfileProperties;
import forge.model.FModel;
import forge.util.Aggregates;
import forge.util.FileUtil;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Main resource class to access files from the selected adventure
 */
public class Config {
    private static Config currentConfig;
    private final String commonDirectoryName = "common";
    private final String prefix;
    private final String commonPrefix;
    private final HashMap<String, FileHandle> Cache = new HashMap<>();
    private ConfigData configData;
    private TuningData tuningData;
    private SpawnTierWeightData spawnTierWeightData;
    private forge.adventure.data.ArmoryRarityData armoryRarityData;
    private forge.adventure.data.WarChampionData warChampionData;
    private forge.adventure.data.FrontierSpawnData frontierSpawnData;
    private forge.adventure.data.RoamingGuardConfig roamingGuardConfig;
    /** Round 140 (S2-6): set when a plane data file that EXISTS failed to parse, so the menu can
     *  say so instead of the game running with every feature silently defaulted off. */
    private String fatalDataError = null;
    private final String[] adventures;
    private SettingData settingsData;
    private String Lang = "en-us";
    private final String plane;
    private ObjectMap<String, ObjectMap<String, Sprite>> atlasSprites = new ObjectMap<>();
    private ObjectMap<PointOfInterestData, Array<Sprite>> poiSprites = new ObjectMap<>();
    private ObjectMap<String, ObjectMap<String, Array<Sprite>>> animatedSprites = new ObjectMap<>();

    private final FolderDeckCatalog preconDeckCatalog = new FolderDeckCatalog("decks/starter/precon/");
    private final FolderDeckCatalog commanderPreconDeckCatalog = new FolderDeckCatalog("decks/starter/commanderprecon/");

    static public Config instance() {
        if (currentConfig == null)
            currentConfig = new Config();
        return currentConfig;
    }

    private Config() {
        String path = resPath();
        FilenameFilter planesFilter = (file, s) -> !s.contains(".") && !s.equals(commonDirectoryName);

        adventures = new File(GuiBase.isMobile() ? ForgeConstants.ADVENTURE_DIR : path + "/res/adventure").list(planesFilter);
        try {
            settingsData = new Json().fromJson(SettingData.class, new FileHandle(ForgeConstants.USER_ADVENTURE_DIR + "settings.json"));
        } catch (Exception e) {
            settingsData = new SettingData();
        }
        if (settingsData.plane == null || settingsData.plane.isEmpty()) {
            if (adventures != null && adventures.length >= 1) {
                // TFR (2026-08-27, Android round): this game's own plane is the default. The
                // stock code preferred Shandalar, which the Android assets.zip doesn't even
                // ship - and wherever both exist, a fresh player must land in The Forsaken
                // Realms, not stock Shandalar.
                for (String plane : adventures) {
                    if (plane.equalsIgnoreCase("The Forsaken Realms"))
                        settingsData.plane = plane;
                }
                //init Shandalar as fallback default plane if found...
                if (settingsData.plane == null || settingsData.plane.isEmpty()) {
                    for (String plane : adventures) {
                        if (plane.equalsIgnoreCase("Shandalar"))
                            settingsData.plane = plane;
                    }
                }
                //if can't find either, just get any random plane available
                if (settingsData.plane == null || settingsData.plane.isEmpty())
                    settingsData.plane = Aggregates.random(adventures);
            }
        }
        plane = settingsData.plane;

        if (settingsData.width == 0 || settingsData.height == 0) {
            settingsData.width = 1280;
            settingsData.height = 720;
        }
        if (settingsData.videomode == null || settingsData.videomode.isEmpty())
            settingsData.videomode = "720p";
        //reward card display fine tune
        if (settingsData.rewardCardAdj == null || settingsData.rewardCardAdj == 0f)
            settingsData.rewardCardAdj = 1f;
        //tooltip fine tune
        if (settingsData.cardTooltipAdj == null || settingsData.cardTooltipAdj == 0f)
            settingsData.cardTooltipAdj = 1f;
        //reward card display fine tune landscape
        if (settingsData.rewardCardAdjLandscape == null || settingsData.rewardCardAdjLandscape == 0f)
            settingsData.rewardCardAdjLandscape = 1f;
        //tooltip fine tune landscape
        if (settingsData.cardTooltipAdjLandscape == null || settingsData.cardTooltipAdjLandscape == 0f)
            settingsData.cardTooltipAdjLandscape = 1f;

        //prefix = "forge-gui/res/adventure/Shandalar/";
        prefix = getPlanePath(settingsData.plane);
        commonPrefix = resPath() + "/res/adventure/" + commonDirectoryName + "/";

        currentConfig = this;
        if (FModel.getPreferences() != null)
            Lang = FModel.getPreferences().getPref(ForgePreferences.FPref.UI_LANGUAGE);
        FileHandle file = new FileHandle(prefix + "config.json");
        //TODO: Plane's config file should be merged with the common config file.
        if(!file.exists())
            file = new FileHandle(commonPrefix + "config.json");
        try {
            configData = new Json().fromJson(ConfigData.class, file);
        } catch (Exception e) {
            // Round 140 (code review S2-6): falling back to a default ConfigData turns EVERY mod
            // feature off at once - territory control, reputation, fog of war, the economy
            // buildings, the arenas - and the only sign of it was a stack trace in a log the
            // player never opens. A plane that shipped a config.json and cannot parse it is
            // broken, not "running with defaults", so it is recorded here and surfaced by the
            // menu (see SaveLoadScene) instead of being absorbed.
            fatalDataError = "config.json (" + file.path() + ") could not be parsed: "
                    + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage());
            System.err.println("========================================================================");
            System.err.println("[TFR-Config] FATAL: " + fatalDataError);
            System.err.println("[TFR-Config] Every plane feature flag is now at its default (off).");
            System.err.println("========================================================================");
            e.printStackTrace();
            configData = new ConfigData();
        }

        // Tuning file (2026-08-14 user request): same plane-local/fallback-to-common load pattern
        // as config.json above, but for numeric game-balance tunables rather than boolean feature
        // flags. Stock planes (Shandalar etc.) have no settings.json at all, so `file.exists()` is
        // false for both the plane-local AND common paths - the try/catch below then falls back
        // to a plain `new TuningData()`, i.e. TuningData's own hardcoded defaults, silently. No
        // stack trace printed for that expected case (only a genuinely malformed settings.json
        // should print one) - checked via file.exists() first, same as configData intentionally
        // does NOT do (a missing config.json IS unexpected there, since every plane has one).
        // Relocated 2026-08-16 (user request): was "tuning.json" directly under the plane folder,
        // now "config tables/settings.json" alongside items.csv/enemies.csv - same subfolder,
        // same class (TuningData), just a moved/renamed backing file.
        FileHandle tuningFile = new FileHandle(prefix + "config tables/settings.json");
        if (!tuningFile.exists())
            tuningFile = new FileHandle(commonPrefix + "config tables/settings.json");
        if (tuningFile.exists()) {
            try {
                tuningData = new Json().fromJson(TuningData.class, tuningFile);
            } catch (Exception e) {
                // Round 140 (S2-6), same reasoning as config.json above: a settings.json that
                // EXISTS but does not parse silently reverts every balance number in the plane to
                // TuningData's hardcoded defaults. Recorded, not absorbed. (A settings.json that
                // is simply absent is an expected case - stock planes have none - and is handled
                // by the else branch below without complaint.)
                fatalDataError = "settings.json (" + tuningFile.path() + ") could not be parsed: "
                        + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage());
                System.err.println("========================================================================");
                System.err.println("[TFR-Config] FATAL: " + fatalDataError);
                System.err.println("[TFR-Config] Every balance tunable is now at its hardcoded default.");
                System.err.println("========================================================================");
                e.printStackTrace();
                tuningData = new TuningData();
            }
        } else {
            tuningData = new TuningData();
        }

        // Restricted Cards file (2026-08-22 user request: "create a Restricted card list in the
        // settings folder that we can add more cards to if needed" - part of the RoL/Commander
        // card-mixing fix, MOD_CHANGELOG.md). Same plane-local/fallback-to-common load pattern as
        // settings.json above. Merges into configData.restrictedCards (a pre-existing field,
        // already wired into RewardData.initializeAllCards()'s main pool filter and
        // cardPackShop's edition filter) rather than replacing it, so anything a plane still sets
        // inline in its own config.json survives untouched.
        FileHandle restrictedCardsFile = new FileHandle(prefix + "config tables/restricted_cards.json");
        if (!restrictedCardsFile.exists())
            restrictedCardsFile = new FileHandle(commonPrefix + "config tables/restricted_cards.json");
        if (restrictedCardsFile.exists()) {
            try {
                RestrictedCardsData restrictedCardsData = new Json().fromJson(RestrictedCardsData.class, restrictedCardsFile);
                if (restrictedCardsData.restrictedCards != null && restrictedCardsData.restrictedCards.length > 0) {
                    Set<String> merged = new LinkedHashSet<>();
                    if (configData.restrictedCards != null)
                        merged.addAll(Arrays.asList(configData.restrictedCards));
                    merged.addAll(Arrays.asList(restrictedCardsData.restrictedCards));
                    configData.restrictedCards = merged.toArray(new String[0]);
                    System.out.println("[TFR-RestrictedCards] loaded " + restrictedCardsData.restrictedCards.length
                            + " card(s) from " + restrictedCardsFile.path() + " (" + configData.restrictedCards.length
                            + " total after merge)");
                }
            } catch (Exception e) {
                System.err.println("[TFR-RestrictedCards] restricted_cards.json failed to load, none applied: " + e);
            }
        }
        // Null-safety net (2026-08-22 review fix): before this file existed, every plane's
        // config.json set restrictedCards inline, so it was never null. Now a plane can end up
        // with no non-null source at all (restricted_cards.json emptied to "[]", deleted, or the
        // catch above firing) - without this, RewardData.initializeAllCards()'s
        // "new HashSet<>(Arrays.asList(configData.restrictedCards))" and the cardPackShop
        // edition-purge loop both NPE on the very first reward/shop generation, far from this
        // load site, contradicting the log line above's claim of graceful degradation.
        if (configData.restrictedCards == null)
            configData.restrictedCards = new String[0];

        // Spawn Tier Weighting file (2026-08-23 user spec) - same plane-local/fallback-to-common
        // load pattern as restricted_cards.json above. Unlike that file, this one is NOT merged
        // into an existing ConfigData field - it's this feature's own dedicated data, silently
        // absent (spawnTierWeightData stays null) on any plane without the file, which
        // SpawnTierWeighting.targetTierWeight() already treats as "target 0 for everything" -
        // harmless, since the feature is separately gated off by default via
        // ConfigData.weightedSpawnTiersEnabled anyway.
        FileHandle spawnTierWeightFile = new FileHandle(prefix + "config tables/spawn_tier_weighting.json");
        if (!spawnTierWeightFile.exists())
            spawnTierWeightFile = new FileHandle(commonPrefix + "config tables/spawn_tier_weighting.json");
        if (spawnTierWeightFile.exists()) {
            try {
                spawnTierWeightData = new Json().fromJson(SpawnTierWeightData.class, spawnTierWeightFile);
            } catch (Exception e) {
                System.err.println("[TFR-SpawnTierWeighting] spawn_tier_weighting.json failed to load, feature will no-op: " + e);
                spawnTierWeightData = null;
            }
        }

        // Armory item-rarity weights by venue and week (2026-08-31 user spec) - same plane-local /
        // fallback-to-common pattern. Absent or unparseable leaves armoryRarityData null, which
        // ArmoryRarity treats as "use the flat Common 60 / Uncommon 30 / Rare 8 / Mythic 2".
        FileHandle armoryRarityFile = new FileHandle(prefix + "config tables/armory_rarity.json");
        if (!armoryRarityFile.exists())
            armoryRarityFile = new FileHandle(commonPrefix + "config tables/armory_rarity.json");
        if (armoryRarityFile.exists()) {
            try {
                armoryRarityData = new Json().fromJson(forge.adventure.data.ArmoryRarityData.class, armoryRarityFile);
            } catch (Exception e) {
                System.err.println("[TFR-ArmoryRarity] armory_rarity.json failed to load - falling back to the flat "
                        + "Common 60 / Uncommon 30 / Rare 8 / Mythic 2 with no time gating: " + e);
                armoryRarityData = null;
            }
        }

        // War champions (round 139, user spec 2026-09-07) - same plane-local / fallback-to-common
        // pattern. Absent or unparseable leaves warChampionData null, which WarChampions reads as
        // "no cast, feature off": no plane without this file changes behaviour in any way.
        FileHandle warChampionFile = new FileHandle(prefix + "config tables/war_champions.json");
        if (!warChampionFile.exists())
            warChampionFile = new FileHandle(commonPrefix + "config tables/war_champions.json");
        if (warChampionFile.exists()) {
            try {
                warChampionData = new Json().fromJson(forge.adventure.data.WarChampionData.class, warChampionFile);
            } catch (Exception e) {
                System.err.println("[TFR-WarChampions] war_champions.json failed to load, feature will no-op: " + e);
                warChampionData = null;
            }
        }

        // Frontier spawns (round 142, user spec 2026-09-07) - same plane-local / fallback-to-common
        // pattern. Absent leaves frontierSpawnData null, which FrontierSpawns reads as "off".
        FileHandle frontierFile = new FileHandle(prefix + "config tables/frontier_spawns.json");
        if (!frontierFile.exists())
            frontierFile = new FileHandle(commonPrefix + "config tables/frontier_spawns.json");
        if (frontierFile.exists()) {
            try {
                frontierSpawnData = new Json().fromJson(forge.adventure.data.FrontierSpawnData.class, frontierFile);
            } catch (Exception e) {
                System.err.println("[TFR-FrontierSpawns] frontier_spawns.json failed to load, feature will no-op: " + e);
                frontierSpawnData = null;
            }
        }

        // Roaming guards (MOD_SCOPE #116, round 145) - same plane-local / fallback-to-common
        // pattern. Absent leaves roamingGuardConfig null, which RoamingGuards reads as "off", so
        // no plane without this file gains the feature.
        FileHandle roamingGuardFile = new FileHandle(prefix + "config tables/roaming_guards.json");
        if (!roamingGuardFile.exists())
            roamingGuardFile = new FileHandle(commonPrefix + "config tables/roaming_guards.json");
        if (roamingGuardFile.exists()) {
            try {
                roamingGuardConfig = new Json().fromJson(forge.adventure.data.RoamingGuardConfig.class, roamingGuardFile);
            } catch (Exception e) {
                System.err.println("[TFR-RoamGuard] roaming_guards.json failed to load, feature will no-op: " + e);
                roamingGuardConfig = null;
            }
        }
    }

    private String resPath() {
        // Android/iOS: resources live at ASSETS_DIR (extracted storage / app bundle);
        // the desktop-relative "./res" probes below never match there
        if (GuiBase.isMobile()) {
            return ForgeConstants.ASSETS_DIR;
        }
        return Files.exists(Paths.get("./res")) ? "./" : Files.exists(Paths.get("./forge-gui/")) ? "./forge-gui/" : "../forge-gui";
    }

    public String getPlanePath(String plane) {
        if (plane.startsWith("<user>")) {
            return ForgeConstants.USER_ADVENTURE_DIR + "/userplanes/" + plane.substring("<user>".length()) + "/";
        } else {
            return resPath() + "/res/adventure/" + plane + "/";
        }
    }

    public ConfigData getConfigData() {
        return configData;
    }

    public TuningData getTuningData() {
        return tuningData;
    }

    public forge.adventure.data.ArmoryRarityData getArmoryRarityData() {
        return armoryRarityData;
    }

    public SpawnTierWeightData getSpawnTierWeightData() {
        return spawnTierWeightData;
    }

    public forge.adventure.data.WarChampionData getWarChampionData() {
        return warChampionData;
    }

    /** Round 140 (S2-6): null when the plane's data loaded cleanly. */
    public String getFatalDataError() {
        return fatalDataError;
    }

    public forge.adventure.data.FrontierSpawnData getFrontierSpawnData() {
        return frontierSpawnData;
    }

    public forge.adventure.data.RoamingGuardConfig getRoamingGuardConfig() {
        return roamingGuardConfig;
    }

    // Push the plane's allowed/restricted editions and restricted token pairs into TokenDb.
    private void applyTokenEditionFilter() {
        if (configData == null) return;
        String[] allowedArr = configData.allowedEditions;
        String[] restrictedArr = configData.restrictedEditions;
        String[] restrictedTokensArr = configData.restrictedTokens;
        Set<String> allowed = (allowedArr == null || allowedArr.length == 0)
                ? null : new HashSet<>(Arrays.asList(allowedArr));
        Set<String> restricted = (restrictedArr == null || restrictedArr.length == 0)
                ? Collections.emptySet() : new HashSet<>(Arrays.asList(restrictedArr));
        Set<String> restrictedTokens = (restrictedTokensArr == null || restrictedTokensArr.length == 0)
                ? Collections.emptySet() : new HashSet<>(Arrays.asList(restrictedTokensArr));
        FModel.getMagicDb().getAllTokens().setRestrictedTokenEntries(restrictedTokens);
        FModel.getMagicDb().getAllTokens().setPreferEraMatchedArt(
            settingsData != null && settingsData.preferEraMatchedTokenArt);
        if (allowed == null && restricted.isEmpty()) {
            FModel.getMagicDb().getAllTokens().setDefaultEditionFilter(null);
            return;
        }
        FModel.getMagicDb().getAllTokens().setDefaultEditionFilter(edition -> {
            String code = edition.getCode();
            if (restricted.contains(code)) return false;
            return allowed == null || allowed.contains(code);
        });
    }

    public int getBlurDivisor() {
        int val = 1;
        try {
            switch(settingsData.videomode) {
                case "720p":
                case "768p":
                    val = 8;
                    break;
                case "900p":
                case "1080p":
                    val = 16;
                    break;
                case "1440p":
                case "2160p":
                    val = 32;
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            return val;
        }
        return val;
    }
    public String getPrefix() {
        return prefix;
    }

    public String getFilePath(String path) {
        return prefix + path;
    }

    public String getCommonFilePath(String path) {
        return commonPrefix + path;
    }

    public FileHandle getFile(String path) {
        if (Cache.containsKey(path)) return Cache.get(path);

        //if (Cache.containsKey(commonPath)) return Cache.get(commonPath);

        //not cached, look for resource
        System.out.print("Looking for resource " + path + "... ");
        String fullPath = (prefix + path).replace("//", "/");
        String fileName = fullPath.replaceFirst("[.][^.]+$", "");
        String ext = fullPath.substring(fullPath.lastIndexOf('.'));
        String langFile = fileName + "-" + Lang + ext;

        for (int iter = 1; iter <= 2; iter++) {
            if (Files.exists(Paths.get(langFile))) {
                System.out.println("Found!");
                Cache.put(path, new FileHandle(langFile));
                break;
            } else if (Files.exists(Paths.get(fullPath))) {
                System.out.println("Found!");
                Cache.put(path, new FileHandle(fullPath));
                break;
            }
            //no local resource, check common resources
            fullPath = (commonPrefix + path).replace("//", "/");
            fileName = fullPath.replaceFirst("[.][^.]+$", "");
            langFile = fileName + "-" + Lang + ext;
        }
        return Cache.get(path);
    }

    public String getPlane() {
        return plane.replace("<user>", "user_");
    }

    public String[] colorIdNames() {
        return configData.colorIdNames;
    }

    public String[] colorIds() {
        return configData.colorIds;
    }

    public String[] starterEditionNames() {
        return configData.starterEditionNames;
    }

    public String[] starterEditions() {
        return configData.starterEditions;
    }

    public Deck starterDeck(ColorSet color, DifficultyData difficultyData, AdventureModes mode, int index, CardEdition starterEdition) {
        return starterDeck(color, difficultyData, mode, index, starterEdition, -1);
    }

    /**
     * Builds a generated starter deck confined to {@code editionCodes}, widening the restriction
     * rather than handing over an illegal deck: the requested sets first, then the race's full
     * four, then no restriction at all. Round 150 - shared by every starting mode that generates
     * its deck (Standard, Constructed, Pile), so all three answer the same question the same way.
     * <p>
     * The widening steps are logged because reaching one means a template bucket is too narrow for
     * that race, which is a data problem to fix rather than something to absorb silently.
     */
    /**
     * Round 152 (user request: "can we add more logging to confirm starting decks are good. I can
     * create a bunch of new games, but it's hard for me to confirm if the decks are correct").
     * <p>
     * One scannable AUDIT line - lands, rarity split, worst duplicate count and the edition spread
     * - then the list itself. The audit line is the point: a rare in a no-rares template, a fifth
     * copy of a card, or a set that should not be in this race all show up without reading 40 rows.
     */
    private static final java.util.Set<String> BASIC_LAND_NAMES = new java.util.HashSet<>(
            java.util.Arrays.asList("Plains", "Island", "Swamp", "Mountain", "Forest", "Wastes",
                    "Snow-Covered Plains", "Snow-Covered Island", "Snow-Covered Swamp",
                    "Snow-Covered Mountain", "Snow-Covered Forest"));

    private void describeStarterDeck(String label, Deck deck) {
        if (deck == null)
            return;
        java.util.TreeMap<String, Integer> byName = new java.util.TreeMap<>();
        java.util.TreeMap<String, Integer> byEdition = new java.util.TreeMap<>();
        java.util.TreeMap<String, Integer> byRarity = new java.util.TreeMap<>();
        int lands = 0;
        for (java.util.Map.Entry<forge.item.PaperCard, Integer> e : deck.getMain()) {
            forge.item.PaperCard card = e.getKey();
            int n = e.getValue();
            byName.merge(card.getName(), n, Integer::sum);
            byEdition.merge(card.getEdition(), n, Integer::sum);
            byRarity.merge(String.valueOf(card.getRarity()), n, Integer::sum);
            if (card.getRules() != null && card.getRules().getType() != null
                    && card.getRules().getType().isLand())
                lands += n;
        }
        // Round 153: BASIC LANDS ARE EXEMPT from the 4-of rule, and counting them made every single
        // audit line in the user's first log cry "ILLEGAL, more than 4" over 17 Island / 24 Swamp -
        // a false alarm on all ten decks, which is worse than no check at all.
        String worst = "";
        int worstCount = 0;
        for (java.util.Map.Entry<String, Integer> e : byName.entrySet()) {
            if (BASIC_LAND_NAMES.contains(e.getKey()))
                continue;
            if (e.getValue() > worstCount) {
                worstCount = e.getValue();
                worst = e.getKey();
            }
        }
        int total = deck.getMain().countAll();
        System.out.println("[TFR-StarterDeck] " + label + " AUDIT: " + total + " cards, " + lands
                + " land / " + (total - lands) + " spells | rarity " + byRarity
                + " | editions " + byEdition + " | most copies of one NON-LAND card: " + worstCount
                + " (" + worst + ")" + (worstCount > 4 ? "  <-- ILLEGAL, more than 4" : ""));
        StringBuilder list = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : byName.entrySet())
            list.append(e.getValue()).append("x ").append(e.getKey()).append("; ");
        System.out.println("[TFR-StarterDeck] " + label + " LIST: " + list);
    }

    private Deck racedStarterDeck(String path, String label, java.util.List<String> editionCodes,
            java.util.List<String> raceCodes) {
        Deck deck = CardUtil.getDeck(path, false, false, "", false, false, editionCodes, false, true);
        int size = deck == null ? 0 : deck.getMain().countAll();
        if (size >= configData.minDeckSize) {
            System.out.println("[TFR-StarterDeck] " + label + " from editions " + editionCodes
                    + " -> " + size + " cards (" + path + ")");
            describeStarterDeck(label, deck);
            return deck;
        }
        if (editionCodes != null && raceCodes != null && !raceCodes.equals(editionCodes)) {
            System.out.println("[TFR-StarterDeck] " + label + ": " + editionCodes + " filled only " + size
                    + " of " + configData.minDeckSize + " - widening to the race's full set list");
            deck = CardUtil.getDeck(path, false, false, "", false, false, raceCodes, false, true);
            size = deck == null ? 0 : deck.getMain().countAll();
            if (size >= configData.minDeckSize) {
                System.out.println("[TFR-StarterDeck] " + label + " from race editions " + raceCodes
                        + " -> " + size + " cards");
                return deck;
            }
        }
        System.out.println("[TFR-StarterDeck] " + label + ": race editions could only fill " + size
                + " of " + configData.minDeckSize + " for " + path + " - rebuilding unrestricted");
        return CardUtil.getDeck(path, false, false, "", false, false, (java.util.List<String>) null, false, false);
    }

    /**
     * @param race index into heroes.json, or -1 when the caller has no race to offer. Round 149:
     *             the Constructed starter decks are generated from the chosen race's own four
     *             expansions ({@code raceEditions}) instead of a fixed hand-written card list that
     *             drew on fifteen sets the player had no connection to.
     */
    public Deck starterDeck(ColorSet color, DifficultyData difficultyData, AdventureModes mode, int index,
            CardEdition starterEdition, int race) {
        switch (mode) {
            case Constructed:
                for (ObjectMap.Entry<String, String> entry : difficultyData.constructedStarterDecks) {
                    if (ColorSet.fromNames(entry.key.toCharArray()).getColor() == color.getColor()) {
                        java.util.List<String> raceCodes = forge.adventure.util.EditionProgression.raceEditionCodes(race);
                        return racedStarterDeck(entry.value, "Constructed " + entry.key, raceCodes, raceCodes);
                    }
                }
            case Standard:
                // Check for edition-specific starter decks first
                if (starterEdition != null && configData.starterDecksByEdition != null) {
                    ObjectMap<String, String> editionDecks = configData.starterDecksByEdition.get(starterEdition.getCode());
                    if (editionDecks != null) {
                        for (ObjectMap.Entry<String, String> entry : editionDecks) {
                            if (ColorSet.fromNames(entry.key.toCharArray()).getColor() == color.getColor()) {
                                return CardUtil.getDeck(entry.value, false, false, "", false, false);
                            }
                        }
                    }
                }
                // Round 150: the same race-set restriction Constructed uses. Standard's old
                // jumpstartPacks shape could never have honoured it - jumpstart-style boosters only
                // exist for 18 editions, and only SIX of the sixteen races have one of those in
                // their four sets, so ten races would have picked from an empty pack pool. The
                // templates are ordinary mainDeck reward filters now, which any set can fill.
                // starterEdition here is one of the player's OWN race sets (see NewGameScene), or
                // null for "all of them".
                for (ObjectMap.Entry<String, String> entry : difficultyData.starterDecks) {
                    if (ColorSet.fromNames(entry.key.toCharArray()).getColor() == color.getColor()) {
                        java.util.List<String> raceCodes = forge.adventure.util.EditionProgression.raceEditionCodes(race);
                        java.util.List<String> picked = starterEdition == null ? raceCodes
                                : java.util.Collections.singletonList(starterEdition.getCode());
                        return racedStarterDeck(entry.value, "Standard " + entry.key, picked, raceCodes);
                    }
                }
            case Chaos:
                if ("Commander".equalsIgnoreCase(configData.chaosDeckFormat)) {
                    return DeckgenUtil.generateCommanderDeck(false, GameType.Commander);
                }
                return DeckgenUtil.getRandomOrPreconOrThemeDeck("", false, false, false, configData.allowedEditions);
            case Custom:
                return DeckProxy.getAllCustomStarterDecks().get(index).getDeck();
            case Pile:
                // Round 150: a pile is meant to be janky, not unrelated to who you are - same race
                // restriction, existing two-color templates untouched.
                for (ObjectMap.Entry<String, String> entry : difficultyData.pileDecks) {
                    if (ColorSet.fromNames(entry.key.toCharArray()).getColor() == color.getColor()) {
                        java.util.List<String> raceCodes = forge.adventure.util.EditionProgression.raceEditionCodes(race);
                        return racedStarterDeck(entry.value, "Pile " + entry.key, raceCodes, raceCodes);
                    }
                }
            case Commander:
                // Null-guard (2026-08-13 holistic review): removing Commander mode from a plane's
                // config.json leaves commanderDecks null there, and the pre-existing Pile->Commander
                // fall-through above (stock behavior, deliberately preserved) would then NPE for a
                // Pile pick whose color found no pileDecks match. Harmless on planes that still
                // ship commanderDecks.
                if (difficultyData.commanderDecks != null) {
                    for (ObjectMap.Entry<String, String> entry : difficultyData.commanderDecks) {
                        if (ColorSet.fromNames(entry.key.toCharArray()).getColor() == color.getColor()) {
                            return CardUtil.getDeck(entry.value, false, false, "", false, false);
                        }
                    }
                }
                return null;
            case Precon:
                String preconPath = getPreconDeckPath(index);
                if (preconPath != null) {
                    return CardUtil.getDeck(preconPath, false, false, "", false, false);
                }
                return null;
            case CommanderPrecon:
                String commanderPreconPath = getCommanderPreconDeckPath(index);
                if (commanderPreconPath != null) {
                    return CardUtil.getDeck(commanderPreconPath, false, false, "", false, false);
                }
                return null;
        }
        return null;
    }

    public TextureAtlas getAtlas(String spriteAtlas) {
        String fileName = getFile(spriteAtlas).path();
        TextureAtlas atlas = Forge.getAssets().manager().get(fileName, TextureAtlas.class, false);
        if (atlas == null) {
            Forge.getAssets().manager().load(fileName, TextureAtlas.class);
            Forge.getAssets().manager().finishLoadingAsset(fileName);
            atlas = Forge.getAssets().manager().get(fileName, TextureAtlas.class, false);
        }
        return atlas;
    }

    public Sprite getItemSprite(String itemName) {
        return getAtlasSprite(forge.adventure.util.Paths.ITEMS_ATLAS, itemName);
    }

    public Sprite getAtlasSprite(String atlasName, String itemName) {
        Sprite sprite;
        ObjectMap<String, Sprite> sprites = atlasSprites.get(atlasName);
        if (sprites == null) {
            sprites = new ObjectMap<>();
        }
        sprite = sprites.get(itemName);
        if (sprite == null) {
            sprite = getAtlas(atlasName).createSprite(itemName);
            if (sprite != null) {
                sprites.put(itemName, sprite);
                atlasSprites.put(atlasName, sprites);
            }
        }
        return sprite;
    }

    public Array<Sprite> getPOISprites(PointOfInterestData d) {
        Array<Sprite> sprites = poiSprites.get(d);
        if (sprites == null) {
            sprites = getAtlas(d.spriteAtlas).createSprites(d.sprite);
            poiSprites.put(d, sprites);
        }
        return sprites;
    }

    public Array<Sprite> getAnimatedSprites(String path, String animationName) {
        Array<Sprite> sprites;
        ObjectMap<String, Array<Sprite>> mapSprites = animatedSprites.get(path);
        if (mapSprites == null) {
            mapSprites = new ObjectMap<>();
        }
        sprites = mapSprites.get(animationName);
        if (sprites == null) {
            sprites = getAtlas(path).createSprites(animationName);
            if (sprites != null) {
                mapSprites.put(animationName, sprites);
                animatedSprites.put(path, mapSprites);
            }
        }
        return sprites;
    }

    public SettingData getSettingData() {
        return settingsData;
    }

    public Array<String> getAllAdventures() {
        String path = ForgeConstants.USER_ADVENTURE_DIR + "/userplanes/";
        Array<String> adventures = new Array<>();
        if (new File(path).exists())
            adventures.addAll(new File(path).list());
        for (int i = 0; i < adventures.size; i++) {
            adventures.set(i, "<user>" + adventures.get(i));
        }
        adventures.addAll(this.adventures);

        // A hard-coded list of planes that are currently not finished and are considered to be in development
        // (these planes will only appear in the choice box if Developer Mode is enabled in Forge)
        // TODO: migrate this to an externally configurable ini or json file
        if (!FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.DEV_MODE_ENABLED)) {
            adventures.removeValue("Amonkhet", false);
            adventures.removeValue("Innistrad", false);
            adventures.removeValue("Crystal_Kingdoms", false);
        }

        return adventures;
    }

    public void saveSettings() {
        Json json = new Json(JsonWriter.OutputType.json);
        FileHandle handle = new FileHandle(ForgeProfileProperties.getUserDir() + "/adventure/settings.json");
        handle.writeString(json.prettyPrint(json.toJson(settingsData, SettingData.class)), false);
    }

    // --- Folder-backed starter deck support ---

    private static final class FolderDeckCatalog {
        private final String folderPath;
        private Array<String> setNames;
        private Array<Array<String>> deckNames;
        private Array<Array<String>> deckPaths;
        private Array<String> currentPaths;
        private boolean scanned = false;

        private FolderDeckCatalog(String folderPath) {
            this.folderPath = folderPath;
        }

        private void ensureScanned(String prefix, String commonPrefix) {
            if (scanned) {
                return;
            }
            scanned = true;
            scan(prefix, commonPrefix);
        }

        private void scan(String prefix, String commonPrefix) {
            if (!scanRoot(prefix)) {
                scanRoot(commonPrefix);
            }
        }

        private boolean scanRoot(String rootPrefix) {
            String dirPath = rootPrefix + folderPath;
            File dir = new File(dirPath);
            if (!dir.exists() || !dir.isDirectory()) {
                return false;
            }

            File[] dckFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".dck"));
            if (dckFiles == null || dckFiles.length == 0) {
                return false;
            }

            TreeMap<String, List<String[]>> setMap = new TreeMap<>();
            for (File file : dckFiles) {
                String filename = file.getName();
                String nameNoExt = filename.substring(0, filename.length() - 4);
                int dash = nameNoExt.indexOf(" - ");
                String setDisplayName = "";
                String deckName;
                if (dash >= 0) {
                    CardEdition edition = FModel.getMagicDb().getEditions().get(nameNoExt.substring(0, dash));
                    if (edition != null) {
                        setDisplayName = edition.getName();
                    }
                    deckName = nameNoExt.substring(dash + 3);
                } else {
                    deckName = nameNoExt;
                }
                setMap.computeIfAbsent(setDisplayName, k -> new ArrayList<>())
                        .add(new String[]{deckName, folderPath + filename});
            }
            for (List<String[]> decks : setMap.values()) {
                decks.sort(Comparator.comparing(a -> a[0]));
            }

            setNames = new Array<>();
            deckNames = new Array<>();
            deckPaths = new Array<>();

            setNames.add("All Editions");
            Array<String> allNames = new Array<>();
            Array<String> allPaths = new Array<>();
            for (List<String[]> decks : setMap.values()) {
                for (String[] deck : decks) {
                    allNames.add(deck[0]);
                    allPaths.add(deck[1]);
                }
            }
            deckNames.add(allNames);
            deckPaths.add(allPaths);

            for (Map.Entry<String, List<String[]>> entry : setMap.entrySet()) {
                if (entry.getKey().isEmpty()) {
                    continue;
                }
                setNames.add(entry.getKey());
                Array<String> names = new Array<>();
                Array<String> paths = new Array<>();
                for (String[] deck : entry.getValue()) {
                    names.add(deck[0]);
                    paths.add(deck[1]);
                }
                deckNames.add(names);
                deckPaths.add(paths);
            }
            currentPaths = allPaths;
            return true;
        }

        private boolean hasDecks(String prefix, String commonPrefix) {
            ensureScanned(prefix, commonPrefix);
            return setNames != null && setNames.size > 0;
        }

        private Array<String> getSetNames(String prefix, String commonPrefix) {
            ensureScanned(prefix, commonPrefix);
            return setNames;
        }

        private Array<String> filterDecks(String prefix, String commonPrefix, int setIndex) {
            ensureScanned(prefix, commonPrefix);
            Array<String> result = new Array<>();
            result.add(Forge.getLocalizer().getMessage("lblRandomDeck"));
            if (deckPaths == null || deckPaths.size == 0) {
                return result;
            }
            if (setIndex < 0 || setIndex >= deckPaths.size) {
                setIndex = 0;
            }
            currentPaths = deckPaths.get(setIndex);
            result.addAll(deckNames.get(setIndex));
            return result;
        }

        private String getDeckPath(String prefix, String commonPrefix, int deckIndex) {
            ensureScanned(prefix, commonPrefix);
            if (currentPaths == null || currentPaths.size == 0) {
                return null;
            }
            if (deckIndex <= 0) {
                return currentPaths.get(new Random().nextInt(currentPaths.size));
            }
            int idx = deckIndex - 1;
            return idx < currentPaths.size ? currentPaths.get(idx) : null;
        }
    }

    public boolean hasPreconDecks() {
        return preconDeckCatalog.hasDecks(prefix, commonPrefix);
    }

    public Array<String> getPreconSetNames() {
        return preconDeckCatalog.getSetNames(prefix, commonPrefix);
    }

    /** Filters deck list by set index. Returns deck names with "Random" prepended for colorId. */
    public Array<String> filterPreconDecks(int setIndex) {
        return preconDeckCatalog.filterDecks(prefix, commonPrefix, setIndex);
    }

    /** Resolves deck path from colorId index. Index 0 = random from current filter. */
    public String getPreconDeckPath(int deckIndex) {
        return preconDeckCatalog.getDeckPath(prefix, commonPrefix, deckIndex);
    }

    public boolean hasCommanderPreconDecks() {
        return commanderPreconDeckCatalog.hasDecks(prefix, commonPrefix);
    }

    public Array<String> getCommanderPreconSetNames() {
        return commanderPreconDeckCatalog.getSetNames(prefix, commonPrefix);
    }

    public Array<String> filterCommanderPreconDecks(int setIndex) {
        return commanderPreconDeckCatalog.filterDecks(prefix, commonPrefix, setIndex);
    }

    public String getCommanderPreconDeckPath(int deckIndex) {
        return commanderPreconDeckCatalog.getDeckPath(prefix, commonPrefix, deckIndex);
    }

    public void loadResources() {
        // Content filter tables (user spec 2026-08-12): fold the expansions table's Include=N
        // codes into restrictedEditions BEFORE the token filter and card-pool init below, so
        // every edition consumer sees one merged list. This is the earliest point where the
        // Magic DB is guaranteed loaded (the next line already depends on it).
        ContentFilterTables.applyEditionExclusions(configData);
        AdventureOverrides.instance().load(prefix, FModel.getMagicDb().getEditions(), configData);
        applyTokenEditionFilter();
        RewardData.getAllCards();//initialize before loading custom cards
        final CardRules.Reader rulesReader = new CardRules.Reader();
        ImageKeys.ADVENTURE_CARD_PICS_DIR = Config.currentConfig.getCommonFilePath(forge.adventure.util.Paths.CUSTOM_CARDS_PICS);// not the cleanest solution
        File[] customCards = new File(getCommonFilePath(forge.adventure.util.Paths.CUSTOM_CARDS)).listFiles();
        if (customCards == null)
            return;
        for (File cardFile : customCards) {
            FileInputStream fileInputStream;
            try {
                fileInputStream = new FileInputStream(cardFile);
                rulesReader.reset();
                final List<String> lines = FileUtil.readAllLines(new InputStreamReader(fileInputStream, Charset.forName(CardStorageReader.DEFAULT_CHARSET_NAME)), true);
                CardRules rules = rulesReader.readCard(lines, com.google.common.io.Files.getNameWithoutExtension(cardFile.getName()));
                rules.setCustom();
                PaperCard card = new PaperCard(rules, CardEdition.UNKNOWN_CODE, CardRarity.Special) {
                    @Override
                    public String getImageKey(boolean altState) {
                        return ImageKeys.ADVENTURECARD_PREFIX + getName();
                    }
                };
                CardDb db = rules.isVariant() ? FModel.getMagicDb().getVariantCards() : FModel.getMagicDb().getCommonCards();
                db.addCard(card);
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
