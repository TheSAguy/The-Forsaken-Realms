package forge.adventure.util;

import forge.adventure.data.RewardData;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import java.util.Random;

/**
 * TFR round 972 (Fable P2-12): the seeds that make a reload roll quest rewards, the Temple's offering and a realm boon
 * offer the same way. Each seed is built from saved state only, so equal state must give an equal seed, and each part
 * that tells two legitimate rolls apart must change it.
 */
public class SeededRollsTest {
    private static final long WORLD = 1234567890123L;

    @Test
    public void aSeedIsTheSameForTheSamePartsAndChangesWithEachPart() {
        AssertJUnit.assertEquals(SeededRolls.seedOf(WORLD, "a", 1, 2L), SeededRolls.seedOf(WORLD, "a", 1, 2L));
        AssertJUnit.assertFalse(SeededRolls.seedOf(WORLD, "a", 1, 2L) == SeededRolls.seedOf(WORLD + 1, "a", 1, 2L));
        AssertJUnit.assertFalse(SeededRolls.seedOf(WORLD, "a", 1, 2L) == SeededRolls.seedOf(WORLD, "b", 1, 2L));
        AssertJUnit.assertFalse(SeededRolls.seedOf(WORLD, "a", 1, 2L) == SeededRolls.seedOf(WORLD, "a", 2, 2L));
        AssertJUnit.assertFalse("order matters", SeededRolls.seedOf(WORLD, 1, 2) == SeededRolls.seedOf(WORLD, 2, 1));
        AssertJUnit.assertFalse("no parts is a seed of its own", SeededRolls.seedOf(WORLD) == SeededRolls.seedOf(WORLD, 0));
    }

    @Test
    public void aRollAtASeedDrawsAlikeWhereverTheStreamStood() {
        Random fresh = new Random(1);
        Random runOn = new Random(1);
        for (int i = 0; i < 17; i++)
            runOn.nextInt(); // a session that rolled other things before the save
        long seed = SeededRolls.seedOf(WORLD, "reward");
        int[] a = SeededRolls.withSeed(fresh, seed, () -> new int[]{fresh.nextInt(100), fresh.nextInt(100), fresh.nextInt(100)});
        int[] b = SeededRolls.withSeed(runOn, seed, () -> new int[]{runOn.nextInt(100), runOn.nextInt(100), runOn.nextInt(100)});
        AssertJUnit.assertEquals(a[0], b[0]);
        AssertJUnit.assertEquals(a[1], b[1]);
        AssertJUnit.assertEquals(a[2], b[2]);
    }

    @Test
    public void theStreamResumesAfterASeededRoll() {
        Random one = new Random(7);
        Random two = new Random(7);
        SeededRolls.withSeed(one, 99L, one::nextLong);
        SeededRolls.withSeed(two, 99L, two::nextLong);
        AssertJUnit.assertEquals("resumes from a draw taken before the seed", one.nextLong(), two.nextLong());
        Random three = new Random(7);
        SeededRolls.withSeed(three, 99L, three::nextLong);
        AssertJUnit.assertFalse("not stuck at the seed", three.nextLong() == new Random(99L).nextLong());
    }

    @Test
    public void aRewardSeedChangesWithPlaceSpeakerGrantDayDuelsAndQuests() {
        long base = SeededRolls.rewardSeed(WORLD, "Plains Town 3", 12, "card:3", 40, 25, "7,9,");
        AssertJUnit.assertEquals("a reload reaches the same seed", base,
                SeededRolls.rewardSeed(WORLD, "Plains Town 3", 12, "card:3", 40, 25, "7,9,"));
        AssertJUnit.assertFalse(base == SeededRolls.rewardSeed(WORLD, "Island Town 1", 12, "card:3", 40, 25, "7,9,"));
        AssertJUnit.assertFalse(base == SeededRolls.rewardSeed(WORLD, "Plains Town 3", 13, "card:3", 40, 25, "7,9,"));
        AssertJUnit.assertFalse(base == SeededRolls.rewardSeed(WORLD, "Plains Town 3", 12, "card:4", 40, 25, "7,9,"));
        AssertJUnit.assertFalse("a later day", base == SeededRolls.rewardSeed(WORLD, "Plains Town 3", 12, "card:3", 41, 25, "7,9,"));
        AssertJUnit.assertFalse("after a duel", base == SeededRolls.rewardSeed(WORLD, "Plains Town 3", 12, "card:3", 40, 26, "7,9,"));
        AssertJUnit.assertFalse("a quest done", base == SeededRolls.rewardSeed(WORLD, "Plains Town 3", 12, "card:3", 40, 25, "9,"));
    }

    @Test
    public void aGrantsKeyReadsItsAuthoredFields() {
        AssertJUnit.assertEquals(SeededRolls.rewardKey(new RewardData[]{card(3, "W")}),
                SeededRolls.rewardKey(new RewardData[]{card(3, "W")}));
        AssertJUnit.assertFalse(SeededRolls.rewardKey(new RewardData[]{card(3, "W")})
                .equals(SeededRolls.rewardKey(new RewardData[]{card(4, "W")})));
        AssertJUnit.assertFalse(SeededRolls.rewardKey(new RewardData[]{card(3, "W")})
                .equals(SeededRolls.rewardKey(new RewardData[]{card(3, "U")})));
        AssertJUnit.assertEquals("a null entry is skipped", SeededRolls.rewardKey(new RewardData[]{card(3, "W")}),
                SeededRolls.rewardKey(new RewardData[]{null, card(3, "W")}));
    }

    @Test
    public void theTempleAndTheBoonSeedTheirOwnRolls() {
        AssertJUnit.assertEquals(SeededRolls.templeSeed(WORLD, 4), SeededRolls.templeSeed(WORLD, 4));
        AssertJUnit.assertFalse(SeededRolls.templeSeed(WORLD, 4) == SeededRolls.templeSeed(WORLD, 5));
        AssertJUnit.assertFalse(SeededRolls.templeSeed(WORLD, 4) == SeededRolls.templeSeed(WORLD + 1, 4));
        AssertJUnit.assertEquals(SeededRolls.boonSeed(WORLD, 10, 1), SeededRolls.boonSeed(WORLD, 10, 1));
        AssertJUnit.assertFalse(SeededRolls.boonSeed(WORLD, 10, 1) == SeededRolls.boonSeed(WORLD, 20, 1));
        AssertJUnit.assertFalse(SeededRolls.boonSeed(WORLD, 10, 1) == SeededRolls.boonSeed(WORLD, 10, 2));
        AssertJUnit.assertFalse("salted apart", SeededRolls.templeSeed(WORLD, 10) == SeededRolls.boonSeed(WORLD, 10, 0));
    }

    @Test
    public void anInnEventKeepsItsStoredSeedAndAnOldSaveBuildsOne() {
        AssertJUnit.assertEquals("a stored seed is the seed", 20417L,
                SeededRolls.eventSeed(20417L, WORLD, "inn 12", "Dominaria", 6, "Draft"));
        long old = SeededRolls.eventSeed(0L, WORLD, "inn 12", "Dominaria", 6, "Draft");
        AssertJUnit.assertEquals("an old save (seed 0) builds the same one on every load", old,
                SeededRolls.eventSeed(0L, WORLD, "inn 12", "Dominaria", 6, "Draft"));
        AssertJUnit.assertFalse(old == 0L);
        AssertJUnit.assertFalse(old == SeededRolls.eventSeed(0L, WORLD, "inn 13", "Dominaria", 6, "Draft"));
        AssertJUnit.assertFalse(old == SeededRolls.eventSeed(0L, WORLD, "inn 12", "Dominaria", 7, "Draft"));
    }

    @Test
    public void anEventsOpeningsDifferByWhatBlockAndWeek() {
        long draft = SeededRolls.openingSeed(20417L, "draft", "Dominaria", 6);
        AssertJUnit.assertEquals(draft, SeededRolls.openingSeed(20417L, "draft", "Dominaria", 6));
        AssertJUnit.assertFalse(draft == SeededRolls.openingSeed(20417L, "sealed pool", "Dominaria", 6));
        AssertJUnit.assertFalse(draft == SeededRolls.openingSeed(20417L, "draft", "Ixalan", 6));
        AssertJUnit.assertFalse("same Inn, same real day, next week",
                draft == SeededRolls.openingSeed(20417L, "draft", "Dominaria", 7));
    }

    @Test
    public void neighboringWeeksDrawUnlikeFirstPicks() {
        // round 870's lesson: unmixed seeds a step apart draw nearly the same first value
        int same = 0;
        for (int week = 0; week < 200; week++)
            if (new Random(SeededRolls.templeSeed(WORLD, week)).nextInt(40)
                    == new Random(SeededRolls.templeSeed(WORLD, week + 1)).nextInt(40))
                same++;
        AssertJUnit.assertTrue("the same first pick " + same + " of 200 times", same < 20);
    }

    private static RewardData card(int count, String color) {
        RewardData r = new RewardData();
        r.type = "card";
        r.count = count;
        r.colors = new String[]{color};
        return r;
    }
}
