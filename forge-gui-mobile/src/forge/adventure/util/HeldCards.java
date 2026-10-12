package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.Forge;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.scene.RewardScene;
import forge.deck.CardPool;
import forge.item.PaperCard;

import java.util.List;

/**
 * Round 582 (the user: "For the temple. In stead of just having the weekly cards go to you inventory, let's have a
 * "collect reward" button ... and have it keep the card there till you come collect them. Need to make sure it can hold
 * more than 1 weeks worth of cards ... It should act like when you open a booster. Let's actually do the same for the
 * guards that collect cards. In the armory somewhere, add the same button to get the cards the guards collected").
 * <p>
 * Two piles on the player, saved with it: the Temple's weekly offerings (TempleOfferings.pay) and the roaming guards'
 * Spoils of War (RoamingGuardRuntime.guardSpoils). A pile grows until it is collected - any number of weeks, any number
 * of spoils. Collecting opens the reward screen the way opening a booster does (Type.EventReward): the cards face down,
 * flipped by Done, added to the collection as the screen closes, with its "All" auto-sell box.
 */
public final class HeldCards {
    private HeldCards() {}

    public static CardPool temple() {
        return AdventurePlayer.current().getTempleOffering();
    }

    public static int templeWeeks() {
        return AdventurePlayer.current().getTempleOfferingWeeks();
    }

    public static CardPool spoils() {
        return AdventurePlayer.current().getGuardSpoils();
    }

    /** TempleOfferings.pay: one week's offering joins whatever earlier weeks left waiting. */
    public static void holdTempleOffering(List<PaperCard> cards) {
        AdventurePlayer player = AdventurePlayer.current();
        for (PaperCard card : cards)
            player.getTempleOffering().add(card);
        player.setTempleOfferingWeeks(player.getTempleOfferingWeeks() + 1);
        System.out.println("[TFR-HeldCards] Temple: +" + cards.size() + " card(s), " + player.getTempleOffering().countAll()
                + " waiting from " + player.getTempleOfferingWeeks() + " week(s)");
    }

    /** RoamingGuardRuntime.guardSpoils: a card a guard took waits at the Armory. */
    public static void holdSpoils(PaperCard card) {
        spoils().add(card);
        System.out.println("[TFR-HeldCards] Spoils of War: +" + card.getName() + ", " + spoils().countAll() + " waiting");
    }

    /** The Temple dialog's Collect button: the offering, shown as a booster opens. */
    public static void collectTemple() {
        Array<Reward> rewards = take(temple(), "Temple");
        AdventurePlayer.current().setTempleOfferingWeeks(0);
        if (rewards.isEmpty())
            return;
        RewardScene.instance().loadRewards(rewards, RewardScene.Type.EventReward, null);
        Forge.switchScene(RewardScene.instance());
    }

    /** The Armory's Collect Spoils (RoamingGuardUI.openGuardChooser) - from the Armory's own page, which is the reward
     *  screen itself; the console's "guard orders" opens the same dialog over other screens. */
    public static void collectSpoils() {
        Array<Reward> rewards = take(spoils(), "Spoils of War");
        if (rewards.isEmpty())
            return;
        if (Forge.getCurrentScene() == RewardScene.instance()) {
            RewardScene.instance().closeShopAndReveal(rewards);
            return;
        }
        RewardScene.instance().loadRewards(rewards, RewardScene.Type.EventReward, null);
        Forge.switchScene(RewardScene.instance());
    }

    /** The pile as rewards, emptied: the reward screen adds them to the collection when it closes. */
    private static Array<Reward> take(CardPool pile, String what) {
        Array<Reward> rewards = new Array<>();
        for (PaperCard card : pile.toFlatList())
            rewards.add(new Reward(card));
        System.out.println("[TFR-HeldCards] " + what + ": " + rewards.size + " card(s) collected");
        pile.clear();
        return rewards;
    }
}
