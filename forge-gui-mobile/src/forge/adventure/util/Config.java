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
                //init Shandalar as default plane if found...
                for (String plane : adventures) {
                    if (plane.equalsIgnoreCase("Shandalar"))
                        settingsData.plane = plane;
                }
                //if can't find shandalar, just get any random plane available
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
        switch (mode) {
            case Constructed:
                for (ObjectMap.Entry<String, String> entry : difficultyData.constructedStarterDecks) {
                    if (ColorSet.fromNames(entry.key.toCharArray()).getColor() == color.getColor()) {
                        return CardUtil.getDeck(entry.value, false, false, "", false, false);
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
                // Fall back to default starter decks (JSON generation with edition filter)
                for (ObjectMap.Entry<String, String> entry : difficultyData.starterDecks) {
                    if (ColorSet.fromNames(entry.key.toCharArray()).getColor() == color.getColor()) {
                        return CardUtil.getDeck(entry.value, false, false, "", false, false, starterEdition, true);
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
                for (ObjectMap.Entry<String, String> entry : difficultyData.pileDecks) {
                    if (ColorSet.fromNames(entry.key.toCharArray()).getColor() == color.getColor()) {
                        return CardUtil.getDeck(entry.value, false, false, "", false, false);
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
