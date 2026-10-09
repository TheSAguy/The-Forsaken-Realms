package forge.adventure.util;

import forge.adventure.data.ItemData;
import forge.deck.Deck;
import forge.item.PaperCard;

/**
 * Reward class that may contain gold,cards or items
 */
public class Reward {
    public enum Type {
        Card,
        Gold,
        Item,
        Life,
        Shards,
        CardPack,
        Stone,
        Wood,
        // Mod addition (The Forsaken Realms, 2026-08-31): a shop-type blueprint, so a drop can be
        // REVEALED as a card the player turns over instead of a HUD notification that scrolls past
        // unread. User report: "I got a blue-print, but was not very obvious. Might have missed the
        // pop-up... Let's show a card or something with a Scroll/Blue-print on it that you need to
        // click (Like when you get a card)".
        Blueprint,
        // Round 517 (the user: "let's make a card out of it and when you win a duel, have it as a reward, and only apply
        // the Power once you open/collect your reward"): Ascendance Power on a won duel's loot screen - paid by
        // AdventurePlayer.addReward when collected; its card shows the "Power" icon of items.atlas.
        Power;
        private final String labelKey = "lbl" + this.name();
        /**
         * @return The pre-cached localizer key name (e.g., "lblLife", "lblShards", "lblGold").
         */
        public String getLabelKey() {
            return this.labelKey;
        }
    }

    Type type;
    PaperCard card;
    ItemData item;
    Deck deck;
    // Blueprint only: the SHOP DATA NAME the blueprint teaches (e.g. "Creature8Black"), not its
    // display name - the unlock set is keyed by data name.
    String blueprintShopName;
    boolean isNoSell, isAutoSell;
    // Round 406: gold paid for promised cards a reward list could not hand over (RewardData's deckCard fallback).
    // CardBudget drops it - the budget trims the list's cards and pays its own gold for a real shortfall.
    boolean cardFallbackGold;
    private final int count;

    public Reward(ItemData item) {
        type = Type.Item;
        this.item = item;
        count = 1;
    }

    public Reward(int count) {
        type = Type.Gold;
        this.count = count;
    }

    /** A shop-type blueprint. Static factory rather than a constructor because {@code Reward} is
     *  already overloaded on a bare String-free signature set and a second String constructor
     *  would be ambiguous at the call site. */
    public static Reward blueprint(String shopName) {
        Reward reward = new Reward(Type.Blueprint, 1);
        reward.blueprintShopName = shopName;
        return reward;
    }

    public String getBlueprintShopName() {
        return blueprintShopName;
    }

    // Round 517: what the Power was for ("beat Wild Rat (Apprentice, first win)") - the notice says it when collected.
    String powerSource = "";

    /** Round 517: a won duel's Power, already scaled by the leveling speed - collected as it stands. */
    public static Reward power(int count, String source) {
        Reward reward = new Reward(Type.Power, count);
        reward.powerSource = source == null ? "" : source;
        return reward;
    }

    public String getPowerSource() {
        return powerSource;
    }

    /** Round 406: gold standing in for cards a reward list promised and could not pay. */
    public static Reward cardFallbackGold(int count) {
        Reward reward = new Reward(count);
        reward.cardFallbackGold = true;
        return reward;
    }

    public boolean isCardFallbackGold() {
        return cardFallbackGold;
    }

    public Reward(PaperCard card) {
        this(card, false);
    }

    public Reward(PaperCard card, boolean isNoSell) {
        type = Type.Card;
        this.card = card;
        count = 0;
        this.isNoSell = isNoSell;
        if(isNoSell)
            this.card = card.getNoSellVersion();
    }

    public Reward(Type type, int count) {
        this.type = type;
        this.count = count;
    }

    public Reward(Deck deck) {
        this(deck, false);
    }

    public Reward(Deck deck, boolean isNoSell) {
        type = Type.CardPack;
        this.deck = deck;
        count = 0;
        this.isNoSell = isNoSell;
        if(isNoSell)
            deck.getTags().add("noSell");
        //Could go through the deck and replace everything in it with the noSellValue version but the tag should
        //handle that later.
    }

    public PaperCard getCard() {
        return card;
    }

    public ItemData getItem() {
        return item;
    }

    public Deck getDeck() {
        return deck;
    }

    public Type getType() {
        return type;
    }

    public int getCount() {
        return count;
    }

    public boolean isNoSell() {
        return isNoSell;
    }

    public boolean isAutoSell() {
        return isAutoSell;
    }

    public void setAutoSell(boolean val) {
        isAutoSell = val;
    }
}
