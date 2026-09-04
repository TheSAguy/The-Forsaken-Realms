package forge.adventure.util;

import forge.adventure.data.ConfigData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.TileMapScene;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.card.CardEdition;
import forge.model.FModel;
import forge.util.StreamUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Progressive Set Unlocks (MOD_SCOPE.md #4, opt-in via editionProgressionEnabled). Splits every
 * real, obtainable edition into 6 groups once per new game - one per color (white/blue/black/red/
 * green) plus a "neutral" group for wasteland/non-colored encounters (user spec 2026-08-12) - and
 * persists the split on World for the rest of that save's lifetime.
 *
 * Neutral is NOT an equal 1/6 share (redesigned 2026-08-25, user spec): it's a small fixed size
 * (NEUTRAL_SHARD_SIZE) at seed time, then grows by whatever the player's chosen race's starting
 * editions are once reservePlayerEditions() runs (those are pulled OUT of the 5 color shards and
 * added into neutral instead of just being discarded). The 5 AI colors split whatever's left of
 * the master pool after neutral's fixed slice, round-robin, same "near-equal, differs by at most
 * 1" mechanism as before.
 *
 * This is the AI/world side of the feature: roaming-monster loot and AI-color-town shop stock both
 * draw from a color's assigned shard, permanently, regardless of the player's own research
 * progress. The player's own shop stock uses a SEPARATE mechanism (AdventurePlayer.
 * unlockedEditions, grown by research at the Lab) - the two lists start from the same master pool
 * but are otherwise independent.
 */
public class EditionProgression {
    public static final String NEUTRAL = "neutral";
    private static final String[] GROUPS = {"white", "blue", "black", "red", "green", NEUTRAL};
    private static final String[] COLOR_GROUPS = {"white", "blue", "black", "red", "green"};
    private static final int NEUTRAL_SHARD_SIZE = 12;

    /** Every real, obtainable edition this plane could ever show - the same CAN_MAKE_BOOSTER +
     *  hasBoosterTemplate filter the existing "cardPackShop" booster-generation code already uses
     *  (see RewardData.generate()), minus this plane's own restrictedEditions. Deliberately NOT
     *  gated by allowedEditions/restrictedCards here - those apply at the individual-card level
     *  once a specific edition's pool is actually queried, not at this "which editions exist at
     *  all" level. */
    public static List<CardEdition> getMasterEditionList() {
        ConfigData configData = Config.instance().getConfigData();
        Predicate<CardEdition> filter = CardEdition.Predicates.CAN_MAKE_BOOSTER;
        List<CardEdition> all = new ArrayList<>();
        StreamUtil.stream(FModel.getMagicDb().getEditions())
                .filter(filter)
                .filter(CardEdition::hasBoosterTemplate)
                .forEach(all::add);
        if (configData.restrictedEditions != null && configData.restrictedEditions.length > 0) {
            Set<String> restricted = new HashSet<>(Arrays.asList(configData.restrictedEditions));
            all.removeIf(e -> restricted.contains(e.getCode()));
        }
        return all;
    }

    /**
     * Splits the master edition list into 6 groups (5 colors + neutral) and stores the result on
     * World for this save's lifetime. Called once from World.generateNew() - shuffles with the
     * world's own seeded Random (so the split is reproducible from the same world seed), then
     * neutral greedily claims the first NEUTRAL_SHARD_SIZE editions off the front of that shuffle
     * (a fixed size, not a 1/6 share - user spec 2026-08-25), and everything left over deals
     * round-robin across the 5 AI colors so they get a near-equal share (differs by at most 1).
     * Race-starting editions aren't special-cased here - a player hasn't been created yet at this
     * point in World.generateNew() (see reservePlayerEditions(), which moves the chosen race's
     * editions into neutral as a second pass once a race exists).
     */
    public static void seedColorShards(World world) {
        List<CardEdition> editions = new ArrayList<>(getMasterEditionList());
        Collections.shuffle(editions, world.getRandom());
        Map<String, List<String>> shards = new HashMap<>();
        for (String group : GROUPS)
            shards.put(group, new ArrayList<>());
        List<String> neutralShard = shards.get(NEUTRAL);
        List<CardEdition> colorPool = new ArrayList<>();
        for (CardEdition edition : editions) {
            if (neutralShard.size() < NEUTRAL_SHARD_SIZE)
                neutralShard.add(edition.getCode());
            else
                colorPool.add(edition);
        }
        for (int i = 0; i < colorPool.size(); i++)
            shards.get(COLOR_GROUPS[i % COLOR_GROUPS.length]).add(colorPool.get(i).getCode());
        world.setColorEditionShards(shards);
        // Diagnostic-only logging (this whole feature is otherwise invisible/hard to test) -
        // greppable in forge.log as "[TFR-EditionShard]". One line per group, so a single run
        // shows the full split without needing to inspect the save file directly.
        for (String group : GROUPS) {
            List<String> list = shards.get(group);
            System.out.println("[TFR-EditionShard] " + group + " (" + list.size() + "): " + list);
        }
    }

    /**
     * Moves the player's race-assigned editions out of the 5 AI COLOR shards and into NEUTRAL
     * (2026-08-16 user spec: "These should be exclusive" - a real playtest showed AFR in both the
     * black shard and the player's own unlocked set; redesigned 2026-08-25, user spec: "Give
     * neutral also ALL the starting race sets [of the player's own chosen race]" - previously
     * these were just discarded from the color shards and NEUTRAL was left untouched; now they
     * land in neutral instead, growing it past its seedColorShards() baseline of
     * NEUTRAL_SHARD_SIZE). Runs as a second pass because seedColorShards() fires inside
     * World.generateNew(), BEFORE a player exists (WorldSave.generateNewWorld() line order), so
     * the seed pass can't know what to move yet. Moves the race's FULL edition pool (all 4), not
     * just the difficulty-scaled unlocked subset - on Hard/Insane the locked remainder is still
     * this character's thematic set and shouldn't fly an AI banner either. Only the player's OWN
     * chosen race is affected - the other 15 races' starting editions are untouched and stay
     * wherever seedColorShards()'s shuffle happened to put them. Falls back to the player's
     * actual unlockedEditions when the race has no raceEditions entry (the starterEditions
     * fallback pool is large - excluding all of it would gut the shards). Idempotent, so it also
     * runs on every save LOAD as a migration for worlds seeded before this existed - logs only
     * when it actually moved something.
     */
    public static void reservePlayerEditions(World world, forge.adventure.player.AdventurePlayer player) {
        if (world == null || player == null || !world.isEditionProgressionEnabled())
            return;
        Map<String, List<String>> shards = world.getColorEditionShards();
        if (shards == null || shards.isEmpty())
            return;
        Set<String> reserved = new HashSet<>();
        String raceName = forge.adventure.data.HeroListData.getRawRaceName(player.getHeroRace());
        forge.adventure.data.RaceEditionData[] raceEditions = Config.instance().getConfigData().raceEditions;
        if (raceName != null && raceEditions != null) {
            for (forge.adventure.data.RaceEditionData entry : raceEditions) {
                if (entry != null && raceName.equalsIgnoreCase(entry.race)
                        && entry.editions != null && entry.editions.length > 0) {
                    reserved.addAll(Arrays.asList(entry.editions));
                    break;
                }
            }
        }
        if (reserved.isEmpty())
            reserved.addAll(player.getUnlockedEditions());
        if (reserved.isEmpty())
            return;
        List<String> removedLog = new ArrayList<>();
        for (String group : GROUPS) {
            if (NEUTRAL.equals(group))
                continue;
            List<String> shard = shards.get(group);
            if (shard == null)
                continue;
            for (String code : reserved) {
                if (shard.remove(code))
                    removedLog.add(code + " (from " + group + ")");
            }
        }
        // Moved into neutral rather than discarded (2026-08-25 redesign - see this method's own
        // doc comment). Dedup-safe: harmless no-op on a re-run (idempotent migration case) or if
        // seedColorShards() had coincidentally already put one of these codes in neutral.
        List<String> neutralShard = shards.get(NEUTRAL);
        List<String> addedLog = new ArrayList<>();
        if (neutralShard != null) {
            for (String code : reserved) {
                if (!neutralShard.contains(code)) {
                    neutralShard.add(code);
                    addedLog.add(code);
                }
            }
        }
        if (!removedLog.isEmpty() || !addedLog.isEmpty()) {
            System.out.println("[TFR-EditionShard] reserved for player (race=" + raceName + "): "
                    + reserved + " - removed from AI color shards: " + removedLog
                    + " - added to neutral: " + addedLog);
        }
    }

    /** The editions assigned to a color (or "neutral") for this save - empty if the sharding
     *  hasn't been seeded yet (feature disabled, or an older save from before this existed). */
    public static List<String> getEditionsForColor(World world, String color) {
        Map<String, List<String>> shards = world.getColorEditionShards();
        List<String> shard = shards == null ? null : shards.get(color);
        return shard == null ? Collections.emptyList() : shard;
    }

    /**
     * Clones each RewardData in the given collection (via RewardData's own copy constructor) and
     * sets .editions on the CLONE only - the originals are never touched. This is the single
     * mechanism the whole feature uses to restrict card generation to a specific edition list,
     * reused for three different sources of the list: the player's own unlockedEditions (shops),
     * a color's assigned shard (AI-color-town shops), and a defeated monster's color's shard
     * (roaming-monster loot).
     * <p>
     * Cloning matters because the source RewardData objects are SHARED - every town/shop resolving
     * to the same shops.json name, or every enemy sharing an EnemyData template, points at the
     * exact same RewardData instances. Mutating .editions on those directly would leak across every
     * other town/enemy using them. Card-type rewards ("card"/"randomCard") already respect
     * .editions via CardPredicate; other reward types (gold, items, etc.) simply ignore the field,
     * so cloning them is a harmless no-op rather than something to branch around.
     * <p>
     * A null or empty editionCodes list is treated as "no restriction" (returns clones with
     * .editions left at whatever the original had) rather than "restrict to nothing" - callers
     * that want a hard restriction to an empty pool should filter it out before calling this.
     */
    public static List<RewardData> restrictToEditions(Iterable<RewardData> original, List<String> editionCodes) {
        return restrictToEditions(original, editionCodes, false);
    }

    /**
     * uniqueCards gate (2026-08-15 review finding): this helper is shared by shop rewards, dungeon
     * chests, and roaming-monster loot alike, but the shop dedup opt-in below must NOT leak into
     * the latter two (they legitimately want repeats) - only {@link #restrictShopRewardsForCurrentTown}
     * passes true. The public 2-arg overload above always passes false, so every other existing
     * caller (EnemySprite's monster loot, {@link #restrictDungeonRewardsForCurrentPoi}) is unaffected.
     */
    private static List<RewardData> restrictToEditions(Iterable<RewardData> original, List<String> editionCodes, boolean uniqueCards) {
        List<RewardData> result = new ArrayList<>();
        if (original == null)
            return result;
        boolean restrict = editionCodes != null && !editionCodes.isEmpty();
        String[] editionsArray = restrict ? editionCodes.toArray(new String[0]) : null;
        for (RewardData rd : original) {
            RewardData clone = new RewardData(rd);
            if (restrict) {
                clone.editions = editionsArray;
                // Shop dedup opt-in (2026-08-15, screenshot audit: 8/8 slots of the same card
                // when the restriction shrank a shop type's legal pool to one name) - see
                // RewardData.uniqueCards' own comment.
                if (uniqueCards) {
                    clone.uniqueCards = true;
                }
                // "Union"-type rewards build their card pool exclusively from the NESTED
                // cardUnion entries (RewardData.generate()'s Union branch never consults the
                // outer .editions), and the copy constructor only shallow-clones that array -
                // the nested elements stay the shared originals. Without deep-cloning them
                // here, all 157 Union-type reward entries in this plane's shops.json bypassed
                // the restriction entirely (2026-08-12 review finding). Overwriting rather
                // than intersecting is safe: no nested entry in shops.json carries its own
                // editions field (verified across the whole file).
                if (clone.cardUnion != null) {
                    for (int i = 0; i < clone.cardUnion.length; i++) {
                        if (clone.cardUnion[i] == null)
                            continue;
                        RewardData nested = new RewardData(clone.cardUnion[i]);
                        nested.editions = editionsArray;
                        clone.cardUnion[i] = nested;
                    }
                }
            }
            result.add(clone);
        }
        return result;
    }

    /**
     * The editions Inn tournaments/events may draw from (user spec 2026-08-12): the player's
     * researched/starting unlocks PLUS the neutral shard (the "unaligned" slice of the 6-way
     * split - territory no color owns, so its sets are fair tournament stock anywhere). Null
     * means NO restriction: feature off, no world loaded yet, or nothing to restrict by (a
     * pre-feature save - consistent with restrictToEditions()' fail-open contract).
     */
    /**
     * The editions tied to the player's STARTING RACE in plane data ({@code raceEditions}), or an
     * empty set when the race has no entry. Factored out of reservePlayerEditions(), which had
     * this same lookup inline.
     */
    public static Set<String> raceEditionCodes(AdventurePlayer player) {
        Set<String> raceCodes = new HashSet<>();
        if (player == null)
            return raceCodes;
        String raceName = forge.adventure.data.HeroListData.getRawRaceName(player.getHeroRace());
        forge.adventure.data.RaceEditionData[] raceEditions = Config.instance().getConfigData().raceEditions;
        if (raceName == null || raceEditions == null)
            return raceCodes;
        for (forge.adventure.data.RaceEditionData entry : raceEditions) {
            if (entry != null && raceName.equalsIgnoreCase(entry.race)
                    && entry.editions != null && entry.editions.length > 0) {
                raceCodes.addAll(Arrays.asList(entry.editions));
                break;
            }
        }
        return raceCodes;
    }

    /**
     * Event editions for a PLAYER-OWNED town's Inn (user spec 2026-08-31: "let's have those only
     * have tournaments from the Players starting race and unlocked sets").
     * <p>
     * The player's own towns run on their own stock: the race's lore editions UNION everything
     * they have researched - deliberately a union, not an intersection. unlockedEditions starts as
     * a difficulty-scaled random subset of the race's own sets and then grows to arbitrary sets
     * through the Lab, so an intersection would shrink to a fixed handful forever, while the union
     * is monotonically growing and is what "your race and your unlocked sets" plainly reads as.
     * <p>
     * The neutral shard is deliberately NOT added here - that is exactly the wide pool this is
     * meant to narrow away from. AI and neutral towns keep the no-arg method unchanged, so the
     * player's towns feel like their own and rival towns still show you the wider world.
     */
    public static Set<String> playerTownEventEditionCodes() {
        WorldSave save = WorldSave.getCurrentSave();
        World world = save == null ? null : save.getWorld();
        if (world == null || !world.isEditionProgressionEnabled())
            return null;
        AdventurePlayer player = AdventurePlayer.current();
        if (player == null)
            return null;
        Set<String> allowed = new HashSet<>(raceEditionCodes(player));
        if (player.getUnlockedEditions() != null)
            allowed.addAll(player.getUnlockedEditions());
        return allowed.isEmpty() ? null : allowed;
    }

    /**
     * A cheap, order-independent fingerprint of the pool a player-town event would be built from.
     * Stored on the event so a cached tournament can notice the player has researched a new set
     * since it was rolled - the user asked that these "update as time passes to take into account
     * newly unlocked sets", and an Available event otherwise sits in the save forever.
     * Never 0 for a real pool, so 0 can mean "legacy event, leave alone".
     */
    public static int playerTownPoolStamp() {
        Set<String> pool = playerTownEventEditionCodes();
        if (pool == null || pool.isEmpty())
            return 0;
        int stamp = pool.size();
        for (String code : pool)
            stamp += code.hashCode(); // sum: order-independent, no sorting needed
        return stamp == 0 ? 1 : stamp;
    }

    public static Set<String> eventAllowedEditionCodes() {
        WorldSave save = WorldSave.getCurrentSave();
        World world = save == null ? null : save.getWorld();
        if (world == null || !world.isEditionProgressionEnabled())
            return null;
        Set<String> allowed = new HashSet<>();
        AdventurePlayer player = AdventurePlayer.current();
        if (player != null && player.getUnlockedEditions() != null)
            allowed.addAll(player.getUnlockedEditions());
        allowed.addAll(getEditionsForColor(world, NEUTRAL));
        return allowed.isEmpty() ? null : allowed;
    }

    /**
     * True when at least one of the player's unlocked editions can actually produce a booster
     * pack (has a Draft template) - the "cardPackShop" reward type silently generates nothing
     * otherwise (see RewardData.generate()'s empty-allEditions guard). Fresh saves can start
     * booster-incapable: Insane seeds only Jumpstart, whose family has no booster templates.
     * Always true with the feature off - shops then draw from the unrestricted master pool.
     */
    public static boolean playerHasBoosterCapableUnlockedEdition() {
        if (!WorldSave.getCurrentSave().getWorld().isEditionProgressionEnabled())
            return true;
        for (String code : AdventurePlayer.current().getUnlockedEditions()) {
            CardEdition ed = FModel.getMagicDb().getEditions().get(code);
            if (ed != null && ed.hasBoosterTemplate())
                return true;
        }
        return false;
    }

    /**
     * Same owner-lookup + restrictToEditions() combination MapStage.java's initial shop-build uses,
     * factored out for any OTHER code path that re-generates a shop's rewards after the map is
     * already loaded - restocking (paid Refresh), the Armory's own manual re-roll, the shop-type
     * re-roll, and town/shop restoration all used to read the shop's raw RewardData directly and
     * skip this restriction entirely, so a single Refresh purchase could draw cards from every
     * edition again regardless of unlockedEditions/color shard (real bug, user-reported 2026-08-12,
     * screenshot showed a dozen-plus different sets in a fresh game).
     * Reads the CURRENT town the same way the map-build path does (TileMapScene.instance().rootPoint),
     * so this is only valid to call while actually standing in the shop's own town/POI.
     */
    // Diagnostic logging extension (2026-08-13, user request - "can we somehow create a log for
    // future testing" for AI-color shop/Inn/monster-drop edition assignments). Adds a caller-
    // supplied trigger label (init/restock/armory-reroll/armory-upgrade/shop-reroll/town-restore/
    // shop-rebuild) and the actual town/POI name + branch reason to [TFR-ShopEditions], replacing
    // the old unconditional "(regen)" suffix that couldn't distinguish first-ever map-load
    // generation from a player-triggered regeneration, and gave no way to independently verify
    // ColorReputation.colorOfTown()'s name-prefix heuristic classified a given town correctly.
    public static Iterable<RewardData> restrictShopRewardsForCurrentTown(
            Iterable<RewardData> source, PointOfInterestChanges changes, String shopNameForLogging, String trigger) {
        World world = WorldSave.getCurrentSave().getWorld();
        if (!world.isEditionProgressionEnabled())
            return source;
        if (shopNameForLogging != null && shopNameForLogging.startsWith("Ring")) { // round 106: Ring City shops see EVERY edition
            System.out.println("[TFR-ShopEditions] shop=" + shopNameForLogging + " is a Ring City shop - no edition restriction (" + trigger + ")");
            return source;
        }
        // Single guarded read (adversarial review, 2026-08-13) - an earlier version of this log
        // line null-checked rootPoint only for the townName log field, five lines after the
        // else-branch's color lookup already dereferenced it unguarded; that guard could never
        // actually help, since a null rootPoint would already have thrown before reaching it.
        // Reading it once here means the color-match branch below stays covered by this same check.
        PointOfInterest rootPoint = TileMapScene.instance().rootPoint;
        List<String> editionRestriction;
        String ownerLabel;
        String reason;
        if (TownRestoration.isCurrentTownCapitol()) {
            editionRestriction = new ArrayList<>(AdventurePlayer.current().getUnlockedEditions());
            ownerLabel = "player-unlocked";
            reason = "capitol";
        } else if (TownRestoration.isTownRestored(changes)) {
            editionRestriction = new ArrayList<>(AdventurePlayer.current().getUnlockedEditions());
            ownerLabel = "player-unlocked";
            reason = "restored";
        } else {
            String townColor = rootPoint != null ? ColorReputation.colorOfTown(rootPoint.getData()) : null;
            ownerLabel = townColor != null ? townColor : NEUTRAL;
            reason = townColor != null ? "color=" + townColor : "no-match-neutral";
            editionRestriction = getEditionsForColor(world, ownerLabel);
        }
        String townName = rootPoint != null ? rootPoint.getData().name : "(unknown)";
        System.out.println("[TFR-ShopEditions] shop=" + shopNameForLogging + " town=\"" + townName + "\""
                + " owner=" + ownerLabel + " reason=" + reason + " trigger=" + trigger
                + " restriction(" + editionRestriction.size() + ")=" + editionRestriction);
        List<RewardData> restricted = restrictToEditions(source, editionRestriction, true);

        // Armory item-rarity venue stamp (user spec 2026-08-31). This is the single stamping
        // point because all six shop-generation call sites route through this method, and it
        // already holds both the town's changes and the shop name. The clones are fresh, so
        // mutating them here cannot leak into the shared ShopData originals.
        //
        // Only Armory-family shops carry a venue; ordinary card shops never roll an item rarity,
        // and the AI capitals' Armory shops use hand-written fixed item lists that never reach
        // the roll at all (classifyVenue returns null for an AI town anyway).
        if (EconomyBuildings.isArmoryShopName(shopNameForLogging)) {
            String venue = ArmoryRarity.classifyVenue(changes);
            if (venue != null) {
                for (RewardData clone : restricted)
                    if (clone != null)
                        clone.armoryRarityVenue = venue;
                System.out.println("[TFR-ArmoryRarity] shop=" + shopNameForLogging + " town=\"" + townName + "\""
                        + " venue=" + venue + " day=" + world.getCurrentDay()
                        + " week=" + SpawnTierWeighting.currentWeek(world)
                        + " trigger=" + trigger + " weights=" + ArmoryRarity.describe(venue));
            }
        }
        return restricted;
    }

    /**
     * Dungeon treasure/chest pickups (RewardSprite.getRewards(), a POI object placed directly in
     * a dungeon .tmx - not a shop, not an enemy drop) had NO edition restriction at all until now
     * (2026-08-13 QC pass, user report: "hard to verify by eye... hoping you can have some QC
     * steps in the background" for dungeon loot specifically) - a real gap, since roaming-monster
     * loot in the same territory already respects the color's shard via restrictToEditions() in
     * EnemySprite. Keyed off TerritoryControl.currentColorAtPoi() - the CURRENT owner of the
     * dungeon's land, same lookup WorldStage's roaming spawner and TerritoryControl's own
     * enemy-re-theming already use - so a dungeon chest re-restricts itself if the surrounding
     * territory changes hands after world-gen, consistent with how its roaming enemies would.
     * Falls back to NEUTRAL (not "no restriction") when the current territory has no color match.
     * <p>
     * Two fixes from the 2026-08-13 holistic review of this (same-day) feature:
     * <ul>
     * <li>Hand-authored edition themes win. 21 of this plane's dungeon .tmx maps carry their own
     * {@code editions} arrays on chest rewards (e.g. the Prismari Classroom's all-STX booster
     * chest, Tarnation's OTJ/BIG/OTP chests) - restrictToEditions() would silently OVERWRITE those
     * with the territory shard, destroying the authored set theme (its "overwrite not intersect"
     * comment was only ever verified against shops.json, where no nested entry carries editions).
     * An entry that already declares editions is deliberate content, not unrestricted loot - pass
     * it through untouched, restrict only the open-ended entries.</li>
     * <li>The NEUTRAL fallback now actually fires for non-color land. currentColorAtPoi() returns
     * the raw BIOME name and is only null off-map - this plane's wasteland/"player"/ocean biomes
     * returned "waste"/"player"/"ocean", which aren't shard keys, so getEditionsForColor() came
     * back empty and restrictToEditions() treated that as NO restriction - the exact gap this
     * feature claims to close stayed open on all non-color land (and capturing territory around a
     * dungeon silently UN-restricted its chests). Any color with no shard entry now maps to
     * NEUTRAL, matching this doc's original claim.</li>
     * </ul>
     */
    public static Iterable<RewardData> restrictDungeonRewardsForCurrentPoi(Iterable<RewardData> source) {
        World world = WorldSave.getCurrentSave().getWorld();
        if (!world.isEditionProgressionEnabled())
            return source;
        PointOfInterest rootPoint = TileMapScene.instance().rootPoint;
        String color = rootPoint != null ? TerritoryControl.currentColorAtPoi(world, rootPoint) : null;
        String colorLabel = color != null ? color : NEUTRAL;
        List<String> editionRestriction = getEditionsForColor(world, colorLabel);
        if (editionRestriction.isEmpty()) {
            colorLabel = NEUTRAL;
            editionRestriction = getEditionsForColor(world, NEUTRAL);
        }
        List<RewardData> restricted = new ArrayList<>();
        int authored = 0;
        for (RewardData rd : source) {
            if (rd != null && rd.editions != null && rd.editions.length > 0) {
                restricted.add(rd);
                authored++;
            } else {
                for (RewardData clone : restrictToEditions(Collections.singletonList(rd), editionRestriction))
                    restricted.add(clone);
            }
        }
        // Diagnostic-only logging - greppable in forge.log as "[TFR-LootEditions]", same tag
        // EnemySprite's roaming-monster loot restriction already uses, distinguished by the
        // "dungeon-chest" source label instead of an enemy name.
        System.out.println("[TFR-LootEditions] dungeon-chest poi=\"" + (rootPoint != null ? rootPoint.getData().name : "(unknown)")
                + "\" color=" + colorLabel + " restriction(" + editionRestriction.size() + ")=" + editionRestriction
                + (authored > 0 ? " (authored-theme entries passed through: " + authored + ")" : ""));
        return restricted;
    }
}
