package forge.adventure.util;

import forge.card.CardRarity;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * TFR round 968: the chest's traveling artificer (Duplicate) never offers a banned card. The 200-shard slot takes the
 * deck's rarest allowed card, the 25-shard slot one of a lower tier; an all-banned deck offers nothing (gold instead).
 */
public class ChestDuplicateOfferTest {
    private static final String[] BANNED = {"Sol Ring", "Black Lotus", "Azorius Signet"};

    private static final class Card {
        final String name;
        final CardRarity rarity;

        Card(String name, CardRarity rarity) {
            this.name = name;
            this.rarity = rarity;
        }
    }

    private static ChestEvents.DuplicateOffer<Card> offer(List<Card> deck, long seed) {
        return ChestEvents.duplicateOffer(deck, c -> c.name, c -> c.rarity, BANNED, new Random(seed));
    }

    private static List<Card> deck(Card... cards) {
        return new ArrayList<>(Arrays.asList(cards));
    }

    @Test
    public void neitherSlotEverOffersABannedCard() {
        List<Card> deck = deck(new Card("Sol Ring", CardRarity.Uncommon), new Card("sol ring", CardRarity.Uncommon),
                new Card("Black Lotus", CardRarity.Rare), new Card("Llanowar Elves", CardRarity.Common),
                new Card("Shivan Dragon", CardRarity.Rare), new Card("Giant Growth", CardRarity.Common));
        for (long seed = 0; seed < 200; seed++) {
            ChestEvents.DuplicateOffer<Card> o = offer(deck, seed);
            AssertJUnit.assertEquals("Shivan Dragon", o.expensive.name);
            AssertJUnit.assertEquals(CardRarity.Common, o.cheap.rarity);
            AssertJUnit.assertEquals(3, o.skipped);
            AssertJUnit.assertEquals(Arrays.asList("Black Lotus", "Sol Ring", "sol ring"), o.skippedNames);
        }
    }

    @Test
    public void theCheapSlotTakesTheNextTierDown() {
        List<Card> deck = deck(new Card("Shivan Dragon", CardRarity.MythicRare), new Card("Counterspell", CardRarity.Uncommon),
                new Card("Island", CardRarity.BasicLand));
        ChestEvents.DuplicateOffer<Card> o = offer(deck, 1);
        AssertJUnit.assertEquals("Shivan Dragon", o.expensive.name);
        AssertJUnit.assertEquals("Counterspell", o.cheap.name);
        AssertJUnit.assertEquals(0, o.skipped);
    }

    @Test
    public void oneTierOnlyOffersTwoDifferentCards() {
        List<Card> deck = deck(new Card("Shivan Dragon", CardRarity.Rare), new Card("Shivan Dragon", CardRarity.Rare),
                new Card("Serra Angel", CardRarity.Rare));
        for (long seed = 0; seed < 50; seed++) {
            ChestEvents.DuplicateOffer<Card> o = offer(deck, seed);
            AssertJUnit.assertNotNull(o.expensive);
            AssertJUnit.assertFalse(o.cheap.name.equals(o.expensive.name));
        }
    }

    @Test
    public void copiesOfOneCardMakeASingleCheapOffer() {
        List<Card> deck = deck(new Card("Shivan Dragon", CardRarity.Rare), new Card("Shivan Dragon", CardRarity.Rare),
                new Card("Sol Ring", CardRarity.Uncommon));
        ChestEvents.DuplicateOffer<Card> o = offer(deck, 3);
        AssertJUnit.assertEquals("Shivan Dragon", o.cheap.name);
        AssertJUnit.assertNull(o.expensive);
        AssertJUnit.assertEquals(1, o.skipped);
    }

    @Test
    public void anAllBannedDeckOffersNothing() {
        List<Card> deck = deck(new Card("Sol Ring", CardRarity.Uncommon), new Card("Azorius Signet", CardRarity.Uncommon));
        ChestEvents.DuplicateOffer<Card> o = offer(deck, 0);
        AssertJUnit.assertNull(o.cheap);
        AssertJUnit.assertNull(o.expensive);
        AssertJUnit.assertEquals(2, o.skipped);
    }

    @Test
    public void noBanListMeansEveryCardIsAllowed() {
        List<Card> deck = deck(new Card("Sol Ring", CardRarity.Uncommon), new Card("Llanowar Elves", CardRarity.Common));
        ChestEvents.DuplicateOffer<Card> o = ChestEvents.duplicateOffer(deck, c -> c.name, c -> c.rarity, null, new Random(0));
        AssertJUnit.assertEquals("Sol Ring", o.expensive.name);
        AssertJUnit.assertEquals("Llanowar Elves", o.cheap.name);
        AssertJUnit.assertEquals(0, o.skipped);
    }
}
