package forge.adventure.player;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * TFR round 983 (logs-1106b P2-1): one lost ante copy leaves every deck that would hold more copies than the collection
 * keeps - and nothing at all when the collection held none (a padded deck's placeholder Wastes). Before, owning 0 gave
 * leftInPool -1, and the loss was recorded against every deck, empty slots too.
 */
public class AnteDeckRemovalsTest {

    @Test
    public void aCardNotInTheCollectionTouchesNoDeck() {
        int[] plan = AdventurePlayer.anteDeckRemovals(0, new int[]{0, 0, 1, 4});
        AssertJUnit.assertTrue(java.util.Arrays.equals(new int[]{0, 0, 0, 0}, plan));
    }

    @Test
    public void theLastCopyLeavesEveryDeckThatHeldIt() {
        int[] plan = AdventurePlayer.anteDeckRemovals(1, new int[]{1, 0, 1});
        AssertJUnit.assertTrue(java.util.Arrays.equals(new int[]{1, 0, 1}, plan));
    }

    @Test
    public void sparesKeepTheDecksWhole() {
        // 3 owned, 2 left: a deck with 2 keeps them, a deck with 3 loses one.
        int[] plan = AdventurePlayer.anteDeckRemovals(3, new int[]{2, 3, 0});
        AssertJUnit.assertTrue(java.util.Arrays.equals(new int[]{0, 1, 0}, plan));
    }

    @Test
    public void noDecks() {
        AssertJUnit.assertEquals(0, AdventurePlayer.anteDeckRemovals(2, new int[0]).length);
    }
}
