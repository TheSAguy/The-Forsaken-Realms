package forge.adventure.data;

import com.badlogic.gdx.utils.Array;
import forge.ImageKeys;
import forge.StaticData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.util.*;
import forge.adventure.world.WorldSave;
import forge.card.CardDb;
import forge.card.CardEdition;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.item.PaperCardPredicates;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.IterableUtil;
import forge.util.StreamUtil;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Data class that will be used to read Json configuration files
 * BiomeData
 * contains the information for a "reward"
 * that can be a random card, gold or items.
 * Also used for deck generation and shops
 */
public class RewardData implements Serializable {
    @Serial
    private static final long serialVersionUID = 3158932532013393718L;
    public String type; // TODO convert to enum
    public float probability;
    public int count;
    public int addMaxCount;
    public String cardName;
    public String itemName;
    public String[] itemNames;
    // Dynamic item pool by rarity (2026-08-12): when set on an "item"-type reward with no
    // itemNames, the pool becomes EVERY shop-worthy catalog item of this rarity
    // (ItemListData.getItemNamesByRarity) - the armory tiers use this instead of hand lists.
    public String itemRarity;
    public String[] editions;
    public String[] colors;
    public int startDate;
    public int endDate;
    public String[] rarity;
    public String[] subTypes;
    public String[] cardTypes;
    public String[] superTypes;
    public int[] manaCosts;
    public String[] keyWords;
    public String colorType;
    public String cardText;
    public boolean matchAllSubTypes;
    public boolean matchAllColors;
    public RewardData[] cardUnion;
    public String[] deckNeeds;
    public RewardData[] rotation;
    public Deck cardPack;
    public String sourceDeck;
    public String minDate;
    // Shop-only card dedup (2026-08-15): when true, CardUtil.generateCards()/the Union branch
    // pick unique card NAMES (shuffle-then-take-front, gracefully capped at the pool's own
    // unique-name count) instead of independent picks with replacement. NEVER set in JSON data -
    // stamped only via EditionProgression.restrictShopRewardsForCurrentTown()'s private
    // restrictToEditions(..., uniqueCards=true) call. Every OTHER restrictToEditions() caller
    // (EnemySprite's monster loot, EditionProgression.restrictDungeonRewardsForCurrentPoi()) goes
    // through the public 2-arg overload, which always passes false - both legitimately need
    // repeats, same as deck generation (which never goes through restrictToEditions() at all).
    // (2026-08-15 review finding: this used to be stamped unconditionally inside the shared
    // helper, silently deduping ordinary enemy loot and unauthored dungeon chests too.)
    public transient boolean uniqueCards;

    public RewardData() { }

    public RewardData(RewardData rewardData) {
        if (rewardData == null)
            return;

        type             = rewardData.type;
        probability      = rewardData.probability;
        count            = rewardData.count;
        addMaxCount      = rewardData.addMaxCount;
        cardName         = rewardData.cardName;
        itemName         = rewardData.itemName;
        startDate        = rewardData.startDate;
        endDate          = rewardData.endDate;
        itemNames        = rewardData.itemNames == null ? null : rewardData.itemNames.clone();
        itemRarity       = rewardData.itemRarity;
        editions         = rewardData.editions == null ? null : rewardData.editions.clone();
        colors           = rewardData.colors == null ? null : rewardData.colors.clone();
        rarity           = rewardData.rarity == null ? null : rewardData.rarity.clone();
        subTypes         = rewardData.subTypes == null ? null : rewardData.subTypes.clone();
        cardTypes        = rewardData.cardTypes == null ? null : rewardData.cardTypes.clone();
        superTypes       = rewardData.superTypes == null ? null : rewardData.superTypes.clone();
        manaCosts        = rewardData.manaCosts == null ? null : rewardData.manaCosts.clone();
        keyWords         = rewardData.keyWords == null ? null : rewardData.keyWords.clone();
        colorType        = rewardData.colorType;
        cardText         = rewardData.cardText;
        matchAllSubTypes = rewardData.matchAllSubTypes;
        matchAllColors   = rewardData.matchAllColors;
        cardUnion        = rewardData.cardUnion == null ? null : rewardData.cardUnion.clone();
        rotation         = rewardData.rotation == null ? null : rewardData.rotation.clone();
        deckNeeds        = rewardData.deckNeeds == null ? null : rewardData.deckNeeds.clone();
        cardPack         = rewardData.cardPack;
        sourceDeck       = rewardData.sourceDeck;
        minDate          = rewardData.minDate;
        uniqueCards      = rewardData.uniqueCards;
    }

    /** Union-branch per-pick finishing (2026-08-15, extracted when the shop dedup split the pick
     *  loop in two): printing remap first (the pool pick itself can BE an out-of-list printing -
     *  a VOW-restricted shop selling the DBL reprint, per the 2026-08-13 screenshot audit; safe
     *  to key off the OUTER editions since EditionProgression.restrictToEditions() stamps the
     *  outer and every nested cardUnion entry with the same list), then the all-card-variants art
     *  re-roll, then a SECOND remap - getCardByNameAndEdition()'s fail-open fallbacks ignore
     *  editions entirely and were silently undoing the first remap (the 2026-08-15 printing-leak
     *  root cause, same fix as CardUtil's own finishCandidate()). */
    private PaperCard finishUnionCard(PaperCard cardTemplate, boolean allCardVariants, java.util.Random rewardRandom) {
        cardTemplate = CardUtil.remapToEditionList(cardTemplate, this.editions, rewardRandom);
        if (allCardVariants) {
            PaperCard variant = CardUtil.getCardByNameAndEdition(cardTemplate.getCardName(), cardTemplate.getEdition());
            if (variant != null)
                cardTemplate = CardUtil.remapToEditionList(variant, this.editions, rewardRandom);
        }
        return cardTemplate;
    }

    private static Iterable<PaperCard> allCards;
    private static Iterable<PaperCard> allEnemyCards;

    static private void initializeAllCards() {
        ConfigData configData = Config.instance().getConfigData();
        RewardData legals = configData.legalCards;

        List<Predicate<PaperCard>> filters = new ArrayList<>();

        if (legals != null)
            filters.add(new CardUtil.CardPredicate(legals, true));
        
        // Filter out by editions and obtainability
        if (configData.allowedEditions != null && configData.allowedEditions.length > 0)
            filters.add(PaperCardPredicates.printedInAnyEditions(configData.allowedEditions));
        else if (configData.restrictedEditions != null && configData.restrictedEditions.length > 0)
            filters.add(PaperCardPredicates.isObtainableNotRestricted(configData.restrictedEditions));
        else
            filters.add(PaperCardPredicates.isObtainableAnyEdition());

        if (Config.instance().getSettingData().excludeAlchemyVariants)
            filters.add(PaperCardPredicates.IS_REBALANCED.negate());

        if (!FModel.getPreferences().getPrefBoolean(FPref.UI_ANTE))
            filters.add(pc -> !pc.getRules().hasKeyword("Remove CARDNAME from your deck before playing if you're not playing for ante."));

        if (!AdventurePlayer.current().isCommanderMode())
            filters.add(pc -> !pc.getRules().getAiHints().getRemNonCommanderDecks());

        filters.add(pc -> !(pc.getRules().isCustom() && pc.getImageKey(false).startsWith(ImageKeys.ADVENTURECARD_PREFIX)));

        Set<String> restrictedCards = new HashSet<>(Arrays.asList(configData.restrictedCards));
        filters.add(pc -> !restrictedCards.contains(pc.getName()));
        if (!restrictedCards.isEmpty())
            System.out.println("[TFR-RestrictedCards] main reward/shop/booster pool built with "
                    + restrictedCards.size() + " card(s) excluded");

        // Filter out specific cards.
        allCards = CardUtil.getFullCardPool(false).stream()
                .filter(IterableUtil.and(filters))
                .collect(Collectors.toList());

        //Filter AI cards for enemies.
        allEnemyCards = IterableUtil.filter(allCards, input -> {
            if (input == null) return false;
            return !input.getRules().getAiHints().getRemAIDecks();
        });
    }

    static public Iterable<PaperCard> getAllCards() {
        if (allCards == null)
            initializeAllCards();
        return allCards;
    }

    public static void invalidateCardPool() {
        allCards = null;
    }

    // Restricted Cards enforcement for sourceDeck-based rewards (2026-08-22 fix, MOD_CHANGELOG.md).
    // initializeAllCards()'s filter above only ever applied to the generic rarity-weighted pool -
    // a sourceDeck reward (the "Union" branch's per-entry sourceDeck below, and the "card"/
    // "randomCard" branch's own sourceDeck case) reads a .dck file's card list directly via
    // CardUtil.getDeck() and never consulted configData.restrictedCards at all. Root cause of the
    // user's "2 Sol Rings in a 40-card deck" / "Commander-only signet rewards" report:
    // common/decks/rewards/Alt-Art_Staples.dck (a boss-kill reward deck referenced by dozens of
    // enemies) lists 30 separate Sol Ring printings + 21 separate Arcane Signet printings + all 10
    // guild Signets as literal entries, and that reward path bypassed this list entirely - worse,
    // Sol Ring's 30 printings made it wildly over-weighted vs. every single-copy card in the same
    // pool. Logs only when something is actually removed (same convention as ContentFilterTables'
    // [ContentFilter] lines) so ordinary sourceDeck rewards (the funny-card shops, the goblin king
    // deck, etc.) that contain nothing restricted stay silent.
    private static List<PaperCard> filterRestrictedCards(List<PaperCard> cards, String sourceDeckName) {
        String[] restrictedArr = Config.instance().getConfigData().restrictedCards;
        if (restrictedArr == null || restrictedArr.length == 0 || cards == null || cards.isEmpty())
            return cards;
        Set<String> restricted = new HashSet<>(Arrays.asList(restrictedArr));
        List<PaperCard> filtered = new ArrayList<>(cards.size());
        Set<String> removedNames = null;
        int removedCount = 0;
        for (PaperCard card : cards) {
            if (card != null && restricted.contains(card.getName())) {
                if (removedNames == null) removedNames = new LinkedHashSet<>();
                removedNames.add(card.getName());
                removedCount++;
            } else {
                filtered.add(card);
            }
        }
        if (removedCount > 0)
            System.out.println("[TFR-RestrictedCards] " + sourceDeckName + ": filtered " + removedCount
                    + " restricted-card printing(s) (" + removedNames + ")");
        return filtered;
    }

    // Weighted item-rarity roll (user spec 2026-08-12): Common 60% / Uncommon 30% / Rare 8% /
    // Mythic 2%, cumulative boundaries. Shared by every "item" reward using itemRarity="Weighted"
    // (currently the Armory family) so the odds live in exactly one place.
    private static final String[] WEIGHTED_ITEM_RARITY_TIERS = {"Common", "Uncommon", "Rare", "Mythic"};
    private static final float[] WEIGHTED_ITEM_RARITY_CUMULATIVE = {60f, 90f, 98f, 100f};

    private static String rollWeightedItemRarity(Random random) {
        float roll = random.nextFloat() * 100f;
        for (int i = 0; i < WEIGHTED_ITEM_RARITY_CUMULATIVE.length; i++)
            if (roll < WEIGHTED_ITEM_RARITY_CUMULATIVE[i])
                return WEIGHTED_ITEM_RARITY_TIERS[i];
        return "Mythic"; // unreachable (last boundary is 100), kept as a safe fallback
    }

    public Array<Reward> generate(boolean isForEnemy, boolean useSeedlessRandom) {
        return generate(isForEnemy, null, useSeedlessRandom);
    }

    public Array<Reward> generate(boolean isForEnemy, boolean useSeedlessRandom, boolean isNoSell) {
        return generate(isForEnemy, null, useSeedlessRandom, isNoSell);
    }

    public Array<Reward> generate(boolean isForEnemy, Iterable<PaperCard> cards, boolean useSeedlessRandom){
        return generate(isForEnemy, cards, useSeedlessRandom, false);
    }

    public Array<Reward> generate(boolean isForEnemy, Iterable<PaperCard> cards, boolean useSeedlessRandom, boolean isNoSell) {
        boolean allCardVariants = Config.instance().getSettingData().useAllCardVariants;
        Random rewardRandom = useSeedlessRandom ? new Random() : WorldSave.getCurrentSave().getWorld().getRandom();
        //Keep using same generation method for shop rewards, but fully randomize loot drops by not using the instance pre-seeded by the map

        if (allCards==null)
            initializeAllCards();
        Array<Reward> ret=new Array<>();

        if (probability == 0 || rewardRandom.nextFloat() <= probability) {
            if(type == null || type.isEmpty())
                type="randomCard";
            int maxCount = Math.round(addMaxCount * Current.player().getDifficulty().rewardMaxFactor);
            int addedCount = (maxCount > 0 ? rewardRandom.nextInt(maxCount) : 0);

            switch(type) {
                case "Union":
                    HashSet<PaperCard> pool = new HashSet<>();
                    for (RewardData r : cardUnion) {
                        if (r.cardName != null && !r.cardName.isEmpty() ) {
                            PaperCard pc;
                            if (allCardVariants) {
                                CardDb.CardRequest req = CardDb.CardRequest.fromString(r.cardName);
                                pc = (req.edition != null)
                                    ? CardUtil.getCardByNameAndEdition(req.cardName, req.edition)
                                    : CardUtil.getCardByName(req.cardName);
                            } else {
                                pc = StaticData.instance().getCommonCards().getCard(r.cardName);
                            }
                            if (pc != null)
                                pool.add(pc);
                        } else if (r.sourceDeck != null && !r.sourceDeck.isEmpty() ) {
                            pool.addAll(filterRestrictedCards(
                                    CardUtil.getDeck(r.sourceDeck, false, false, "", false, false).getAllCardsInASinglePool().toFlatList(),
                                    r.sourceDeck));
                        } else {
                            pool.addAll(CardUtil.getPredicateResult(allCards, r));
                        }
                    }
                    ArrayList<PaperCard> finalPool = new ArrayList<>(pool);

                    if (finalPool.size() > 0){
                        // Shop dedup (2026-08-15, same opt-in flag/pattern as CardUtil.
                        // generateCards() - see uniqueCards' own field comment): unique names,
                        // shuffle-then-take-front, graceful cap at the pool's unique-name count.
                        if (uniqueCards) {
                            Collections.shuffle(finalPool, rewardRandom);
                            Set<String> takenNames = new HashSet<>();
                            int added = 0;
                            for (PaperCard cardTemplate : finalPool) {
                                if (added >= count)
                                    break;
                                if (cardTemplate == null || !takenNames.add(cardTemplate.getCardName()))
                                    continue;
                                ret.add(new Reward(finishUnionCard(cardTemplate, allCardVariants, rewardRandom), isNoSell));
                                added++;
                            }
                        } else {
                            for (int i = 0; i < count; i++) {
                                PaperCard cardTemplate = finalPool.get(rewardRandom.nextInt(finalPool.size()));
                                if (cardTemplate != null)
                                    ret.add(new Reward(finishUnionCard(cardTemplate, allCardVariants, rewardRandom), isNoSell));
                            }
                        }
                    }
                    break;
                case "card":
                case "randomCard":
                    if (cardName != null && !cardName.isEmpty()) {
                        // Named-card rewards get the same printing remap as random picks
                        // (2026-08-15, printing-leak audit: this branch never consulted
                        // this.editions at all, so a named card at an edition-restricted shop
                        // always rendered its latest/random printing).
                        if (allCardVariants) {
                            CardDb.CardRequest request = CardDb.CardRequest.fromString(cardName);
                            PaperCard card = (request.edition != null)
                                ? CardUtil.getCardByNameAndEdition(request.cardName, request.edition)
                                : CardUtil.getCardByName(request.cardName);
                            if (card != null) {
                                for (int i = 0; i < count + addedCount; i++) {
                                    PaperCard finalCard = CardUtil.getCardByNameAndEdition(request.cardName, card.getEdition());
                                    if (finalCard != null) {
                                        finalCard = CardUtil.remapToEditionList(finalCard, this.editions, rewardRandom);
                                        ret.add(new Reward(finalCard, isNoSell));
                                    }
                                }
                            }
                        } else {
                            for (int i = 0; i < count + addedCount; i++) {
                                PaperCard card = StaticData.instance().getCommonCards().getCard(cardName);
                                if (card != null) {
                                    card = CardUtil.remapToEditionList(card, this.editions, rewardRandom);
                                    ret.add(new Reward(card, isNoSell));
                                } else
                                    System.err.println("Missing card: " + cardName);
                            }
                        }
                    } else if (sourceDeck != null && !sourceDeck.isEmpty()) {
                        List<PaperCard> sourcePool = filterRestrictedCards(
                                CardUtil.getDeck(sourceDeck, false, false, "", false, false).getAllCardsInASinglePool().toFlatList(),
                                sourceDeck);
                        for( PaperCard card : CardUtil.generateCards(sourcePool, this, count+addedCount, rewardRandom)) {
                            if (card != null)
                                ret.add(new Reward(card, isNoSell));
                        }
                    } else {
                        for (PaperCard card : CardUtil.generateCards(isForEnemy ? allEnemyCards:allCards,this, count + addedCount, rewardRandom)) {
                            if (card != null)
                                ret.add(new Reward(card, isNoSell));
                        }
                    }
                    break;
                case "item":
                    // Weighted rarity mix (user spec 2026-08-12, Armory): itemRarity="Weighted"
                    // rolls EACH slot's rarity independently (Common 60% / Uncommon 30% /
                    // Rare 8% / Mythic 2%) rather than resolving the whole reward to one fixed
                    // rarity - replaces the old rank-threshold shop-tier gate entirely, so a
                    // Mythic can appear in stock from the player's very first Armory visit.
                    if ("Weighted".equals(itemRarity)) {
                        Set<String> alreadyPicked = new HashSet<>();
                        int slots = count + addedCount;
                        for (int i = 0; i < slots; i++) {
                            String rolledRarity = rollWeightedItemRarity(rewardRandom);
                            List<String> rarityPool = new ArrayList<>(ItemListData.getItemNamesByRarity(rolledRarity));
                            rarityPool.removeAll(alreadyPicked); // same no-duplicate-within-one-roll guarantee as below
                            if (rarityPool.isEmpty())
                                continue; // this rarity's pool exhausted this roll - skip, don't dupe or crash
                            String itemName = rarityPool.get(rewardRandom.nextInt(rarityPool.size()));
                            alreadyPicked.add(itemName);
                            ItemData itemData = ItemListData.getItem(itemName);
                            if (itemData != null)
                                ret.add(new Reward(itemData));
                            else
                                System.err.println("Missing item: " + itemName);
                        }
                        break;
                    }
                    // itemRarity expands to the full catalog-by-rarity pool when no explicit
                    // list is given (see the field's own comment). Resolved here at generate
                    // time so the pool tracks the live, filter-table-aware catalog.
                    String[] resolvedNames = itemNames;
                    if (resolvedNames == null && itemRarity != null && !itemRarity.isEmpty()) {
                        java.util.List<String> byRarity = ItemListData.getItemNamesByRarity(itemRarity);
                        if (!byRarity.isEmpty())
                            resolvedNames = byRarity.toArray(new String[0]);
                    }
                    if(resolvedNames!=null) {
                        // No-duplicates-within-one-roll (2026-08-11, round 8 - user report:
                        // "There should never be 2 of the same item for sale"). The old loop
                        // picked count+addedCount times independently at random, so the same name
                        // could (and visibly did) come up twice in one shop's inventory. Shuffling
                        // a copy of the pool once and taking the front is still deterministic
                        // under the same rewardRandom seed as before (shop stock stays stable
                        // across re-renders/same-week visits), but never repeats a name within
                        // this roll - and gracefully caps at the pool's own size if a smaller
                        // pool (e.g. a 2-item pool) is asked for more than it can uniquely provide.
                        List<String> shuffledNames = new ArrayList<>(Arrays.asList(resolvedNames));
                        Collections.shuffle(shuffledNames, rewardRandom);
                        int uniqueCount = Math.min(count + addedCount, shuffledNames.size());
                        for (int i = 0; i < uniqueCount; i++) {
                            String itemName = shuffledNames.get(i);
                            ItemData itemData = ItemListData.getItem(itemName);
                            if (itemData != null)
                                ret.add(new Reward(itemData));
                            else
                                System.err.println("Missing item: " + itemName);
                        }
                    } else if (itemName != null && !itemName.isEmpty()) {
                        for (int i = 0; i < count + addedCount; i++) {
                            ItemData itemData = ItemListData.getItem(itemName);
                            if (itemData != null)
                                ret.add(new Reward(itemData));
                            else
                                System.err.println("Missing item: " + itemName);
                        }
                    }
                    break;
                case "cardPackShop": {
                    if (colors == null) {
                        CardEdition.Collection editions = FModel.getMagicDb().getEditions();
                        Predicate<CardEdition> filter = CardEdition.Predicates.CAN_MAKE_BOOSTER;
                        List<CardEdition> allEditions = new ArrayList<>();
                        StreamUtil.stream(editions)
                            .filter(filter)
                            .filter(CardEdition::hasBoosterTemplate)
                            .forEach(allEditions::add);
                        ConfigData configData = Config.instance().getConfigData();

                        if (this.editions != null && this.editions.length > 0) {
                            Set<String> allowed = new HashSet<>(Arrays.asList(this.editions));
                            allEditions.removeIf(q -> !allowed.contains(q.getCode()));
                        } else {
                            for (String restricted : configData.restrictedEditions) {
                                allEditions.removeIf(q -> q.getCode().equals(restricted));
                            }
                            for (String restrictedCard : configData.restrictedCards) {
                                allEditions.removeIf(cardEdition -> cardEdition.getObtainableCards().stream().anyMatch(
                                    o -> o.name().equals(restrictedCard)));
                            }
                            endDate = endDate == 0 ? 9999 : endDate;
                            allEditions.removeIf(q -> q.getDate().getYear()+1900 < startDate || q.getDate().getYear()+1900 > endDate);
                        }
                        // this.editions can come from a plane's curated unlockedEditions/starterEditions
                        // list (EditionProgression), which isn't validated against CAN_MAKE_BOOSTER -
                        // e.g. Jumpstart-family editions are valid for regular card rewards but have no
                        // "Draft" booster template, so they never appear in allEditions above. If every
                        // allowed edition falls into that gap, skip rather than nextInt(0)-crash.
                        if (!allEditions.isEmpty()) {
                            for (int i = 0; i < count + addedCount; i++) {
                                ret.add(new Reward(AdventureEventController.instance().generateBooster(
                                    allEditions.get(rewardRandom.nextInt(allEditions.size())).getCode())));
                            }
                        } else {
                            System.err.println("No booster-capable edition available for cardPackShop reward (editions=" + Arrays.toString(this.editions) + ")");
                        }
                    } else {
                        // Edition-restriction fix (2026-08-13) - this branch previously ignored
                        // this.editions entirely, unconditionally bypassing Progressive Set
                        // Unlocks for every colored-booster shop. See
                        // AdventureEventController.generateBoosterByColor(String, String[]).
                        for (int i = 0; i < count + addedCount; i++) {
                            ret.add(new Reward(AdventureEventController.instance().generateBoosterByColor(colors[0], this.editions)));
                        }
                    }
                    break;
                }
                case "landSketchbookShop":
                    Array<ItemData> sketchbookItems = ItemListData.getSketchBooks();
                    for (int i = 0; i < count + addedCount; i++) {
                        ItemData item = sketchbookItems.get(rewardRandom.nextInt(sketchbookItems.size));
                        if (item != null)
                            ret.add(new Reward(item));
                    }
                    break;
                case "cardPack":
                    if (cardPack!=null) {
                        if (isNoSell) {
                            cardPack.getTags().add("noSell");
                        }
                        ret.add(new Reward(cardPack, isNoSell));
                    }
                    break;
                case "deckCard":
                    if (cards == null)
                        return ret;
                    for (PaperCard card : CardUtil.generateCards(cards,this, count + addedCount + Current.player().bonusDeckCards(), rewardRandom)) {
                        if (card != null)
                            ret.add(new Reward(card, isNoSell));
                    }
                    break;
                case "gold":
                    ret.add(new Reward(count + addedCount));
                    break;
                case "life":
                    ret.add(new Reward(Reward.Type.Life, count + addedCount));
                    break;
                case "mana": //backwards compatibility for reward data
                case "shards":
                    ret.add(new Reward(shardsSubstituteType(rewardRandom), count + addedCount));
                    break;
                // Mod addition (The Forsaken Realms, 2026-08-10): Stone as a walkover-pickup
                // reward type, mirroring "shards" above - see Reward.Type.Stone.
                case "stone":
                    ret.add(new Reward(Reward.Type.Stone, count + addedCount));
                    break;
                // Mod addition (The Forsaken Realms, 2026-08-11): Wood as a walkover-pickup
                // reward type, mirroring "stone" above - see Reward.Type.Wood.
                case "wood":
                    ret.add(new Reward(Reward.Type.Wood, count + addedCount));
                    break;
            }
        }
        return ret;
    }

    // Resource loot variety (2026-08-11 user request, opt-in resourceLootVarietyEnabled - same
    // "must not affect Shandalar or any other stock plane" rule every mod feature follows):
    // "go through all Caves and replace 25% of Shards with Stone, and 25% of Shards in Forts with
    // Wood." Deliberately a per-pickup 25% ROLL rather than pre-selecting a fixed 25% of map
    // objects - converges to the same ~25% split over many pickups without needing to hand-edit
    // (or copy out of common/, which would break the plane-isolation rule) every cave/fort .tmx
    // file. "Cave" vs "Fort" is read from the CURRENT dungeon's own map path (mostRecentPOI, the
    // same context TerritoryControl.reThemedEnemyFor() already reads for enemy re-theming) since
    // that's the real cave/fort distinction in this game's content - the POI `type` field is not
    // reliable here (most Fort dungeons are type "dungeon", same as plenty of non-Fort dungeons).
    private static Reward.Type shardsSubstituteType(Random rewardRandom) {
        if (!Config.instance().getConfigData().resourceLootVarietyEnabled || rewardRandom.nextFloat() >= 0.25f)
            return Reward.Type.Shards;
        PointOfInterest current = AdventureQuestController.instance().mostRecentPOI;
        String mapPath = current == null || current.getData() == null ? null : current.getData().map;
        if (mapPath == null)
            return Reward.Type.Shards;
        if (mapPath.contains("/cave/"))
            return Reward.Type.Stone;
        if (mapPath.contains("/fort/"))
            return Reward.Type.Wood;
        return Reward.Type.Shards;
    }

    static public List<PaperCard> generateAllCards(Iterable<RewardData> dataList, boolean isForEnemy) {
        return rewardsToCards(generateAll(dataList, isForEnemy));
    }
    static public Iterable<Reward> generateAll(Iterable<RewardData> dataList, boolean isForEnemy) {
        Array<Reward> ret = new Array<Reward>();
        for (RewardData data : dataList)
            ret.addAll(data.generate(isForEnemy, false));
        return ret;
    }
    static public List<PaperCard> rewardsToCards(Iterable<Reward> dataList) {
        ArrayList<PaperCard> ret = new ArrayList<PaperCard>();

        boolean allCardVariants = Config.instance().getSettingData().useAllCardVariants;

        if (allCardVariants) {
            String basicLandEdition = "";
            for (Reward data : dataList) {
                PaperCard card = data.getCard();
                if (card.isVeryBasicLand()) {
                    // ensure that all basic lands share the same edition so the deck doesn't look odd
                    if (basicLandEdition.isEmpty()) {
                        basicLandEdition = card.getEdition();
                    }
                    ret.add(CardUtil.getCardByNameAndEdition(card.getName(), basicLandEdition));
                } else {
                    ret.add(card);
                }
            }
        } else {
            for (Reward data : dataList) {
                ret.add(data.getCard());
            }
        }
        return ret;
    }
}
