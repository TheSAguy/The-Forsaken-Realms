package forge.adventure.util;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * TFR round 986: the loot perks' numbers (notes/perks-fable-1108.md). Bounty Hunter multiplies the Bounty's points and
 * raises its cap; Treasure Seeker gives two map pieces an obelisk and pays a treasure's gold, shards and cards twice;
 * Legend Slayer pays once per legend name.
 */
public class LootPerksTest {
    private static final float NORMAL = 0.015f, INSANE = 0.02f, CAP = 0.30f;

    private static int bounty(int points, float share, int rank) {
        float factor = rank == 0 ? 1f : rank == 1 ? 1.5f : 2f;
        float capBonus = rank == 0 ? 0f : rank == 1 ? 0.10f : 0.20f;
        return LootPerks.bountyPercent(points, share, CAP, factor, capBonus);
    }

    @Test
    public void bountyHunterMatchesTheEvaluationsTable() {
        // {points, today, rank I, rank II} on Normal (1.5% a point)
        int[][] table = {{1, 2, 2, 3}, {4, 6, 9, 12}, {9, 14, 20, 27}, {13, 20, 29, 39}, {21, 30, 40, 50}};
        for (int[] row : table)
            for (int rank = 0; rank <= 2; rank++)
                AssertJUnit.assertEquals(row[0] + " points, rank " + rank, row[rank + 1], bounty(row[0], NORMAL, rank));
    }

    @Test
    public void bountyHunterCapHoldsOnInsane() {
        AssertJUnit.assertEquals(30, bounty(21, INSANE, 0));
        AssertJUnit.assertEquals(40, bounty(21, INSANE, 1));
        AssertJUnit.assertEquals(50, bounty(21, INSANE, 2));
    }

    @Test
    public void bountyOffAndNoCap() {
        AssertJUnit.assertEquals("share 0: the Bounty is off", 0, LootPerks.bountyPercent(21, 0f, CAP, 2f, 0.2f));
        AssertJUnit.assertEquals("no points", 0, LootPerks.bountyPercent(0, NORMAL, CAP, 2f, 0.2f));
        // custom difficulty's cap 0 = no cap: only the factor applies
        AssertJUnit.assertEquals(63, LootPerks.bountyPercent(21, NORMAL, 0f, 2f, 0.2f));
        AssertJUnit.assertEquals(32, LootPerks.bountyPercent(21, NORMAL, 0f, 1f, 0f));
    }

    @Test
    public void obeliskPieces() {
        AssertJUnit.assertEquals("without the perk", 1, LootPerks.piecesAfterTouch(0, 1, 9));
        AssertJUnit.assertEquals("Treasure Seeker: two", 2, LootPerks.piecesAfterTouch(0, 2, 9));
        AssertJUnit.assertEquals(8, LootPerks.piecesAfterTouch(6, 2, 9));
        AssertJUnit.assertEquals("never past the whole map", 9, LootPerks.piecesAfterTouch(8, 2, 9));
        // a map takes 5 obelisks with the perk, 9 without
        int pieces = 0, touches = 0;
        while (pieces < 9) {
            pieces = LootPerks.piecesAfterTouch(pieces, 2, 9);
            touches++;
        }
        AssertJUnit.assertEquals(5, touches);
    }

    @Test
    public void treasurePaysTwice() {
        AssertJUnit.assertEquals(1000, LootPerks.timesCount(1000, 1));
        AssertJUnit.assertEquals(2000, LootPerks.timesCount(1000, 2));
        AssertJUnit.assertEquals(80, LootPerks.timesCount(40, 2));
        AssertJUnit.assertEquals(8, LootPerks.timesCount(4, 2));
        AssertJUnit.assertEquals("never less than once", 4, LootPerks.timesCount(4, 0));
    }

    @Test
    public void legendSlayerPaysOncePerLegend() {
        Map<String, Integer> paid = new HashMap<>();
        paid.put("enemy|life|Zacama", 12); // round 302's key for the same legend is a different key
        AssertJUnit.assertTrue("first win", LootPerks.claimLegendSlayer(paid, "Zacama", 20));
        AssertJUnit.assertFalse("a later win", LootPerks.claimLegendSlayer(paid, "Zacama", 31));
        AssertJUnit.assertEquals("the first day stays", Integer.valueOf(20), paid.get(LootPerks.legendSlayerKey("Zacama")));
        AssertJUnit.assertTrue("another legend", LootPerks.claimLegendSlayer(paid, "Karona", 31));
        AssertJUnit.assertFalse("no name", LootPerks.claimLegendSlayer(paid, "", 31));
        AssertJUnit.assertFalse("no map (an old world)", LootPerks.claimLegendSlayer(null, "Zacama", 31));
    }
}
