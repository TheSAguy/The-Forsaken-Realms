package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.ArenaData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.RewardData;
import forge.adventure.data.WorldData;
import forge.adventure.scene.ArenaScene;
import forge.adventure.scene.RewardScene;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.World;
import forge.deck.Deck;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Chest loot spawn (2026-08-25 user spec): the 6th resource-spawn type, ResourceSpawns.TYPE_CHEST.
 * Resolves at pickup time into a uniform 1-of-6 random event. Gold Chest grants instantly; Lost
 * Card shows the found card on the Loot reward screen; Dangerous Enemy launches an immediate duel
 * (WorldStage.startChestDuel(), user revision 2026-08-25 - replaced the original opt-in
 * spawn-nearby design); Illegal Arena Match launches a full 8-competitor ArenaScene bracket
 * (user revision 2026-08-25); Thief Merchant and Duplicate open a priced scene/dialog.
 * <p>
 * Two documented simplifications from the original spec, both forced by real infrastructure
 * constraints rather than oversight: Thief Merchant is a priced "pick 1 of 8" reward choice
 * (RewardScene.Type.RewardChoice with a per-card gold cost) rather than a real priced shop
 * (RewardScene.Type.Shop requires a real, MapStage-backed ShopActor at every other call site in
 * this codebase - unsafe to fake for a one-off overworld encounter); Duplicate hands the player a
 * RANDOM card from their active deck rather than letting them choose one (no free-form
 * card-picker dialog exists anywhere in this codebase's plain Dialog+TypingLabel+buttons pattern).
 */
public class ChestEvents {
    private static final int EVENT_GOLD = 0;
    private static final int EVENT_LOST_CARD = 1;
    private static final int EVENT_DANGEROUS_ENEMY = 2;
    private static final int EVENT_THIEF_MERCHANT = 3;
    private static final int EVENT_DUPLICATE = 4;
    private static final int EVENT_ILLEGAL_ARENA = 5;

    // Shop blueprint from a chest (user spec 2026-08-30: "add it to diamond AND the Chest Drop").
    private static final float CHEST_BLUEPRINT_CHANCE = 0.25f;

    public static void trigger(World world) {
        // Rolled BEFORE the ordinary 1-of-6 so it does not have to displace an existing event, and
        // it self-disables: grantRandomBlueprint() returns false when blueprints are off for this
        // plane or every type is already known, and the chest then resolves completely normally.
        if (world.getRandom().nextFloat() < CHEST_BLUEPRINT_CHANCE
                && ResourceSpawns.grantRandomBlueprint("Chest")) {
            System.out.println("[ChestEvents] Chest opened, event=blueprint");
            return;
        }
        int roll = world.getRandom().nextInt(6);
        System.out.println("[ChestEvents] Chest opened, event=" + roll);
        switch (roll) {
            case EVENT_GOLD:
                triggerGoldChest(world);
                break;
            case EVENT_LOST_CARD:
                triggerLostCard(world);
                break;
            case EVENT_DANGEROUS_ENEMY:
                triggerDangerousEnemy(world);
                break;
            case EVENT_THIEF_MERCHANT:
                triggerThiefMerchant(world);
                break;
            case EVENT_DUPLICATE:
                triggerDuplicate(world);
                break;
            default:
                triggerIllegalArena(world);
                break;
        }
    }

    private static void triggerGoldChest(World world) {
        int amount = 750 + world.getRandom().nextInt(1250 - 750 + 1);
        Current.player().giveGold(amount);
        String message = "You pry open the chest - " + amount + " Gold spills out!";
        System.out.println("[ChestEvents] Gold Chest: " + message);
        GameHUD.getInstance().addNotification(message);
    }

    private static void triggerLostCard(World world) {
        Array<Reward> rewards = generateCardRewards(new String[]{"Rare"}, 1);
        if (rewards.isEmpty()) {
            System.out.println("[ChestEvents] Lost Card: no eligible card found, awarding gold instead");
            triggerGoldChest(world);
            return;
        }
        Reward reward = rewards.first();
        String cardName = reward.getCard() != null ? reward.getCard().getName() : "a card";
        System.out.println("[ChestEvents] Lost Card: found " + cardName);
        // Shows the actual card image via the same Loot reward screen every other card drop in
        // this game uses (2026-08-26 user revision: "Show an image of the card, not the pop-up
        // text. Player needs to see the card he received."). RewardScene grants the reward
        // itself once this screen is left (see its clearGenerated() method) - do NOT also call
        // addReward() here, that would double-grant.
        RewardScene.instance().loadRewards(rewards, RewardScene.Type.Loot, null);
        Forge.switchScene(RewardScene.instance());
    }

    // User revision 2026-08-25: "Start the duel immediately. Don't just spawn the enemy" -
    // replaces the original opt-in spawn-and-walk-into-it design with a direct duel launch, same
    // template as WorldStage.startForcedCapitolDuel()/startChestDuel().
    private static void triggerDangerousEnemy(World world) {
        WorldStage stage = WorldStage.getInstance();
        if (stage.getPlayerSprite() == null) {
            triggerGoldChest(world);
            return;
        }
        EnemyData enemy = pickRandomArchmage(world);
        if (enemy == null) {
            System.out.println("[ChestEvents] Dangerous Enemy: no eligible enemy found, awarding gold instead");
            triggerGoldChest(world);
            return;
        }
        // 1.5x life handicap (user decision 2026-08-25, in place of an earlier "AI starts with a
        // land" idea that had no code path to hook into). Rewards are left as the enemy's own
        // stock loot - EnemySprite.getRewards() already edition-gates data.rewards by this enemy's
        // own color, so no custom reward array is needed here.
        enemy.life = Math.round(enemy.life * 1.5f);
        EnemySprite sprite = new EnemySprite(enemy);
        String message = "A dangerous " + enemy.name + " stalks out of the chest!";
        System.out.println("[ChestEvents] Dangerous Enemy: " + message);
        // Plain text, no "[*]" bold markup (2026-08-25 user report: illegible/smeared text) -
        // same unclosed-bold-tag bug already fixed once for "Orazca rises..."/"Camelot rises..."
        // (see TownRestoration.java's own comment on this) - [*] renders as smeared double-struck
        // glyphs at this pixel-font size when left open for an entire message.
        GameHUD.getInstance().addNotification(message);
        stage.startChestDuel(sprite);
    }

    // Priced pick-1-of-8 (user revision 2026-08-25: "not free... 0.75x their normal value") -
    // RewardScene computes each card's own price at 0.75x CardUtil.getRewardPrice() and charges
    // gold when the player picks (see RewardScene.ChooseRewardButton / selectionPriceMultiplier).
    private static final float THIEF_MERCHANT_PRICE_MULTIPLIER = 0.75f;

    private static void triggerThiefMerchant(World world) {
        Array<Reward> rewards = generateCardRewards(new String[]{"Rare", "Mythic Rare"}, 8);
        if (rewards.isEmpty()) {
            System.out.println("[ChestEvents] Thief Merchant: no eligible cards found, awarding gold instead");
            triggerGoldChest(world);
            return;
        }
        RewardScene.instance().loadSelectableRewards(rewards, RewardScene.Type.RewardChoice, 1, THIEF_MERCHANT_PRICE_MULTIPLIER);
        Forge.switchScene(RewardScene.instance());
        System.out.println("[ChestEvents] Thief Merchant: offered " + rewards.size + " cards at 0.75x value, pick 1");
    }

    // Duplicate scopes its CANDIDATE pool to the player's ACTIVE deck's mainboard (user revision
    // 2026-08-25: "duplicate a random owned card in the current active deck"), not the full owned
    // collection - but the purchased duplicate itself goes to the general Inventory/collection
    // ONLY, never into the deck (user revision 2026-08-26: a deck already at its 4-copy limit
    // would become an illegal decklist - an earlier deck-insertion version of this was
    // deliberately reverted; do NOT reintroduce it). See WorldStage.showChestDuplicateDialog().
    private static void triggerDuplicate(World world) {
        Deck deck = Current.player().getSelectedDeck();
        List<PaperCard> owned = deck != null ? deck.getMain().toFlatList() : java.util.Collections.emptyList();
        if (owned.isEmpty()) {
            System.out.println("[ChestEvents] Duplicate: active deck has no cards, awarding gold instead");
            triggerGoldChest(world);
            return;
        }
        String[] restrictedNames = Config.instance().getConfigData().restrictedCards;
        List<PaperCard> restrictedOwned = new ArrayList<>();
        List<PaperCard> unrestrictedOwned = new ArrayList<>();
        for (PaperCard card : owned) {
            boolean restricted = false;
            if (restrictedNames != null) {
                for (String restrictedName : restrictedNames) {
                    if (restrictedName.equalsIgnoreCase(card.getName())) {
                        restricted = true;
                        break;
                    }
                }
            }
            (restricted ? restrictedOwned : unrestrictedOwned).add(card);
        }
        // The cheap 25-shard slot draws only from NON-restricted deck cards (2026-08-26 review
        // finding: it used to draw from the whole deck, so a restricted card could roll into the
        // cheap slot and bypass its own 200-shard price - and both buttons could even offer the
        // identical card at two different prices). An all-restricted deck falls back to the full
        // pool rather than offering nothing.
        List<PaperCard> cheapPool = unrestrictedOwned.isEmpty() ? owned : unrestrictedOwned;
        PaperCard cheap = pickByRarityPriority(cheapPool, world);
        PaperCard expensive = restrictedOwned.isEmpty() ? null : pickByRarityPriority(restrictedOwned, world);
        System.out.println("[ChestEvents] Duplicate: offered " + cheap.getName()
                + (expensive != null ? " / " + expensive.getName() : " (no restricted card in active deck)"));
        WorldStage.getInstance().showChestDuplicateDialog(cheap, expensive);
    }

    // Rarity-priority pick (user revision 2026-08-25: "Must be from current deck and Rare or
    // Mythical if possible, else Uncommon next, if still not than Common"). Random WITHIN the
    // highest available tier present, not a flat random across the whole pool - a deck with any
    // Rare/Mythic never offers a Common instead. Falls through to the whole pool as a last resort
    // (a deck that's genuinely all basic lands, say) rather than ever returning null.
    private static PaperCard pickByRarityPriority(List<PaperCard> pool, World world) {
        List<PaperCard> rareOrMythic = new ArrayList<>();
        List<PaperCard> uncommon = new ArrayList<>();
        List<PaperCard> common = new ArrayList<>();
        for (PaperCard card : pool) {
            forge.card.CardRarity rarity = card.getRarity();
            if (rarity == forge.card.CardRarity.Rare || rarity == forge.card.CardRarity.MythicRare)
                rareOrMythic.add(card);
            else if (rarity == forge.card.CardRarity.Uncommon)
                uncommon.add(card);
            else
                common.add(card);
        }
        List<PaperCard> tier = !rareOrMythic.isEmpty() ? rareOrMythic
                : !uncommon.isEmpty() ? uncommon
                : !common.isEmpty() ? common : pool;
        return tier.get(world.getRandom().nextInt(tier.size()));
    }

    // Illegal Arena Match (user revision 2026-08-25: "a real arena match interface, where you
    // start as 1 of 8 competitors. Just like in the capitols. All Archmages. The reward is a
    // Rare(75%)/Mythic(25%) Item, not card") - reuses the Capitol's own ArenaScene bracket
    // wholesale instead of a single spawned duel: rounds=3 gives 2^3-1=7 enemy fighters + the
    // player = 8 competitors, exactly matching the spec. ArenaScene.loadArenaData() needs no
    // MapStage/building context (confirmed - the arenaMapStage==null path only suppresses the
    // Upgrade/Toggle buttons), so this launches directly, mirroring how WorldStage.
    // startForcedCapitolDuel() launches a duel outside the normal building-click flow. ArenaScene
    // itself already shows the entry fee and charges it on its own "Start" button
    // (Current.player().takeGold(arenaData.entryFee) inside ArenaScene.startRound()) - no separate
    // toll dialog needed here, unlike the other paid event (Duplicate).
    private static void triggerIllegalArena(World world) {
        String[] archmagePool = buildArchmagePool();
        if (archmagePool.length == 0) {
            System.out.println("[ChestEvents] Illegal Arena Match: no eligible Archmage-tier enemies found, awarding gold instead");
            triggerGoldChest(world);
            return;
        }
        // Item, not card (user spec) - "Rare"/"Mythic" are ItemData's OWN rarity strings (distinct
        // from CardRarity's "Rare"/"Mythic Rare" - see RewardData.rollWeightedItemRarity()/
        // ItemListData.getItemNamesByRarity() for the same bare-word convention).
        RewardData reward = new RewardData();
        reward.type = "item";
        reward.count = 1;
        reward.itemRarity = world.getRandom().nextFloat() < 0.75f ? "Rare" : "Mythic";

        ArenaData arenaData = new ArenaData();
        arenaData.enemyPool = archmagePool;
        arenaData.rounds = 3;
        arenaData.entryFee = 250;
        // Reward only for winning the WHOLE bracket (user spec: "the reward is..." singular), not
        // per-round loot - ArenaScene.done() grants every round's own rewards[i] independently, so
        // rounds 0/1 get an empty table and only the final round (index 2) carries the item.
        arenaData.rewards = new RewardData[3][];
        arenaData.rewards[0] = new RewardData[0];
        arenaData.rewards[1] = new RewardData[0];
        arenaData.rewards[2] = new RewardData[]{reward};

        System.out.println("[ChestEvents] Illegal Arena Match: launching an 8-competitor Archmage bracket ("
                + archmagePool.length + " eligible names), entry 250 gold");
        ArenaScene.instance().loadArenaDataStandalone(arenaData, 0L);
        Forge.switchScene(ArenaScene.instance());
    }

    // Every Mythic-tier ("Archmage") enemy name in the game, boss/quest-tagged exclusions only
    // (spawnRate<=0 "Legends"-tier enemies are DELIBERATELY included - ArenaScene's own "Champion
    // bounty" mechanism specifically exists to reward drawing one of these arena-exclusive
    // fighters into the bracket). Mixed across all 5 colors, matching how the Capitol's own arena
    // pool is already a hand-picked mix of multiple colors' wizards.
    private static String[] buildArchmagePool() {
        List<String> names = new ArrayList<>();
        for (EnemyData data : new Array.ArrayIterator<>(WorldData.getAllEnemies())) {
            if (data == null || data.boss || (data.questTags != null && data.questTags.length > 0))
                continue;
            if ("Mythic".equals(data.tier))
                names.add(data.name);
        }
        return names.toArray(new String[0]);
    }

    // Archmage-tier pick for Dangerous Enemy (Illegal Arena Match builds its own bracket pool
    // via buildArchmagePool() above instead): a random color's strongest real roaming threat
    // (TerritoryControl.pickGrandmasterMage, made public for this), tried across all 5 colors
    // (shuffled) in case the first roll's color has no Mythic-tier entry. Returns an independent
    // clone (EnemyData's own copy constructor) - safe to mutate (life, rewards) without touching
    // the shared JSON-loaded template.
    private static EnemyData pickRandomArchmage(World world) {
        List<String> colors = new ArrayList<>(java.util.Arrays.asList(ColorReputation.COLORS));
        Collections.shuffle(colors, world.getRandom());
        for (String color : colors) {
            EnemyData found = TerritoryControl.pickGrandmasterMage(world, color);
            if (found != null)
                return new EnemyData(found);
        }
        return null;
    }

    // Builds a fresh, edition-gated card RewardData and immediately generates it - shared by Lost
    // Card and Thief Merchant, both of which grant cards directly rather than through an
    // enemy-fight's own edition-restriction pipeline (EnemySprite.getRewards() does that instead
    // for Dangerous Enemy/Illegal Arena Match's fight-won loot). Draws from the same pool Inn
    // tournaments use (EditionProgression.eventAllowedEditionCodes()) - the player's own unlocked
    // editions plus the neutral shard, a reasonable "found while exploring" pool that isn't tied
    // to any one color's territory.
    private static Array<Reward> generateCardRewards(String[] rarities, int count) {
        RewardData rd = new RewardData();
        rd.type = "card";
        rd.rarity = rarities;
        rd.count = count;
        Set<String> allowed = EditionProgression.eventAllowedEditionCodes();
        if (allowed != null && !allowed.isEmpty())
            rd = EditionProgression.restrictToEditions(Collections.singletonList(rd), new ArrayList<>(allowed)).get(0);
        return rd.generate(false, true);
    }
}
