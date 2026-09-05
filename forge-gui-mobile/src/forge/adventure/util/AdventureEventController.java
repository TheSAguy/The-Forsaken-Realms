package forge.adventure.util;

import forge.StaticData;
import forge.adventure.data.AdventureEventData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.item.BoosterPack;
import forge.item.PaperCard;
import forge.item.SealedTemplate;
import forge.item.generation.BoosterGenerator;
import forge.item.generation.BoosterSlots;
import forge.item.generation.UnOpenedProduct;
import forge.model.CardBlock;
import forge.model.FModel;
import forge.util.Aggregates;
import com.google.common.collect.ImmutableList;
import org.apache.commons.lang3.tuple.Pair;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.*;

public class AdventureEventController implements Serializable {
    // Save compatibility: pinned 2026-09-03 (round 90) at the value derived from the v1.04 class shape so save compatibility no longer depends on the class not changing.
    private static final long serialVersionUID = -8111985415988509005L;

    public void finalizeEvent(AdventureEventData completedEvent) {
        Current.player().getStatistic().setResult(completedEvent);
        Current.player().removeEvent(completedEvent);
    }

    public enum EventFormat {
        Draft,
        Sealed,
        Jumpstart,
        Constructed;

        public static EventFormat smartValueOf(String name) {
            return Arrays.stream(EventFormat.values())
                    .filter(e -> e.name().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
        }

        @Override
        public String toString() {
            return switch (this) {
                case Sealed -> "Sealed Deck";
                case Jumpstart -> "Jumpstart";
                case Draft -> "Draft";
                case Constructed -> "Constructed";
                default -> name();
            };
        }

        public DeckFormat getDeckFormat() {
            return DeckFormat.Limited;
        }
    }

    public enum EventStyle {
        Bracket,
        RoundRobin,
        Swiss
    }

    public enum EventStatus {
        Available, // New event
        Entered,   // Entry fee paid, deck not locked in
        Ready,     // Deck is registered but can still be edited
        Started,   // Matches available
        Completed, // All matches complete, rewards pending
        Awarded,   // Rewards distributed
        Abandoned  // Ended without completing all matches
    }

    /** Total duel wins past which Inns stop offering Jumpstart events (see createEvent). */
    private static final int JUMPSTART_MAX_WINS = 25;

    private static AdventureEventController object;

    public static AdventureEventController instance() {
        if (object == null) {
            object = new AdventureEventController();
        }
        return object;
    }

    private AdventureEventController() {

    }

    private final Map<String, Long> nextEventDate = new HashMap<>();

    /**
     * Lets InnScene's pool-change re-roll bypass the once-per-day availability gate
     * (2026-09-01 release review). That path is REPLACING an event that already existed, not
     * creating an extra one, so the gate - which exists to stop a player farming fresh
     * tournaments - does not apply, and must not be allowed to turn the re-roll into a deletion.
     */
    public void clearNextEventDate(String pointID) {
        nextEventDate.remove(pointID);
    }

    public static void clear() {
        object = null;
    }

    public AdventureEventData createEvent(String pointID) {
        return createEvent(pointID, false);
    }

    /** @param playerTown true when this Inn is in a town the player owns - narrows the tournament
     *                    pool to their race + unlocked sets (user spec 2026-08-31). */
    public AdventureEventData createEvent(String pointID, boolean playerTown) {
        if (nextEventDate.containsKey(pointID) && nextEventDate.get(pointID) >= LocalDate.now().toEpochDay()) {
            // No event currently available here
            return null;
        }

        long eventSeed = getEventSeed(pointID);
        Random random = new Random(eventSeed);

        AdventureEventData e;
        // After a certain number of wins, stop offering Jumpstart events.
        // Raised 10 -> 25 (user request 2026-08-29): Jumpstart is the ONLY event format that
        // accepts a Bronze Challenge Coin, and the player is handed 3 of them by the intro quest
        // (quests.json "freeChallengeCoins" grant). With the old cutoff plus the 30% roll below,
        // a player could easily pass 10 wins having been offered Jumpstart once or twice, leaving
        // the remaining coins permanently unspendable - which is exactly what prompted this.
        // See also the Bronze Coin ante-ransom option in DuelScene, added the same round, which
        // gives them a second, non-expiring use.
        // Round 118 (user spec 2026-09-05): one Jumpstart tournament per run - the jumpstartPlayed character flag is set the
        // moment a Jumpstart event starts (AdventureEventData.startEvent) and no Inn rolls the format afterwards.
        if (Current.player().getStatistic().totalWins() < JUMPSTART_MAX_WINS &&
                Current.player().getCharacterFlag("jumpstartPlayed") <= 0 &&
                random.nextInt(10) <= 2) {
            e = new AdventureEventData(eventSeed, EventFormat.Jumpstart, playerTown);
        } else {
            if (random.nextInt(4) == 3) {
                // Experimental: 1 out of 4 chance for it to be a Sealed Deck event
                e = new AdventureEventData(eventSeed, EventFormat.Sealed, playerTown);
            } else {
                e = new AdventureEventData(eventSeed, EventFormat.Draft, playerTown);
            }
        }

        if (e.cardBlock == null) {
            //covers cases where (somehow) editions that do not match the event style have been picked up
            return null;
        }
        return e;
    }

    public AdventureEventData createEvent(EventFormat format, CardBlock cardBlock, String pointID) {
        return createEvent(format, cardBlock, pointID, false);
    }

    public AdventureEventData createEvent(EventFormat format, CardBlock cardBlock, String pointID, boolean playerTown) {
        long eventSeed = getEventSeed(pointID);
        AdventureEventData e = new AdventureEventData(eventSeed, format, cardBlock);
        if(e.cardBlock == null)
             return null;
        // Stamp the re-rolled event too, or the very next Inn visit would see stamp 0, read it as
        // legacy, and never refresh it when the player unlocks a set.
        e.playerTownPoolStamp = playerTown
                ? forge.adventure.util.EditionProgression.playerTownPoolStamp() : 0;
        return e;
    }

    private static long getEventSeed(String pointID) {
        long eventSeed;
        long timeSeed = LocalDate.now().toEpochDay();
        long placeSeed = Long.parseLong(pointID.replaceAll("[^0-9]", ""));
        long room = Long.MAX_VALUE - placeSeed;
        if (timeSeed > room) {
            //ensuring we don't ever hit an overflow
            eventSeed = Long.MIN_VALUE + timeSeed - room;
        } else {
            eventSeed = timeSeed + placeSeed;
        }
        return eventSeed;
    }

    public void initializeEvent(AdventureEventData e, String pointID, int eventOrigin, PointOfInterestChanges changes) {
        e.sourceID = pointID;
        e.eventOrigin = eventOrigin;

        AdventureEventData.PairingStyle pairingStyle;
        if (e.style == EventStyle.RoundRobin) {
            pairingStyle = AdventureEventData.PairingStyle.RoundRobin;
        } else {
            pairingStyle = AdventureEventData.PairingStyle.SingleElimination;
        }

        e.eventRules = new AdventureEventData.AdventureEventRules(e.format, pairingStyle);

        e.generateParticipants();

        AdventurePlayer.current().addEvent(e);
        nextEventDate.put(pointID, LocalDate.now().toEpochDay() + new Random().nextInt(2)); //next local event availability date
    }

    public Deck generateBooster(String setCode) {
        SealedTemplate template = AdventureOverrides.instance().getBoosterTemplate(setCode);
        List<PaperCard> cards = BoosterGenerator.getBoosterPack(template);
        Deck output = new Deck();
        output.getMain().add(cards);
        String editionName = FModel.getMagicDb().getEditions().get(setCode).getName();
        output.setName(editionName + " Booster");
        output.setComment(setCode);
        return output;
    }
    public Deck generateBoosterByColor(String color) {
        return generateBoosterByColor(color, null);
    }

    // Progressive Set Unlocks edition-restriction fix (2026-08-13) - BoosterPack.fromColor()
    // pulls from the whole card database with no edition filter at all, so every colored-booster
    // shop (White/Blue/Black/Red/Green/Colorless Booster in shops.json) bypassed edition
    // restriction unconditionally, regardless of town ownership or the player's own
    // unlockedEditions - confirmed root cause of the "Nature's Nurture Packs" screenshot showing
    // out-of-shard Green boosters. Mirrors BoosterPack.fromColor()'s own slot layout exactly, just
    // appending a "fromSets(...)" clause (a stock BoosterGenerator predicate operator, see
    // BoosterGenerator.buildExtraPredicate()) to each slot when restrictEditions is non-empty.
    // restrictEditions==null/empty keeps the original unrestricted behavior (stock planes, or a
    // caller with no restriction to apply).
    public Deck generateBoosterByColor(String color, String[] restrictEditions) {
        String setClause = "";
        if (restrictEditions != null && restrictEditions.length > 0) {
            // BoosterGenerator.buildExtraPredicate()'s fromSets(...) parser does
            // operator.substring("fromSets(".length() + 1) - the "+1" is calibrated to also skip
            // a leading quote (mirrors the color("...") convention right above), matching the
            // only other caller in the codebase (QuestUtilCards.java:642). Omitting the quote (as
            // an earlier version of this fix did) shifts that substring by one character and
            // silently truncates the first edition code (e.g. "ONE" -> "NE", matching zero cards).
            setClause = ":fromSets(\"" + String.join(",", restrictEditions) + ")";
        }
        BoosterPack pack = new BoosterPack(color, new SealedTemplate("?", ImmutableList.of(
                Pair.of(BoosterSlots.COMMON + ":color(\"" + color + "\"):!" + BoosterSlots.LAND + setClause, 11),
                Pair.of(BoosterSlots.UNCOMMON + ":color(\"" + color + "\"):!" + BoosterSlots.LAND + setClause, 3),
                Pair.of(BoosterSlots.RARE_MYTHIC + ":color(\"" + color + "\"):!" + BoosterSlots.LAND + setClause, 1),
                Pair.of(BoosterSlots.LAND + ":color(\"" + color + "\")" + setClause, 1))
        ));
        List<PaperCard> cards = pack.getCards();
        Deck output = new Deck();
        output.getMain().add(cards);
        String editionName = color + " Booster Pack";
        output.setName(editionName);
        output.setComment(color);
        return output;
    }

    public List<Deck> getJumpstartBoosters(CardBlock block, int count) {
        // Get all candidates, then remove at random until no more than count are included
        // This will prevent duplicate choices within a round of a Jumpstart draft
        List<Deck> packsAsDecks = new ArrayList<>();
        for (SealedTemplate template : StaticData.instance().getSpecialBoosters()) {
            if (!template.getEdition().contains(block.getLandSet().getCode()))
                continue;
            UnOpenedProduct toOpen = new UnOpenedProduct(template);

            Deck contents = new Deck();
            contents.getMain().add(toOpen.get());

            int size = contents.getMain().toFlatList().size();

            if (size < 18 || size > 25)
                continue;

            contents.setName(template.getEdition());

            int black = 0;
            int blue = 0;
            int green = 0;
            int red = 0;
            int white = 0;
            int multi = 0;
            int colorless = 0;

            for (PaperCard card : contents.getMain().toFlatList()) {
                int colors = 0;
                if (card.getRules().getColorIdentity().hasBlack()) {
                    black++;
                    colors++;
                }
                if (card.getRules().getColorIdentity().hasBlue()) {
                    blue++;
                    colors++;
                }
                if (card.getRules().getColorIdentity().hasGreen()) {
                    green++;
                    colors++;
                }
                if (card.getRules().getColorIdentity().hasRed()) {
                    red++;
                    colors++;
                }
                if (card.getRules().getColorIdentity().hasWhite()) {
                    white++;
                    colors++;
                }
                if (colors == 0 && !card.getRules().getType().isLand()) {
                    colorless++;
                } else if (colors > 1) {
                    multi++;
                }
            }

            if (multi > 3)
                contents.getTags().add("multicolor");
            if (colorless > 3)
                contents.getTags().add("colorless");
            if (black > 3)
                contents.getTags().add("black");
            if (blue > 3)
                contents.getTags().add("blue");
            if (green > 3)
                contents.getTags().add("green");
            if (red > 3)
                contents.getTags().add("red");
            if (white > 3)
                contents.getTags().add("white");

            packsAsDecks.add(contents);
        }

        while (packsAsDecks.size() > count) {
            Aggregates.removeRandom(packsAsDecks);
        }

        return packsAsDecks;
    }
}
