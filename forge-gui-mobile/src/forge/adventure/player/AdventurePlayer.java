package forge.adventure.player;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Null;
import com.github.tommyettinger.textra.TextraLabel;
import com.google.common.collect.Lists;

import forge.Forge;
import forge.adventure.data.*;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.AdventureDeckEditor;
import forge.adventure.scene.DeckEditScene;
import forge.adventure.stage.GameStage;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.*;
import forge.adventure.world.WorldSave;
import forge.card.ColorSet;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckProxy;
import forge.deck.DeckSection;
import forge.item.InventoryItem;
import forge.item.PaperCard;
import forge.sound.SoundEffectType;
import forge.sound.SoundSystem;
import forge.util.ItemPool;
import forge.util.MyRandom;

import java.io.Serializable;
import java.util.*;
import java.util.function.Predicate;

/**
 * Class that represents the player (not the player sprite)
 */
public class AdventurePlayer implements Serializable, SaveFileContent {
    // Save compatibility: pinned 2026-09-03 (round 90) at the value derived from the v1.04 class shape so save compatibility no longer depends on the class not changing.
    private static final long serialVersionUID = -1789065646640642258L;

    public static final int MIN_DECK_COUNT = 10;
    // this is a purely arbitrary limit, could be higher or lower; just meant as some sort of reasonable limit for the user
    private int maxDeckCount = 20;
    // Player profile data.
    private String name;
    private int heroRace;
    private int avatarIndex;
    private boolean isFemale;
    private ColorSet colorIdentity = ColorSet.WUBRG;

    // Deck data
    private Deck deck;
    private final ArrayList<Deck> decks = new ArrayList<>(MIN_DECK_COUNT);
    private int selectedDeckIndex = 0;
    private final DifficultyData difficultyData = new DifficultyData();

    // Commander mode
    private AdventureModes adventureMode;

    // Game data.
    private float worldPosX;
    private float worldPosY;
    private int gold = 0;
    private int maxLife = 20;
    private int life = 20;
    private int shards = 0;
    private int wood = 0;
    private int stone = 0;
    // Currently-applied town-count max-life bonus (mod feature, see TownRestoration.
    // updateTownLifeBonus(): +1 per 5 owned towns, +1 for the Capitol). Tracked so ownership
    // changes apply only the DELTA to maxLife - recomputing is always safe/idempotent.
    private int townLifeBonus = 0;
    private int ringLifeBonus = 0; // round 100: +1 per visited Ring City while neutral/player-held
    // Player reputation with the 5 AI colors (MOD_SCOPE.md #1), keyed "white"/"blue"/"black"/
    // "red"/"green". Stored in INTERNAL HALF-POINTS (user-facing value x2) so that multicolor
    // fights' half-strength effects stay exact integers and the 5 values always sum to exactly
    // zero - see ColorReputation for the rules and the display conversion.
    private final Map<String, Integer> colorReputationHalfPoints = new HashMap<>();
    // Color reputation (MOD_SCOPE.md #1) Partner-tier Inn overheal: true while an auto-granted
    // maxLife+2 top-up (from entering a Partner-tier color's town/capital) is still "unused".
    // Cleared - dropping life back down to maxLife if it's still above it - by the next duel
    // (DuelScene.GameEnd(), the same universal funnel that clears blessing below) or by entering
    // any other town/capital (TileMapScene.enter()), whichever comes first. Deliberately NOT the
    // same mechanism as the pre-existing paid potionOfFalseLife() - that one has no flag and is
    // untouched by this, so a manually-purchased false-life buff keeps its old behavior exactly.
    private boolean partnerOverhealActive = false;
    private EffectData blessing; //Blessing to apply for next battle.
    private final PlayerStatistic statistic = new PlayerStatistic();
    private final Map<String, Byte> questFlags = new HashMap<>();
    private final Map<String, Byte> characterFlags = new HashMap<>();
    private final Map<String, Byte> tutorialFlags = new HashMap<>();

    private final ArrayList<ItemData> inventoryItems = new ArrayList<>();
    private final Array<Deck> boostersOwned = new Array<>();
    private final HashMap<String, Long> equippedItems = new HashMap<>();
    private final ArrayList<HashMap<String, Long>> deckLoadouts = new ArrayList<>();
    private final List<AdventureQuestData> quests = new ArrayList<>();
    private final List<AdventureEventData> events = new ArrayList<>();
    private final Set<PaperCard> unsupportedCards = new HashSet<>();
    private final Predicate<PaperCard> isUnsupported = pc -> pc != null && pc.getRules() != null && pc.getRules().isUnsupported();
    private final Predicate<PaperCard> isValid = pc -> pc != null && pc.getRules() != null && !pc.getRules().isUnsupported();

    // Fantasy/Chaos mode settings.
    private boolean fantasyMode = false;
    private boolean announceFantasy = false;
    private boolean usingCustomDeck = false;
    private boolean announceCustom = false;

    // Bank building preferences (2026-08-13, user spec): control how weekly guard salaries and
    // Gold Mine income route between a town's bank and the player's own gold - see
    // EconomyBuildings.payGuardGold()/processDaysPassed(). Both default true.
    private boolean payGuardsFromBankFirst = true;
    private boolean goldMineDepositsToBankDirectly = true;

    // Signals
    final SignalList onLifeTotalChangeList = new SignalList();
    final SignalList onShardsChangeList = new SignalList();
    final SignalList onGoldChangeList = new SignalList();
    final SignalList onWoodChangeList = new SignalList();
    final SignalList onStoneChangeList = new SignalList();
    final SignalList onPlayerChangeList = new SignalList();
    final SignalList onEquipmentChange = new SignalList();
    final SignalList onBlessing = new SignalList();
    private PointOfInterestChanges currentLocationChanges;

    public AdventurePlayer() {
        clear();
    }

    // ===================== Shop-type blueprints (2026-08-30) =====================

    /** Every shop type this player has learned. EMPTY means "legacy save - treat everything as
     *  unlocked"; see the field's own comment and EconomyBuildings.isShopTypeUnlocked(). */
    public Set<String> getUnlockedShopTypes() {
        return unlockedShopTypes;
    }

    public boolean hasShopTypeUnlocked(String shopName) {
        return shopName != null && unlockedShopTypes.contains(shopName);
    }

    /** Learns a shop type. Returns false if it was already known, so callers can refuse to charge
     *  for a no-op and drop-sources can fall through to another reward. */
    public boolean unlockShopType(String shopName, String source) {
        if (shopName == null || shopName.isEmpty() || !unlockedShopTypes.add(shopName))
            return false;
        System.out.println("[TFR-Blueprint] learned \"" + shopName + "\" via " + source
                + " - now knows " + unlockedShopTypes.size() + " shop type(s)");
        return true;
    }

    public String getStartingColorId() {
        return startingColorId;
    }

    /**
     * Seeds the starting shop types at character creation (user spec 2026-08-30): the chosen
     * color's COMMON trio (e.g. Black -> Black1/Black3/Black5, per config's
     * startingColorShopSuffixes) plus the race's two tribal shops from config's raceShops - 5 in
     * total. The even/uncommon trio and the plain {@code <Color>} rare shop are deliberately left
     * out; those are the blueprint ladder.
     * <p>
     * Color comes from the PICK, not from getColorIdentity(): this plane's constructed starter
     * decks are guild pairs, so the deck identity of a White pick is Azorius and would grant two
     * trios. A null color (Chaos/Precon/Custom, which all report White) simply skips the color
     * half rather than silently granting White's.
     */
    /**
     * The difficulty-scaled starting edition unlocks. Extracted from create() 2026-08-31 so New
     * Game+ can re-seed through the same code path instead of duplicating the table - a New Game+
     * previously carried the whole of the previous run's researched set into a fresh world, which
     * started Progressive Set Unlocks already finished.
     * <p>
     * Reads {@code this.difficultyData.name}, so the caller must have applied the chosen difficulty
     * first. Clears the set itself, making it a no-op for create() (which arrives from clear()).
     */
    private void seedStartingEditions(int race) {
        unlockedEditions.clear();
        if (Config.instance().getConfigData().editionProgressionEnabled) {
            String[] pool = null;
            String raceName = HeroListData.getRawRaceName(race);
            RaceEditionData[] raceEditions = Config.instance().getConfigData().raceEditions;
            if (raceName != null && raceEditions != null) {
                for (RaceEditionData entry : raceEditions) {
                    if (entry != null && raceName.equalsIgnoreCase(entry.race)
                            && entry.editions != null && entry.editions.length > 0) {
                        pool = entry.editions;
                        break;
                    }
                }
            }
            if (pool == null)
                pool = Config.instance().getConfigData().starterEditions;
            if (pool != null && pool.length > 0) {
                int[] startingUnlockCountByDifficultyIndex = {4, 3, 2, 1};
                DifficultyData[] allDifficulties = Config.instance().getConfigData().difficulties;
                int difficultyIndex = 1; // default to Normal-equivalent if not found
                if (allDifficulties != null) {
                    for (int i = 0; i < allDifficulties.length; i++) {
                        if (this.difficultyData.name.equals(allDifficulties[i].name)) {
                            difficultyIndex = i;
                            break;
                        }
                    }
                }
                int cappedIndex = Math.min(difficultyIndex, startingUnlockCountByDifficultyIndex.length - 1);
                java.util.List<String> shuffled = new java.util.ArrayList<>(java.util.Arrays.asList(pool));
                shuffled.remove("(All)"); // starterEditions carries this UI sentinel - not a set code
                java.util.Collections.shuffle(shuffled, MyRandom.getRandom());
                int startingUnlockCount = Math.min(shuffled.size(), startingUnlockCountByDifficultyIndex[cappedIndex]);
                for (int i = 0; i < startingUnlockCount; i++)
                    unlockedEditions.add(shuffled.get(i));
                // Diagnostic-only logging - greppable in forge.log as "[TFR-Research]".
                System.out.println("[TFR-Research] new game, race=" + raceName + ", difficulty="
                        + difficultyData.name + " -> starting unlocked editions: " + unlockedEditions);
            }
        }
    }

    private void seedStartingShopTypes(int raceIndex) {
        ConfigData config = Config.instance().getConfigData();
        if (config == null || !config.shopBlueprintsEnabled)
            return;
        // Self-contained so New Game+ can re-seed with the same code path. A no-op for create(),
        // which always arrives here from clear() with the set already empty.
        unlockedShopTypes.clear();

        if (startingColorId != null && !startingColorId.isEmpty()) {
            String colorName = colorNameForId(startingColorId);
            String[] suffixes = config.startingColorShopSuffixes;
            if (colorName != null && suffixes != null) {
                for (String suffix : suffixes)
                    unlockedShopTypes.add(colorName + suffix);
            }
        } else {
            // Permanent case, not just a legacy one: getStartingColorId() also returns null for
            // Chaos/Precon/CommanderPrecon/Custom, which all report a hardcoded White. Loud,
            // because "my color shops are missing" is otherwise invisible.
            System.out.println("[TFR-Blueprint] startingColorId is null or empty (save predates it, "
                    + "or a Chaos/Precon/Custom start) - seeding RACE tribal shops only, no color trio");
        }

        String raceName = forge.adventure.data.HeroListData.getRawRaceName(raceIndex);
        boolean raceMatched = false;
        if (raceName != null && config.raceShops != null) {
            for (forge.adventure.data.RaceShopData entry : config.raceShops) {
                if (entry == null || entry.race == null || !entry.race.equalsIgnoreCase(raceName))
                    continue;
                raceMatched = true;
                if (entry.shops != null)
                    unlockedShopTypes.addAll(Arrays.asList(entry.shops));
                break;
            }
        }
        // LOUD on an unmatched race, unlike raceEditions which silently falls through to a default
        // - a typo in a config race key would otherwise cost that race its whole tribal grant with
        // no visible symptom at all.
        if (!raceMatched && raceName != null)
            System.err.println("[TFR-Blueprint] no raceShops entry matches race \"" + raceName
                    + "\" - that race starts with no tribal shops. Check config.json raceShops keys.");

        System.out.println("[TFR-Blueprint] starting unlocks (color=" + startingColorId
                + ", race=" + raceName + "): " + new java.util.TreeSet<>(unlockedShopTypes));
    }

    /** "B" -> "Black" etc, matching the color-numbered shop naming in shops.json. Returns null
     *  for anything that is not one of the five single-letter mono colors. */
    private static String colorNameForId(String colorId) {
        if (colorId == null || colorId.length() != 1)
            return null;
        switch (Character.toUpperCase(colorId.charAt(0))) {
            case 'W': return "White";
            case 'U': return "Blue";
            case 'B': return "Black";
            case 'R': return "Red";
            case 'G': return "Green";
            default:  return null;
        }
    }

    /** Bronze Coin ante ransom (2026-08-29) - see coinRansomedEnemies. */
    public void payCoinRansom(String enemyName) {
        removeItem(BRONZE_COIN_ITEM);
        suppressDefeatGoldLoss = true;
        if (enemyName != null && !enemyName.isEmpty())
            coinRansomedEnemies.add(enemyName);
        System.out.println("[TFR-CoinRansom] paid a Bronze Challenge Coin to " + enemyName
                + " - ante recovered, defeat gold loss waived; marked for reclaim on a future win");
    }

    /**
     * Drops an unconsumed Bronze Coin gold-loss waiver (2026-09-01 release review).
     * <p>
     * Only one caller: a Capitol-defense loss ends the run through triggerCapitolDefeat() and
     * never reaches defeated(), so a coin paid on that duel left the flag armed. It survived into
     * the next loaded game in the same process and silently waived an unrelated defeat's gold
     * penalty. load() already clears the flag, so this only has to cover the in-process path.
     */
    public void clearSuppressDefeatGoldLoss() {
        suppressDefeatGoldLoss = false;
    }

    /** Does this enemy still hold a Bronze Coin the player paid them? */
    public boolean owesCoinRansom(String enemyName) {
        return enemyName != null && coinRansomedEnemies.contains(enemyName);
    }

    /** Beating a marked enemy returns the coin. Returns true if one was actually reclaimed.
     *  <p>
     *  Grants the item immediately and silently. As of 2026-09-01 this is only the FALLBACK for
     *  {@link #appendCoinRansomReward} - every ordinary win routes the coin through a loot tile
     *  instead, because a silent add is exactly what the user could not see happening
     *  ("I beat a snail and got my bronze coin back, so that worked. But there was nothing that
     *  told me i got it back"). Kept because losing the coin outright is far worse than showing
     *  it undramatically. */
    public boolean reclaimCoinRansom(String enemyName) {
        if (!owesCoinRansom(enemyName))
            return false;
        coinRansomedEnemies.remove(enemyName);
        addItem(BRONZE_COIN_ITEM);
        System.out.println("[TFR-CoinRansom] reclaimed a Bronze Challenge Coin from " + enemyName
                + " (direct grant - no loot screen on this path)");
        return true;
    }

    /**
     * Beating a marked enemy returns the coin AS LOOT (user request 2026-09-01): "We need to give
     * it as a reward. Part of the loot at the end of the battle... A little card with the bronze
     * coin on it". Appends a {@link Reward.Type#Item} tile for the Bronze Challenge Coin and
     * clears the mark; returns true if a coin was appended.
     * <p>
     * The grant now happens when the loot screen is dismissed rather than the instant the duel
     * ends - identical to how Gold, Shards, Cards and (since round 66) Wood/Stone have always
     * behaved, driven by {@code RewardScene.clearGenerated()} -> {@link #addReward}. Clearing the
     * mark and appending the tile happen together here so the two can never disagree.
     * <p>
     * If the item lookup ever fails (a renamed/removed items.json entry) this falls back to the
     * old direct grant rather than dropping the coin on the floor, and says so in the log. Losing
     * a coin the player is owed is a strictly worse failure than showing it without ceremony.
     *
     * @param rewards   the loot array being assembled for this win; nothing happens if null
     * @param enemyName the RAW enemy name (the same key the mark was written under - never the
     *                  tiered display name)
     */
    public boolean appendCoinRansomReward(Array<Reward> rewards, String enemyName) {
        if (!owesCoinRansom(enemyName))
            return false;
        ItemData coin = ItemListData.getItem(BRONZE_COIN_ITEM);
        if (rewards == null || coin == null) {
            System.out.println("[TFR-CoinRansom] cannot show " + BRONZE_COIN_ITEM
                    + " as loot (rewards=" + (rewards == null ? "null" : "ok")
                    + ", item=" + (coin == null ? "MISSING FROM items.json" : "ok")
                    + ") - falling back to a direct grant");
            return reclaimCoinRansom(enemyName);
        }
        coinRansomedEnemies.remove(enemyName);
        rewards.add(new Reward(coin));
        System.out.println("[TFR-CoinRansom] reclaimed a Bronze Challenge Coin from " + enemyName
                + " - added to this win's loot (granted when the reward screen is dismissed)");
        return true;
    }

    public static final String BRONZE_COIN_ITEM = "Bronze Challenge Coin";
    public static final String SILVER_COIN_ITEM = "Silver Challenge Coin";
    /** The gold coin's data name really is just "Challenge Coin" - no colour word (world/items.json). */
    public static final String GOLD_COIN_ITEM = "Challenge Coin";
    // The loadout every run is meant to begin with (user spec 2026-08-31): 1 gold, 1 silver,
    // 3 bronze. One coin per event format - gold a free draft, silver a free sealed, bronze a
    // free Jumpstart - plus the bronze surplus that doubles as ante ransom.
    private static final int START_GOLD_COINS = 1;
    private static final int START_SILVER_COINS = 1;
    private static final int START_BRONZE_COINS = 3;

    /**
     * Tops the challenge-coin purse back up to a full starting loadout, granting only what is
     * MISSING (user spec 2026-08-31: "If you don't have 1 Gold, 1 Silver and 3 Bronze, you should
     * be given the coins till you have that... so this way you always start with 1 Gold, 1 Silver
     * and 3 Bronze for a Game Start").
     * <p>
     * Top-up rather than grant-outright, so a player who hoarded coins through the previous run
     * keeps the surplus instead of having it clipped back to three. Additive and idempotent -
     * running it twice grants nothing the second time.
     * <p>
     * Called on the New Game+ path only. An ordinary New Game already arrives at this loadout
     * through the intro quest's own coin grant.
     */
    public void topUpChallengeCoins() {
        grantMissingCoins(GOLD_COIN_ITEM, START_GOLD_COINS);
        grantMissingCoins(SILVER_COIN_ITEM, START_SILVER_COINS);
        grantMissingCoins(BRONZE_COIN_ITEM, START_BRONZE_COINS);
    }

    private void grantMissingCoins(String itemName, int target) {
        int have = countItem(itemName);
        int missing = target - have;
        if (missing <= 0) {
            System.out.println("[TFR-NewGamePlus] " + itemName + ": have " + have + "/" + target
                    + " - nothing to grant");
            return;
        }
        for (int i = 0; i < missing; i++)
            addItem(itemName);
        System.out.println("[TFR-NewGamePlus] " + itemName + ": had " + have + ", granted "
                + missing + " -> " + countItem(itemName) + "/" + target);
    }

    public PlayerStatistic getStatistic() {
        return statistic;
    }

    public int getDeckCount() { return decks.size(); }

    public int getMaxDeckCount() { return maxDeckCount; }

    private void clearDecks() {
        decks.clear();
        for (int i = 0; i < MIN_DECK_COUNT; i++)
            decks.add(new Deck(Forge.getLocalizer().getMessage("lblEmptyDeck")));
        deck = decks.get(0);
        selectedDeckIndex = 0;
    }

    private void clear() {
        //Ensure sensitive gameplay data is properly reset between games.
        //Reset all properties HERE.
        fantasyMode = false;
        announceFantasy = false;
        usingCustomDeck = false;
        adventureMode = null;
        blessing = null;
        partnerOverhealActive = false;
        payGuardsFromBankFirst = true;
        goldMineDepositsToBankDirectly = true;
        gold = 0;
        maxLife = 20;
        life = 20;
        shards = 0;
        wood = 0;
        stone = 0;
        townLifeBonus = 0;
        ringLifeBonus = 0;
        colorReputationHalfPoints.clear();
        maxDeckCount = 20;
        clearDecks();
        inventoryItems.clear();
        boostersOwned.clear();
        equippedItems.clear();
        deckLoadouts.clear();
        characterFlags.clear();
        questFlags.clear();
        quests.clear();
        events.clear();
        cards.clear();
        statistic.clear();
        newCards.clear();
        autoSellCards.clear();
        favoriteCards.clear();
        AdventureEventController.clear();
        AdventureQuestController.clear();
        unsupportedCards.clear();
        unlockedEditions.clear();
        coinRansomedEnemies.clear();
        unlockedShopTypes.clear();
        startingColorId = null;
        suppressDefeatGoldLoss = false;
        researchEditionInProgress = null;
        researchInProgress.clear();
        researchStartDay = -1;
    }

    static public AdventurePlayer current() {
        return WorldSave.getCurrentSave().getPlayer();
    }

    private final CardPool cards = new CardPool();

    public final ItemPool<PaperCard> newCards = new ItemPool<>(PaperCard.class);
    public final ItemPool<PaperCard> autoSellCards = new ItemPool<>(PaperCard.class);
    public final Set<PaperCard> favoriteCards = new HashSet<>();

    // Progressive Set Unlocks (MOD_SCOPE.md #4, editionProgressionEnabled) - editions THIS save's
    // own shops (Orazca/owned towns) can sell, separate from World.colorEditionShards (the AI/
    // world side - permanent per-color assignment, unaffected by player research). Starts
    // pre-seeded with difficulty-scaled core sets (see EditionProgression), grows via research at
    // the Lab. researchEditionInProgress/researchStartDay mirror the Archaeologist's single-timer
    // pattern - one edition being researched at a time, null/-1 = none active.
    private final Set<String> unlockedEditions = new HashSet<>();
    private String researchEditionInProgress = null;
    // 2026-09-03 (user spec): several editions can be researched at once, each on its own timer
    // counted from its own start day. Insertion-ordered so the Lab lists them in start order.
    // researchEditionInProgress/researchStartDay above survive only to read pre-round-88 saves.
    private final java.util.LinkedHashMap<String, Integer> researchInProgress = new java.util.LinkedHashMap<>();
    private int researchStartDay = -1;

    // Bronze Coin ante ransom (user spec 2026-08-29). Enemy NAMES the player has bought their
    // ante back from with a Bronze Challenge Coin; beating an enemy of that name later reclaims
    // the coin (user decision: on DEFEATING them, not merely meeting them again). Keyed by name
    // rather than instance for the same reason PlayerStatistic's win/loss record is - roaming
    // enemies are catalog entries respawned freely, so a name is the only durable identity.
    // Persisted with the same shape unlockedEditions above uses (stored as an ArrayList).
    private final Set<String> coinRansomedEnemies = new HashSet<>();
    // Shop-type blueprints (user spec 2026-08-30): the card shop TYPES this player has learned.
    // Seeded at character creation from the chosen color (its common trio) plus the race's two
    // tribal shops - 5 total - then grown by buying blueprints in AI shops and by rare drops.
    // Same persistence shape as coinRansomedEnemies/unlockedEditions (stored as an ArrayList).
    //
    // IMPORTANT - EMPTY MEANS "ALL UNLOCKED", NOT "NONE" (user decision 2026-08-30, feature is
    // New Game only). A save made before this feature loads with an empty set; treating that as
    // "nothing unlocked" would lock the chooser down to nothing on an existing playthrough, which
    // both kills the Re-assign button and can make a destroyed shop UNREBUILDABLE (the rebuild
    // menu's card-shop branch disappears when every tier filters empty). A new game always seeds
    // 5 types, so a legitimately-empty set only ever means "legacy save" - see
    // EconomyBuildings.isShopTypeUnlocked().
    private final Set<String> unlockedShopTypes = new HashSet<>();
    // The color picked on the new-game screen, e.g. "W"/"U"/"B"/"R"/"G", or null when the mode
    // does not really have one (Chaos/Precon/CommanderPrecon/Custom all report White otherwise).
    // Stored because NOTHING else preserves it: NewGameScene hands a ColorSet to
    // WorldSave.generateNewWorld(), which uses it ONLY to pick the starter deck and then discards
    // it. AdventurePlayer.colorIdentity is NOT a substitute - it is derived from the starter DECK,
    // and this plane's constructed starters are guild PAIRS (pick White, get Azorius), so it would
    // report two colors and hand out two trios.
    private String startingColorId = null;
    // Runtime-only, deliberately NOT saved: set when the ransom is paid, consumed by the very
    // next defeated() call a few frames later (the ante popup resolves before WorldStage/
    // MapStage.setWinner() runs). Nothing should carry it across a save/load.
    private transient boolean suppressDefeatGoldLoss = false;

    public void create(String n, Deck startingDeck, boolean male, int race, int avatar, boolean isFantasy,
                       boolean isUsingCustomDeck, DifficultyData difficultyData, AdventureModes adventureMode,
                       String startingColorId) {
        clear();
        // Set AFTER clear() (which nulls it) and BEFORE seedStartingShopTypes() below reads it.
        this.startingColorId = startingColorId;
        this.adventureMode = adventureMode;
        announceFantasy = fantasyMode = isFantasy; //Set Chaos mode first.
        announceCustom = usingCustomDeck = isUsingCustomDeck;

        this.maxDeckCount = Config.instance().getConfigData().maxNumberOfDecks; // Get the MAX_DECK_COUNT from the config file
        // Sanity Check make sure the number is not insane and make sure it is at least 20
        this.maxDeckCount = Math.max(Math.min(this.maxDeckCount, 99), 20);

        clearDecks(); // Reset the empty decks to now already have the commander in the command zone.
        deck = startingDeck;
        decks.set(0, deck);

        cards.addAllFlat(deck.getAllCardsInASinglePool(true, true).toFlatList());

        this.difficultyData.startingLife = difficultyData.startingLife;
        this.difficultyData.startingMoney = difficultyData.startingMoney;
        this.difficultyData.startingDifficulty = difficultyData.startingDifficulty;
        this.difficultyData.name = difficultyData.name;
        this.difficultyData.spawnRank = difficultyData.spawnRank;
        this.difficultyData.enemyLifeFactor = difficultyData.enemyLifeFactor;
        this.difficultyData.sellFactor = difficultyData.sellFactor;
        this.difficultyData.shardSellRatio = difficultyData.shardSellRatio;
        this.difficultyData.goldLoss = difficultyData.goldLoss;
        this.difficultyData.lifeLoss = difficultyData.lifeLoss;

        gold = ringGiftStart() ? 0 : difficultyData.startingMoney; // round 101: the Ring Cities hand the kit over
        name = n;
        heroRace = race;
        avatarIndex = avatar;
        isFemale = !male;

        setColorIdentity(DeckProxy.getColorIdentity(deck));
        // MOD_SCOPE.md #1: the chosen starter deck seeds the player's color reputation (+10 per
        // deck color, +5 its allies, -10 its enemies - zero-sum, no-op when the feature's config
        // flag is off or the deck is colorless). Deliberately here in create() only, NOT near the
        // other setColorIdentity() call sites - identity can change later (custom decks, dialog
        // actions), but the starting bonus is a one-time new-game seed.
        ColorReputation.applyStartingDeckBonus(colorIdentity);

        life = maxLife = difficultyData.startingLife;
        shards = ringGiftStart() ? 0 : difficultyData.startingShards;
        wood = ringGiftStart() ? 0 : difficultyData.startingWood;
        stone = ringGiftStart() ? 0 : difficultyData.startingStone;

        // Progressive Set Unlocks (MOD_SCOPE.md #4): difficulty-scaled starting unlocked
        // editions - Easy 4, Normal 3, Hard 2, Insane 1. Race-driven (user spec 2026-08-12,
        // "your starting race you pick has no effect... assign each race a unique expansion"):
        // the chosen race's 4 lore-assigned editions (ConfigData.raceEditions, keyed by the RAW
        // heroes.json race name) are the pool, and below-Easy difficulties get a RANDOM pick of
        // N from them - two Hard runs as the same race can start with different sets. Races
        // without an entry (or planes without the array) fall back to the flat starterEditions
        // list, picked randomly too for consistency. Same difficulty-index-lookup pattern
        // EconomyBuildings.difficultyPriceMultiplier() already uses.
        seedStartingEditions(race);

        // Shop-type blueprints (2026-08-30): 3 color shops + 2 race tribal shops. Placed here,
        // after heroRace is set above, since the race grant is keyed off it.
        seedStartingShopTypes(race);

        for (String s : (ringGiftStart() ? new String[0] : difficultyData.startItems)) { // round 101: Llanowar hands the kit over
            ItemData i = ItemListData.getItem(s);
            if (i == null)
                continue;
            inventoryItems.add(i);
        }

        onGoldChangeList.emit();
        onLifeTotalChangeList.emit();
        onShardsChangeList.emit();
        // Missing since Wood/Stone starting grants were added (2026-08-15 bug report: "did NOT
        // get the starting resources") - ResourceDisplayActor seeds its labels once from
        // GameHUD's own process-lifetime singleton construction, then only ever updates via these
        // signals; without them a fresh character's real (correctly-granted) wood/stone value
        // never reaches the HUD if that singleton was already built (e.g. testing a 2nd
        // character in the same session).
        onWoodChangeList.emit();
        onStoneChangeList.emit();
    }

    public void setSelectedDeckSlot(int slot) {
        setSelectedDeckSlot(slot, true);
    }

    public void setSelectedDeckSlot(int slot, boolean switchLoadout) {
        if (slot >= 0 && slot < getDeckCount()) {
            boolean bindLoadouts = Config.instance().getSettingData().bindEquipmentLoadoutsToDecks;
            if (switchLoadout && bindLoadouts && slot != selectedDeckIndex) {
                // Save current loadout to old deck
                ensureDeckLoadoutsSize();
                deckLoadouts.set(selectedDeckIndex, new HashMap<>(equippedItems));

                // Clear current equipment
                for (ItemData item : inventoryItems) {
                    if (item != null) {
                        item.isEquipped = false;
                    }
                }
                equippedItems.clear();

                // Restore loadout for new deck (if any)
                HashMap<String, Long> newLoadout = deckLoadouts.get(slot);
                if (newLoadout != null) {
                    for (Map.Entry<String, Long> entry : newLoadout.entrySet()) {
                        ItemData item = getItemFromInventory(entry.getValue());
                        if (item != null) {
                            item.isEquipped = true;
                            equippedItems.put(entry.getKey(), entry.getValue());
                        }
                    }
                }

                onEquipmentChange.emit();
            }

            selectedDeckIndex = slot;
            deck = decks.get(selectedDeckIndex);
            setColorIdentity(DeckProxy.getColorIdentity(deck));
        }
    }

    /**
     * Makes New Game+ mean what the player expects it to mean (user spec 2026-08-31: "a NG+ should
     * basically be a new game, + your Cards, Equipment and resources").
     * <p>
     * New Game gets its per-run reset from clear() + create(). New Game+ deliberately calls
     * NEITHER - it loads an existing save and keeps the collection - so every per-run field that
     * is not carried progression was silently inheriting the previous run. The reported symptom
     * was the shop blueprints: unlockedShopTypes was never re-seeded, and on a save written before
     * that feature existed the empty set trips isShopTypeUnlocked()'s "legacy save = everything
     * unlocked" escape hatch, so the whole mechanic looked switched off.
     * <p>
     * <b>Do NOT simplify this into a clear() or create() call.</b> clear() wipes cards, decks,
     * inventory, equipment, boosters and every resource; it is survivable inside load() only
     * because load() immediately repopulates from the save file. Called from the NG+ path it would
     * destroy the run the player is trying to carry forward. Every reset here is therefore
     * deliberate and individually chosen.
     * <p>
     * Must be called AFTER updateDifficulty(), because the edition seed is difficulty-scaled, and
     * BEFORE EditionProgression.reservePlayerEditions(), which reads the re-seeded set.
     * <p>
     * One caveat worth knowing: clearing {@code events} discards an in-progress draft/sealed
     * tournament, including cards drafted but not yet banked. A New Game already accepts this, and
     * the entries point at POIs the regenerated world no longer has, so keeping them is not an
     * option - but starting a New Game+ mid-tournament does cost that draft.
     */
    public void resetForNewGamePlus() {
        System.out.println("[TFR-NewGamePlus] reset begin - race="
                + forge.adventure.data.HeroListData.getRawRaceName(heroRace)
                + ", startingColorId=" + startingColorId
                + ", difficulty=" + difficultyData.name
                + " | CARRYING " + cards.countAll() + " cards, " + decks.size() + " decks, "
                + inventoryItems.size() + " items, " + gold + " gold, " + shards + " shards, "
                + wood + " wood, " + stone + " stone");

        // ---- narrative / progression bookkeeping -------------------------------------------
        quests.clear();
        questFlags.clear();
        // Before the newGamePlus flag below, or quest 28's "Been here, done that (New Game+)"
        // branch never fires. Also clears one-shot grant flags like firstArmoryTorchGranted, which
        // otherwise deny the new run its first Armory torch forever.
        characterFlags.clear();
        events.clear();
        AdventureQuestController.clear();
        AdventureEventController.clear();
        statistic.clear();
        setCharacterFlag("newGamePlus", 1);

        // ---- per-run combat / buff state ----------------------------------------------------
        blessing = null;
        partnerOverhealActive = false;
        // Bronze Coin ante marks are keyed by enemy NAME, and every one of those names still
        // exists in the new world's catalog - so carrying them would hand out free coins for
        // enemies this run never took one from.
        coinRansomedEnemies.clear();

        // ---- gates a New Game re-rolls ------------------------------------------------------
        // The five values must sum to zero (see the field's own comment), so clear-then-reseed
        // through ColorReputation rather than zeroing entries by hand.
        colorReputationHalfPoints.clear();
        ColorReputation.applyStartingDeckBonus(colorIdentity);
        // A research timer in flight would survive into a world whose day counter is back to 1,
        // leaving a negative "days remaining" and every research button disabled for months.
        clearResearch();
        seedStartingEditions(heroRace);
        seedStartingShopTypes(heroRace);

        // ONE line that proves or disproves the whole reset (user request 2026-09-02: verifying
        // New Game+ by playing it is slow and easy to get wrong, so make it greppable instead).
        // Every field the round-74 audit found leaking is printed with its POST-reset value, so a
        // single grep answers "did NG+ actually start a new run". Anything non-zero/non-empty in
        // the "should be clear" group is a leak from the previous run.
        System.out.println("[TFR-NewGamePlus] reset done"
                + " | RESEEDED shopTypes(" + unlockedShopTypes.size() + ")=" + new java.util.TreeSet<>(unlockedShopTypes)
                + " editions(" + unlockedEditions.size() + ")=" + new java.util.TreeSet<>(unlockedEditions)
                + " colorRepEntries=" + colorReputationHalfPoints.size() + "(reseeded from the deck)"
                + " | CLEARED characterFlags=" + characterFlags.size() + "(expect 1: newGamePlus)"
                + " events=" + events.size()
                + " coinRansomMarks=" + coinRansomedEnemies.size()
                + " blessing=" + (blessing == null ? "null" : "SET(LEAK)")
                + " partnerOverheal=" + partnerOverhealActive
                + " | difficulty=" + difficultyData.name
                + " rewardMaxFactor=" + difficultyData.rewardMaxFactor
                + " startingLife=" + difficultyData.startingLife);
    }

    public void updateDifficulty(DifficultyData diff) {
        // New Game+ bug fix (2026-08-25 user report: Insane NG+ started at 7 life instead of the
        // expected 9): New Game+ regenerates the world (town/Capitol ownership wiped by
        // WorldSave.clearChanges()+World.generateNew() in SaveLoadScene's NewGamePlus flow) but
        // this method is the only per-playthrough reset NG+ runs on the player object - it must
        // also clear the carried-over town-count life bonus (townLifeBonus), or the next
        // TownRestoration.updateTownLifeBonus() call (first town restored/Capitol founded/town
        // lost in the new world) silently subtracts the OLD playthrough's stale bonus from
        // maxLife/life. Mirrors the reset clear() already does for a fresh New Game.
        townLifeBonus = 0;
        ringLifeBonus = 0;
        System.out.println("[TFR-NGPlusLife] updateDifficulty: reset townLifeBonus to 0, starting life now " + diff.startingLife);
        int lb = life, mb = maxLife;
        maxLife = diff.startingLife;
        logLife("updateDifficulty", lb, mb);
        this.difficultyData.startingShards = diff.startingShards;
        this.difficultyData.startingLife = diff.startingLife;
        this.difficultyData.startingMoney = diff.startingMoney;
        this.difficultyData.startingDifficulty = diff.startingDifficulty;
        this.difficultyData.name = diff.name;
        this.difficultyData.spawnRank = diff.spawnRank;
        this.difficultyData.enemyLifeFactor = diff.enemyLifeFactor;
        this.difficultyData.sellFactor = diff.sellFactor;
        this.difficultyData.shardSellRatio = diff.shardSellRatio;
        this.difficultyData.goldLoss = diff.goldLoss;
        this.difficultyData.lifeLoss = diff.lifeLoss;
        // rewardMaxFactor was the one field this method forgot (found 2026-08-31 in the New Game+
        // audit). It drives RewardData's random loot count and is shown on the new-game screen as
        // "Random loot rate", so a player who picked Insane for a New Game+ off an Easy save kept
        // Easy's loot rate for the whole run.
        this.difficultyData.rewardMaxFactor = diff.rewardMaxFactor;
        resetToMaxLife();
    }

    //Getters
    public int getSelectedDeckIndex() {
        return selectedDeckIndex;
    }

    public DifficultyData getDifficultyData() {
        return difficultyData;
    }

    public Deck getSelectedDeck() {
        return deck;
    }

    public ArrayList<ItemData> getItems() {
        return inventoryItems;
    }

    public ItemData getItemFromInventory(Long id) {
        if (id == null)
            return null;
        for (ItemData data : inventoryItems) {
            if (data == null)
                continue;
            if (id.equals(data.longID))
                return data;
        }
        return null;
    }

    public ItemData getEquippedItem(Long id) {
        if (id == null)
            return null;
        for (ItemData data : inventoryItems) {
            if (data == null)
                continue;
            if (id.equals(data.longID) && data.isEquipped)
                return data;
        }
        return null;
    }

    public Array<Deck> getBoostersOwned() {
        return boostersOwned;
    }

    public Deck getDeck(int index) {
        return decks.get(index);
    }

    public CardPool getCards() {
        return cards;
    }

    public String getName() {
        return name;
    }

    public Boolean isFemale() {
        return isFemale;
    }
    
    public float getWorldPosX() {
        return worldPosX;
    }

    public float getWorldPosY() {
        return worldPosY;
    }

    public int getGold() {
        return gold;
    }

    /** [TFR-Life] diagnostic (user report 2026-09-02: life total wrong after a lost fight + Load).
     *  One line per life/maxLife mutation with before -> after, so forge.log alone can reconstruct
     *  the total. Standing [TFR-*] rule: hard-to-observe mechanics log as they are built. */
    private void logLife(String reason, int lifeBefore, int maxBefore) {
        System.out.println("[TFR-Life] " + reason + ": " + lifeBefore + "/" + maxBefore + " -> " + life + "/" + maxLife
                + " (townLifeBonus=" + townLifeBonus + ", partnerOverheal=" + partnerOverhealActive + ")");
    }

    public int getLife() {
        return life;
    }

    public AdventureModes getAdventureMode(){
        return adventureMode;
    }

    public boolean isCommanderMode() {
        return adventureMode != null && adventureMode.isCommanderLike();
    }

    public int getMaxLife() {
        return maxLife;
    }

    public int getShards() {
        return shards;
    }

    public int getWood() {
        return wood;
    }

    public int getStone() {
        return stone;
    }

    public @Null EffectData getBlessing() {
        return blessing;
    }

    public Collection<Long> getEquippedItems() {
        return equippedItems.values();
    }

    public ColorSet getColorIdentity() {
        return colorIdentity;
    }

    public String getColorIdentityLong() {
        return colorIdentity.toString();
    }

    public Collection<PaperCard> getUnsupportedCards() {
        return unsupportedCards;
    }


    //Setters
    public void setWorldPosX(float worldPosX) {
        this.worldPosX = worldPosX;
    }

    public void setWorldPosY(float worldPosY) {
        this.worldPosY = worldPosY;
    }

    public void setColorIdentity(String C) {
        colorIdentity = ColorSet.fromNames(C.toCharArray());
    }

    public void setColorIdentity(ColorSet set) {
        this.colorIdentity = set;
    }

    @Override
    public void load(SaveFileData data) {
        boolean migration = false;
        clear(); // Reset player data.
        this.statistic.load(data.readSubData("statistic"));
        this.difficultyData.startingLife = data.readInt("startingLife");
        // Support for old typo
        if (data.containsKey("staringMoney")) {
            this.difficultyData.startingMoney = data.readInt("staringMoney");
        } else {
            this.difficultyData.startingMoney = data.readInt("startingMoney");
        }
        this.difficultyData.startingDifficulty = data.readBool("startingDifficulty");
        this.difficultyData.name = data.readString("difficultyName");
        this.difficultyData.enemyLifeFactor = data.readFloat("enemyLifeFactor");
        this.difficultyData.sellFactor = data.readFloat("sellFactor");
        if (this.difficultyData.sellFactor == 0)
            this.difficultyData.sellFactor = 0.2f;

        //BEGIN SPECIAL CASES
        //Previously these were not being read from or written to save files, causing defaults to appear after reload
        //Pull from config if appropriate
        DifficultyData configuredDifficulty = null;
        for (DifficultyData candidate : Config.instance().getConfigData().difficulties) {
            if (candidate.name.equals(this.difficultyData.name)) {
                configuredDifficulty = candidate;
                break;
            }
        }

        if (configuredDifficulty != null && (this.difficultyData.shardSellRatio == data.readFloat("shardSellRatio") || data.readFloat("shardSellRatio") == 0))
            this.difficultyData.shardSellRatio = configuredDifficulty.shardSellRatio;
        else
            this.difficultyData.shardSellRatio = data.readFloat("shardSellRatio");
        if (configuredDifficulty != null && !data.containsKey("goldLoss"))
            this.difficultyData.goldLoss = configuredDifficulty.goldLoss;
        else
            this.difficultyData.goldLoss = data.readFloat("goldLoss");
        if (configuredDifficulty != null && !data.containsKey("lifeLoss"))
            this.difficultyData.lifeLoss = configuredDifficulty.lifeLoss;
        else
            this.difficultyData.lifeLoss = data.readFloat("lifeLoss");
        if (configuredDifficulty != null && !data.containsKey("spawnRank"))
            this.difficultyData.spawnRank = configuredDifficulty.spawnRank;
        else
            this.difficultyData.spawnRank = data.readInt("spawnRank");
        if (configuredDifficulty != null && !data.containsKey("rewardMaxFactor"))
            this.difficultyData.rewardMaxFactor = configuredDifficulty.rewardMaxFactor;
        else
            this.difficultyData.rewardMaxFactor = data.readFloat("rewardMaxFactor");
        // END SPECIAL CASES

        name = data.readString("name");
        heroRace = data.readInt("heroRace");
        avatarIndex = data.readInt("avatarIndex");
        isFemale = data.readBool("isFemale");

        String _mode = data.readString("adventure_mode");
        if (_mode == null)
            adventureMode = AdventureModes.Standard;
        else
            adventureMode = AdventureModes.valueOf(_mode);

        if (data.containsKey("colorIdentity")) {
            String temp = data.readString("colorIdentity");
            if (temp != null)
                setColorIdentity(temp);
            else
                colorIdentity = ColorSet.WUBRG;
        } else
            colorIdentity = ColorSet.WUBRG;

        gold = data.readInt("gold");
        maxLife = data.readInt("maxLife");
        life = data.readInt("life");
        shards = data.containsKey("shards") ? data.readInt("shards") : 0;
        wood = data.containsKey("wood") ? data.readInt("wood") : 0;
        stone = data.containsKey("stone") ? data.readInt("stone") : 0;
        townLifeBonus = data.containsKey("townLifeBonus") ? data.readInt("townLifeBonus") : 0;
        System.out.println("[TFR-Life] load: " + life + "/" + maxLife + " (townLifeBonus=" + townLifeBonus + ")");
        partnerOverhealActive = data.containsKey("partnerOverhealActive") && data.readBool("partnerOverhealActive");
        // Default true for saves predating this feature (2026-08-13) - inverted containsKey guard.
        payGuardsFromBankFirst = !data.containsKey("payGuardsFromBankFirst") || data.readBool("payGuardsFromBankFirst");
        goldMineDepositsToBankDirectly = !data.containsKey("goldMineDepositsToBankDirectly") || data.readBool("goldMineDepositsToBankDirectly");
        colorReputationHalfPoints.clear();
        if (data.containsKey("colorReputationHalfPoints")) {
            Object obj = data.readObject("colorReputationHalfPoints");
            if (obj instanceof Map)
                colorReputationHalfPoints.putAll((Map<String, Integer>) obj);
        }
        worldPosX = data.readFloat("worldPosX");
        worldPosY = data.readFloat("worldPosY");

        if (data.containsKey("blessing")) {
            EffectData temp = (EffectData) data.readObject("blessing");
            if (temp != null)
                blessing = temp;
        }

        if (data.containsKey("inventory")) {
            try {
                ItemData[] inv = (ItemData[]) data.readObject("inventory");
                for (int i = 0; i < inv.length; i++) {
                    ItemData itemData = inv[i];
                    if (itemData != null) {
                        inventoryItems.add(itemData);
                    }
                }
            } catch (Exception ignored) {
                migration = true;
                // migrate from string..
                try {
                    String[] inv = (String[]) data.readObject("inventory");
                    // Prevent items with wrong names from getting through. Hell breaks loose if it causes null pointers.
                    // This only needs to be done on load.
                    for (int j = 0; j < inv.length; j++) {
                        String i = inv[j];
                        ItemData itemData = ItemListData.getItem(i);
                        if (itemData != null) {
                            inventoryItems.add(itemData);
                        } else {
                            System.err.printf("Cannot find item name %s\n", i);
                            // Allow official© permission for the player to get a refund. We will allow it this time.
                            // TODO: Divine retribution if the player refunds too much. Use the orbital laser cannon.
                            System.out.println("Developers have blessed you! You are allowed to cheat the cost of the item back!");
                        }
                    }
                } catch (Exception e) {
                    //shouldn't crash if coming from string...
                    e.printStackTrace();
                }
            }
        }
        if (data.containsKey("equippedSlots") && data.containsKey("equippedItems")) {
            try {
                String[] slots = (String[]) data.readObject("equippedSlots");
                Long[] items = (Long[]) data.readObject("equippedItems");

                assert (slots.length == items.length);
                // Prevent items with wrong names. If it triggered in inventory, it'll trigger here as well.
                for (int i = 0; i < slots.length; i++) {
                    ItemData itemData = getItemFromInventory(items[i]);
                    if (itemData != null) {
                        if (itemData.longID == null)
                            itemData = itemData.clone();
                        if (itemData.longID != null) {
                            itemData.isEquipped = true;
                            equippedItems.put(slots[i], itemData.longID);
                        } else {
                            itemData.isEquipped = false;
                            System.err.println("Missing ID: " + itemData.name);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        if (data.containsKey("boosters")) {
            Deck[] decks = (Deck[]) data.readObject("boosters");
            if (decks != null) {
                for (Deck d : decks) {
                    if (d != null && !d.isEmpty()) {
                        boostersOwned.add(d);
                    } else {
                        System.err.printf("Null or empty booster %s\n", d);
                        System.out.println("You have an empty booster pack in your inventory.");
                    }
                }
            } else {
                System.err.println("Deck[] is null! [boosters]");
            }
        }

        deck = new Deck(data.readString("deckName"));
        CardPool deckCards = CardPool.fromCardList(Lists.newArrayList((String[]) data.readObject("deckCards")));
        deck.getMain().addAll(deckCards.getFilteredPool(isValid));
        unsupportedCards.addAll(deckCards.getFilteredPool(isUnsupported).toFlatList());
        if (data.containsKey("sideBoardCards")) {
            CardPool sideBoardCards = CardPool.fromCardList(Lists.newArrayList((String[]) data.readObject("sideBoardCards")));
            deck.getOrCreate(DeckSection.Sideboard).addAll(sideBoardCards.getFilteredPool(isValid));
            unsupportedCards.addAll(sideBoardCards.getFilteredPool(isUnsupported).toFlatList());
        }
        if (data.containsKey("attractionDeckCards")) {
            CardPool attractionDeckCards = CardPool.fromCardList(List.of((String[]) data.readObject("attractionDeckCards")));
            deck.getOrCreate(DeckSection.Attractions).addAll(attractionDeckCards.getFilteredPool(isValid));
            unsupportedCards.addAll(attractionDeckCards.getFilteredPool(isUnsupported).toFlatList());
        }
        if (data.containsKey("contraptionDeckCards")) {//TODO: Generalize this. Can't we just serialize the whole deck?
            CardPool contraptionDeckCards = CardPool.fromCardList(List.of((String[]) data.readObject("contraptionDeckCards")));
            deck.getOrCreate(DeckSection.Contraptions).addAll(contraptionDeckCards.getFilteredPool(isValid));
            unsupportedCards.addAll(contraptionDeckCards.getFilteredPool(isUnsupported).toFlatList());
        }
        if (data.containsKey("commanderCards")) {
            CardPool commanderCards = CardPool.fromCardList(List.of((String[]) data.readObject("commanderCards")));
            deck.getOrCreate(DeckSection.Commander).addAll(commanderCards.getFilteredPool(isValid));
            unsupportedCards.addAll(commanderCards.getFilteredPool(isUnsupported).toFlatList());
        }
        if (data.containsKey("characterFlagsKey") && data.containsKey("characterFlagsValue")) {
            String[] keys = (String[]) data.readObject("characterFlagsKey");
            Byte[] values = (Byte[]) data.readObject("characterFlagsValue");
            assert (keys.length == values.length);
            for (int i = 0; i < keys.length; i++) {
                characterFlags.put(keys[i], values[i]);
            }
        }

        if (data.containsKey("questFlagsKey") && data.containsKey("questFlagsValue")) {
            String[] keys = (String[]) data.readObject("questFlagsKey");
            Byte[] values = (Byte[]) data.readObject("questFlagsValue");
            assert (keys.length == values.length);
            for (int i = 0; i < keys.length; i++) {
                questFlags.put(keys[i], values[i]);
            }
        }
        if (data.containsKey("quests")) {
            quests.clear();
            Object[] q = (Object[]) data.readObject("quests");
            if (q != null) {
                for (Object itsReallyAQuest : q)
                    quests.add((AdventureQuestData) itsReallyAQuest);
            }
        }
        if (data.containsKey("events")) {
            events.clear();
            Object[] q = (Object[]) data.readObject("events");
            if (q != null) {
                for (Object itsReallyAnEvent : q) {
                    events.add((AdventureEventData) itsReallyAnEvent);
                }
            }
        }

        // Set max deck count to either the value in the config or the current player deck count and then ensure it is bound by 20 and 99
        this.maxDeckCount = Math.min(Math.max(Math.max(data.containsKey("deckCount") ? data.readInt("deckCount") : 20, Config.instance().getConfigData().maxNumberOfDecks), 20), 99);


        // Load decks
        // Check if this save has dynamic deck count, use set-count load if not
        boolean hasDynamicDeckCount = data.containsKey("deckCount");
        if (hasDynamicDeckCount) {
            int dynamicDeckCount = data.readInt("deckCount");
            // In case the save had previously saved more decks than the current version allows (in case of the max being lowered)
            dynamicDeckCount = Math.min(maxDeckCount, dynamicDeckCount);
            for (int i = 0; i < dynamicDeckCount; i++){
                // The first x elements are pre-created
                if (i < MIN_DECK_COUNT) {
                    decks.set(i, new Deck(data.readString("deck_name_" + i)));
                }
                else {
                    decks.add(new Deck(data.readString("deck_name_" + i)));
                }
                CardPool mainCards = CardPool.fromCardList(Lists.newArrayList((String[]) data.readObject("deck_" + i)));
                decks.get(i).getMain().addAll(mainCards.getFilteredPool(isValid));
                unsupportedCards.addAll(mainCards.getFilteredPool(isUnsupported).toFlatList());
                if (data.containsKey("sideBoardCards_" + i)) {
                    CardPool sideBoardCards = CardPool.fromCardList(Lists.newArrayList((String[]) data.readObject("sideBoardCards_" + i)));
                    decks.get(i).getOrCreate(DeckSection.Sideboard).addAll(sideBoardCards.getFilteredPool(isValid));
                    unsupportedCards.addAll(sideBoardCards.getFilteredPool(isUnsupported).toFlatList());
                }
                if (data.containsKey("attractionDeckCards_" + i)) {
                    CardPool attractionCards = CardPool.fromCardList(Lists.newArrayList((String[]) data.readObject("attractionDeckCards_" + i)));
                    decks.get(i).getOrCreate(DeckSection.Attractions).addAll(attractionCards.getFilteredPool(isValid));
                    unsupportedCards.addAll(attractionCards.getFilteredPool(isUnsupported).toFlatList());
                }
                if (data.containsKey("contraptionDeckCards_" + i)) {
                    CardPool contraptionCards = CardPool.fromCardList(Lists.newArrayList((String[]) data.readObject("contraptionDeckCards_" + i)));
                    decks.get(i).getOrCreate(DeckSection.Contraptions).addAll(contraptionCards.getFilteredPool(isValid));
                    unsupportedCards.addAll(contraptionCards.getFilteredPool(isUnsupported).toFlatList());
                }
                if (data.containsKey("commanderCards_" + i)) {
                    CardPool commanderCards = CardPool.fromCardList(List.of((String[]) data.readObject("commanderCards_" + i)));
                    decks.get(i).getOrCreate(DeckSection.Commander).addAll(commanderCards.getFilteredPool(isValid));
                    unsupportedCards.addAll(commanderCards.getFilteredPool(isUnsupported).toFlatList());
                }
            }
            // In case we allow removing decks from the deck selection GUI, populate up to the minimum
            for (int i = dynamicDeckCount++; i < MIN_DECK_COUNT; i++) {
                decks.set(i, new Deck(Forge.getLocalizer().getMessage("lblEmptyDeck")));
            }
        // Legacy load
        } else {
            for (int i = 0; i < MIN_DECK_COUNT; i++) {
                if (!data.containsKey("deck_name_" + i)) {
                    if (i == 0) decks.set(i, deck);
                    else decks.set(i, new Deck(Forge.getLocalizer().getMessage("lblEmptyDeck")));
                    continue;
                }
                decks.set(i, new Deck(data.readString("deck_name_" + i)));
                CardPool mainCards = CardPool.fromCardList(Lists.newArrayList((String[]) data.readObject("deck_" + i)));
                decks.get(i).getMain().addAll(mainCards.getFilteredPool(isValid));
                unsupportedCards.addAll(mainCards.getFilteredPool(isUnsupported).toFlatList());
                if (data.containsKey("sideBoardCards_" + i)) {
                    CardPool sideBoardCards = CardPool.fromCardList(Lists.newArrayList((String[]) data.readObject("sideBoardCards_" + i)));
                    decks.get(i).getOrCreate(DeckSection.Sideboard).addAll(sideBoardCards.getFilteredPool(isValid));
                    unsupportedCards.addAll(sideBoardCards.getFilteredPool(isUnsupported).toFlatList());
                }
                if (data.containsKey("commanderCards_" + i)) {
                    CardPool commanderCards = CardPool.fromCardList(List.of((String[]) data.readObject("commanderCards_" + i)));
                    decks.get(i).getOrCreate(DeckSection.Commander).addAll(commanderCards.getFilteredPool(isValid));
                    unsupportedCards.addAll(commanderCards.getFilteredPool(isUnsupported).toFlatList());
                }
            }
        }

        // Load deck loadouts (equipment tied to each deck)
        for (int i = 0; i < getDeckCount(); i++) {
            HashMap<String, Long> loadout = null;
            if (data.containsKey("deckLoadout_slots_" + i) && data.containsKey("deckLoadout_items_" + i)) {
                try {
                    String[] loadoutSlots = (String[]) data.readObject("deckLoadout_slots_" + i);
                    Long[] loadoutItems = (Long[]) data.readObject("deckLoadout_items_" + i);
                    if (loadoutSlots.length == loadoutItems.length) {
                        loadout = new HashMap<>();
                        for (int j = 0; j < loadoutSlots.length; j++) {
                            loadout.put(loadoutSlots[j], loadoutItems[j]);
                        }
                    }
                } catch (Exception ignored) {}
            }
            deckLoadouts.add(loadout);
        }

        // Use false to skip loadout switching during load (equippedItems already loaded correctly above)
        setSelectedDeckSlot(data.readInt("selectedDeckIndex"), false);
        CardPool cardPool = CardPool.fromCardList(Lists.newArrayList((String[]) data.readObject("cards")));
        cards.addAll(cardPool.getFilteredPool(isValid));
        unsupportedCards.addAll(cardPool.getFilteredPool(isUnsupported).toFlatList());

        if (data.containsKey("newCards")) {
            InventoryItem[] items = (InventoryItem[]) data.readObject("newCards");
            for (InventoryItem item : items) {
                if (item instanceof PaperCard pc) {
                    if (isUnsupported.test(pc))
                        unsupportedCards.add(pc);
                    else
                        newCards.add(pc);
                }
            }
        }
        if (data.containsKey("noSellCards")) {
            // Legacy list of unsellable cards. Now done via CardRequest flags. Convert the corresponding cards.
            PaperCard[] items = (PaperCard[]) data.readObject("noSellCards");
            CardPool noSellPool = new CardPool();
            for (PaperCard pc : items) {
                if (isUnsupported.test(pc))
                    unsupportedCards.add(pc);
                else
                    noSellPool.add(pc);
            }
            for (Map.Entry<PaperCard, Integer> noSellEntry : noSellPool) {
                PaperCard item = noSellEntry.getKey();
                if (item == null)
                    continue;
                int totalCopies = cards.count(item);
                int noSellCopies = Math.min(noSellEntry.getValue(), totalCopies);
                if (!cards.remove(item, noSellCopies)) {
                    System.err.printf("Failed to update noSellValue flag - %s%n", item);
                    continue;
                }

                int remainingSellableCopies = totalCopies - noSellCopies;

                PaperCard noSellVersion = item.getNoSellVersion();
                cards.add(noSellVersion, noSellCopies);

                System.out.printf("Converted legacy noSellCards item - %s (%d / %d copies)%n", item, noSellCopies, totalCopies);

                // Also go through their decks and update cards there.
                for (Deck deck : decks) {
                    int inUse = 0;
                    for (Map.Entry<DeckSection, CardPool> section : deck) {
                        CardPool pool = section.getValue();
                        inUse += pool.count(item);
                        if(inUse > remainingSellableCopies) {
                            int toConvert = inUse - remainingSellableCopies;
                            pool.remove(item, toConvert);
                            pool.add(noSellVersion, toConvert);
                            System.out.printf("- Converted %d copies in deck - %s/%s%n", toConvert, deck.getName(), section.getKey());
                        }
                    }
                }

            }
        }
        if (data.containsKey("autoSellCards")) {
            PaperCard[] items = (PaperCard[]) data.readObject("autoSellCards");
            for (PaperCard pc : items) {
                if (isUnsupported.test(pc))
                    unsupportedCards.add(pc);
                else
                    autoSellCards.add(pc);
            }
        }
        if (data.containsKey("favoriteCards")) {
            PaperCard[] items = (PaperCard[]) data.readObject("favoriteCards");
            for (PaperCard pc : items) {
                if (isUnsupported.test(pc))
                    unsupportedCards.add(pc);
                else
                    favoriteCards.add(pc);
            }
        }

        fantasyMode = data.containsKey("fantasyMode") && data.readBool("fantasyMode");
        announceFantasy = data.containsKey("announceFantasy") && data.readBool("announceFantasy");
        usingCustomDeck = data.containsKey("usingCustomDeck") && data.readBool("usingCustomDeck");
        announceCustom = data.containsKey("announceCustom") && data.readBool("announceCustom");

        unlockedEditions.clear();
        if (data.containsKey("unlockedEditions")) {
            //noinspection unchecked
            unlockedEditions.addAll((java.util.List<String>) data.readObject("unlockedEditions"));
        }
        // Bronze Coin ante ransom (2026-08-29). Absent on every pre-round-67 save - the
        // containsKey guard keeps those loading cleanly with an empty set (no marked enemies),
        // same forward-compatible shape unlockedEditions above uses.
        coinRansomedEnemies.clear();
        if (data.containsKey("coinRansomedEnemies")) {
            //noinspection unchecked
            coinRansomedEnemies.addAll((java.util.List<String>) data.readObject("coinRansomedEnemies"));
        }
        // Shop-type blueprints (2026-08-30). Absent on every pre-round-71 save; the containsKey
        // guard leaves the set EMPTY there, which isShopTypeUnlocked() deliberately reads as
        // "legacy save, everything unlocked" rather than "nothing unlocked" - see the field.
        unlockedShopTypes.clear();
        if (data.containsKey("unlockedShopTypes")) {
            //noinspection unchecked
            unlockedShopTypes.addAll((java.util.List<String>) data.readObject("unlockedShopTypes"));
        }
        startingColorId = data.containsKey("startingColorId") ? data.readString("startingColorId") : null;
        if (startingColorId != null && startingColorId.isEmpty())
            startingColorId = null; // "" is the persisted form of null - see save()
        suppressDefeatGoldLoss = false;
        researchEditionInProgress = data.containsKey("researchEditionInProgress") ? data.readString("researchEditionInProgress") : null;
        researchStartDay = data.containsKey("researchStartDay") ? data.readInt("researchStartDay") : -1;
        researchInProgress.clear();
        if (data.containsKey("researchInProgressList")) {
            // "CODE:startDay;CODE:startDay" - see save()
            for (String entry : data.readString("researchInProgressList").split(";")) {
                int sep = entry.lastIndexOf(':');
                if (sep > 0)
                    try { researchInProgress.put(entry.substring(0, sep), Integer.parseInt(entry.substring(sep + 1))); }
                    catch (NumberFormatException ignored) { }
            }
        } else if (researchEditionInProgress != null) {
            researchInProgress.put(researchEditionInProgress, researchStartDay); // pre-round-88 single slot
        }
        researchEditionInProgress = null;
        if (migration) {
            getCurrentGameStage().setExtraAnnouncement(Forge.getLocalizer().getMessage("lblDataMigrationMsg"));
        }

        RewardData.invalidateCardPool();
        onLifeTotalChangeList.emit();
        onShardsChangeList.emit();
        onGoldChangeList.emit();
        // Missing since Wood/Stone were introduced (2026-08-16 user report: "grabbed the stone in
        // the starting area... I have 0 stone"): the loaded value was read correctly above, but
        // GameHUD's ResourceDisplayActor is a process-lifetime singleton that only updates via
        // these signals - loading a save never refreshed its labels, so the display kept showing
        // whatever the PREVIOUS game state's wood/stone was (usually 0) while the real loaded
        // value sat invisible underneath. Same bug class create() was given emits for on
        // 2026-08-15; the load path was missed then.
        onWoodChangeList.emit();
        onStoneChangeList.emit();
        onBlessing.emit();
    }

    @Override
    public SaveFileData save() {
        SaveFileData data = new SaveFileData();

        data.store("statistic", this.statistic.save());
        data.store("startingLife", this.difficultyData.startingLife);
        data.store("startingMoney", this.difficultyData.startingMoney);
        data.store("startingDifficulty", this.difficultyData.startingDifficulty);
        data.store("difficultyName", this.difficultyData.name);
        data.store("enemyLifeFactor", this.difficultyData.enemyLifeFactor);
        data.store("sellFactor", this.difficultyData.sellFactor);
        data.store("shardSellRatio", this.difficultyData.shardSellRatio);
        data.store("goldLoss", this.difficultyData.goldLoss);
        data.store("lifeLoss", this.difficultyData.lifeLoss);
        data.store("spawnRank", this.difficultyData.spawnRank);
        data.store("rewardMaxFactor", this.difficultyData.rewardMaxFactor);

        data.store("name", name);
        data.store("heroRace", heroRace);
        data.store("avatarIndex", avatarIndex);
        data.store("isFemale", isFemale);
        data.store("colorIdentity", colorIdentity.getColor());

        data.store("adventure_mode", adventureMode.toString());

        data.store("fantasyMode", fantasyMode);
        data.store("announceFantasy", announceFantasy);
        data.store("usingCustomDeck", usingCustomDeck);
        data.store("announceCustom", announceCustom);

        data.storeObject("unlockedEditions", new ArrayList<>(unlockedEditions));
        data.storeObject("coinRansomedEnemies", new ArrayList<>(coinRansomedEnemies));
        data.storeObject("unlockedShopTypes", new ArrayList<>(unlockedShopTypes));
        // store() with a null String throws (writeUTF) - persist "" and read it back as null.
        data.store("startingColorId", startingColorId == null ? "" : startingColorId);
        StringBuilder researchList = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> r : researchInProgress.entrySet())
            researchList.append(researchList.length() == 0 ? "" : ";").append(r.getKey()).append(':').append(r.getValue());
        data.store("researchInProgressList", researchList.toString());

        data.store("worldPosX", worldPosX);
        data.store("worldPosY", worldPosY);
        data.store("gold", gold);
        data.store("life", life);
        data.store("maxLife", maxLife);
        data.store("shards", shards);
        data.store("wood", wood);
        data.store("stone", stone);
        data.store("townLifeBonus", townLifeBonus);
        data.store("partnerOverhealActive", partnerOverhealActive);
        data.store("payGuardsFromBankFirst", payGuardsFromBankFirst);
        data.store("goldMineDepositsToBankDirectly", goldMineDepositsToBankDirectly);
        data.storeObject("colorReputationHalfPoints", new HashMap<>(colorReputationHalfPoints));
        data.store("deckName", deck.getName());

        data.storeObject("inventory", inventoryItems.toArray(new ItemData[0]));

        ArrayList<String> slots = new ArrayList<>();
        ArrayList<Long> items = new ArrayList<>();
        for (Map.Entry<String, Long> entry : equippedItems.entrySet()) {
            slots.add(entry.getKey());
            items.add(entry.getValue());
        }
        data.storeObject("equippedSlots", slots.toArray(new String[0]));
        data.storeObject("equippedItems", items.toArray(new Long[0]));

        data.storeObject("boosters", boostersOwned.toArray(Deck.class));

        data.storeObject("blessing", blessing);

        // Save character flags.
        ArrayList<String> characterFlagsKey = new ArrayList<>();
        ArrayList<Byte> characterFlagsValue = new ArrayList<>();
        for (Map.Entry<String, Byte> entry : characterFlags.entrySet()) {
            characterFlagsKey.add(entry.getKey());
            characterFlagsValue.add(entry.getValue());
        }
        data.storeObject("characterFlagsKey", characterFlagsKey.toArray(new String[0]));
        data.storeObject("characterFlagsValue", characterFlagsValue.toArray(new Byte[0]));

        // Save quest flags.
        ArrayList<String> questFlagsKey = new ArrayList<>();
        ArrayList<Byte> questFlagsValue = new ArrayList<>();
        for (Map.Entry<String, Byte> entry : questFlags.entrySet()) {
            questFlagsKey.add(entry.getKey());
            questFlagsValue.add(entry.getValue());
        }
        data.storeObject("questFlagsKey", questFlagsKey.toArray(new String[0]));
        data.storeObject("questFlagsValue", questFlagsValue.toArray(new Byte[0]));
        data.storeObject("quests", quests.toArray());
        data.storeObject("events", events.toArray());

        data.storeObject("deckCards", deck.getMain().toCardList("\n").split("\n"));
        if (deck.get(DeckSection.Sideboard) != null)
            data.storeObject("sideBoardCards", deck.get(DeckSection.Sideboard).toCardList("\n").split("\n"));
        if (deck.get(DeckSection.Attractions) != null)
            data.storeObject("attractionDeckCards", deck.get(DeckSection.Attractions).toCardList("\n").split("\n"));
        if (deck.get(DeckSection.Contraptions) != null)
            data.storeObject("contraptionDeckCards", deck.get(DeckSection.Contraptions).toCardList("\n").split("\n"));
        if (deck.get(DeckSection.Commander) != null)
            data.storeObject("commanderCards", deck.get(DeckSection.Commander).toCardList("\n").split("\n"));

        // save decks dynamically
        data.store("deckCount", getDeckCount());
        for (int i = 0; i < getDeckCount(); i++) {
            data.store("deck_name_" + i, decks.get(i).getName());
            data.storeObject("deck_" + i, decks.get(i).getMain().toCardList("\n").split("\n"));
            if (decks.get(i).get(DeckSection.Sideboard) != null)
                data.storeObject("sideBoardCards_" + i, decks.get(i).get(DeckSection.Sideboard).toCardList("\n").split("\n"));
            if (decks.get(i).get(DeckSection.Attractions) != null)
                data.storeObject("attractionDeckCards_" + i, decks.get(i).get(DeckSection.Attractions).toCardList("\n").split("\n"));
            if (decks.get(i).get(DeckSection.Contraptions) != null)
                data.storeObject("contraptionDeckCards_" + i, decks.get(i).get(DeckSection.Contraptions).toCardList("\n").split("\n"));
            if (decks.get(i).get(DeckSection.Commander) != null)
                data.storeObject("commanderCards_" + i, decks.get(i).get(DeckSection.Commander).toCardList("\n").split("\n"));
        }

        // Save deck loadouts (equipment tied to each deck)
        // First, save current equipment to current deck's loadout
        ensureDeckLoadoutsSize();
        deckLoadouts.set(selectedDeckIndex, new HashMap<>(equippedItems));
        for (int i = 0; i < getDeckCount(); i++) {
            HashMap<String, Long> loadout = i < deckLoadouts.size() ? deckLoadouts.get(i) : null;
            if (loadout != null) {
                ArrayList<String> loadoutSlots = new ArrayList<>();
                ArrayList<Long> loadoutItems = new ArrayList<>();
                for (Map.Entry<String, Long> entry : loadout.entrySet()) {
                    loadoutSlots.add(entry.getKey());
                    loadoutItems.add(entry.getValue());
                }
                data.storeObject("deckLoadout_slots_" + i, loadoutSlots.toArray(new String[0]));
                data.storeObject("deckLoadout_items_" + i, loadoutItems.toArray(new Long[0]));
            }
        }

        data.store("selectedDeckIndex", selectedDeckIndex);
        data.storeObject("cards", cards.toCardList("\n").split("\n"));

        data.storeObject("newCards", newCards.toFlatList().toArray(new PaperCard[0]));
        data.storeObject("autoSellCards", autoSellCards.toFlatList().toArray(new PaperCard[0]));
        data.storeObject("favoriteCards", favoriteCards.toArray(new PaperCard[0]));

        return data;
    }

    public String spriteName() {
        return HeroListData.instance().getHero(heroRace, isFemale);
    }

    public FileHandle sprite() {
        return Config.instance().getFile(HeroListData.instance().getHero(heroRace, isFemale));
    }

    public TextureRegion avatar() {
        return HeroListData.instance().getAvatar(heroRace, isFemale, avatarIndex);
    }

    public String raceName() {
        return HeroListData.instance().getRaces().get(Current.player().heroRace);
    }

    public GameStage getCurrentGameStage() {
        if (MapStage.getInstance().isInMap())
            return MapStage.getInstance();
        return WorldStage.getInstance();
    }

    public void addStatusMessage(String iconName, String message, Integer itemCount, float x, float y) {
        String symbol = itemCount == null || itemCount < 0 ? "" : " +";
        String icon = iconName == null ? "" : "[+" + iconName + "]";
        String count = itemCount == null ? "" : String.valueOf(itemCount);
        TextraLabel actor = Controls.newTextraLabel("[%95]" + icon + "[WHITE]" + symbol + count + " " + message);
        actor.setPosition(x, y);
        actor.addAction(Actions.sequence(
                Actions.parallel(Actions.moveBy(0f, 5f, 3f), Actions.fadeIn(2f)),
                Actions.hide(),
                Actions.removeActor())
        );
        getCurrentGameStage().addActor(actor);
    }

    public void addCard(PaperCard card) {
        addCard(card, 1);
    }

    public void addCard(PaperCard card, int amount) {
        cards.add(card, amount);
        newCards.add(card, amount);
        // Research-threshold popup fires from here too, not just addReward() (2026-08-26 review
        // finding): ante wins, ante buy-backs, and the Chest artificer's duplicates all land
        // through this method - a threshold crossed on one of those paths would otherwise never
        // notify, and the stateless crossing test in addReward()'s hook could then never fire
        // for that edition again. addReward()'s Card branch adds to `cards` directly (not via
        // this method), so the two hooks can never double-fire for one grant.
        maybeNotifyResearchThreshold(card, amount);
    }

    public void addCards(ItemPool<PaperCard> cardPool) {
        cards.addAll(cardPool);
        newCards.addAll(cardPool);
    }

    public void addReward(Reward reward) {
        switch (reward.getType()) {
            case Card:
                cards.add(reward.getCard());
                newCards.add(reward.getCard());
                if (reward.isAutoSell()) {
                    autoSellCards.add(reward.getCard());
                    refreshEditor();
                }
                maybeNotifyResearchThreshold(reward.getCard(), 1);
                break;
            case Gold:
                addGold(reward.getCount());
                break;
            case Item:
                if (reward.getItem() != null)
                    addItem(reward.getItem().name);
                break;
            case CardPack:
                if (reward.getDeck() != null) {
                    boostersOwned.add(reward.getDeck());
                }
                break;
            case Life:
                addMaxLife(reward.getCount());
                break;
            case Shards:
                addShards(reward.getCount());
                break;
            // Mod addition (The Forsaken Realms, 2026-08-10): Stone as a Reward type.
            // Logged since 2026-08-27 - a playtest report ("reward screen vanished, not sure I
            // got anything") was unanswerable from the log without a grant line.
            case Stone:
                addStone(reward.getCount());
                System.out.println("[TFR-Reward] stone +" + reward.getCount() + " (reward grant)");
                break;
            // Mod addition (The Forsaken Realms, 2026-08-11): Wood as a Reward type.
            case Wood:
                addWood(reward.getCount());
                System.out.println("[TFR-Reward] wood +" + reward.getCount() + " (reward grant)");
                break;
            // Mod addition (The Forsaken Realms, 2026-08-31): shop-type blueprint. Idempotent -
            // the drop sites unlock the type BEFORE showing the reveal card so a player who closes
            // the reward screen without turning the card over can never lose it, which makes this
            // a no-op on the normal path and the real grant on any future path that only builds
            // the Reward.
            case Blueprint:
                unlockShopType(reward.getBlueprintShopName(), "reward card");
                break;
        }
    }

    // Research-threshold popup (user request 2026-08-25, spec: "add in a pop-up when you get new
    // cards and you reach a 10% threshold an an expansion - something like, you can now research
    // 'x' expansion at a research lab in the capitol"). Hooked into BOTH addReward()'s Card
    // branch (shop buys, loot screens, quest rewards, chest picks) AND addCard() (ante wins,
    // ante buy-backs, artificer duplicates) - together those cover every real card-pickup path;
    // bulk addCards() (starter-deck import) is deliberately NOT hooked, a fresh save's starting
    // cards shouldn't fire pickup popups. Stateless crossing detection (owned-after >= threshold AND
    // owned-before < threshold) rather than a persisted "already notified" set: counts only ever
    // cross the threshold once in normal play, no save-format change needed, and the rare
    // sell-below-then-recross duplicate popup is harmless. Same threshold formula and legal-pool
    // counting the Research Lab's own screen uses (ResearchScene.thresholdForEditionCode()).
    // Never allowed to break a card grant - hard try/catch around the whole convenience.
    private void maybeNotifyResearchThreshold(PaperCard card, int amountAdded) {
        try {
            if (card == null)
                return;
            forge.adventure.world.World world = Current.world();
            if (world == null || !world.isEditionProgressionEnabled())
                return;
            String code = card.getEdition();
            if (code == null || hasUnlockedEdition(code) || isResearching(code))
                return;
            int threshold = forge.adventure.scene.ResearchScene.thresholdForEditionCode(code);
            if (threshold == Integer.MAX_VALUE)
                return;
            int owned = 0;
            for (Map.Entry<PaperCard, Integer> entry : cards)
                if (code.equals(entry.getKey().getEdition()))
                    owned += entry.getValue();
            if (owned >= threshold && owned - amountAdded < threshold) {
                forge.card.CardEdition edition = forge.model.FModel.getMagicDb().getEditions().get(code);
                String name = edition != null ? edition.getName() : code;
                System.out.println("[TFR-Research] threshold reached: " + code + " owned=" + owned + "/" + threshold);
                forge.adventure.stage.GameHUD.getInstance().addNotification(
                        "You can now research " + name + " (" + code + ") at your Capitol's Research Lab!");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void refreshEditor() {
        AdventureDeckEditor editor = ((AdventureDeckEditor) DeckEditScene.getInstance().getScreen());
        if (editor != null)
            editor.refresh();
    }

    private void addGold(int goldCount) {
        gold += goldCount;
        onGoldChangeList.emit();
    }

    public void onShardsChange(Runnable o) {
        onShardsChangeList.add(o);
        o.run();
    }

    public void onLifeChange(Runnable o) {
        onLifeTotalChangeList.add(o);
        o.run();
    }

    public void onPlayerChanged(Runnable o) {
        onPlayerChangeList.add(o);
        o.run();
    }

    public void onEquipmentChanged(Runnable o) {
        onEquipmentChange.add(o);
        o.run();
    }

    public void onGoldChange(Runnable o) {
        onGoldChangeList.add(o);
        o.run();
    }

    public void onWoodChange(Runnable o) {
        onWoodChangeList.add(o);
        o.run();
    }

    public void onStoneChange(Runnable o) {
        onStoneChangeList.add(o);
        o.run();
    }

    public void onBlessing(Runnable o) {
        onBlessing.add(o);
        o.run();
    }

    public boolean fullHeal() {
        if (life < maxLife) {
            resetToMaxLife();
            return true;
        }
        return false;
    }

    public void resetToMaxLife() {
        int lb = life, mb = maxLife;
        life = maxLife;
        logLife("fullHeal", lb, mb);
        onLifeTotalChangeList.emit();
    }

    public boolean potionOfFalseLife() {
        if (gold >= falseLifeCost() && life == maxLife) {
            life = maxLife + 2;
            gold -= falseLifeCost();
            onLifeTotalChangeList.emit();
            onGoldChangeList.emit();
            return true;
        } else {
            System.out.println("Can't afford cost of false life " + falseLifeCost());
            System.out.println("Only has this much gold " + gold);
        }
        return false;
    }

    public int falseLifeCost() {
        int ret = 200 + (int) (50 * getStatistic().winLossRatio());
        return ret < 0 ? 250 : ret;
    }

    public void heal(int amount) {
        int lb = life, mb = maxLife;
        life = Math.min(life + amount, maxLife);
        logLife("heal(" + amount + ")", lb, mb);
        onLifeTotalChangeList.emit();
    }

    public boolean isPartnerOverhealActive() {
        return partnerOverhealActive;
    }

    public boolean isPayGuardsFromBankFirst() {
        return payGuardsFromBankFirst;
    }

    public void setPayGuardsFromBankFirst(boolean val) {
        payGuardsFromBankFirst = val;
    }

    public boolean isGoldMineDepositsToBankDirectly() {
        return goldMineDepositsToBankDirectly;
    }

    public void setGoldMineDepositsToBankDirectly(boolean val) {
        goldMineDepositsToBankDirectly = val;
    }

    /** Color reputation (MOD_SCOPE.md #1): free top-up to maxLife+2 on entering a Partner-tier
     *  town/capital. Re-grants (refreshes back to maxLife+2) even if already active or already at
     *  maxLife+2, so re-entering doesn't stack past +2. */
    public void grantPartnerOverheal() {
        int lb = life, mb = maxLife;
        life = maxLife + 2;
        partnerOverhealActive = true;
        logLife("partnerOverheal", lb, mb);
        onLifeTotalChangeList.emit();
    }

    /** Drops an unused Partner overheal back down to maxLife. No-op if not active. */
    public void clearPartnerOverhealIfActive() {
        if (!partnerOverhealActive)
            return;
        int lb = life, mb = maxLife;
        life = Math.min(life, maxLife);
        partnerOverhealActive = false;
        logLife("partnerOverhealCleared", lb, mb);
        onLifeTotalChangeList.emit();
    }

    public void heal(float percent) {
        int lb = life, mb = maxLife;
        life = Math.min(life + (int) (maxLife * percent), maxLife);
        logLife("heal(" + percent + ")", lb, mb);
        onLifeTotalChangeList.emit();
    }

    public boolean defeated() {
        // Bronze Coin ante ransom (2026-08-29): paying the coin buys off the GOLD penalty only -
        // the life loss still applies, so a loss is never consequence-free. One-shot: consumed
        // here so it can never leak into a later, unrelated defeat.
        if (suppressDefeatGoldLoss) {
            suppressDefeatGoldLoss = false;
            System.out.println("[TFR-CoinRansom] defeat gold loss waived by Bronze Coin (gold kept: " + gold + ")");
        } else {
            gold = (int) (gold - (gold * difficultyData.goldLoss));
        }
        int lb = life, mb = maxLife;
        life = (int) (life - (maxLife * difficultyData.lifeLoss));
        logLife("defeated(lifeLoss=" + difficultyData.lifeLoss + ")", lb, mb);
        onLifeTotalChangeList.emit();
        onGoldChangeList.emit();
        return life < 1;
        // If true, the player would have had 0 or less, and thus is actually "defeated" if the caller cares about it
    }

    public void win() {
        Current.player().addShards(1);
    }

    /** Round 100: mirrors applyTownLifeBonus for the Ring City life bonus. Returns the delta applied. */
    public int applyRingLifeBonus(int target) {
        int delta = target - ringLifeBonus;
        if (delta == 0)
            return 0;
        int lb = life, mb = maxLife;
        ringLifeBonus = target;
        maxLife += delta;
        if (delta > 0)
            life += delta;
        else
            life = Math.max(1, Math.min(life, maxLife));
        logLife("ringLifeBonus(target=" + target + ")", lb, mb);
        onLifeTotalChangeList.emit();
        return delta;
    }
    /** Round 101: "start with nothing" - the five Ring Cities hand the difficulty's starting kit over instead. */
    private static boolean ringGiftStart() {
        return forge.adventure.util.Config.instance().getConfigData().ringGiftStart;
    }
    /** Round 101: a Ring City's gift - kind is gold / shards / wood / stone / items / all (the difficulty's starting amounts). */
    public void grantRingGift(String kind) {
        if (difficultyData == null || kind == null)
            return;
        // Round 102: read the CONFIG difficulty - the player's own copy only carries life/money/factors
        // (round 101 shipped 1 shard and no wood/stone/items because of that), and hand the gift over as
        // reward cards with icons, exactly like a duel's loot (RewardScene), instead of silent adds.
        DifficultyData cfg = difficultyData;
        DifficultyData[] all = forge.adventure.util.Config.instance().getConfigData().difficulties;
        if (all != null)
            for (DifficultyData d : all)
                if (d != null && d.name != null && d.name.equals(difficultyData.name)) { cfg = d; break; }
        String k = kind.trim().toLowerCase();
        boolean everything = "all".equals(k);
        java.util.List<forge.adventure.data.RewardData> gifts = new java.util.ArrayList<>();
        if (everything || "gold".equals(k))
            gifts.add(rewardOf("gold", cfg.startingMoney, null));
        if (everything || "shards".equals(k))
            gifts.add(rewardOf("shards", cfg.startingShards, null));
        if (everything || "wood".equals(k))
            gifts.add(rewardOf("wood", cfg.startingWood, null));
        if (everything || "stone".equals(k))
            gifts.add(rewardOf("stone", cfg.startingStone, null));
        if (everything || "items".equals(k)) {
            for (String s : cfg.startItems)
                gifts.add(rewardOf("item", 1, s));
            if (!everything) { // the "all" (skip-intro) path already hands these out itself
                gifts.add(rewardOf("item", 3, "Bronze Challenge Coin"));
                gifts.add(rewardOf("item", 1, "Challenge Coin"));
                gifts.add(rewardOf("item", 1, "Silver Challenge Coin"));
                setCharacterFlag("freeChallengeCoins", 1);
            }
        }
        com.badlogic.gdx.utils.Array<forge.adventure.util.Reward> rewards = new com.badlogic.gdx.utils.Array<>();
        for (forge.adventure.data.RewardData r : gifts)
            if (r.count > 0)
                rewards.addAll(r.generate(false, true));
        System.out.println("[TFR-RingGift] " + k + " (difficulty " + cfg.name + "): gold " + cfg.startingMoney + ", shards " + cfg.startingShards
                + ", wood " + cfg.startingWood + ", stone " + cfg.startingStone + ", start items " + cfg.startItems.length
                + " -> " + rewards.size + " reward card(s)");
        if (rewards.size == 0)
            return;
        forge.adventure.scene.RewardScene.instance().loadRewards(rewards, forge.adventure.scene.RewardScene.Type.QuestReward, null);
        forge.Forge.switchScene(forge.adventure.scene.RewardScene.instance());
    }
    private static forge.adventure.data.RewardData rewardOf(String type, int count, String itemName) {
        forge.adventure.data.RewardData r = new forge.adventure.data.RewardData();
        r.type = type;
        r.count = count;
        r.itemName = itemName;
        return r;
    }
    public void addMaxLife(int count) {
        int lb = life, mb = maxLife;
        maxLife += count;
        life += count;
        logLife("addMaxLife(" + count + ")", lb, mb);
        onLifeTotalChangeList.emit();
    }

    /**
     * Sets the town-count max-life bonus (mod feature, see TownRestoration.updateTownLifeBonus())
     * to the given target, applying only the difference from what's already applied. Gaining
     * bonus life also heals by the gain (same behavior as addMaxLife()); losing it clamps current
     * life down to the new max but never below 1. Returns the delta actually applied (0 = no
     * change).
     */
    public int applyTownLifeBonus(int target) {
        int delta = target - townLifeBonus;
        if (delta == 0)
            return 0;
        int lb = life, mb = maxLife;
        townLifeBonus = target;
        maxLife += delta;
        if (delta > 0)
            life += delta;
        else
            life = Math.max(1, Math.min(life, maxLife));
        logLife("townLifeBonus(target=" + target + ")", lb, mb);
        onLifeTotalChangeList.emit();
        return delta;
    }

    public void giveGold(int price) {
        takeGold(-price);
    }

    public void takeGold(int price) {
        // Floor at zero (2026-08-30, Android tester reported -167 gold). The immediate cause was
        // the Capital entry toll's pay button staying clickable while greyed (see
        // WorldStage.showCapitalTollDialog - fixed there too), but the reason it could turn into a
        // NEGATIVE BALANCE rather than a refused purchase is here: this was a bare `gold -= price`
        // with no floor, and there are ~20 call sites, several of which pay first and trust an
        // affordability check made elsewhere. A negative balance is never a legitimate game state -
        // it silently breaks every `getGold() >= cost` gate afterwards, so no shop, build or toll
        // works again until the player earns past the debt.
        // NOTE: addGold() routes through here as takeGold(-price), so the guard must only clamp
        // actual spends - a negative `price` is a credit and must pass through untouched.
        if (price > 0 && price > gold) {
            System.out.println("[TFR-Gold] refused overspend of " + price + " with only " + gold
                    + " - clamping to 0 (a caller skipped its affordability check)");
            gold = 0;
        } else {
            gold -= price;
        }
        onGoldChangeList.emit();
        //play sfx
        SoundSystem.instance.play(SoundEffectType.CoinsDrop, false);
    }

    public void addShards(int number) {
        takeShards(-number);
    }

    public void takeShards(int number) {
        shards -= number;
        onShardsChangeList.emit();
        //play sfx
        SoundSystem.instance.play(SoundEffectType.TakeShard, false);
    }

    public void setShards(int number) {
        boolean changed = shards != number;
        if (changed) {
            shards = number;
            onShardsChangeList.emit();
        }
    }

    public void addWood(int number) {
        takeWood(-number);
    }

    public void takeWood(int number) {
        wood -= number;
        onWoodChangeList.emit();
    }

    public void addStone(int number) {
        takeStone(-number);
    }

    public void takeStone(int number) {
        stone -= number;
        onStoneChangeList.emit();
    }

    // Color reputation (MOD_SCOPE.md #1) - values in INTERNAL HALF-POINTS, see the field's own
    // comment and ColorReputation (which owns all the rules; these are dumb storage accessors).
    public int getColorReputationHalfPoints(String color) {
        return colorReputationHalfPoints.getOrDefault(color, 0);
    }

    public void addColorReputationHalfPoints(String color, int halfPoints) {
        colorReputationHalfPoints.merge(color, halfPoints, Integer::sum);
    }

    public void addBlessing(EffectData bless) {
        blessing = bless;
        onBlessing.emit();
    }

    public void clearBlessing() {
        blessing = null;
        onBlessing.emit();
    }

    public boolean hasBlessing(String name) { //Checks for a named blessing.
        //It is not necessary to name all blessings, only the ones you'd want to check for.
        if (blessing == null) return false;
        return blessing.name.equals(name);
    }

    public boolean isFantasyMode() {
        return fantasyMode;
    }

    public boolean isUsingCustomDeck() {
        return usingCustomDeck;
    }

    public boolean hasAnnounceFantasy() {
        return announceFantasy;
    }

    public void clearAnnounceFantasy() {
        announceFantasy = false;
    }

    public boolean hasAnnounceCustom() {
        return announceCustom;
    }

    public void clearAnnounceCustom() {
        announceCustom = false;
    }

    public boolean hasColorView() {
        for (Long id : equippedItems.values()) {
            ItemData data = getEquippedItem(id);
            if (data != null && data.effect != null && data.effect.colorView) return true;
        }
        if (blessing != null) {
            return blessing.colorView;
        }
        return false;
    }

    public ItemData getRandomEquippedItem() {
        Array<ItemData> items = new Array<>();
        for (Long id : equippedItems.values()) {
            ItemData item = getEquippedItem(id);
            if (item == null)
                continue;
            if (isHardorInsaneDifficulty()) {
                items.add(item);
            } else {
                switch (item.equipmentSlot) {
                    // limit to these for easy and normal
                    case "Boots", "Body", "Neck" -> items.add(item);
                }
            }
        }
        return items.random();
    }

    public boolean hasEquippedItem() {
        for (Long id : equippedItems.values()) {
            ItemData item = getEquippedItem(id);
            if (item == null)
                continue;
            if (isHardorInsaneDifficulty()) {
                return true;
            } else {
                switch (item.equipmentSlot) {
                    // limit to these for easy and normal
                    case "Boots", "Body", "Neck" -> {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public ItemData getEquippedAbility1() {
        for (Long id : equippedItems.values()) {
            ItemData data = getEquippedItem(id);
            if (data != null && "Ability1".equalsIgnoreCase(data.equipmentSlot)) {
                return data;
            }
        }
        return null;
    }

    public ItemData getEquippedAbility2() {
        for (Long id : equippedItems.values()) {
            ItemData data = getEquippedItem(id);
            if (data != null && "Ability2".equalsIgnoreCase(data.equipmentSlot)) {
                return data;
            }
        }
        return null;
    }

    public int bonusDeckCards() {
        int result = 0;
        for (Long id : equippedItems.values()) {
            ItemData data = getEquippedItem(id);
            if (data != null && data.effect != null && data.effect.cardRewardBonus > 0)
                result += data.effect.cardRewardBonus;
        }
        if (blessing != null) {
            if (blessing.cardRewardBonus > 0) result += blessing.cardRewardBonus;
        }
        return Math.min(result, 3);
    }

    public DifficultyData getDifficulty() {
        return difficultyData;
    }

    public boolean isHardorInsaneDifficulty() {
        return "Hard".equalsIgnoreCase(difficultyData.name) || "Insane".equalsIgnoreCase(difficultyData.name);
    }

    // ---- Progressive Set Unlocks (MOD_SCOPE.md #4) ----

    /** Tunable via config tables/settings.json (researchDays); falls back to 7. */
    public static int researchDays() {
        int d = Config.instance().getTuningData().researchDays;
        return d > 0 ? d : 7;
    }
    /** Tunable via config tables/settings.json (researchShardCost); falls back to 100. */
    public static int researchShardCost() {
        int c = Config.instance().getTuningData().researchShardCost;
        return c > 0 ? c : 100;
    }

    public int getHeroRace() {
        return heroRace;
    }

    public Set<String> getUnlockedEditions() {
        return unlockedEditions;
    }

    public boolean hasUnlockedEdition(String editionCode) {
        return unlockedEditions.contains(editionCode);
    }

    public void unlockEdition(String editionCode) {
        unlockedEditions.add(editionCode);
    }

    /** Editions currently being researched, in start order, mapped to their start day. */
    public java.util.Map<String, Integer> getResearchInProgress() {
        return java.util.Collections.unmodifiableMap(researchInProgress);
    }

    public boolean isResearching(String editionCode) {
        return researchInProgress.containsKey(editionCode);
    }

    public int getResearchDaysLeft(String editionCode, int currentDay) {
        Integer start = researchInProgress.get(editionCode);
        return start == null ? -1 : Math.max(0, researchDays() - (currentDay - start));
    }

    public void startResearch(String editionCode, int currentDay) {
        researchInProgress.put(editionCode, currentDay);
        // Diagnostic-only logging - greppable in forge.log as "[TFR-Research]".
        System.out.println("[TFR-Research] started " + editionCode + " on day " + currentDay
                + " (completes day " + (currentDay + researchDays()) + "; " + researchInProgress.size() + " in progress)");
    }

    public void clearResearch() {
        researchInProgress.clear();
    }

    /** Auto-completes a finished research (no separate "collect" step, unlike the Archaeologist's
     *  reward-flip flow - there's no physical loot here, just an unlock) - called both lazily
     *  (ResearchScene.enter()) and from the daily-tick hook (EconomyBuildings.processDaysPassed())
     *  so the edition becomes shoppable the moment the timer elapses, not only when the player
     *  happens to revisit the Lab. Idempotent - safe to call every day even with no research
     *  active. */
    public void checkResearchCompletion(int currentDay) {
        if (researchInProgress.isEmpty())
            return;
        // Several editions at once (2026-09-03): each completes researchDays() after its OWN start.
        java.util.Iterator<java.util.Map.Entry<String, Integer>> it = researchInProgress.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, Integer> r = it.next();
            if (currentDay - r.getValue() < researchDays())
                continue;
            String done = r.getKey();
            it.remove();
            // Diagnostic-only logging - greppable in forge.log as "[TFR-Research]".
            System.out.println("[TFR-Research] completed " + done + " on day " + currentDay
                    + " - now unlocked (" + (unlockedEditions.size() + 1) + " total)");
            unlockedEditions.add(done);
            RewardData.invalidateCardPool();
            // Main-quest hook (2026-08-26, "Raise the Banner" - user decision: the objective
            // completes when the 7-day research actually FINISHES, not when it starts). The
            // matching quest stage must set worldMapOK:true AND anyPOI:true - completion can
            // fire from the daily overworld tick or lazily on ResearchScene.enter().
            setCharacterFlag("researchComplete", 1);
            System.out.println("[TFR-MainQuest] researchComplete -> 1");
        }
    }

    public void renameDeck(String text) {
        deck = (Deck) deck.copyTo(text);
        decks.set(selectedDeckIndex, deck);
    }

    public int cardSellPrice(PaperCard card) {
        if (card.hasNoSellValue()) {
            return 0;
        }

        int basePrice = (int) (CardUtil.getCardPrice(card) * difficultyData.sellFactor);

        if (card.isFoil()) {
            basePrice += basePrice * 20 / 100;
        }

        float townPriceModifier = currentLocationChanges == null ? 1f : currentLocationChanges.getTownPriceModifier();
        return (int) (basePrice * (2.0f - townPriceModifier));
    }

    /**
     * Sells a number of copies of a card.
     * @return the number of copies successfully sold.
     */
    public int sellCard(PaperCard card, Integer amount) {
        if (amount == null || amount < 1)
            return 0;

        int amountToSell = Math.min(amount, cards.count(card));
        int earned = performSale(card, amountToSell);

        if(earned > 0)
            giveGold(earned);
        return amountToSell;
    }

    /**
     * Sells all cards in the given card pool and adds the resulting amount of gold.
     * The given card pool will be emptied.
     */
    public void doBulkSell(ItemPool<PaperCard> cards) {
        int profit = 0;
        for (PaperCard cardToSell : cards.toFlatList()) {
            profit += AdventurePlayer.current().performSale(cardToSell, 1);
            cards.remove(cardToSell);
        }
        giveGold(profit); //do this as one transaction so as not to get multiple copies of sound effect
    }

    /**
     * Removes a number of copies of a card from the player's inventory and returns the amount of gold they sold for.
     * Does *not* update the player's gold. Can be used as part of bulk-sell operations that update the amount all at once.
     */
    private int performSale(PaperCard card, int amount) {
        int amountToSell = Math.min(amount, cards.count(card));
        if(!cards.remove(card, amountToSell))
            return 0; //Failed to sell?
        return cardSellPrice(card) * amountToSell;
    }

    public void removeItem(String name) {
        inventoryItems.stream().filter(itemData -> name.equalsIgnoreCase(itemData.name)).findFirst().ifPresent(this::removeItem);
    }

    public void removeItem(ItemData item) {
        if (item == null)
            return;
        inventoryItems.remove(item);
        if (getEquippedItems().contains(item.longID) && !inventoryItems.contains(item)) {
            item.isEquipped = false;
            getEquippedItems().remove(item.longID);
        }
    }

    public void equip(ItemData item) {
        Long itemID = equippedItems.get(item.equipmentSlot);
        if (itemID != null && itemID.equals(item.longID)) {
            item.isEquipped = false;
            equippedItems.remove(item.equipmentSlot);
        } else {
            item.isEquipped = true;
            equippedItems.put(item.equipmentSlot, item.longID);
        }
        onEquipmentChange.emit();
    }

    public Long itemInSlot(String key) {
        return equippedItems.get(key);
    }

    public float equipmentSpeed() {
        float factor = 1.0f;
        for (Long id : equippedItems.values()) {
            ItemData data = getEquippedItem(id);
            if (data != null && data.effect != null && data.effect.moveSpeed > 0.0)  //Avoid negative speeds. It would be silly.
                factor *= data.effect.moveSpeed;
        }
        if (blessing != null) { //If a blessing gives speed, take it into account.
            if (blessing.moveSpeed > 0.0)
                factor *= blessing.moveSpeed;
        }
        return factor;
    }

    // Torch (MOD_SCOPE.md, 2026-08-13): same equipped-item-effect-product pattern as
    // equipmentSpeed()/goldModifier() above. World.getVisionRadius() reads this every frame via
    // setPlayerTilePosition() while the player moves, so equip/unequip takes effect immediately -
    // no separate cache-invalidation call needed.
    public float visionRadiusMultiplier() {
        float factor = 1.0f;
        for (Long id : equippedItems.values()) {
            ItemData data = getEquippedItem(id);
            if (data != null && data.effect != null && data.effect.visionRadiusMultiplier > 0.0)
                factor *= data.effect.visionRadiusMultiplier;
        }
        return factor;
    }

    public float goldModifier(boolean sale) {
        float factor = 1.0f;
        for (Long id: equippedItems.values()) {
            ItemData data = getEquippedItem(id);
            if (data != null && data.effect != null && data.effect.goldModifier > 0.0)  //Avoid negative modifiers.
                factor *= data.effect.goldModifier;
        }
        if (blessing != null) { //If a blessing gives speed, take it into account.
            if (blessing.goldModifier > 0.0)
                factor *= blessing.goldModifier;
        }
        if (sale) return Math.max(1.0f + (1.0f - factor), 2.5f);
        return Math.max(factor, 0.25f);
    }

    public float goldModifier() {
        return goldModifier(false);
    }

    public boolean hasItem(String name) {
        return inventoryItems.stream().anyMatch(itemData -> name.equalsIgnoreCase(itemData.name));
    }

    public int countItem(String name) {
        return (int) inventoryItems.stream().filter(Objects::nonNull).filter(i -> i.name.equals(name)).count();
    }

    public boolean addItem(String name) {
        return addItem(name, true);
    }
    public boolean addItem(String name, boolean updateEvent) {
        ItemData item = ItemListData.getItem(name);
        if (item == null)
            return false;
        inventoryItems.add(item);
        if (updateEvent)
            AdventureQuestController.instance().updateItemReceived(item);
        return true;
    }

    public void removeAllQuestItems(){
        inventoryItems.removeIf(data -> data != null && data.questItem);
    }

    public boolean addBooster(Deck booster) {
        if (booster == null || booster.isEmpty())
            return false;
        boostersOwned.add(booster);
        return true;
    }

    public void removeBooster(Deck booster) {
        boostersOwned.removeValue(booster, true);
    }

    //Permanent character flags
    public void setCharacterFlag(String key, int value) {
        if (value != 0)
            characterFlags.put(key, (byte) value);
        else
            characterFlags.remove(key);
        AdventureQuestController.instance().updateQuestsCharacterFlag(key, value);
    }

    public void advanceCharacterFlag(String key) {
        if (characterFlags.get(key) != null) {
            characterFlags.put(key, (byte) (characterFlags.get(key) + 1));
        } else {
            characterFlags.put(key, (byte) 1);
        }
    }

    public boolean checkCharacterFlag(String key) {
        return characterFlags.get(key) != null;
    }

    public int getCharacterFlag(String key) {
        return (int) characterFlags.getOrDefault(key, (byte) 0);
    }

    // Quest functions.
    public void setQuestFlag(String key, int value) {
        if (value != 0)
            questFlags.put(key, (byte) value);
        else
            questFlags.remove(key);
        AdventureQuestController.instance().updateQuestsQuestFlag(key, value);
        // Color Defeat (MOD_SCOPE.md #61) real trigger hook - THIS is the actual call site every
        // castle's boss-defeat dialog action fires (JSON action key "setQuestFlag" routes here via
        // MapDialog.java's Current.player().setQuestFlag(...), confirmed by reading the .tmx and
        // MapDialog's dispatcher directly - MapStage.setQuestFlag(), a differently-named method
        // backing the DIFFERENT "setMapFlag" action key, was the wrong hook point originally, caught
        // by adversarial review 2026-08-14). No-ops for every other quest flag in the game -
        // TerritoryControl.onCastleQuestFlagSet() checks the name against exactly the 5 known ones.
        TerritoryControl.onCastleQuestFlagSet(key, value);
    }

    public void advanceQuestFlag(String key) {
        if (questFlags.get(key) != null) {
            questFlags.put(key, (byte) (questFlags.get(key) + 1));
        } else {
            questFlags.put(key, (byte) 1);
        }
    }

    public boolean checkQuestFlag(String key) {
        return questFlags.get(key) != null;
    }

    public int getQuestFlag(String key) {
        return (int) questFlags.getOrDefault(key, (byte) 0);
    }

    public void resetQuestFlags() {
        questFlags.clear();
    }

    public void addQuest(String questID, boolean isNewGame) {
        int id = Integer.parseInt(questID);
        addQuest(id, isNewGame);
    }

    public void addQuest(int questID, boolean isNewGame) {
        AdventureQuestData toAdd = AdventureQuestController.instance().generateQuest(questID);

        if (toAdd != null) {
            addQuest(toAdd, isNewGame);
        }
    }

    public void addQuest(AdventureQuestData q, boolean isNewGame) {
        //TODO: add a config flag for this
        boolean noTrackedQuests = true;
        for (AdventureQuestData existing : quests) {
            if (noTrackedQuests && existing.isTracked) {
                noTrackedQuests = false;
                break;
            }
        }
        quests.add(q);
        if (noTrackedQuests || q.autoTrack)
            AdventureQuestController.trackQuest(q);
        q.activateNextStages();
        if (!isNewGame)
            AdventureQuestController.instance().showQuestDialogs(MapStage.getInstance());
    }

    public List<AdventureQuestData> getQuests() {
        return quests;
    }

    public void addEvent(AdventureEventData e) {
        events.add(e);
    }

    public List<AdventureEventData> getEvents() {
        return events;
    }

    public int getEnemyDeckNumber(String enemyName, int maxDecks) {
        int deckNumber = 0;
        if (statistic.getWinLossRecord().get(enemyName) != null) {
            int playerWins = statistic.getWinLossRecord().get(enemyName).getKey();
            int enemyWins = statistic.getWinLossRecord().get(enemyName).getValue();
            if (playerWins > enemyWins) {
                int deckNumberAfterAlgorithmOutput = (int) ((playerWins - enemyWins) * (difficultyData.enemyLifeFactor / 3));
                if (deckNumberAfterAlgorithmOutput < maxDecks) {
                    deckNumber = deckNumberAfterAlgorithmOutput;
                } else {
                    deckNumber = maxDecks - 1;
                }
            }
        }
        return deckNumber;
    }

    public void removeQuest(AdventureQuestData quest) {
        quests.remove(quest);
    }

    /**
     * Clears a deck by replacing the current selected deck with a new deck
     */
    public void clearDeck() {
        deck = decks.set(selectedDeckIndex, new Deck(Forge.getLocalizer().getMessage("lblEmptyDeck")));
        ensureDeckLoadoutsSize();
        deckLoadouts.set(selectedDeckIndex, null);
    }

    /**
     * Actually removes the deck from the list of decks.
     */
    public void deleteDeck(){
        int oldIndex = selectedDeckIndex;
        this.setSelectedDeckSlot(0);
        decks.remove(oldIndex);
        if (oldIndex < deckLoadouts.size()) {
            deckLoadouts.remove(oldIndex);
        }
    }

    public void addDeck(){
        decks.add(new Deck(Forge.getLocalizer().getMessage("lblEmptyDeck")));
        deckLoadouts.add(null);
    }

    /**
     * Attempts to copy a deck to an empty slot.
     *
     * @return int - index of new copy slot, or -1 if no slot was available
     */
    public int copyDeck() {
        for (int i = 0; i < maxDeckCount; i++) {
            if (i >= getDeckCount()) addDeck();
            if (isEmptyDeck(i)) {
                decks.set(i, (Deck) deck.copyTo(deck.getName() + " (" + Forge.getLocalizer().getMessage("lblCopy") + ")"));
                // Copy loadout from source deck to new slot
                ensureDeckLoadoutsSize();
                HashMap<String, Long> sourceLoadout = selectedDeckIndex < deckLoadouts.size() ? deckLoadouts.get(selectedDeckIndex) : null;
                deckLoadouts.set(i, sourceLoadout != null ? new HashMap<>(sourceLoadout) : null);
                return i;
            }
        }

        return -1;
    }

    private void ensureDeckLoadoutsSize() {
        while (deckLoadouts.size() < getDeckCount()) {
            deckLoadouts.add(null);
        }
    }

    public boolean isEmptyDeck(int deckIndex) {
        return decks.get(deckIndex).isEmpty() && decks.get(deckIndex).getName().equals(Forge.getLocalizer().getMessage("lblEmptyDeck"));
    }

    public void removeEvent(AdventureEventData completedEvent) {
        events.remove(completedEvent);
    }

    public ItemPool<PaperCard> getAutoSellCards() {
        return autoSellCards;
    }

    /**
     * Gets a list of cards that can be safely sold without taking copies out of the player's decks.
     */
    public ItemPool<PaperCard> getSellableCards() {
        ItemPool<PaperCard> sellableCards = new ItemPool<>(PaperCard.class);
        sellableCards.addAllFlat(cards.toFlatList());

        // Nosell cards used to be filtered out here. Instead we're going to replace their value with 0

        // 1a. Potentially return here if we want to give config option to sell cards from decks
        // but would need to update the decks on sell, not just the catalog

        // 2. Count max cards across all decks in excess of unsellable
        Map<PaperCard, Integer> maxCardCounts = new HashMap<>();

        for (Deck deck : decks) {
            for (final Map.Entry<PaperCard, Integer> cp : deck.getAllCardsInASinglePool(true, true)) {
                int count = cp.getValue();
                if (count > maxCardCounts.getOrDefault(cp.getKey(), 0)) {
                    maxCardCounts.put(cp.getKey(), count);
                }
            }
        }

        // 3. Remove the highest use count of each card, remainder can be sold safely
        for (PaperCard card : maxCardCounts.keySet()) {
            sellableCards.remove(card, maxCardCounts.get(card));
        }

        return sellableCards;
    }

    /**
     * Gets the number of copies of this card that the player needs to keep for their decks to remain valid.
     * Copies are shared between decks, so if one deck uses 1 copy and another deck uses 2, the player needs 2 copies.
     */
    public int getCopiesUsedInDecks(PaperCard card) {
        int copiesUsed = 0;
        for(Deck deck : decks) {
            copiesUsed = Math.max(copiesUsed, deck.count(card));
        }
        return copiesUsed;
    }

    public void removeLostCardFromPools(PaperCard card) {
        if (card.isVeryBasicLand() && !card.isFoil()) {
            return;
        }

        int leftInPool = Current.player().getCards().count(card) - 1;

        for (final Deck deck : decks) {
            int cntInDeck = deck.count(card);
            int nToRemoveFromThisDeck = cntInDeck - leftInPool;
            if (nToRemoveFromThisDeck <= 0) {
                continue;
            }

            for(DeckSection section : DeckSection.values()) {
                if (section == DeckSection.Main || deck.get(section) == null) {
                    continue;
                }
                int cntInSection = deck.get(section).count(card);
                int nToRemoveFromSection = Math.min(cntInSection, nToRemoveFromThisDeck);
                if (nToRemoveFromSection > 0) {
                    deck.get(section).remove(card, nToRemoveFromSection);
                    nToRemoveFromThisDeck -= nToRemoveFromSection;
                    if (nToRemoveFromThisDeck <= 0) {
                        break;
                    }
                }
            }

            if (nToRemoveFromThisDeck <= 0) {
                continue;
            }

            deck.getMain().remove(card, nToRemoveFromThisDeck);
        }
        Current.player().getCards().remove(card, 1);
    }

    public CardPool getCollectionCards(boolean allCards) {
        CardPool collectionCards = new CardPool();
        collectionCards.addAll(cards);
        if (!allCards) {
            collectionCards.removeAll(autoSellCards);
        }

        return collectionCards;
    }

    public void loadChanges(PointOfInterestChanges changes) {
        this.currentLocationChanges = changes;
    }

    /**
     * Points {@link #cardSellPrice(PaperCard)} at the town the player is actually standing in.
     * <p>
     * Fixes a stale-state defect found 2026-08-31: {@code currentLocationChanges} had exactly one
     * writer in the whole codebase - the Inn's Sell Cards button, via ShopScene.loadChanges() -
     * and was never cleared, not even by {@link #clear()} on a save load. So every sell price the
     * game quoted was computed from whichever town's shop screen was opened LAST, which after
     * walking to another town is simply the wrong town, and on a fresh save load is the previous
     * playthrough's.
     * <p>
     * That is not only a mislabelled price: two callers move real gold on it - the ante Buy Back
     * charge in DuelScene and the auto-sell payout on a Loot reward screen, which is the screen
     * Inn-tournament prizes arrive on.
     * <p>
     * Called on entering a map so the value means "here". A POI with no reputation yields exactly
     * 1.0f from getTownPriceModifier(), so a dungeon or the world map is neutral rather than
     * inheriting a town's haggling.
     */
    public void setCurrentLocationChanges(PointOfInterestChanges changes) {
        this.currentLocationChanges = changes;
    }
}
