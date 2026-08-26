package forge.adventure.util;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.EnemyData;
import forge.adventure.data.RewardData;
import forge.adventure.scene.RewardScene;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.World;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Chest loot spawn (2026-08-25 user spec): the 6th resource-spawn type, ResourceSpawns.TYPE_CHEST.
 * Resolves at pickup time into a uniform 1-of-6 random event. Two are instant (Gold Chest, Lost
 * Card); two spawn a strong roaming enemy nearby for the player to engage or avoid (Dangerous
 * Enemy, Illegal Arena Match - opt-in via ordinary collision, same mechanism as ResourceSpawns'
 * own Mystery-pickup ambush); two open a dialog/scene (Thief Merchant, Duplicate).
 * <p>
 * Two documented simplifications from the original spec, both forced by real infrastructure
 * constraints rather than oversight: Thief Merchant is a free "pick 1 of 8" reward choice rather
 * than a priced shop (RewardScene.Type.Shop requires a real, MapStage-backed ShopActor at every
 * other call site in this codebase - unsafe to fake for a one-off overworld encounter); Duplicate
 * hands the player a RANDOM owned card rather than letting them choose one (no free-form
 * card-picker dialog exists anywhere in this codebase's plain Dialog+TypingLabel+buttons pattern).
 */
public class ChestEvents {
    private static final int EVENT_GOLD = 0;
    private static final int EVENT_LOST_CARD = 1;
    private static final int EVENT_DANGEROUS_ENEMY = 2;
    private static final int EVENT_THIEF_MERCHANT = 3;
    private static final int EVENT_DUPLICATE = 4;
    private static final int EVENT_ILLEGAL_ARENA = 5;

    public static void trigger(World world) {
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
        Current.player().addReward(reward);
        String cardName = reward.getCard() != null ? reward.getCard().getName() : "a card";
        String message = "A forgotten card lies within the chest - you find " + cardName + "!";
        System.out.println("[ChestEvents] Lost Card: " + message);
        GameHUD.getInstance().addNotification(message);
    }

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
        stage.spawnAt(sprite, nearbyPosition(world, stage));
        String message = "A dangerous " + enemy.name + " stalks out of the chest!";
        System.out.println("[ChestEvents] Dangerous Enemy: " + message);
        GameHUD.getInstance().addNotification("[*]" + message);
    }

    private static void triggerThiefMerchant(World world) {
        Array<Reward> rewards = generateCardRewards(new String[]{"Rare", "Mythic Rare"}, 8);
        if (rewards.isEmpty()) {
            System.out.println("[ChestEvents] Thief Merchant: no eligible cards found, awarding gold instead");
            triggerGoldChest(world);
            return;
        }
        RewardScene.instance().loadSelectableRewards(rewards, RewardScene.Type.RewardChoice, 1);
        Forge.switchScene(RewardScene.instance());
        System.out.println("[ChestEvents] Thief Merchant: offered " + rewards.size + " cards, pick 1");
    }

    private static void triggerDuplicate(World world) {
        List<PaperCard> owned = Current.player().getCards().toFlatList();
        if (owned.isEmpty()) {
            System.out.println("[ChestEvents] Duplicate: player owns no cards, awarding gold instead");
            triggerGoldChest(world);
            return;
        }
        String[] restrictedNames = Config.instance().getConfigData().restrictedCards;
        List<PaperCard> restrictedOwned = new ArrayList<>();
        if (restrictedNames != null) {
            for (PaperCard card : owned) {
                for (String restricted : restrictedNames) {
                    if (restricted.equalsIgnoreCase(card.getName())) {
                        restrictedOwned.add(card);
                        break;
                    }
                }
            }
        }
        PaperCard cheap = owned.get(world.getRandom().nextInt(owned.size()));
        PaperCard expensive = restrictedOwned.isEmpty() ? null
                : restrictedOwned.get(world.getRandom().nextInt(restrictedOwned.size()));
        System.out.println("[ChestEvents] Duplicate: offered " + cheap.getName()
                + (expensive != null ? " / " + expensive.getName() : " (no restricted card owned)"));
        WorldStage.getInstance().showChestDuplicateDialog(cheap, expensive);
    }

    private static void triggerIllegalArena(World world) {
        WorldStage stage = WorldStage.getInstance();
        if (stage.getPlayerSprite() == null) {
            triggerGoldChest(world);
            return;
        }
        EnemyData enemy = pickRandomArchmage(world);
        if (enemy == null) {
            System.out.println("[ChestEvents] Illegal Arena Match: no eligible enemy found, awarding gold instead");
            triggerGoldChest(world);
            return;
        }
        // Single-card reward, 75% Rare / 25% Mythic (user spec) - replaces the enemy's own stock
        // loot entirely, unlike Dangerous Enemy above. Left unrestricted by edition here;
        // EnemySprite.getRewards() applies this enemy's own color-based edition gate automatically
        // when the fight resolves, same as every other roaming enemy's loot.
        RewardData reward = new RewardData();
        reward.type = "card";
        reward.count = 1;
        reward.rarity = world.getRandom().nextFloat() < 0.75f ? new String[]{"Rare"} : new String[]{"Mythic Rare"};
        enemy.rewards = new RewardData[]{reward};
        System.out.println("[ChestEvents] Illegal Arena Match: offered vs " + enemy.name);
        stage.showChestArenaTollDialog(enemy, nearbyPosition(world, stage));
    }

    // Archmage-tier pick shared by Dangerous Enemy and Illegal Arena Match: a random color's
    // strongest real roaming threat (TerritoryControl.pickGrandmasterMage, made public for this),
    // tried across all 5 colors (shuffled) in case the first roll's color has no Mythic-tier
    // entry. Returns an independent clone (EnemyData's own copy constructor) - safe to mutate
    // (life, rewards) without touching the shared JSON-loaded template.
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

    // A couple tiles off the player's own position, in a random direction - opt-in via ordinary
    // collision (walk into it or avoid it) rather than an ambush placed directly on top of the
    // player, matching the Chest's "here's a risk you can choose to take" flavor. Clamped to the
    // world's pixel bounds so a chest opened at the map's edge can't spawn something unreachable.
    private static Vector2 nearbyPosition(World world, WorldStage stage) {
        float tileSize = world.getTileSize();
        float offset = (2 + world.getRandom().nextInt(2)) * tileSize;
        float angle = world.getRandom().nextFloat() * (float) (Math.PI * 2);
        float x = stage.getPlayerSprite().getX() + (float) Math.cos(angle) * offset;
        float y = stage.getPlayerSprite().getY() + (float) Math.sin(angle) * offset;
        x = Math.max(0, Math.min(x, world.getWidthInPixels() - tileSize));
        y = Math.max(0, Math.min(y, world.getHeightInPixels() - tileSize));
        return new Vector2(x, y);
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
