package forge.adventure.data;

import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.utils.Array;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.RewardScene;
import forge.adventure.util.AdventureEventController;
import forge.adventure.util.AdventureOverrides;
import forge.adventure.util.CardUtil;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.adventure.util.InnEventRepair;
import forge.adventure.util.Reward;
import forge.adventure.util.SeededRolls;
import forge.card.CardEdition;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.GameType;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.LimitedPlayer;
import forge.gamemodes.limited.LimitedPoolType;
import forge.gamemodes.limited.SealedDeckBuilder;
import forge.item.SealedTemplate;
import forge.item.generation.IUnOpenedProduct;
import forge.model.CardBlock;
import forge.model.FModel;
import forge.util.Aggregates;
import forge.util.IterableUtil;
import forge.util.Localizer;
import forge.util.MyRandom;
import forge.util.StreamUtil;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class AdventureEventData implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private static final int JUMPSTART_TO_PICK_FROM = 6;
    public transient BoosterDraft draft;
    public AdventureEventParticipant[] participants;
    public int rounds;
    public int currentRound;
    public AdventureEventRules eventRules = new AdventureEventRules();
    // Player-town Inn pool fingerprint (2026-08-31). Non-transient so it persists with the event;
    // a save written before this field existed deserializes it as 0, which reads as "legacy /
    // not a player-town event - leave alone". See EditionProgression.playerTownPoolStamp().
    public int playerTownPoolStamp = 0;
    /** Round 368 (the user: "Inn Tournaments should automatically switch/cycle at the start of each week"): the
     *  in-game week (SpawnTierWeighting.currentWeek - day 8 starts week 2) this event was dealt in; 0 = before this
     *  round. InnScene.initLocalEvent deals a fresh one once the week has turned. */
    public int createdWeek = 0;
    public AdventureEventReward[] rewards;
    public int eventOrigin;
    public String sourceID;
    public long eventSeed;
    public AdventureEventController.EventStyle style;
    public AdventureEventController.EventStatus eventStatus;
    public AdventureEventController.EventFormat format;
    private transient Random random = new Random();
    public Deck registeredDeck;
    public Deck rewardDeck; //Copy of registered before basic lands are added for event reward purposes
    public List<Deck> jumpstartBoosters = new ArrayList<>();
    public boolean isDraftComplete = false;
    public String description = "";
    public String[] packConfiguration = new String[0];
    public transient CardBlock cardBlock;
    public String cardBlockName;
    public boolean playerWon;
    public int matchesWon, matchesLost;
    // 2026-08-29 (Inn-tournament tutorial nudge): which specific Coin item, if any, paid for
    // entry - null if the player paid with gold/shards instead. Lets an early loss refund ONLY
    // an actually-spent Coin (never gold/shards, which are lost regardless of outcome). Set in
    // EventScene's entry dialog callback, read in setWinner()'s loss branch.
    public String enteredWithCoinItem;
    /** Round 334: what the entry cost in gold or shards (0 = not that, or entered before this field existed), so an
     *  event that turns out undealable can hand exactly that back - see EventScene.voidUndealableEvent(). */
    public int entryPaidGold;
    public int entryPaidShards;
    private Deck[] rewardPacks;

    public AdventureEventData(AdventureEventData other) {
        participants = other.participants.clone();
        rounds = other.rounds;
        eventRules = other.eventRules;
        // TFR round 996: null-safe, and the reward packs come along (stock left them null - getDescription's NPE)
        rewards = other.rewards == null ? null : other.rewards.clone();
        rewardPacks = other.rewardPacks == null ? null : other.rewardPacks.clone();
        eventOrigin = other.eventOrigin;
        sourceID = other.sourceID;
        eventSeed = other.eventSeed;
        style = other.style;
        random.setSeed(eventSeed);
        eventStatus = other.eventStatus;
        format = other.format;
        registeredDeck = other.registeredDeck;
        isDraftComplete = other.isDraftComplete;
        description = other.description;
        cardBlockName = other.cardBlockName;
        packConfiguration = other.packConfiguration;
        playerWon = other.playerWon;
        matchesWon = other.matchesWon;
        matchesLost = other.matchesLost;
        enteredWithCoinItem = other.enteredWithCoinItem;
        entryPaidGold = other.entryPaidGold; // round 334
        entryPaidShards = other.entryPaidShards;
    }

    public Deck[] getRewardPacks(int count) {
        Deck[] ret = new Deck[count];
        for (int i = 0; i < count; i++) {
            ret[i] = AdventureEventController.instance().generateBooster(Aggregates.random(getCardBlock().getSets()).getCode());
        }
        return ret;
    }

    private Random getEventRandom() {
        if (random == null)
            random = (eventSeed > 0 ? new Random(eventSeed) : new Random());
        return random;
    }

    public AdventureEventData(Long seed, AdventureEventController.EventFormat selectedFormat) {
        this(seed, selectedFormat, false);
    }

    public AdventureEventData(Long seed, AdventureEventController.EventFormat selectedFormat, boolean playerTown) {
        this(seed, selectedFormat, playerTown, null);
    }

    /** Round 954: {@code innPointID} is the Inn's town, for its color shard and its neighbors' sets (null = neither). */
    public AdventureEventData(Long seed, AdventureEventController.EventFormat selectedFormat, boolean playerTown, String innPointID) {
        this(seed, selectedFormat, null, pickCardBlockByFormat(selectedFormat, playerTown, innPointID));
        // Stamped so a cached Available event can tell that the player has unlocked a set since it
        // was rolled - see InnScene.initLocalEvent(). 0 on a non-player town or a legacy event.
        this.playerTownPoolStamp = playerTown
                ? forge.adventure.util.EditionProgression.playerTownPoolStamp() : 0;
    }

    public AdventureEventData(Long seed, AdventureEventController.EventFormat selectedFormat, CardBlock cardBlock) {
        this(seed, selectedFormat, null, cardBlock);
    }

    public AdventureEventData(Long seed, AdventureEventController.EventFormat selectedFormat, AdventureEventController.EventStyle style, CardBlock cardBlock) {
        setEventSeed(seed);
        eventStatus = AdventureEventController.EventStatus.Available;
        registeredDeck = new Deck();
        format = selectedFormat;
        this.cardBlock = cardBlock;
        if (cardBlock == null)
            return;
        cardBlockName = cardBlock.getName();
        if (format == AdventureEventController.EventFormat.Draft) {
            setupDraftRewards();
        } else if (format == AdventureEventController.EventFormat.Sealed) {
            setupSealedRewards();
        } else if (format == AdventureEventController.EventFormat.Jumpstart) {
            jumpstartBoosters = AdventureEventController.instance().getJumpstartBoosters(cardBlock, JUMPSTART_TO_PICK_FROM);
            packConfiguration = new String[]{cardBlock.getLandSet().getCode(), cardBlock.getLandSet().getCode(), cardBlock.getLandSet().getCode()};

            setupJumpstartRewards();
        }

        if(style == null) {
            // If the chosen event seed recommends a four-person pod, run it as a RoundRobin
            if (getRecommendedPodSize(cardBlock) == 4)
                style = AdventureEventController.EventStyle.RoundRobin;
            else
                style = AdventureEventController.EventStyle.Bracket;
        }
        this.style = style;
    }

    public void setEventSeed(long seed) {
        eventSeed = seed; // round 972: stock only seeded the Random, so every save stored 0 and a reload drew unseeded
        getEventRandom().setSeed(seed);
    }

    /** Round 972 (Fable P2-12): the seed a reload of this event reaches again - the stored event seed, or for an event
     *  saved before round 972 (seed 0) one built from what that save does hold: its Inn, its block, its week, its format. */
    long stableSeed() {
        long world = eventSeed != 0 || Current.world() == null ? 0L : Current.world().getSeed();
        return SeededRolls.eventSeed(eventSeed, world, sourceID, cardBlockName, createdWeek, format);
    }

    /** Round 972: a Random for one of this event's openings (the draft's packs, the Sealed pool), at its own seed, so
     *  it does not depend on how far the event's stream ran before the save. The event seed is the real-world date plus
     *  the Inn (stock's getEventSeed), so the block and the in-game week are mixed in: two weeks' events at one Inn on
     *  one real day must not open the same packs. */
    private Random openingRandom(String what) {
        long seed = SeededRolls.openingSeed(stableSeed(), what, cardBlockName, createdWeek);
        System.out.println("[TFR-Seed] Inn " + format + " " + what + " (" + cardBlockName + ", " + sourceID + ", event seed "
                + (eventSeed != 0 ? eventSeed : "0 - built from the event") + "): seed " + seed);
        return new Random(seed);
    }

    /** TFR round 996 (notes/logs-1109b.md P2-2): a saved event whose prize state comes back broken is named in the log at
     *  load, so the next player log shows whether the broken shape was on disk. Repaired where it is read, below. */
    @Serial
    private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
        in.defaultReadObject();
        try {
            String broken = InnEventRepair.brokenShape(rewards, rewardPacks, usesRewardPacks());
            if (broken != null && format != null)
                System.out.println("[TFR-InnEvent] loaded with a broken reward state: " + broken + " (" + shapeText() + ")");
        } catch (Throwable ignored) {
            // a log line must never fail a load
        }
    }

    /** Draft and Sealed read three reward packs; Jumpstart has none by design. */
    private boolean usesRewardPacks() {
        return format == AdventureEventController.EventFormat.Draft || format == AdventureEventController.EventFormat.Sealed;
    }

    private String shapeText() {
        return "format=" + format + ", status=" + eventStatus + ", rewards.length=" + (rewards == null ? "null" : rewards.length)
                + ", rewardPacks null=" + (rewardPacks == null) + ", wins=" + matchesWon + "-" + matchesLost
                + ", block=" + cardBlockName + ", inn=" + sourceID + ", event seed=" + eventSeed;
    }

    /**
     * TFR round 996 (notes/logs-1109b.md P2-2): a player's saved Inn Draft came back with no reward packs (the Inn's
     * description threw an NPE) and then with a three-slot prize table (the champion's prize threw writing slot 3, and was
     * never paid). No constructor builds either shape and the cause was not found, so the state is checked and repaired
     * before it is read: the packs read back from the prize table (the constructors put the same decks there), else dealt
     * again from the event's seed; the table grown to four slots; a lost tier rebuilt. Logs each repair.
     * @return true when something was repaired
     */
    boolean repairRewardState(String where) {
        if (format != AdventureEventController.EventFormat.Draft && format != AdventureEventController.EventFormat.Sealed
                && format != AdventureEventController.EventFormat.Jumpstart)
            return false;
        String broken = InnEventRepair.brokenShape(rewards, rewardPacks, usesRewardPacks());
        if (broken == null)
            return false;
        String shape = shapeText();
        List<String> done = new ArrayList<>();
        if (usesRewardPacks() && !InnEventRepair.packsWhole(rewardPacks)) {
            Deck[] packs = InnEventRepair.packsFromTiers(rewards);
            if (packs != null) {
                done.add("reward packs read back from the prize table");
            } else {
                packs = regenerateRewardPacks();
                done.add(packs != null ? "reward packs dealt again from the event seed"
                        : "no reward packs - the text names a generic booster");
            }
            if (packs != null)
                rewardPacks = packs;
        }
        AdventureEventReward[] grown = InnEventRepair.grown(rewards);
        if (grown != rewards) {
            done.add("prize table grown from " + (rewards == null ? "null" : rewards.length) + " to " + grown.length + " slots");
            rewards = grown;
        }
        for (int i = 0; i < InnEventRepair.TIERS; i++) {
            if (rewards[i] != null)
                continue;
            if (format == AdventureEventController.EventFormat.Jumpstart) {
                AdventureEventReward[] kept = rewards;
                setupJumpstartRewards();
                AdventureEventReward stock = rewards[i];
                rewards = kept;
                rewards[i] = stock;
            } else {
                rewards[i] = InnEventRepair.packTier(i, rewardPacks,
                        format == AdventureEventController.EventFormat.Sealed ? "Silver Challenge Coin" : "Challenge Coin");
            }
            done.add("prize tier " + i + " rebuilt");
        }
        System.out.println("[TFR-InnEvent] reward state repaired: " + String.join("; ", done) + " (" + where + "; was "
                + broken + "; " + shape + ")");
        return true;
    }

    /** Round 996: the three reward packs dealt again at this event's own seed, as a reload deals its draft packs; null
     *  when the block cannot be read back. */
    private Deck[] regenerateRewardPacks() {
        if (getCardBlock() == null)
            return null;
        Random placeholder = MyRandom.getRandom();
        try {
            MyRandom.setRandom(openingRandom("reward packs"));
            Deck[] packs = getRewardPacks(InnEventRepair.PACKS);
            return InnEventRepair.packsWhole(packs) ? packs : null;
        } catch (RuntimeException e) {
            System.out.println("[TFR-InnEvent] reward packs could not be dealt again: " + e);
            return null;
        } finally {
            MyRandom.setRandom(placeholder);
        }
    }

    /** Round 996: the name a reward pack goes by in the description when the pack itself is gone. */
    private String packFallback(int i) {
        if (packConfiguration != null && packConfiguration.length > 0 && packConfiguration[i % packConfiguration.length] != null)
            return packConfiguration[i % packConfiguration.length];
        return cardBlockName;
    }

    public CardBlock getCardBlock() {
        if (cardBlock == null) {
            cardBlock = AdventureOverrides.instance().getBlock(cardBlockName);
        }
        return cardBlock;
    }

    public BoosterDraft getDraft() {
        if (format != AdventureEventController.EventFormat.Draft)
            return null;

        Random placeholder = MyRandom.getRandom();
        MyRandom.setRandom(getEventRandom());
        if (draft == null && (eventStatus == AdventureEventController.EventStatus.Available || eventStatus == AdventureEventController.EventStatus.Entered)) {
            // Round 972: the draft is transient, so a reload deals it again - from the same packs now
            MyRandom.setRandom(openingRandom("draft"));
            CardBlock block = getCardBlock();
            CardUtil.withRestrictedAwareBoosterResolver(block, () -> {
                draft = BoosterDraft.createDraft(LimitedPoolType.Block, block, packConfiguration, participants.length);
                registeredDeck = draft.getHumanPlayer().getDeck();
                assignPlayerNames(draft);
            });
        }
        if (packConfiguration == null) {
            packConfiguration = getBoosterConfiguration(getCardBlock());
        }
        MyRandom.setRandom(placeholder);
        return draft;
    }

    // Widened from private (2026-08-24, Inn Tournament Re-roll, user spec: "let the player re-roll
    // the tournament draft set... keep the sets gated to each Inn/color"). InnScene.rerollEvent()
    // calls this directly so the re-roll draws from the exact same EditionProgression-gated pool
    // as the original roll (the gating lives inside pickWeightedCardBlock/pickJumpstartCardBlock
    // below, not duplicated in InnScene) - no other behavior change, still a pure pick.
    public static CardBlock pickCardBlockByFormat(AdventureEventController.EventFormat format) {
        return pickCardBlockByFormat(format, false);
    }

    /** @param playerTown true for an Inn in a town the player owns - narrows the pool to the
     *                    player's race editions plus their unlocked sets (user spec 2026-08-31). */
    public static CardBlock pickCardBlockByFormat(AdventureEventController.EventFormat format, boolean playerTown) {
        return pickCardBlockByFormat(format, playerTown, null);
    }

    /** Round 954: {@code innPointID}, the Inn's town id, feeds innColorShardEvents and innSpreadNearbyTowns. */
    public static CardBlock pickCardBlockByFormat(AdventureEventController.EventFormat format, boolean playerTown, String innPointID) {
        return switch (format) {
            case Draft, Sealed -> pickWeightedCardBlock(format.toString(), playerTown, innPointID);
            case Jumpstart -> pickJumpstartCardBlock(playerTown);
            case Constructed -> null;
        };
    }

    /**
     * The edition pool an event roll should use, and the fallback that keeps the Inn alive.
     * <p>
     * A player town narrows to race+unlocked. That pool is often SMALL, and a Draft block is only
     * legal when EVERY set in it is inside the pool - so for most races, early on, no block
     * qualifies at all and the Inn would just say "No events at this time" indefinitely. The
     * callers therefore try the narrow pool first and fall back to the global one rather than
     * offering nothing; as research unlocks more sets the narrow pool starts winning on its own.
     */
    private static java.util.Set<String> eventPool(boolean playerTown, String townColor) {
        if (!playerTown)
            return forge.adventure.util.EditionProgression.innEventEditionCodes(townColor); // round 954: + color shard
        java.util.Set<String> narrow = forge.adventure.util.EditionProgression.playerTownEventEditionCodes();
        return narrow != null ? narrow : forge.adventure.util.EditionProgression.eventAllowedEditionCodes();
    }

    // Diagnostic logging (2026-08-13, user request - "can we somehow create a log for future
    // testing" for edition-restriction decisions) - Inn tournament/event edition selection had NO
    // TFR tag at all before this. Still not per-AI-color: an AI or neutral town's Inn draws from
    // one global playerUnlocked+neutralShard set regardless of which town it is.
    //
    // As of 2026-08-31 the town is no longer irrelevant, though: a PLAYER-OWNED town narrows to
    // the player's race editions plus their unlocked sets, so the line now reports which kind of
    // town rolled it and whether the narrow pool was empty enough to force the global fallback.
    private static void logInnEditions(String format, java.util.Set<String> allowed, CardBlock picked) {
        logInnEditions(format, allowed, picked, false, false);
    }

    private static void logInnEditions(String format, java.util.Set<String> allowed, CardBlock picked,
                                       boolean playerTown, boolean fellBack) {
        List<String> playerUnlocked = AdventurePlayer.current() != null
                ? new ArrayList<>(AdventurePlayer.current().getUnlockedEditions()) : Collections.emptyList();
        List<String> neutralShard = forge.adventure.util.EditionProgression.getEditionsForColor(
                forge.adventure.world.WorldSave.getCurrentSave().getWorld(), forge.adventure.util.EditionProgression.NEUTRAL);
        java.util.Set<String> raceCodes = forge.adventure.util.EditionProgression.raceEditionCodes(AdventurePlayer.current());
        System.out.println("[TFR-InnEditions] format=" + format
                + " town=" + (playerTown ? "PLAYER" : "ai/neutral")
                + (playerTown ? " raceEditions(" + raceCodes.size() + ")=" + raceCodes : "")
                + (fellBack ? " NARROW-POOL-EMPTY->fell back to the global pool" : "")
                + " playerUnlocked(" + playerUnlocked.size() + ")=" + playerUnlocked
                + " neutralShard(" + neutralShard.size() + ")=" + neutralShard
                + " allowed(" + allowed.size() + ")=" + allowed
                + " -> " + (picked != null ? "picked=" + blockLabel(picked) : "NONE (no legal blocks)"));
    }

    /** Round 417: the block's own sets, not its land set - "picked=M19" for an Ultimate Masters draft misled a log reader. */
    private static String blockLabel(CardBlock block) {
        List<String> codes = new ArrayList<>();
        for (CardEdition ed : block.getSets())
            codes.add(ed.getCode());
        String label = block.getName() + " " + codes;
        CardEdition land = block.getLandSet();
        if (land != null && !codes.contains(land.getCode()))
            label += " (basics from " + land.getCode() + ")";
        return label;
    }

    private static final Predicate<CardEdition> filterPioneer = FModel.getFormats().getPioneer().editionLegalPredicate;
    private static final Predicate<CardEdition> filterModern = FModel.getFormats().getModern().editionLegalPredicate;
    private static final Predicate<CardEdition> filterVintage = FModel.getFormats().getVintage().editionLegalPredicate;
    private static final Predicate<CardEdition> filterStandard = FModel.getFormats().getStandard().editionLegalPredicate;

    public static Predicate<CardEdition> selectSetPool() {
        if (Config.instance().getConfigData().vintageOnlyEditions) {
            return filterVintage;
        }

        final int rollD100 = MyRandom.getRandom().nextInt(100);
        Predicate<CardEdition> rolledFilter;
        if (rollD100 < 30) {
            rolledFilter = filterStandard;
        } else if (rollD100 < 60) {
            // Remove standard from older pools because its representation is already inflated.
            rolledFilter = filterPioneer.and(filterStandard.negate());
        } else if (rollD100 < 80) {
            rolledFilter = filterModern.and(filterStandard.negate());
        } else {
            rolledFilter = filterVintage.and(filterStandard.negate());
        }
        return rolledFilter;
    }

    private static CardBlock pickWeightedCardBlock(String formatForLogging) {
        return pickWeightedCardBlock(formatForLogging, false);
    }

    private static CardBlock pickWeightedCardBlock(String formatForLogging, boolean playerTown) {
        return pickWeightedCardBlock(formatForLogging, playerTown, null);
    }

    private static CardBlock pickWeightedCardBlock(String formatForLogging, boolean playerTown, String innPointID) {
        CardEdition.Collection editions = FModel.getMagicDb().getEditions();
        ConfigData configData = Config.instance().getConfigData();
        // Round 954: the Inn's town, its color (an AI color town only, when innColorShardEvents is on) and the label
        // the [TFR-InnEvent] line prints. A player town (restored or the Capitol) keeps race + unlocked below.
        forge.adventure.pointofintrest.PointOfInterest innTown = innPointID == null ? null : innTownById(innPointID);
        String aiColor = innTown == null || playerTown ? null : forge.adventure.util.ColorReputation.colorOfTown(innTown.getData());
        String townColor = configData.innColorShardEvents ? aiColor : null;
        // Round 954 (follow-up, innNeutralNearestColor): a neutral town's Inn (a working Waste town) borrows the color
        // shard of the nearest AI color town or capital.
        String borrowedColor = innTown != null && !playerTown && aiColor == null
                && configData.innColorShardEvents && configData.innNeutralNearestColor ? nearestAiColor(innTown) : null;
        if (borrowedColor != null)
            townColor = borrowedColor;
        Predicate<CardEdition> filter = CardEdition.Predicates.CAN_MAKE_BOOSTER;

        if(configData.allowedEvents != null && configData.allowedEvents.length > 0) {
            Set<String> allowedEvents = Set.of(configData.allowedEvents);
            filter = filter.and(q -> allowedEvents.contains(q.getCode()));
        }
        else
        {
            //The whitelist beats all other filters.
            if(configData.restrictedEvents != null) {
                //Temporary restriction until rewards are more diverse - don't want to award restricted cards so these editions need different rewards added.
                //Also includes sets that use conspiracy or commander drafts.
                Set<String> restrictedEvents = Set.of(configData.restrictedEvents);
                filter = filter.and((q) -> !restrictedEvents.contains(q.getCode()));
            }
            if (configData.allowedEditions != null && configData.allowedEditions.length > 0) {
                Set<String> allowed = Set.of(configData.allowedEditions);
                filter = filter.and(q -> allowed.contains(q.getCode()));
            } else if(configData.restrictedEditions != null) {
                List<String> restrictedList = Arrays.asList(configData.restrictedEditions);
                Set<String> restricted = new HashSet<>(restrictedList); //Would use Set.of but that throws an error if there's any duplicates, and users edit these lists all the time.
                filter = filter.and(q -> !restricted.contains(q.getCode()));
            }

            // Round 954: innUniformBlockPick drops the tier roll - in TFR's small pools its "30% Standard only" lands on
            // the race's one Standard set nearly every time (a Dragon save became a Tarkir: Dragonstorm save).
            if (!configData.innUniformBlockPick) {
                Predicate<CardEdition> setPoolFilter = selectSetPool();
                if(editions.stream().anyMatch(setPoolFilter))
                    filter = filter.and(setPoolFilter);
            }
        }

        // Progressive Set Unlocks (user spec 2026-08-12): Inn tournaments may only use editions
        // the player has researched/started with, plus the neutral shard. Applied OUTSIDE the
        // allowedEvents branch above deliberately - the progression lock must hold even when a
        // plane whitelists events. Null = feature off/unseeded, no restriction. An emptied
        // result is already graceful: no legal blocks -> null -> "No events at this time".
        // Round 954: with innColorShardEvents an AI color town adds its color shard (the user's OK reverses the 2026-08-12
        // "Inns = researched + neutral" rule for those towns only).
        final java.util.Set<String> narrowPool = eventPool(playerTown, townColor);
        java.util.Set<String> loggedPool = narrowPool;
        final Predicate<CardEdition> baseFilter = filter;
        if (narrowPool != null)
            filter = filter.and(q -> narrowPool.contains(q.getCode()));

        List<CardEdition> allEditions = new ArrayList<>();
        StreamUtil.stream(editions)
                .filter(filter)
                .filter(CardEdition::hasBoosterTemplate)
                .forEach(allEditions::add);

        List<CardBlock> legalBlocks = getValidDraftBlocks(allEditions);

        // Player-town fallback (2026-08-31). A draft block is legal only when EVERY set in it is
        // inside the pool, so a narrow race+unlocked pool very often matches no block at all -
        // especially early, when the player has two or three sets. Rather than leave the player's
        // own Inn permanently empty, fall back to the ordinary global pool and say so in the log.
        // The narrowing then starts biting on its own as research widens the pool.
        boolean fellBack = false;
        if (playerTown && legalBlocks.isEmpty()) {
            final java.util.Set<String> wide = forge.adventure.util.EditionProgression.eventAllowedEditionCodes();
            Predicate<CardEdition> wideFilter = wide == null
                    ? baseFilter : baseFilter.and(q -> wide.contains(q.getCode()));
            List<CardEdition> wideEditions = new ArrayList<>();
            StreamUtil.stream(editions)
                    .filter(wideFilter)
                    .filter(CardEdition::hasBoosterTemplate)
                    .forEach(wideEditions::add);
            legalBlocks = getValidDraftBlocks(wideEditions);
            fellBack = true;
            loggedPool = wide;
        }

        // Round 954 (innSpreadNearbyTowns): a block sharing a set with an event showing at a neighbor's Inn is skipped,
        // unless that skips every legal block.
        Set<String> nearbySets = innTown == null || configData.innSpreadNearbyTowns <= 0
                ? Collections.emptySet() : setsShowingNearby(innTown, configData.innSpreadNearbyTowns);
        List<CardBlock> candidates = legalBlocks;
        Set<String> skipped = new TreeSet<>();
        if (!nearbySets.isEmpty()) {
            candidates = new ArrayList<>();
            for (CardBlock b : legalBlocks) {
                List<String> shared = new ArrayList<>();
                for (CardEdition ed : b.getSets())
                    if (nearbySets.contains(ed.getCode()))
                        shared.add(ed.getCode());
                if (shared.isEmpty())
                    candidates.add(b);
                else
                    skipped.addAll(shared);
            }
            if (candidates.isEmpty())
                candidates = legalBlocks;
        }

        CardBlock picked = candidates.isEmpty() ? null : Aggregates.random(candidates);
        if (loggedPool != null)
            logInnEditions(formatForLogging, loggedPool, picked, playerTown, fellBack);
        if (innTown != null) {
            String colorLabel = playerTown ? "player town"
                    : aiColor == null ? (borrowedColor == null ? "neutral" : "neutral, nearest " + borrowedColor)
                    : townColor == null ? aiColor + ", shared pool" : aiColor;
            System.out.println("[TFR-InnEvent] " + innTown.getDisplayName() + " (" + colorLabel + "): pool "
                    + (loggedPool == null ? "all" : String.valueOf(loggedPool.size())) + " sets / " + legalBlocks.size()
                    + " legal blocks, picked " + (picked == null ? "nothing" : blockLabel(picked)) + " (" + formatForLogging
                    + "), skipped " + (skipped.isEmpty() ? "none" : skipped)
                    + (candidates == legalBlocks && !skipped.isEmpty() ? " - all taken, any block" : "")
                    + (nearbySets.isEmpty() ? "" : " (showing nearby: " + new TreeSet<>(nearbySets) + ")"));
        }
        return picked;
    }

    private static forge.adventure.pointofintrest.PointOfInterest innTownById(String id) {
        forge.adventure.world.WorldSave save = forge.adventure.world.WorldSave.getCurrentSave();
        if (save == null || save.getWorld() == null)
            return null;
        for (forge.adventure.pointofintrest.PointOfInterest poi : save.getWorld().getAllPointOfInterest())
            if (poi.getID().equals(id))
                return poi;
        return null;
    }

    /**
     * Round 954 (follow-up): the color of the AI color town or capital nearest to {@code town} by world distance, or null
     * when there is none. Read from the towns, not the territory map: a Waste town stands on neutral ground, and the
     * towns' names are what colorOfTown() and the shops already trust. A restored town counts for no color. A tie in
     * distance goes to the lower POI id, so the same town always borrows the same color.
     */
    private static String nearestAiColor(forge.adventure.pointofintrest.PointOfInterest town) {
        forge.adventure.world.WorldSave save = forge.adventure.world.WorldSave.getCurrentSave();
        if (save == null || save.getWorld() == null)
            return null;
        String best = null, bestId = null;
        float bestDst = Float.MAX_VALUE;
        for (forge.adventure.pointofintrest.PointOfInterest poi : save.getWorld().getAllPointOfInterest()) {
            String color = forge.adventure.util.ColorReputation.colorOfTown(poi.getData());
            if (color == null || poi.getID().equals(town.getID())
                    || forge.adventure.util.TownRestoration.isTownRestored(save.peekPointOfInterestChanges(poi.getID())))
                continue;
            float d = poi.getPosition().dst2(town.getPosition());
            if (d < bestDst || (d == bestDst && poi.getID().compareTo(bestId) < 0)) {
                best = color;
                bestId = poi.getID();
                bestDst = d;
            }
        }
        return best;
    }

    /**
     * Round 954: the set codes of the Draft/Sealed events showing at the Inns of the {@code count} towns nearest to
     * {@code town} (towns and capitals, by world distance). An event from an earlier week that is Available, Awarded or
     * Abandoned is left out: that Inn deals a new one on the next visit (round 368).
     */
    private static Set<String> setsShowingNearby(forge.adventure.pointofintrest.PointOfInterest town, int count) {
        forge.adventure.world.World world = forge.adventure.world.WorldSave.getCurrentSave().getWorld();
        AdventurePlayer player = AdventurePlayer.current();
        if (world == null || player == null || player.getEvents() == null || player.getEvents().isEmpty())
            return Collections.emptySet();
        List<forge.adventure.pointofintrest.PointOfInterest> towns = new ArrayList<>();
        for (forge.adventure.pointofintrest.PointOfInterest poi : world.getAllPointOfInterest())
            if (forge.adventure.util.RoadNetwork.isTownOrCapital(poi) && !poi.getID().equals(town.getID()))
                towns.add(poi);
        towns.sort(Comparator.comparingDouble(p -> p.getPosition().dst2(town.getPosition())));
        Set<String> nearIds = new HashSet<>();
        for (int i = 0; i < Math.min(count, towns.size()); i++)
            nearIds.add(towns.get(i).getID());
        int week = forge.adventure.util.SpawnTierWeighting.currentWeek(world);
        Set<String> sets = new HashSet<>();
        for (AdventureEventData e : player.getEvents()) {
            if (e.sourceID == null || !nearIds.contains(e.sourceID))
                continue;
            if (e.format != AdventureEventController.EventFormat.Draft && e.format != AdventureEventController.EventFormat.Sealed)
                continue;
            boolean settled = e.eventStatus == AdventureEventController.EventStatus.Available
                    || e.eventStatus == AdventureEventController.EventStatus.Awarded
                    || e.eventStatus == AdventureEventController.EventStatus.Abandoned;
            if (settled && week > 0 && e.createdWeek != week)
                continue;
            CardBlock block = e.getCardBlock();
            if (block != null)
                for (CardEdition ed : block.getSets())
                    sets.add(ed.getCode());
        }
        return sets;
    }

    public static List<CardBlock> getValidDraftBlocks(List<CardEdition> validEditions) {
        List<CardBlock> legalBlocks = new ArrayList<>();
        for (CardBlock b : AdventureOverrides.instance().allBlocks()) { // for each block
            if (b.getSets().isEmpty() || (b.getCntBoostersDraft() < 1))
                continue;
            if (!isValidDraftBlock(b, validEditions))
                continue;
            legalBlocks.add(b);
        }
        return legalBlocks;
    }

    private static boolean isValidDraftBlock(CardBlock b, List<CardEdition> validEditions) {
        for (CardEdition c : b.getSets()) {
            if (!validEditions.contains(c))
                return false;
            if (!c.hasBoosterTemplate())
                return false;
            if(c.getBoosterTemplate().getNumberOfCardsExpected() <= 7)
                return false;
        }
        return true;
    }

    private static CardBlock pickJumpstartCardBlock() {
        return pickJumpstartCardBlock(false);
    }

    /** Round 334: does this engine hold Jumpstart pack templates for the block's land set (the packs
     *  AdventureEventController.getJumpstartBoosters() deals from)? */
    public static boolean hasJumpstartPacks(CardBlock block) {
        if (block == null || block.getLandSet() == null)
            return false;
        String code = block.getLandSet().getCode();
        for (SealedTemplate template : forge.StaticData.instance().getSpecialBoosters())
            if (template.getEdition().contains(code))
                return true;
        return false;
    }

    /** Round 334: deal this Jumpstart event's packs again (a save whose event was made on a product with no packs,
     *  or whose packs did not survive). True when at least one pack could be dealt. */
    public boolean redealJumpstartPacks() {
        if (format != AdventureEventController.EventFormat.Jumpstart)
            return false;
        if (cardBlock == null && cardBlockName != null)
            cardBlock = AdventureOverrides.instance().getBlock(cardBlockName);
        if (cardBlock == null || !hasJumpstartPacks(cardBlock))
            return false;
        jumpstartBoosters = AdventureEventController.instance().getJumpstartBoosters(cardBlock, JUMPSTART_TO_PICK_FROM);
        System.out.println("[TFR-Event] Jumpstart packs dealt again for " + cardBlockName + ": " + jumpstartBoosters.size());
        return !jumpstartBoosters.isEmpty();
    }

    private static CardBlock pickJumpstartCardBlock(boolean playerTown) {
        Iterable<CardBlock> src = AdventureOverrides.instance().allBlocks(); //all blocks
        List<CardBlock> legalBlocks = new ArrayList<>();
        ConfigData configData = Config.instance().getConfigData();
        if (configData.allowedJumpstart != null) {
            Set<String> allowed = Set.of(configData.allowedJumpstart);
            for (CardBlock b : src) { // for each block
                if (allowed.contains(b.getName())) {
                    legalBlocks.add(b);
                }
            }
            for (CardBlock b : FModel.getFantasyBlocks()) {
                if (allowed.contains(b.getName())) {
                    legalBlocks.add(b);
                }
            }
        }
        else {
            for (CardBlock b : src) { // for each block
                //I hate doing this, but it seems like the simplest way to reliably filter out prereleases
                if (b.getName().toUpperCase().contains("JUMPSTART")) {
                    legalBlocks.add(b);
                }
            }
            if (configData.allowedEditions != null) {
                Set<String> allowed = Set.of(configData.allowedEditions);
                legalBlocks.removeIf(q -> !allowed.contains(q.getName()));
            } else {
                for (String restricted : configData.restrictedEditions) {
                    legalBlocks.removeIf(q -> q.getName().equals(restricted));
                }
            }
        }
        // Round 334 (the user: "I tried to join a Jumpstart tournament, and I can't select any decks"): blocks.txt lists
        // "Marvel Super Heroes Jumpstart" (MSH) but boosters-special.txt holds no MSH packs, so an event on it dealt
        // nothing and its Select Deck did nothing - round 322's "every product on offer" fallback reached it. A product
        // this engine cannot deal packs for is never offered, on any path.
        for (CardBlock b : new ArrayList<>(legalBlocks)) {
            if (!hasJumpstartPacks(b)) {
                legalBlocks.remove(b);
                System.out.println("[TFR-InnEditions] format=Jumpstart: " + b.getName() + " has no packs in boosters-special.txt"
                        + " for its land set - not offered");
            }
        }
        // Round 335 (the user: "it's not fair for certain races to get jump-start sets and other not"): the draft
        // path's pool narrowing used to apply here too - a player town narrowed to race + unlocked sets, any other Inn
        // to unlocked + the neutral shard - and round 322 fell back to every product when nothing qualified. Only
        // Human (DMU), Dwarf (BRO) and Phyrexian (ONE) start with a set that has a Jumpstart companion, so their own
        // Inn was pinned to that 10-theme product while the other 17 races drew from the whole pool. The Jumpstart
        // tournament is one event per player, ever: it now draws one product from the same pool in every town, for
        // every race. allowedJumpstart in config.json still curates that pool.
        CardBlock picked = legalBlocks.isEmpty() ? null : Aggregates.random(legalBlocks);
        System.out.println("[TFR-InnEditions] format=Jumpstart town=" + (playerTown ? "PLAYER" : "ai/neutral")
                + ": " + legalBlocks.size() + " product(s) on offer, every town alike (round 335) - picked "
                + (picked == null ? "nothing" : picked.getName()));
        return picked;
    }

    public void generateSealedPool() {
        if (getCardBlock() == null) return; // round 972: the block is transient - read it back after a reload

        // Round 972 (Fable P2-12): the pool opened at "Create Deck", after the fee was saved, came from MyRandom - a reload
        // opened a new one. Now from the event's own seed: the same pool, and the same opponents' pools.
        Random placeholder = MyRandom.getRandom();
        MyRandom.setRandom(openingRandom("sealed pool"));
        try {
            // Open all boosters; restricted cards are excluded from non-meta packs.
            CardPool humanPool = new CardPool();

            for (String setCode : packConfiguration) {
                IUnOpenedProduct booster = openSealedBooster(setCode);
                if (booster == null) {
                    continue;
                }
                humanPool.addAllFlat(forge.adventure.util.CommanderCards.cleanPack(booster.get(), setCode + " event pack")); // round 387
            }

            if (humanPool.isEmpty()) return;

            // Store the pool in sideboard
            registeredDeck = new Deck();
            registeredDeck.getOrCreate(DeckSection.Sideboard).addAll(humanPool);
            registeredDeck.setName("Sealed Pool - " + cardBlockName);

            // Store the reward deck consisting of every card in the opened sealed boosters
            rewardDeck = new Deck();
            rewardDeck.getOrCreate(DeckSection.Main).addAll(humanPool);
            rewardDeck.setName("Sealed Pool Cards");

            // Generate AI opponents' decks
            for (AdventureEventParticipant participant : participants) {
                if (participant instanceof AdventureEventHuman) continue;

                CardPool aiPool = new CardPool();
                for (String setCode : packConfiguration) {
                    IUnOpenedProduct booster = openSealedBooster(setCode);
                    if (booster == null) {
                        continue;
                    }
                    aiPool.addAllFlat(forge.adventure.util.CommanderCards.cleanPack(booster.get(), setCode + " event pack")); // round 387
                }

                if (aiPool.isEmpty()) continue;

                SealedDeckBuilder deckBuilder = new SealedDeckBuilder(aiPool.toFlatList());
                String landSet = cardBlock.getLandSet() != null ? cardBlock.getLandSet().getCode() : null;
                Deck aiDeck = deckBuilder.buildDeck(landSet);
                participant.setDeck(aiDeck);
            }

            eventStatus = AdventureEventController.EventStatus.Entered;

        } catch (Exception e) {
            System.err.println("Error generating Sealed pool: " + e.getMessage());
        } finally {
            MyRandom.setRandom(placeholder);
        }
    }

    private IUnOpenedProduct openSealedBooster(String setCode) {
        for (String metaSetName : cardBlock.getMetaSetNames()) {
            if (metaSetName.equals(setCode)) {
                return cardBlock.getBooster(setCode);
            }
        }
        SealedTemplate template = AdventureOverrides.instance().getBoosterTemplate(setCode);
        if (template == null) {
            return null;
        }
        return CardUtil.createRestrictedAwareBooster(template);
    }

    private void setupDraftRewards() {
        //Below all to be fully generated in later release
        rewardPacks = getRewardPacks(3);
        if (cardBlock != null) {
            packConfiguration = getBoosterConfiguration(cardBlock);

            rewards = new AdventureEventData.AdventureEventReward[4];
            AdventureEventData.AdventureEventReward r0 = new AdventureEventData.AdventureEventReward();
            AdventureEventData.AdventureEventReward r1 = new AdventureEventData.AdventureEventReward();
            AdventureEventData.AdventureEventReward r2 = new AdventureEventData.AdventureEventReward();
            AdventureEventData.AdventureEventReward r3 = new AdventureEventData.AdventureEventReward();
            r0.minWins = 0;
            r0.maxWins = 0;
            r0.cardRewards = new Deck[]{rewardPacks[0]};
            rewards[0] = r0;
            r1.minWins = 1;
            r1.maxWins = 3;
            r1.cardRewards = new Deck[]{rewardPacks[1], rewardPacks[2]};
            rewards[1] = r1;
            r2.minWins = 2;
            r2.maxWins = 3;
            r2.itemRewards = new String[]{"Challenge Coin"};
            rewards[2] = r2;
        }
    }

    private void setupSealedRewards() {
        packConfiguration = getBoosterConfiguration(cardBlock);

        rewardPacks = getRewardPacks(3);
        rewards = new AdventureEventReward[4];

        AdventureEventReward r0 = new AdventureEventReward();
        AdventureEventReward r1 = new AdventureEventReward();
        AdventureEventReward r2 = new AdventureEventReward();
        AdventureEventReward r3 = new AdventureEventReward();
        r0.minWins = 0;
        r0.maxWins = 0;
        r0.cardRewards = new Deck[]{rewardPacks[0]};
        rewards[0] = r0;
        r1.minWins = 1;
        r1.maxWins = 3;
        r1.cardRewards = new Deck[]{rewardPacks[1], rewardPacks[2]};
        rewards[1] = r1;
        r2.minWins = 2;
        r2.maxWins = 3;
        r2.itemRewards = new String[]{"Silver Challenge Coin"};
        rewards[2] = r2;
        r3.minWins = 3;
        r3.maxWins = 3;
        rewards[3] = r3;
    }

    private void setupJumpstartRewards() {
        rewards = new AdventureEventData.AdventureEventReward[4];
        AdventureEventData.AdventureEventReward r0 = new AdventureEventData.AdventureEventReward();
        AdventureEventData.AdventureEventReward r1 = new AdventureEventData.AdventureEventReward();
        AdventureEventData.AdventureEventReward r2 = new AdventureEventData.AdventureEventReward();
        AdventureEventData.AdventureEventReward r3 = new AdventureEventData.AdventureEventReward();

        RewardData r0gold = new RewardData();
        r0gold.count = 100;
        r0gold.type = "gold";
        r0.rewards = new RewardData[]{r0gold};
        r0.minWins = 1;
        r0.maxWins = 1;
        rewards[0] = r0;
        RewardData r1gold = new RewardData();
        r1gold.count = 200;
        r1gold.type = "gold";
        r1.rewards = new RewardData[]{r1gold};
        r1.minWins = 2;
        r1.maxWins = 2;
        rewards[1] = r1;
        r2.minWins = 3;
        r2.maxWins = 3;
        RewardData r2gold = new RewardData();
        r2gold.count = 500;
        r2gold.type = "gold";
        r2.rewards = new RewardData[]{r2gold};
        rewards[2] = r2;
        r3.minWins = 0;
        r3.maxWins = 3;
        rewards[3] = r3;
        //r3 will be the selected card packs
    }


    public String[] getBoosterConfiguration(CardBlock selectedBlock) {
        Random placeholder = MyRandom.getRandom();
        MyRandom.setRandom(getEventRandom());

        int numBoosters;
        String[] ret;

        if (format == AdventureEventController.EventFormat.Sealed) {
            numBoosters = selectedBlock.getCntBoostersSealed();
            ret = new String[numBoosters];

            for (int i = 0; i < numBoosters; i++) {
                ret[i] = selectedBlock.getSets().get(i % selectedBlock.getNumberSets()).getCode();
            }
        } else {
            numBoosters = selectedBlock.getCntBoostersDraft();
            ret = new String[numBoosters];

            for (int i = 0; i < numBoosters; i++) {
                if (i < selectedBlock.getNumberSets())
                    ret[i] = selectedBlock.getSets().get(i).getCode();
                else
                    ret[i] = Aggregates.random(selectedBlock.getSets()).getCode();
            }
        }

        MyRandom.setRandom(placeholder);
        return ret;
    }

    public void startEvent() {
        if (eventStatus == AdventureEventController.EventStatus.Ready) {
            currentRound = 1;
            eventStatus = AdventureEventController.EventStatus.Started;
            if (format == AdventureEventController.EventFormat.Jumpstart) { // round 118: the player's one Jumpstart has been played
                Current.player().setCharacterFlag("jumpstartPlayed", 1);
                System.out.println("[TFR-Jumpstart] Jumpstart tournament started - Inns will not offer the format again, "
                        + "in this run or in any New Game+ after it (round 253)");
            }
        }
    }

    public void generateParticipants() {
        this.generateParticipants(getRecommendedPodSize(this.cardBlock) - 1); //-1 to account for the player
    }

    public void generateParticipants(int numberOfOpponents) {
        participants = new AdventureEventParticipant[numberOfOpponents + 1];

        Iterable<EnemyData> validParticipants = IterableUtil.filter(WorldData.getAllEnemies(), q -> q.nextEnemy == null);
        List<EnemyData> data = Aggregates.random(validParticipants, numberOfOpponents);
        for (int i = 0; i < numberOfOpponents; i++) {
            participants[i] = new AdventureEventParticipant().generate(data.get(i));
        }

        participants[numberOfOpponents] = getHumanPlayer();

        if (format == AdventureEventController.EventFormat.Jumpstart) {
            for (AdventureEventParticipant participant : participants) {
                List<Deck> availableOptions = AdventureEventController.instance().getJumpstartBoosters(cardBlock, JUMPSTART_TO_PICK_FROM);
                List<Deck> chosenPacks = new ArrayList<>();

                Map<String, List<Deck>> themeMap = new HashMap<>();

                //1. Search for matching themes from deck names, fill deck with them if possible
                for (Deck option : availableOptions) {
                    // This matches up theme for all except DMU - with only 2 per color the next part will handle that
                    String theme = option.getName().replaceAll("\\d$", "").trim();
                    if (!themeMap.containsKey(theme)) {
                        themeMap.put(theme, new ArrayList<>());
                    }
                    themeMap.get(theme).add(option);
                }

                String themeAdded = "";
                boolean done = false;
                while (!done) {
                    for (int i = packConfiguration.length - chosenPacks.size(); i > 1; i--) {
                        if (themeAdded.isEmpty()) {
                            for (String theme : themeMap.keySet()) {
                                if (themeMap.get(theme).size() >= i) {
                                    themeAdded = theme;
                                    break;
                                }
                            }
                        }
                    }
                    if (themeAdded.isEmpty()) {
                        done = true;
                    } else {
                        chosenPacks.addAll(themeMap.get(themeAdded).subList(0, Math.min(themeMap.get(themeAdded).size(), packConfiguration.length - chosenPacks.size())));
                        availableOptions.removeAll(themeMap.get(themeAdded));
                        themeMap.remove(themeAdded);
                        themeAdded = "";
                    }
                }

                //2. Fill remaining slots with colors already picked whenever possible
                Map<String, List<Deck>> colorMap = new HashMap<>();
                for (Deck option : availableOptions) {
                    if (option.getTags().contains("black"))
                        colorMap.computeIfAbsent("black", (k) -> new ArrayList<>()).add(option);
                    if (option.getTags().contains("blue"))
                        colorMap.computeIfAbsent("blue", (k) -> new ArrayList<>()).add(option);
                    if (option.getTags().contains("green"))
                        colorMap.computeIfAbsent("green", (k) -> new ArrayList<>()).add(option);
                    if (option.getTags().contains("red"))
                        colorMap.computeIfAbsent("red", (k) -> new ArrayList<>()).add(option);
                    if (option.getTags().contains("white"))
                        colorMap.computeIfAbsent("white", (k) -> new ArrayList<>()).add(option);
                    if (option.getTags().contains("multicolor"))
                        colorMap.computeIfAbsent("multicolor", (k) -> new ArrayList<>()).add(option);
                    if (option.getTags().contains("colorless"))
                        colorMap.computeIfAbsent("colorless", (k) -> new ArrayList<>()).add(option);
                }

                done = false;
                String colorAdded = "";
                while (!done) {
                    List<String> colorsAlreadyPicked = new ArrayList<>();
                    for (Deck picked : chosenPacks) {
                        if (picked.getTags().contains("black")) colorsAlreadyPicked.add("black");
                        if (picked.getTags().contains("blue")) colorsAlreadyPicked.add("blue");
                        if (picked.getTags().contains("green")) colorsAlreadyPicked.add("green");
                        if (picked.getTags().contains("red")) colorsAlreadyPicked.add("red");
                        if (picked.getTags().contains("white")) colorsAlreadyPicked.add("white");
                        if (picked.getTags().contains("multicolor")) colorsAlreadyPicked.add("multicolor");
                        if (picked.getTags().contains("colorless")) colorsAlreadyPicked.add("colorless");
                    }

                    while (colorAdded.isEmpty() && !colorsAlreadyPicked.isEmpty()) {
                        String colorToTry = Aggregates.removeRandom(colorsAlreadyPicked);
                        for (Deck toCheck : availableOptions) {
                            if (toCheck.getTags().contains(colorToTry)) {
                                colorAdded = colorToTry;
                                chosenPacks.add(toCheck);
                                availableOptions.remove(toCheck);
                                break;
                            }
                        }
                    }
                    //3. If no matching color found and need more packs, add any available at random.
                    if (packConfiguration.length > chosenPacks.size() && colorAdded.isEmpty() && !availableOptions.isEmpty()) {
                        chosenPacks.add(Aggregates.removeRandom(availableOptions));
                    } else {
                        done = colorAdded.isEmpty() || packConfiguration.length <= chosenPacks.size();
                    }
                    colorAdded = "";
                }
                participant.registeredDeck = new Deck();
                for (Deck chosen : chosenPacks) {
                    participant.registeredDeck.getMain().addAllFlat(chosen.getMain().toFlatList());
                }
            }
        }

        switch (this.style) {
            case Swiss:
            case Bracket:
                this.rounds = (participants.length / 2) - 1;
                break;
            case RoundRobin:
                this.rounds = participants.length - 1;
                break;
        }
    }

    public static int getRecommendedPodSize(CardBlock cardBlock) {
        // Set can be null when it is only a meta set such as some Jumpstart events.
        CardEdition firstSet = cardBlock.getSets().isEmpty() ? null : cardBlock.getSets().get(0);
        return firstSet == null ? 8 : firstSet.getDraftOptions().getRecommendedPodSize();
    }

    private void assignPlayerNames(BoosterDraft draft) {
        if(participants == null)
            return;
        draft.getHumanPlayer().setName(getHumanPlayer().getName());
        LimitedPlayer[] opponents = draft.getOpposingPlayers();
        for(int i = 0; i < Math.min(participants.length, opponents.length); i++)
            opponents[i].setName(participants[i].getName());
    }

    private transient AdventureEventHuman humanPlayerInstance;

    public AdventureEventHuman getHumanPlayer() {
        if (humanPlayerInstance == null) {
            humanPlayerInstance = new AdventureEventHuman();
        }
        return humanPlayerInstance;
    }

    public AdventureEventParticipant nextOpponent = null;

    public List<AdventureEventMatch> getMatches(int round) {
        if (matches.containsKey(round)) {
            return matches.get(round);
        }

        List<AdventureEventParticipant> activePlayers = new ArrayList<>();
        if (style == AdventureEventController.EventStyle.Bracket) {
            if (round == 1) {
                activePlayers = Arrays.stream(participants).collect(Collectors.toList());
            } else {
                if (matches.get(round - 1) == null) {
                    return null;
                }
                for (int i = 0; i < matches.get(round - 1).size(); i++) {
                    AdventureEventParticipant w = matches.get(round - 1).get(i).winner;
                    if (w == null)
                        return null;
                    else
                        activePlayers.add(w);
                }
            }
            matches.put(round, new ArrayList<>());
            while (!activePlayers.isEmpty()) {
                AdventureEventMatch match = new AdventureEventMatch();
                match.p1 = activePlayers.remove(MyRandom.getRandom().nextInt(activePlayers.size()));
                if (!activePlayers.isEmpty()) {
                    match.p2 = activePlayers.remove(MyRandom.getRandom().nextInt(activePlayers.size()));
                }
                matches.get(round).add(match);
            }
        } else if (style == AdventureEventController.EventStyle.RoundRobin) {
            // In a roundrobin everyone plays everyone else once
            // We do have this logic already in ForgeTOurnament, we should see if we could reuse it
            matches.put(round, new ArrayList<>());
            activePlayers = Arrays.stream(participants).collect(Collectors.toList());

            if (round > 1) {
                AdventureEventParticipant pivot = activePlayers.remove(0);
                for(int i = 1; i < round; i++) {
                    // Rotate X amount of players, where X is the current round-1
                    AdventureEventParticipant rotate = activePlayers.remove(0);
                    activePlayers.add(rotate);
                }
                activePlayers.add(0, pivot);
            }

            int numPlayers = activePlayers.size();
            for (int i = 0; i < numPlayers / 2; i++) {
                AdventureEventMatch match = new AdventureEventMatch();
                match.p1 = activePlayers.get(i);
                match.p2 = activePlayers.get(numPlayers - i - 1);
                matches.get(round).add(match);
            }
        } else {
            System.out.println(style + " not yet implemented!!!");
        }
        return matches.get(currentRound);
    }

    public Map<Integer, List<AdventureEventMatch>> matches = new HashMap<>();

    // Mod addition (user spec 2026-08-23): basic lands drafted/opened in an Inn tournament
    // (Draft/Sealed/Jumpstart) shouldn't clutter the player's permanent card collection - they're
    // effectively free/unlimited everywhere else. Stripped here, before the deck is wrapped into
    // a "cardPack" reward, since this is the last point the tournament's own Deck is still
    // distinct from the shared RewardScene/AdventurePlayer pipeline that ordinary shop boosters
    // also flow through (stripping downstream would affect every booster in the game, not just
    // tournament rewards). Reuses PaperCard.isVeryBasicLand(), the same check this project's own
    // AdventurePlayer.removeLostCardFromPools()/RewardData.rewardsToCards()/EnemySprite loot
    // filtering already use elsewhere - not a new concept.
    private static void stripBasicLands(Deck deck) {
        if (deck == null)
            return;
        deck.getMain().removeIf(forge.item.PaperCard::isVeryBasicLand);
        if (deck.has(DeckSection.Sideboard))
            deck.getOrCreate(DeckSection.Sideboard).removeIf(forge.item.PaperCard::isVeryBasicLand);
    }

    public void giveRewards() {
        boolean firstAward = eventStatus != AdventureEventController.EventStatus.Awarded; // round 369
        int wins = matchesWon;
        Array<Reward> ret = new Array<>();
        repairRewardState("prize"); // TFR round 996: a three-slot table threw writing slot 3 and the champion went unpaid
        if (rewardDeck == null && registeredDeck != null && (format == AdventureEventController.EventFormat.Draft
                || format == AdventureEventController.EventFormat.Sealed)) {
            rewardDeck = new Deck(registeredDeck); // round 996: stripBasicLands below takes the lands back out
            System.out.println("[TFR-InnEvent] reward state repaired: no reward deck - the registered deck pays instead ("
                    + shapeText() + ")");
        }

        //Todo: this should be automatic... "somehow"

        if (format == AdventureEventController.EventFormat.Draft) {

            rewards[3] = new AdventureEventReward();
            rewards[3].minWins = 3;
            rewards[3].maxWins = 3;
            stripBasicLands(rewardDeck);
            rewardDeck.setName("Drafted Deck");
            rewardDeck.setComment(Forge.getLocalizer().getMessage("advPrizeDraftFirst"));
            rewards[3].cardRewards = new Deck[]{rewardDeck};

        } else if (format == AdventureEventController.EventFormat.Sealed) {

            rewards[3] = new AdventureEventReward();
            rewards[3].minWins = 3;
            rewards[3].maxWins = 3;
            stripBasicLands(rewardDeck);
            rewardDeck.setName("Sealed Card Pool");
            rewardDeck.setComment(Forge.getLocalizer().getMessage("advPrizeSealedFirst"));
            rewards[3].cardRewards = new Deck[]{rewardDeck};

        } else if (format == AdventureEventController.EventFormat.Jumpstart) {

            rewards[3] = new AdventureEventReward();
            rewards[3].minWins = 0;
            rewards[3].maxWins = 3;
            stripBasicLands(registeredDeck);
            registeredDeck.setName("Jumpstart Event Packs");
            rewards[3].cardRewards = new Deck[]{registeredDeck};
            rewards[3].isNoSell = true;

        }

        //end todo

        // TFR round 996: a missing tier, pack or list is skipped rather than thrown on
        for (AdventureEventReward r : InnEventRepair.payingTiers(rewards, wins)) {
            for (Deck pack : r.cardRewards == null ? new Deck[0] : r.cardRewards) {
                if (pack == null)
                    continue;
                RewardData data = new RewardData();
                data.type = "cardPack";
                data.count = 1;
                data.cardPack = pack;
                ret.addAll(data.generate(false, true, r.isNoSell));
            }
            for (String item : r.itemRewards == null ? new String[0] : r.itemRewards) {
                RewardData data = new RewardData();
                data.type = "item";
                data.count = 1;
                data.itemName = item;
                ret.addAll(data.generate(false, true));
            }
            for (RewardData data : r.rewards == null ? new RewardData[0] : r.rewards) {
                ret.addAll(data.generate(false, true));
            }
        }
        // TFR round 987: Tournament Regular - a won bracket (3-0 champion) pays one card from the event's set, a rare or
        // 1 in 8 a mythic. Behind round 369's firstAward, so a re-opened reward screen cannot pay it twice.
        if (firstAward) {
            forge.item.PaperCard regularCard = forge.adventure.util.Ascendance.tournamentRegularCard(this);
            if (regularCard != null)
                ret.add(new Reward(regularCard));
        }
        if (ret.size > 0) {
            RewardScene.instance().loadRewards(ret, RewardScene.Type.EventReward, null);
            Forge.switchScene(RewardScene.instance());
        }

        //todo: more robust logic for event types that can be won without perfect record (Swiss w/cut, round robin)
        if (style == AdventureEventController.EventStyle.Bracket) {
            playerWon = matchesLost == 0 || matchesWon == rounds;
        } else if (style == AdventureEventController.EventStyle.RoundRobin) {
            if (matchesWon == rounds) {
                playerWon = true;
            } else {
                //If multiple players are tied for first, only the one with the best tiebreaker wins
                List<AdventureEventParticipant> topPlayers = new ArrayList<>();
                int bestRecord = 0;
                for (AdventureEventParticipant p : participants) {
                    if (p.wins > bestRecord) {
                        bestRecord = p.wins;
                        topPlayers.clear();
                        topPlayers.add(p);
                    } else if (p.wins == bestRecord) {
                        topPlayers.add(p);
                    }
                }
                if (topPlayers.size() == 1) {
                    playerWon = topPlayers.get(0).getName().equals(getHumanPlayer().getName());
                } else {
                    //multiple players tied for first, use tiebreaker
                    Map<AdventureEventParticipant, Integer> tiebreakers = new HashMap<>();
                    for (AdventureEventParticipant p : topPlayers) {
                        int tb = 0;
                        for (AdventureEventMatch m : matches.values().stream().flatMap(List::stream).collect(Collectors.toList())) {
                            if (m.p1 == p && m.winner != null && m.winner != p) {
                                tb += m.p2.wins;
                            } else if (m.p2 == p && m.winner != null && m.winner != p) {
                                tb += m.p1.wins;
                            }
                        }
                        tiebreakers.put(p, tb);
                    }
                    int bestTiebreaker = 0;
                    AdventureEventParticipant winner = null;
                    boolean tie = false;
                    for (AdventureEventParticipant p : tiebreakers.keySet()) {
                        if (tiebreakers.get(p) > bestTiebreaker) {
                            bestTiebreaker = tiebreakers.get(p);
                            winner = p;
                            tie = false;
                        } else if (tiebreakers.get(p) == bestTiebreaker) {
                            tie = true;
                        }
                    }
                    playerWon = !tie && winner != null && winner.getName().equals(getHumanPlayer().getName());
                }
            }
        } else {
            playerWon = false;
        }

        // Round 369 (the user: "Winning an Inn Tournament should raise local reputation by 1"): the Inn's own place -
        // its town's Reputation, the number shown on entering it. Once, on the first award only.
        if (firstAward && playerWon && sourceID != null && forge.adventure.world.WorldSave.getCurrentSave() != null) {
            // Round 527 (the user: "you can only Win one Inn Tournament per town/Capitol per week"): this town's Inn deals
            // no new tournament until the week turns (InnScene.initLocalEvent).
            forge.adventure.world.World world = forge.adventure.world.WorldSave.getCurrentSave().getWorld();
            if (world != null) {
                world.getInnWinWeek().put(sourceID, world.getCurrentWeek());
                System.out.println("[TFR-InnWeekly] tournament won at " + sourceID + " in week " + world.getCurrentWeek()
                        + " (day " + world.getCurrentDay() + ") - the next one here from day "
                        + forge.adventure.world.World.nextWeekBoundary(world.getCurrentDay()));
            }
            PointOfInterestChanges changes = forge.adventure.world.WorldSave.getCurrentSave().getPointOfInterestChanges(sourceID);
            if (changes != null) {
                changes.addMapReputation(1);
                System.out.println("[TFR-InnReputation] tournament won at " + sourceID + " - local reputation now "
                        + changes.getMapReputation());
                // Round 711: the line names the town - in the notice log it otherwise reads as a reputation of nowhere.
                String town = forge.adventure.util.DungeonSources.displayNameOf(sourceID);
                forge.adventure.stage.GameHUD.getInstance().addNotification("[BLACK]Tournament won" + (town == null ? "" : " at " + town)
                        + ": " + (town == null ? "local " : "") + "+1 [WHITE]" + forge.adventure.util.ResourceGlyphs.reputation()
                        + "[BLACK] (now " + changes.getMapReputation() + ").", true);
            }
        }
        eventStatus = AdventureEventController.EventStatus.Awarded;
    }

    public String getDescription(PointOfInterestChanges changes) {
        Localizer localizer = Forge.getLocalizer();
        repairRewardState("description"); // TFR round 996: a saved Draft with no reward packs threw an NPE here
        // TFR round 987: every entry fee through Ascendance.innFee (town price x Tournament Regular), as EventScene charges it.
        if (format == AdventureEventController.EventFormat.Draft) {
            description = localizer.getMessage("advEventTypeBoosterDraft");
            description += "Block: " + getCardBlock() + "\n";
            description += "Boosters: " + String.join(", ", packConfiguration) + "\n";
            description += "Competition Style: " + participants.length + " players, matches played as best of " + eventRules.gamesPerMatch + ", " + (eventRules.getPairingDescription()) + "\n\n";

            if (eventStatus == AdventureEventController.EventStatus.Available) {
                description += String.format("Pay 1 Entry Fee\n- Gold %d[][+Gold][BLACK]\n- Mana Shards %d[][+Shards][BLACK]\n", forge.adventure.util.Ascendance.innFee(eventRules.goldToEnter, changes), forge.adventure.util.Ascendance.innFee(eventRules.shardsToEnter, changes));
                if (eventRules.acceptsBronzeChallengeCoin) {
                    description += "- Bronze Challenge Coin [][+BronzeChallengeCoin][BLACK]\n\n";
                } else if (eventRules.acceptsSilverChallengeCoin) {
                    description += "- Silver Challenge Coin [][+SilverChallengeCoin][BLACK]\n\n";
                } else if (eventRules.acceptsChallengeCoin) {
                    description += "- Gold Challenge Coin [][+ChallengeCoin][BLACK]\n\n";
                } else {
                    description += "\n";
                }
            }
            description += String.format("Prizes\nChampion: Keep drafted deck\n2+ round wins: Challenge Coin \n1+ round wins: %s Booster, %s Booster\n0 round wins: %s Booster",
                    // TFR round 996: a missing pack names the event's set; and the packs in the order they pay, as Sealed
                    // lists them (stock named pack 0 for 1+ wins and pack 2 for 0 wins)
                    InnEventRepair.packLabel(rewardPacks, 1, packFallback(1)), InnEventRepair.packLabel(rewardPacks, 2, packFallback(2)),
                    InnEventRepair.packLabel(rewardPacks, 0, packFallback(0)));
        } else if (format == AdventureEventController.EventFormat.Jumpstart) {
            description = "Event Type: Jumpstart\n";
            description += "Block: " + getCardBlock() + "\n";
            description += "Competition Style: " + participants.length + " players, matches played as best of " + eventRules.gamesPerMatch + ", " + (eventRules.getPairingDescription()) + "\n\n";
            description += String.format("Pay 1 Entry Fee\n- Gold %d[][+Gold][BLACK]\n- Mana Shards %d[][+Shards][BLACK]\n", forge.adventure.util.Ascendance.innFee(eventRules.goldToEnter, changes), forge.adventure.util.Ascendance.innFee(eventRules.shardsToEnter, changes));
            if (eventRules.acceptsBronzeChallengeCoin) {
                description += "- Bronze Challenge Coin [][+BronzeChallengeCoin][BLACK]\n\n";
            } else if (eventRules.acceptsSilverChallengeCoin) {
                description += "- Silver Challenge Coin [][+SilverChallengeCoin][BLACK]\n\n";
            } else if (eventRules.acceptsChallengeCoin) {
                description += "- Gold Challenge Coin [][+ChallengeCoin][BLACK]\n\n";
            } else {
                description += "\n";
            }
            description += localizer.getMessage("advDraftPrizesDesc").replace(" gold", " [][+Gold][BLACK]"); // round 881
            description += localizer.getMessage("advJumpstartParticipationPrize");
        } else if (format == AdventureEventController.EventFormat.Sealed) {
            description = "Event Type: Sealed Deck\n";
            description += "Block: " + getCardBlock() + "\n";
            description += "Boosters: " + packConfiguration.length + " Packs (" +
                    String.join(", ", packConfiguration) + ")\n";
            description += "Competition Style: " + participants.length + " players, matches played as best of " +
                    eventRules.gamesPerMatch + ", " + (eventRules.getPairingDescription()) + "\n\n";

            if (eventStatus == AdventureEventController.EventStatus.Available) {
                description += String.format("Pay 1 Entry Fee\n- Gold %d[][+Gold][BLACK]\n- Mana Shards %d[][+Shards][BLACK]\n",
                        forge.adventure.util.Ascendance.innFee(eventRules.goldToEnter, changes),
                        forge.adventure.util.Ascendance.innFee(eventRules.shardsToEnter, changes));

                if (eventRules.acceptsSilverChallengeCoin) {
                    description += "- Silver Challenge Coin [][+SilverChallengeCoin][BLACK]\n\n";
                } else {
                    description += "\n";
                }
            }

            description += "Prizes\n";
            description += localizer.getMessage("advSealedChampionPrize", packConfiguration.length);
            description += "2+ wins: Silver Challenge Coin\n";
            // TFR round 996: a missing pack names the event's set
            description += String.format("1+ wins: %s Booster, %s Booster\n", InnEventRepair.packLabel(rewardPacks, 1, packFallback(1)),
                    InnEventRepair.packLabel(rewardPacks, 2, packFallback(2)));
            description += String.format("0 wins: %s Booster", InnEventRepair.packLabel(rewardPacks, 0, packFallback(0)));
        }
        return description;
    }


    public static class AdventureEventParticipant implements Serializable, Comparable<AdventureEventParticipant> {
        @Serial
        private static final long serialVersionUID = 1L;
        private transient EnemySprite sprite;
        String enemyDataName;
        Deck registeredDeck;

        public int wins;
        public int losses;

        public AdventureEventParticipant generate(EnemyData data) {
            AdventureEventParticipant ret = new AdventureEventParticipant();
            ret.enemyDataName = data.getName();
            ret.sprite = new EnemySprite(data);
            return ret;
        }

        public String getRecord() {
            return String.format("%d-%d", wins, losses);
        }

        private AdventureEventParticipant() {
        }

        public Deck getDeck() {
            return registeredDeck;
        }

        public String getName() {
            EnemyData enemyData = WorldData.getEnemy(enemyDataName);
            if (enemyData != null)
                return enemyData.getDisplayName();
            return "";
        }

        public Image getAvatar() {
            if (sprite == null) {
                EnemyData data = WorldData.getEnemy(enemyDataName);
                if (data == null) {
                    //enemyDataName was not found, replace with something valid.
                    enemyDataName = Aggregates.random(WorldData.getAllEnemies()).getName();
                }
                sprite = new EnemySprite(WorldData.getEnemy(enemyDataName));
            }
            return sprite.getAvatar() == null ? new Image() : new Image(sprite.getAvatar());
        }

        public String getAtlasPath() {
            return sprite == null ? "" : sprite.getAtlasPath();
        }

        public EnemySprite getSprite() {
            if (sprite == null) {
                sprite = new EnemySprite(WorldData.getEnemy(enemyDataName));
            }
            return sprite;
        }

        @Override
        public int compareTo(AdventureEventParticipant other) {
            if (this.wins != other.wins)
                return other.wins - this.wins;
            else
                return this.losses - other.losses;
        }

        public void setDeck(Deck deck) {
            registeredDeck = deck;
        }
    }

    public static class AdventureEventHuman extends AdventureEventParticipant {
        // Round 123 (2026-09-05 code review S1-3): pinned at the derived v1.05 value (saved inside every event).
        private static final long serialVersionUID = -6819102903640880265L;
        @Override
        public Deck getDeck() {
            return registeredDeck == null ? Current.player().getSelectedDeck() : registeredDeck;
        }

        @Override
        public String getName() {
            return Current.player().getName();
        }

        @Override
        public Image getAvatar() {
            return new Image(Current.player().avatar());
        }
    }

    public static class AdventureEventRules implements Serializable {
        @Serial
        private static final long serialVersionUID = -2902188278147984885L;
        public int goldToEnter;
        public int shardsToEnter;
        public boolean acceptsChallengeCoin = false;
        public boolean acceptsSilverChallengeCoin = false;
        public boolean acceptsBronzeChallengeCoin = false;
        public GameType gameType = GameType.AdventureEvent;
        public int startingLife = 20;
        public boolean allowsShards = false;
        public boolean allowsItems = false;
        public boolean allowsBlessings = false;
        public boolean allowsAddBasicLands = true;
        public int gamesPerMatch = 3;
        public PairingStyle pairingStyle = PairingStyle.SingleElimination;

        public AdventureEventRules() {
            this(AdventureEventController.EventFormat.Constructed, PairingStyle.SingleElimination);
        }

        public AdventureEventRules(AdventureEventController.EventFormat format) {
            this(format, PairingStyle.SingleElimination);
        }

        public AdventureEventRules(AdventureEventController.EventFormat format, PairingStyle pairingStyle) {
            int baseGoldEntry = 99999;
            int baseShardEntry = 9999;
            this.pairingStyle = pairingStyle;

            switch (format) {
                case Constructed:
                    acceptsSilverChallengeCoin = true;
                    acceptsChallengeCoin = false;
                    acceptsBronzeChallengeCoin = false;
                    baseGoldEntry = 1500;
                    baseShardEntry = 25;
                    allowsAddBasicLands = false;
                    break;
                case Draft:
                    acceptsChallengeCoin = true;
                    acceptsSilverChallengeCoin = false;
                    acceptsBronzeChallengeCoin = false;
                    baseGoldEntry = 3000;
                    baseShardEntry = 50;
                    startingLife = 20;
                    allowsAddBasicLands = true;
                    break;
                case Sealed:
                    acceptsChallengeCoin = false;
                    acceptsSilverChallengeCoin = true;
                    acceptsBronzeChallengeCoin = false;
                    baseGoldEntry = 6000;
                    baseShardEntry = 100;
                    startingLife = 20;
                    allowsAddBasicLands = true;
                    break;
                case Jumpstart:
                    acceptsChallengeCoin = false;
                    acceptsSilverChallengeCoin = false;
                    acceptsBronzeChallengeCoin = true;
                    // Round 346 (the user: "change the entry fee for Jumpstart to 50 shards or 500g or the bronze coin");
                    // was 200 gold / 5 shards. Unscaled, like the others - the town price modifier applies on read.
                    baseGoldEntry = 500;
                    baseShardEntry = 50;
                    startingLife = 15;
                    allowsAddBasicLands = false;
                    break;
            }
            // Entry fees are stored UNSCALED on purpose (2026-08-31). Every read of these two
            // fields already multiplies by a LIVE town price modifier - four sites in
            // getDescription() here and six in EventScene - so baking the modifier in at
            // construction would apply it twice. It would also freeze the fee: eventRules is
            // serialized with the event, so a stored value would stop tracking town reputation
            // while the display path kept scaling it. The dead localPriceModifier parameter this
            // constructor used to take was removed rather than made live, for both reasons.
            goldToEnter = baseGoldEntry;
            shardsToEnter = baseShardEntry;
        }

        public String getPairingDescription() {
            return switch (pairingStyle) {
                case Swiss -> "swiss";
                case SwissWithCut -> "swiss (with cut)";
                case RoundRobin -> "round robin";
                case SingleElimination -> "single elimination";
                case DoubleElimination -> "double elimination";
            };
        }
    }

    public static class AdventureEventMatch implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        public AdventureEventParticipant p1;
        public AdventureEventParticipant p2;
        public AdventureEventParticipant winner;
        public int round;
    }

    public static class AdventureEventReward implements Serializable {
        @Serial
        private final static long serialVersionUID = -2605375040895115477L;
        public int minWins = -1;
        public int maxWins = -1;
        public Deck[] cardRewards = new Deck[0];
        public String[] itemRewards = new String[0];
        public RewardData[] rewards = new RewardData[0];
        public boolean isNoSell = false;
    }

    public enum PairingStyle {
        SingleElimination,
        DoubleElimination,
        Swiss,
        SwissWithCut,
        RoundRobin
    }
}
