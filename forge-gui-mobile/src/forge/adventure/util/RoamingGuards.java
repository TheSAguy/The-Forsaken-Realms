package forge.adventure.util;

import forge.adventure.stage.GameHUD;
import com.badlogic.gdx.math.Vector2;
import forge.adventure.data.RoamingGuardConfig;
import forge.adventure.data.RoamingGuardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.world.WorldSave;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.item.PaperCard;

import java.util.List;

/**
 * Roaming guards (MOD_SCOPE #116, user spec 2026-09-07). A Capitol-only alternative to the hired
 * local guard: a champion who carries one of the player's own decks, walks the overworld, and races
 * an attacking mage to a threatened town.
 * <p>
 * <b>How this differs from a local guard.</b> A local guard is a NUMBER - when a mage arrives,
 * {@code TerritoryControl.guardFightAttackerWinChance()} rolls tier against tier and the town lives
 * or falls. A roaming guard is a DECK, and it resolves by an actual AI-vs-AI duel. That makes this
 * the game's first duel-resolved town defence, which is why the two systems share a cost table and
 * a tier ladder but nothing else.
 * <p>
 * <b>The card round-trip is the dangerous part.</b> Handing a deck to a guard removes those cards
 * from the player's collection, and the collection carries {@code noSellValue} duplicate entries
 * that the round-141 sell exploit already proved easy to desync. So a guard stores the EXACT card
 * list it was given ({@link RoamingGuardData#deckCards}) and giving it back replays that list -
 * never a recomputation from the deck, which may have been edited in the meantime.
 */
public class RoamingGuards {
    private RoamingGuards() {}

    /** Same ladder as hired local guards, so the two share a cost table and display names. */
    public static final String[] TIERS_ASCENDING = EconomyBuildings.GUARD_TIERS_ASCENDING;

    private static RoamingGuardConfig config() {
        return Config.instance().getRoamingGuardConfig();
    }

    public static boolean isEnabled() {
        RoamingGuardConfig c = config();
        return c != null && c.maxGuards > 0;
    }

    public static int maxGuards() {
        RoamingGuardConfig c = config();
        return c == null ? 0 : c.maxGuards;
    }

    public static int recoveryDays() {
        RoamingGuardConfig c = config();
        return c == null || c.recoveryDays <= 0 ? 30 : c.recoveryDays;
    }

    public static List<RoamingGuardData> roster() {
        return AdventurePlayer.current().getRoamingGuards();
    }

    // ------------------------------------------------------------------ tier stats

    public static int tierIndex(String tier) {
        for (int i = 0; i < TIERS_ASCENDING.length; i++) {
            if (TIERS_ASCENDING[i].equals(tier))
                return i;
        }
        return 0;
    }

    public static int lifeFor(String tier) {
        RoamingGuardConfig c = config();
        if (c == null)
            return 12;
        switch (tierIndex(tier)) {
            case 3:  return c.lifeArchmage;
            case 2:  return c.lifeMaster;
            case 1:  return c.lifeAdept;
            default: return c.lifeApprentice;
        }
    }

    /**
     * User's rule: "if the player is at 40, we will have Archmage be 40 and each tier below that -2
     * speed." Derived from the plane's own playerBaseSpeed rather than hardcoded, so retuning the
     * player carries the guards with it.
     * <p>
     * Worth knowing when tuning: the mages this races run 20-24 (Common/Uncommon), 30 (Rare) and
     * 50-60 (Mythic) - measured across 275 dispatches in a real session. So a guard outruns 96% of
     * attackers and NONE of the Mythic ones. That is deliberate: the counterplay to a Mythic attack
     * is a teleporter at the target town, which lets the guard skip the race entirely.
     */
    public static float speedFor(String tier) {
        float playerSpeed = Config.instance().getConfigData().playerBaseSpeed;
        RoamingGuardConfig c = config();
        int step = c == null || c.speedStepBelowPlayer <= 0 ? 2 : c.speedStepBelowPlayer;
        int rungsBelowTop = (TIERS_ASCENDING.length - 1) - tierIndex(tier);
        return Math.max(1f, playerSpeed - (rungsBelowTop * step));
    }

    public static int weeklyGoldCost(String tier) {
        return EconomyBuildings.guardWeeklyGoldCost(tier);
    }

    public static int weeklyShardCost(String tier) {
        return EconomyBuildings.guardWeeklyShardCost(tier);
    }

    public static String displayName(String tier) {
        return EconomyBuildings.guardTierDisplayName(tier);
    }

    // ------------------------------------------------------------------ roster changes

    public static boolean hasRoom() {
        return isEnabled() && roster().size() < maxGuards();
    }

    /** Hires an unequipped guard. The caller charges the cost; this only records the guard. */
    public static RoamingGuardData hire(String tier, int currentDay) {
        RoamingGuardData guard = new RoamingGuardData();
        guard.tier = tier;
        guard.maxLife = lifeFor(tier);
        guard.hiredDay = currentDay;
        guard.lastPaidDay = currentDay;
        roster().add(guard);
        System.out.println("[TFR-RoamGuard] hired a " + displayName(tier) + " (life " + guard.maxLife
                + ", speed " + speedFor(tier) + ") on day " + currentDay
                + " - roster " + roster().size() + "/" + maxGuards());
        return guard;
    }

    /**
     * Upgrade or downgrade in place. Per the user: an upgrade pays the difference in weekly cost,
     * a downgrade refunds nothing and simply starts costing less. Life follows the new tier; the
     * deck is untouched either way.
     *
     * @return the one-off gold difference charged (0 for a downgrade)
     */
    public static int retier(RoamingGuardData guard, String newTier) {
        String oldTier = guard.tier;
        int goldDifference = Math.max(0, weeklyGoldCost(newTier) - weeklyGoldCost(oldTier));
        guard.tier = newTier;
        guard.maxLife = lifeFor(newTier);
        System.out.println("[TFR-RoamGuard] " + displayName(oldTier) + " -> " + displayName(newTier)
                + " (life " + guard.maxLife + ", speed " + speedFor(newTier)
                + "), one-off charge " + goldDifference + " gold");
        return goldDifference;
    }

    /**
     * Dismiss. The deck comes back UNLESS the guard is out of commission - the user's rule is that
     * dismissing a downed guard forfeits its cards, which is the cost of not waiting out the month.
     *
     * @return true when the cards were returned
     */
    public static boolean dismiss(RoamingGuardData guard, int currentDay) {
        boolean forfeit = guard.isOutOfCommission(currentDay);
        if (!forfeit)
            returnDeck(guard);
        else
            System.out.println("[TFR-RoamGuard] dismissed while out of commission - "
                    + cardCount(guard) + " card(s) forfeited with the deck \"" + guard.deckName + "\"");
        roster().remove(guard);
        return !forfeit;
    }

    // ------------------------------------------------------------------ the deck round-trip

    /**
     * Hands the player's deck slot to this guard: the exact card list is recorded, those cards
     * leave the collection, and the slot is emptied. Emptying the slot is not cosmetic - leaving it
     * populated would point a deck at cards the player no longer owns, which is precisely the
     * deck-list/card-pool desync that made the round-141 sell exploit possible.
     *
     * @return false when the slot is empty or the guard already holds a deck
     */
    public static boolean giveDeck(RoamingGuardData guard, int deckIndex) {
        AdventurePlayer player = AdventurePlayer.current();
        if (guard.deckCards.length > 0)
            return false;
        Deck deck = player.getDeck(deckIndex);
        if (deck == null || deck.getMain().countAll() == 0)
            return false;

        CardPool main = deck.getMain();
        // Accumulate into a CardPool and serialise with the engine's own toCardList(), which is
        // exactly what AdventurePlayer.save() uses for the player's decks and exactly what
        // CardPool.fromCardList() parses back. Hand-rolling "name|edition|number" here would have
        // been silently unreadable on the way home - processCardList() wants a leading count and
        // a CardDb.CardRequest.compose() body, and this is the one operation in this feature that
        // cannot be undone if it is wrong.
        CardPool taken = new CardPool();
        for (java.util.Map.Entry<PaperCard, Integer> entry : main) {
            PaperCard card = entry.getKey();
            int wanted = entry.getValue();
            // Take only what the player actually holds. A deck can legitimately list more copies
            // than the collection has if the collection was edited underneath it, and silently
            // creating cards on the way back would be worse than carrying a slightly short deck.
            int available = player.getCards().count(card);
            int amount = Math.min(wanted, available);
            if (amount <= 0) {
                System.out.println("[TFR-RoamGuard] deck \"" + deck.getName() + "\" lists " + wanted + "x "
                        + card.getName() + " but the collection holds none - skipped");
                continue;
            }
            player.getCards().remove(card, amount);
            taken.add(card, amount);
        }
        guard.deckName = deck.getName();
        guard.deckCards = taken.countAll() == 0 ? new String[0] : taken.toCardList("\n").split("\n");
        player.clearDeck(deckIndex);
        System.out.println("[TFR-RoamGuard] took deck \"" + guard.deckName + "\" (" + taken.countAll()
                + " cards, " + guard.deckCards.length + " entries) from slot " + deckIndex
                + " - slot cleared, cards removed from the collection");
        return true;
    }

    /** Puts the guard's cards back in the collection. The deck itself is rebuilt by the caller into
     *  whichever slot the player chooses (see returnDeckToSlot). */
    public static void returnDeck(RoamingGuardData guard) {
        if (guard.deckCards.length == 0)
            return;
        AdventurePlayer player = AdventurePlayer.current();
        CardPool returned = CardPool.fromCardList(java.util.Arrays.asList(guard.deckCards));
        player.getCards().addAll(returned);
        System.out.println("[TFR-RoamGuard] returned " + returned.countAll() + " card(s) from \""
                + guard.deckName + "\" to the collection");
        guard.deckCards = new String[0];
        guard.deckName = "";
    }

    /** Returns the cards AND rebuilds the deck into an empty slot, so the player gets the list back
     *  rather than a pile of loose cards. */
    public static boolean returnDeckToSlot(RoamingGuardData guard, int deckIndex) {
        if (guard.deckCards.length == 0)
            return false;
        AdventurePlayer player = AdventurePlayer.current();
        String name = guard.deckName;
        String[] cards = guard.deckCards;
        returnDeck(guard);
        player.setDeck(deckIndex, name, cards);
        System.out.println("[TFR-RoamGuard] rebuilt deck \"" + name + "\" into slot " + deckIndex);
        return true;
    }

    /** How many actual cards the guard is holding. NOT deckCards.length - that is the number of
     *  count-prefixed entry lines, so a 4-of counts once there. */
    public static int cardCount(RoamingGuardData guard) {
        if (guard.deckCards.length == 0)
            return 0;
        return CardPool.fromCardList(java.util.Arrays.asList(guard.deckCards)).countAll();
    }

    /** The Deck a guard fights with, rebuilt from its stored list. Null when it carries nothing. */
    public static Deck battleDeck(RoamingGuardData guard) {
        if (guard.deckCards.length == 0)
            return null;
        Deck deck = new Deck(guard.deckName == null || guard.deckName.isEmpty() ? "Guard" : guard.deckName);
        deck.getMain().addAll(CardPool.fromCardList(java.util.Arrays.asList(guard.deckCards)));
        return deck;
    }

    // ------------------------------------------------------------------ engagement rules

    /** Is this guard willing to fight an enemy of the given EnemyData tier? */
    public static boolean willEngage(RoamingGuardData guard, String enemyTier) {
        int index = tierIndex(enemyTier);
        return guard.engageTier != null && index < guard.engageTier.length && guard.engageTier[index];
    }

    // ------------------------------------------------------------------ daily upkeep

    /**
     * Returns a defeated guard to duty once its month is up. Called from the day tick alongside the
     * local guards' own weekly salary sweep.
     */
    public static void processDailyTick(int currentDay) {
        if (!isEnabled())
            return;
        for (RoamingGuardData guard : roster()) {
            if (guard.downUntilDay > 0 && currentDay >= guard.downUntilDay) {
                guard.downUntilDay = 0;
                System.out.println("[TFR-RoamGuard] a " + displayName(guard.tier)
                        + " has recovered and returned to duty on day " + currentDay);
                GameHUD.getInstance().addNotification("[GREEN]Your " + displayName(guard.tier)
                        + " guard has recovered and returned to duty.");
            }
        }
    }

    /** Records a defeat: the guard is out of commission for the configured recovery window. */
    public static void onDefeated(RoamingGuardData guard, int currentDay) {
        guard.downUntilDay = currentDay + recoveryDays();
        guard.deployed = false;
        guard.missionPoiId = "";
        guard.returningHome = false;
        System.out.println("[TFR-RoamGuard] " + displayName(guard.tier) + " defeated on day " + currentDay
                + " - out of commission until day " + guard.downUntilDay);
    }

    // ------------------------------------------------------------------ persistence

    /** Nested sub-data per guard, keyed by index - the same idiom the player's own decks use. No
     *  new serializable class enters the save, so this cannot move the save format. */
    public static void save(SaveFileData data, List<RoamingGuardData> guards) {
        data.store("roamingGuardCount", guards.size());
        for (int i = 0; i < guards.size(); i++) {
            RoamingGuardData g = guards.get(i);
            SaveFileData sub = new SaveFileData();
            sub.store("tier", g.tier == null ? TIERS_ASCENDING[0] : g.tier);
            sub.store("maxLife", g.maxLife);
            sub.store("deckName", g.deckName == null ? "" : g.deckName);
            sub.storeObject("deckCards", g.deckCards == null ? new String[0] : g.deckCards);
            StringBuilder engage = new StringBuilder();
            for (int t = 0; t < TIERS_ASCENDING.length; t++)
                engage.append(g.engageTier != null && t < g.engageTier.length && g.engageTier[t] ? '1' : '0');
            sub.store("engage", engage.toString());
            sub.store("watchMatches", g.watchMatches);
            sub.store("hiredDay", g.hiredDay);
            sub.store("lastPaidDay", g.lastPaidDay);
            sub.store("downUntilDay", g.downUntilDay);
            sub.store("missionPoiId", g.missionPoiId == null ? "" : g.missionPoiId);
            sub.store("returningHome", g.returningHome);
            sub.store("deployed", g.deployed);
            sub.store("pos", new Vector2(g.x, g.y));
            data.store("roamingGuard_" + i, sub);
        }
    }

    public static void load(SaveFileData data, List<RoamingGuardData> guards) {
        guards.clear();
        if (!data.containsKey("roamingGuardCount"))
            return; // save predates the feature - no guards, nothing to migrate
        int count = data.readInt("roamingGuardCount");
        for (int i = 0; i < count; i++) {
            if (!data.containsKey("roamingGuard_" + i))
                continue;
            SaveFileData sub = data.readSubData("roamingGuard_" + i);
            RoamingGuardData g = new RoamingGuardData();
            g.tier = sub.containsKey("tier") ? sub.readString("tier") : TIERS_ASCENDING[0];
            g.maxLife = sub.containsKey("maxLife") ? sub.readInt("maxLife") : lifeFor(g.tier);
            g.deckName = sub.containsKey("deckName") ? sub.readString("deckName") : "";
            Object cards = sub.containsKey("deckCards") ? sub.readObject("deckCards") : null;
            g.deckCards = cards instanceof String[] ? (String[]) cards : new String[0];
            String engage = sub.containsKey("engage") ? sub.readString("engage") : "1111";
            for (int t = 0; t < g.engageTier.length; t++)
                g.engageTier[t] = t < engage.length() && engage.charAt(t) == '1';
            g.watchMatches = !sub.containsKey("watchMatches") || sub.readBool("watchMatches");
            g.hiredDay = sub.containsKey("hiredDay") ? sub.readInt("hiredDay") : 0;
            g.lastPaidDay = sub.containsKey("lastPaidDay") ? sub.readInt("lastPaidDay") : 0;
            g.downUntilDay = sub.containsKey("downUntilDay") ? sub.readInt("downUntilDay") : 0;
            g.missionPoiId = sub.containsKey("missionPoiId") ? sub.readString("missionPoiId") : "";
            g.returningHome = sub.containsKey("returningHome") && sub.readBool("returningHome");
            g.deployed = sub.containsKey("deployed") && sub.readBool("deployed");
            if (sub.containsKey("pos")) {
                Vector2 pos = sub.readVector2("pos");
                g.x = pos.x;
                g.y = pos.y;
            }
            guards.add(g);
        }
        if (!guards.isEmpty())
            System.out.println("[TFR-RoamGuard] loaded " + guards.size() + " roaming guard(s)");
    }

    // ------------------------------------------------------------------ helpers

    /** The player's Capitol, which is every guard's home and the hub of the teleport network. */
    public static PointOfInterest capitol() {
        for (PointOfInterest poi : WorldSave.getCurrentSave().getWorld().getAllPointOfInterest()) {
            if (TownRestoration.CAPITOL_POI_NAME.equals(poi.getData().name))
                return poi;
        }
        return null;
    }
}
