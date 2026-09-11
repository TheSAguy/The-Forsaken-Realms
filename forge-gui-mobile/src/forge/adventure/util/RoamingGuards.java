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

    /** Shards to cut a defeated guard's recovery short (round 152, user request). */
    public static int healShardCost() {
        RoamingGuardConfig c = config();
        return c == null || c.healShardCost <= 0 ? 100 : c.healShardCost;
    }

    /** Returns a downed guard to duty at once. The caller charges the shards. */
    public static void heal(RoamingGuardData guard, int currentDay) {
        System.out.println("[TFR-RoamGuard] " + displayName(guard.tier) + " healed on day " + currentDay
                + " for " + healShardCost() + " shards - was out until day " + guard.downUntilDay);
        guard.downUntilDay = 0;
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

    /**
     * ROAMING wage, per guard per week (round 157, user spec: 30 / 60 / 100 / 150). No longer the
     * local table - a roaming guard covers the whole map instead of one town, so it is priced
     * above a garrison of the same rank rather than identically to it.
     * <p>
     * scaledCost() still applies, so these are the Normal-difficulty figures.
     */
    public static int weeklyGoldCost(String tier) {
        if (tier == null)
            return EconomyBuildings.scaledCost(30);
        switch (tier) {
            case "Uncommon": return EconomyBuildings.scaledCost(60);
            case "Rare": return EconomyBuildings.scaledCost(100);
            case "Mythic": return EconomyBuildings.scaledCost(150);
            default: return EconomyBuildings.scaledCost(30);
        }
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

    /** A guard with no deck cannot fight, cannot be dispatched, and is not paid (round 146,
     *  user report: "if he does not have a deck, he should probably not be able to leave/cost gold
     *  weekly"). It is a contract, not a soldier, until it is armed. */
    public static boolean isArmed(RoamingGuardData guard) {
        return guard != null && guard.deckCards.length > 0;
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
        // Round 163: the steel is the player's whatever happens to the deck - back to the storage.
        ArmoryStorage.returnGear(guard);
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
        // THE BUG THE USER FOUND (round 146). Adventure decks are VIEWS over one shared
        // collection - the deck editor happily lists the same single card in three decks, and
        // nothing enforces that the decks add up to what is owned. So removing a deck's cards from
        // the collection left every OTHER deck still listing them, which had two consequences:
        //   1. those decks looked full but could no longer be built - the next guard handed one
        //      over silently got a short deck (the user's Norn's Verdict came out 25 of 40);
        //   2. worse, a Deck carries its own CardPool, so the player could still PLAY cards they
        //      had given away. That is the duplication they suspected.
        // Stripping the same cards from every other deck is the only honest resolution: the player
        // genuinely does not own them any more, and a deck that lies about it is the more damaging
        // of the two failure modes.
        stripFromOtherDecks(player, taken, deckIndex);
        guard.deckName = deck.getName();
        guard.deckCards = taken.countAll() == 0 ? new String[0] : taken.toCardList("\n").split("\n");
        player.clearDeck(deckIndex);
        System.out.println("[TFR-RoamGuard] collection after hand-over: " + player.getCards().countAll()
                + " cards (" + player.getCards().countDistinct() + " distinct)");
        System.out.println("[TFR-RoamGuard] took deck \"" + guard.deckName + "\" (" + taken.countAll()
                + " cards, " + guard.deckCards.length + " entries) from slot " + deckIndex
                + " - slot cleared, cards removed from the collection");
        return true;
    }

    /**
     * Removes cards that have just left the collection from every OTHER deck that lists them, so
     * no deck claims cards the player no longer owns. Returns a short human-readable summary of
     * what was touched, for the confirmation the UI shows before this runs.
     */
    private static void stripFromOtherDecks(AdventurePlayer player, CardPool taken, int skipIndex) {
        for (int i = 0; i < player.getDeckCount(); i++) {
            if (i == skipIndex)
                continue;
            Deck other = player.getDeck(i);
            if (other == null || other.getMain().countAll() == 0)
                continue;
            int removed = 0;
            for (java.util.Map.Entry<PaperCard, Integer> entry : taken) {
                int have = other.getMain().count(entry.getKey());
                if (have <= 0)
                    continue;
                // Round 160 (code review): strip only the SHORTFALL against what the collection
                // still holds, not everything the guard took. The collection has already been
                // reduced by the time this runs, so a deck listing 17 Swamps while 23 remain loses
                // nothing - the old min(have, taken) gutted every mono-colour deck sharing basics.
                int remaining = player.getCards().count(entry.getKey());
                int drop = Math.min(have, Math.max(0, have - remaining));
                if (drop <= 0)
                    continue;
                other.getMain().remove(entry.getKey(), drop);
                removed += drop;
            }
            if (removed > 0)
                System.out.println("[TFR-RoamGuard] deck \"" + other.getName() + "\" (slot " + i
                        + ") lost " + removed + " card(s) that went with the guard - they are no"
                        + " longer in the collection");
        }
    }

    /**
     * How many cards this deck would ACTUALLY hand over. Round 148, user spec: "An invalid deck,
     * (less than 40 cards), should not be possible to give."
     * <p>
     * Deliberately not {@code deck.getMain().countAll()}. Adventure decks are views over one shared
     * collection, so a deck can list cards another deck already took with a guard - the listed size
     * says 40 while the collection can only supply 25, which is exactly what happened in the user's
     * save before round 146. The collection is the source of truth, so the gate has to count what
     * giveDeck() will really find.
     */
    public static int deliverableCount(int deckIndex) {
        AdventurePlayer player = AdventurePlayer.current();
        Deck deck = player.getDeck(deckIndex);
        if (deck == null)
            return 0;
        int total = 0;
        for (java.util.Map.Entry<PaperCard, Integer> entry : deck.getMain())
            total += Math.min(entry.getValue(), player.getCards().count(entry.getKey()));
        return total;
    }

    /** The same minimum DuelScene enforces, so a guard can never carry a deck the player could not
     *  legally play themselves. */
    public static int minDeckSize() {
        int configured = Config.instance().getConfigData().minDeckSize;
        return configured > 0 ? configured : 40;
    }

    /** Which of the player's OTHER decks would lose cards if this deck were handed over, and how
     *  many each. Used to warn before the hand-over, since it is not reversible per-deck. */
    public static java.util.LinkedHashMap<String, Integer> sharedCardImpact(int deckIndex) {
        java.util.LinkedHashMap<String, Integer> impact = new java.util.LinkedHashMap<>();
        AdventurePlayer player = AdventurePlayer.current();
        Deck source = player.getDeck(deckIndex);
        if (source == null)
            return impact;
        for (int i = 0; i < player.getDeckCount(); i++) {
            if (i == deckIndex)
                continue;
            Deck other = player.getDeck(i);
            if (other == null || other.getMain().countAll() == 0)
                continue;
            int shared = 0;
            for (java.util.Map.Entry<PaperCard, Integer> entry : source.getMain()) {
                // Round 160: what this deck would actually LOSE - its shortfall once the source
                // deck's copies leave the collection - not merely what the two lists have in common.
                int have = other.getMain().count(entry.getKey());
                if (have <= 0)
                    continue;
                int owned = player.getCards().count(entry.getKey());
                int leaving = Math.min(entry.getValue(), owned);
                shared += Math.min(have, Math.max(0, have - (owned - leaving)));
            }
            if (shared > 0)
                impact.put(other.getName(), shared);
        }
        return impact;
    }

    /** Puts the guard's cards back in the collection. The deck itself is rebuilt by the caller into
     *  whichever slot the player chooses (see returnDeckToSlot). */
    public static void returnDeck(RoamingGuardData guard) {
        if (guard.deckCards.length == 0)
            return;
        AdventurePlayer player = AdventurePlayer.current();
        CardPool returned = CardPool.fromCardList(java.util.Arrays.asList(guard.deckCards));
        int before = player.getCards().countAll();
        player.getCards().addAll(returned);
        System.out.println("[TFR-RoamGuard] collection " + before + " -> " + player.getCards().countAll()
                + " cards after returning \"" + guard.deckName + "\"");
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

    /** Round 148: the colour half of the same question. An attacker with no territory colour is
     *  not a dispatched mage at all and is never filtered out here - the rank rule still applies. */
    public static boolean willEngageColor(RoamingGuardData guard, String territoryColor) {
        int index = colorIndex(territoryColor);
        if (index < 0)
            return true;
        return guard.engageColor == null || index >= guard.engageColor.length || guard.engageColor[index];
    }

    /** Index into TerritoryControl.COLORS, or -1 for null/blank/unknown. */
    public static int colorIndex(String territoryColor) {
        if (territoryColor == null || territoryColor.isEmpty())
            return -1;
        for (int i = 0; i < TerritoryControl.COLORS.length; i++)
            if (TerritoryControl.COLORS[i].equalsIgnoreCase(territoryColor))
                return i;
        return -1;
    }

    /** True when the player has unchecked so much that this guard would refuse every attacker -
     *  worth saying out loud, since an inert guard still draws its wage. */
    public static boolean engagesNothing(RoamingGuardData guard) {
        boolean anyTier = false, anyColor = false;
        for (boolean t : guard.engageTier)
            anyTier |= t;
        for (boolean c : guard.engageColor)
            anyColor |= c;
        return !anyTier || !anyColor;
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
            // Round 148: nine flags now - four ranks then five colours - on the one string. A
            // save written before this reads back four chars and the colour loop below simply
            // never fires, leaving every colour allowed, which is the right default.
            StringBuilder engage = new StringBuilder();
            for (int t = 0; t < TIERS_ASCENDING.length; t++)
                engage.append(g.engageTier != null && t < g.engageTier.length && g.engageTier[t] ? '1' : '0');
            for (int c = 0; c < TerritoryControl.COLORS.length; c++)
                engage.append(g.engageColor != null && c < g.engageColor.length && g.engageColor[c] ? '1' : '0');
            sub.store("engage", engage.toString());
            sub.store("watchMatches", g.watchMatches);
            sub.store("hiredDay", g.hiredDay);
            sub.store("lastPaidDay", g.lastPaidDay);
            sub.store("downUntilDay", g.downUntilDay);
            sub.store("missionPoiId", g.missionPoiId == null ? "" : g.missionPoiId);
            sub.store("returningHome", g.returningHome);
            sub.store("deployed", g.deployed);
            sub.store("inDuel", g.inDuel); // round 173 (review G6)
            sub.store("pos", new Vector2(g.x, g.y));
            // Round 163: the guard's equipment, the inventory's own idiom (an ItemData[]).
            sub.storeObject("equipment", g.equipment.toArray(new forge.adventure.data.ItemData[0]));
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
            for (int c = 0; c < g.engageColor.length; c++) {
                int at = TIERS_ASCENDING.length + c;
                g.engageColor[c] = at >= engage.length() || engage.charAt(at) == '1';
            }
            g.watchMatches = !sub.containsKey("watchMatches") || sub.readBool("watchMatches");
            g.hiredDay = sub.containsKey("hiredDay") ? sub.readInt("hiredDay") : 0;
            g.lastPaidDay = sub.containsKey("lastPaidDay") ? sub.readInt("lastPaidDay") : 0;
            g.downUntilDay = sub.containsKey("downUntilDay") ? sub.readInt("downUntilDay") : 0;
            g.missionPoiId = sub.containsKey("missionPoiId") ? sub.readString("missionPoiId") : "";
            g.returningHome = sub.containsKey("returningHome") && sub.readBool("returningHome");
            g.deployed = sub.containsKey("deployed") && sub.readBool("deployed");
            g.inDuel = sub.containsKey("inDuel") && sub.readBool("inDuel"); // round 173: absent before it
            if (sub.containsKey("pos")) {
                Vector2 pos = sub.readVector2("pos");
                g.x = pos.x;
                g.y = pos.y;
            }
            // Round 163: equipment. A save from before it simply has none.
            Object gear = sub.containsKey("equipment") ? sub.readObject("equipment") : null;
            if (gear instanceof forge.adventure.data.ItemData[]) {
                for (forge.adventure.data.ItemData item : (forge.adventure.data.ItemData[]) gear)
                    if (item != null)
                        g.equipment.add(item);
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
