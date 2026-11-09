package forge.adventure.util;

import org.testng.AssertJUnit;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.adventure.util.DuelArtRelease.Kind;

/** TFR round 997 (r127-test P3-2): the first map after a duel frees its art, once, whatever came between. */
public class DuelArtReleaseTest {
    @BeforeMethod
    public void reset() {
        DuelArtRelease.reset();
    }

    @Test
    public void testTheFirstMapAfterADuelFreesItOnce() {
        AssertJUnit.assertFalse(DuelArtRelease.step(Kind.DUEL));
        AssertJUnit.assertTrue(DuelArtRelease.step(Kind.MAP));
        AssertJUnit.assertFalse(DuelArtRelease.step(Kind.MAP));
    }

    @Test
    public void testAMapWithNoDuelBeforeItFreesNothing() {
        AssertJUnit.assertFalse(DuelArtRelease.step(Kind.MAP));
        AssertJUnit.assertFalse(DuelArtRelease.step(Kind.OTHER));
        AssertJUnit.assertFalse(DuelArtRelease.step(Kind.MAP));
    }

    @Test
    public void testScenesBetweenKeepTheMark() {
        // an Inn bracket: duel, the event scene, a second duel, the event scene, then the town map
        DuelArtRelease.step(Kind.DUEL);
        AssertJUnit.assertFalse(DuelArtRelease.step(Kind.OTHER));
        DuelArtRelease.step(Kind.DUEL);
        AssertJUnit.assertFalse(DuelArtRelease.step(Kind.OTHER));
        AssertJUnit.assertTrue(DuelArtRelease.step(Kind.MAP));
    }
}
