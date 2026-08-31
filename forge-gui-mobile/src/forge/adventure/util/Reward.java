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
        Blueprint
    }

    Type type;
    PaperCard card;
    ItemData item;
    Deck deck;
    // Blueprint only: the SHOP DATA NAME the blueprint teaches (e.g. "Creature8Black"), not its
    // display name - the unlock set is keyed by data name.
    String blueprintShopName;
    boolean isNoSell, isAutoSell;
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
