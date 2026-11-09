package forge.adventure.util;

import forge.adventure.data.AdventureEventData.AdventureEventReward;
import forge.deck.Deck;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TFR round 996 (notes/logs-1109b.md P2-2): the broken Inn Draft from a player's save - no reward packs, a three-slot prize
 * table - is repaired before the description and the champion's prize read it, and the champion is paid in full.
 * AdventureEventData itself cannot load without the card database (its static set filters), so this runs the repair's
 * pieces exactly as getDescription and giveRewards call them.
 */
public class InnEventRepairTest {
    private static Deck pack(String code) {
        Deck d = new Deck(code + " Booster");
        d.setComment(code);
        return d;
    }

    /** The player's event: the three tiers a Draft constructor builds, in a table one slot short, and no reward packs. */
    private static AdventureEventReward[] brokenDraftTable(Deck[] packs) {
        return new AdventureEventReward[]{
                InnEventRepair.packTier(0, packs, "Challenge Coin"),
                InnEventRepair.packTier(1, packs, "Challenge Coin"),
                InnEventRepair.packTier(2, packs, "Challenge Coin")};
    }

    @Test
    public void aWholeDraftIsLeftAlone() {
        Deck[] packs = {pack("LRW"), pack("MOR"), pack("LRW")};
        AdventureEventReward[] table = InnEventRepair.grown(brokenDraftTable(packs));
        AssertJUnit.assertNull(InnEventRepair.brokenShape(table, packs, true));
        AssertJUnit.assertSame(table, InnEventRepair.grown(table));
        // Jumpstart has no reward packs by design
        AssertJUnit.assertNull(InnEventRepair.brokenShape(table, null, false));
    }

    @Test
    public void thePlayersShapeIsNamed() {
        Deck[] packs = {pack("LRW"), pack("MOR"), pack("LRW")};
        String shape = InnEventRepair.brokenShape(brokenDraftTable(packs), null, true);
        AssertJUnit.assertEquals("rewards.length=3, rewardPacks=null", shape);
        AssertJUnit.assertEquals("rewards=null, rewardPacks=[LRW, null, MOR]",
                InnEventRepair.brokenShape(null, new Deck[]{pack("LRW"), null, pack("MOR")}, true));
        AdventureEventReward[] holed = InnEventRepair.grown(brokenDraftTable(packs));
        holed[1] = null;
        AssertJUnit.assertEquals("rewards[1]=null", InnEventRepair.brokenShape(holed, packs, true));
    }

    @Test
    public void theDescriptionReadsThePacksBackFromThePrizeTable() {
        Deck[] packs = {pack("LRW"), pack("MOR"), pack("EVE")};
        AdventureEventReward[] table = brokenDraftTable(packs);
        Deck[] back = InnEventRepair.packsFromTiers(table);
        AssertJUnit.assertNotNull(back);
        for (int i = 0; i < 3; i++)
            AssertJUnit.assertSame(packs[i], back[i]);
        // getDescription's Draft line, as it formats it
        String prizes = String.format("1+ round wins: %s Booster, %s Booster\n0 round wins: %s Booster",
                InnEventRepair.packLabel(back, 1, "LRW"), InnEventRepair.packLabel(back, 2, "LRW"),
                InnEventRepair.packLabel(back, 0, "LRW"));
        AssertJUnit.assertEquals("1+ round wins: MOR Booster, EVE Booster\n0 round wins: LRW Booster", prizes);
    }

    @Test
    public void withNoPacksAnywhereTheDescriptionNamesAGenericBooster() {
        AdventureEventReward[] table = brokenDraftTable(null);
        AssertJUnit.assertNull(InnEventRepair.packsFromTiers(table));
        AssertJUnit.assertEquals("LRW", InnEventRepair.packLabel(null, 0, "LRW"));
        AssertJUnit.assertEquals("Random", InnEventRepair.packLabel(null, 2, null));
        AssertJUnit.assertEquals("Random", InnEventRepair.packLabel(new Deck[0], 1, ""));
    }

    @Test
    public void theChampionIsPaidFromAThreeSlotTable() {
        Deck[] packs = {pack("LRW"), pack("MOR"), pack("EVE")};
        AdventureEventReward[] table = InnEventRepair.grown(brokenDraftTable(packs));
        AssertJUnit.assertEquals(InnEventRepair.SLOTS, table.length);
        // giveRewards' Draft branch writes the champion's slot - index 3, the write that threw for the player
        Deck drafted = new Deck("Drafted Deck");
        table[3] = new AdventureEventReward();
        table[3].minWins = 3;
        table[3].maxWins = 3;
        table[3].cardRewards = new Deck[]{drafted};

        List<Deck> cards = new ArrayList<>();
        List<String> items = new ArrayList<>();
        for (AdventureEventReward r : InnEventRepair.payingTiers(table, 3)) {
            cards.addAll(Arrays.asList(r.cardRewards));
            items.addAll(Arrays.asList(r.itemRewards));
        }
        AssertJUnit.assertEquals(Arrays.asList(packs[1], packs[2], drafted), cards);
        AssertJUnit.assertEquals(List.of("Challenge Coin"), items);

        // and the other records pay as before: 0 wins the one pack, 1 win the two
        AssertJUnit.assertEquals(1, InnEventRepair.payingTiers(table, 0).size());
        AssertJUnit.assertSame(packs[0], InnEventRepair.payingTiers(table, 0).get(0).cardRewards[0]);
        AssertJUnit.assertEquals(1, InnEventRepair.payingTiers(table, 1).size());
        AssertJUnit.assertEquals(2, InnEventRepair.payingTiers(table, 2).size());
    }

    @Test
    public void aLostTierIsRebuiltAndAMissingOneNeverThrows() {
        Deck[] packs = {pack("LRW"), pack("MOR"), pack("EVE")};
        AdventureEventReward[] table = InnEventRepair.grown(new AdventureEventReward[]{
                InnEventRepair.packTier(0, packs, "Silver Challenge Coin"), null});
        AssertJUnit.assertEquals(4, table.length);
        AssertJUnit.assertEquals(1, InnEventRepair.payingTiers(table, 0).size()); // the nulls are skipped
        AssertJUnit.assertEquals(0, InnEventRepair.payingTiers(table, 3).size());
        table[1] = InnEventRepair.packTier(1, packs, "Silver Challenge Coin");
        table[2] = InnEventRepair.packTier(2, packs, "Silver Challenge Coin");
        AssertJUnit.assertEquals(1, table[1].minWins);
        AssertJUnit.assertEquals(3, table[1].maxWins);
        AssertJUnit.assertEquals(Arrays.asList(packs[1], packs[2]), Arrays.asList(table[1].cardRewards));
        AssertJUnit.assertEquals("Silver Challenge Coin", table[2].itemRewards[0]);
        AssertJUnit.assertEquals(2, table[2].minWins);
        // a tier whose packs are gone pays no card rather than a null one
        AssertJUnit.assertEquals(0, InnEventRepair.packTier(1, null, "x").cardRewards.length);
        AssertJUnit.assertNull(InnEventRepair.brokenShape(table, packs, true));
    }

    @Test
    public void aNullTableGrowsEmpty() {
        AdventureEventReward[] table = InnEventRepair.grown(null);
        AssertJUnit.assertEquals(4, table.length);
        AssertJUnit.assertTrue(InnEventRepair.payingTiers(null, 3).isEmpty());
    }
}
