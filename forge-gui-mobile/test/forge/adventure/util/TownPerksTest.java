package forge.adventure.util;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * TFR round 987: the town perks' numbers (notes/perks-fable-1108.md sections 2 and 3). Tournament Regular cuts Inn entry
 * fees by 25% / 50% and pays a won bracket a rare, 1 in 8 a mythic; Guild Favorite pays side quests +50% gold and
 * shards, +1 card a card reward, and 20 days instead of 15.
 */
public class TownPerksTest {
    private static final float RANK_I = 0.75f, RANK_II = 0.5f;

    @Test
    public void innFeesWithoutThePerkAreTheStockFees() {
        int[] bases = {500, 3000, 6000, 50, 100, 1, 7};
        float[] towns = {1f, 0.9f, 1.1f, 1.25f, 0.8f, 1.15f};
        for (int base : bases)
            for (float town : towns)
                AssertJUnit.assertEquals(base + " x " + town, Math.round(base * town), TownPerks.innFee(base, town, 1f));
    }

    @Test
    public void innFeesMatchTheEvaluation() {
        // Draft 3,000 gold / 50 shards, Sealed 6,000 / 100, Jumpstart 500 / 50, at a town price of 1.
        AssertJUnit.assertEquals(2250, TownPerks.innFee(3000, 1f, RANK_I));
        AssertJUnit.assertEquals(1500, TownPerks.innFee(3000, 1f, RANK_II));
        AssertJUnit.assertEquals(4500, TownPerks.innFee(6000, 1f, RANK_I));
        AssertJUnit.assertEquals(3000, TownPerks.innFee(6000, 1f, RANK_II));
        AssertJUnit.assertEquals(375, TownPerks.innFee(500, 1f, RANK_I));
        AssertJUnit.assertEquals(38, TownPerks.innFee(50, 1f, RANK_I)); // 37.5 rounds up
        AssertJUnit.assertEquals(25, TownPerks.innFee(50, 1f, RANK_II));
        AssertJUnit.assertEquals(75, TownPerks.innFee(100, 1f, RANK_I));
        // the town's price and the perk multiply once: 3,000 x 1.1 x 0.75
        AssertJUnit.assertEquals(2475, TownPerks.innFee(3000, 1.1f, RANK_I));
        AssertJUnit.assertEquals(0, TownPerks.innFee(0, 1.1f, RANK_I));
    }

    @Test
    public void onlyAThreeOChampionWinsTheBracket() {
        AssertJUnit.assertTrue(TownPerks.isChampion(3, 0));
        AssertJUnit.assertFalse(TownPerks.isChampion(2, 1));
        AssertJUnit.assertFalse(TownPerks.isChampion(1, 0)); // abandoned after one win
        AssertJUnit.assertFalse(TownPerks.isChampion(3, 1));
    }

    @Test
    public void theChampionCardIsAMythicOneTimeInEight() {
        float chance = 0.125f;
        AssertJUnit.assertTrue(TownPerks.championMythic(0.1f, chance, true, true));
        AssertJUnit.assertFalse(TownPerks.championMythic(0.2f, chance, true, true));
        AssertJUnit.assertFalse("a set with no mythics gives a rare", TownPerks.championMythic(0.0f, chance, false, true));
        AssertJUnit.assertTrue("a set with only mythics gives a mythic", TownPerks.championMythic(0.9f, chance, true, false));
        int mythics = 0, rolls = 8000;
        for (int i = 0; i < rolls; i++)
            if (TownPerks.championMythic((i + 0.5f) / rolls, chance, true, true))
                mythics++;
        AssertJUnit.assertEquals(1000, mythics);
    }

    @Test
    public void guildFavoriteRaisesGoldShardsCardsAndDays() {
        float purse = 0.5f;
        AssertJUnit.assertEquals(750, TownPerks.questAmount(500, purse));
        AssertJUnit.assertEquals(1125, TownPerks.questAmount(750, purse));
        AssertJUnit.assertEquals(2250, TownPerks.questAmount(1500, purse));
        AssertJUnit.assertEquals(8, TownPerks.questAmount(5, purse)); // 7.5 rounds up
        AssertJUnit.assertEquals(150, TownPerks.questAmount(100, purse));
        AssertJUnit.assertEquals(0, TownPerks.questAmount(0, purse));
        AssertJUnit.assertEquals(500, TownPerks.questAmount(500, 0f));
        AssertJUnit.assertEquals(2, TownPerks.questCards(1, 1));
        AssertJUnit.assertEquals(3, TownPerks.questCards(2, 1));
        AssertJUnit.assertEquals(0, TownPerks.questCards(0, 1));
        AssertJUnit.assertEquals(20, TownPerks.questDays(15, 5));
        AssertJUnit.assertEquals(15, TownPerks.questDays(15, 0));
    }

    @Test
    public void guildFavoriteRewritesTheRewardLineOnly() {
        float purse = 0.5f;
        AssertJUnit.assertEquals("You did it. (+2 Local [+Reputation], +750 [+Gold])",
                TownPerks.scaleRewardText("You did it. (+2 Local [+Reputation], +500 [+Gold])", purse));
        AssertJUnit.assertEquals("Paid (+1125 [+Gold], +60 [+Shards])",
                TownPerks.scaleRewardText("Paid (+750 [+Gold], +40 [+Shards])", purse));
        AssertJUnit.assertEquals("(+3 Local [+Reputation])", TownPerks.scaleRewardText("(+3 Local [+Reputation])", purse));
        AssertJUnit.assertEquals("(+500 [+Gold])", TownPerks.scaleRewardText("(+500 [+Gold])", 0f));
        AssertJUnit.assertNull(TownPerks.scaleRewardText(null, purse));
    }
}
