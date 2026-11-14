package forge.adventure.player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * TFR round 1027 (logs-1113 P3-28): a reward with 5 auto-sell cards asked the deck editor for 5 background refreshes
 * (addReward, one per card) and the research v2 sale one more after clearing the pile (sellAutoSellPile), 6 threads
 * walking one ItemManager while the GL thread emptied it. The requests now fold into one, posted after the frame.
 */
public class EditorRefreshCoalesceTest {

    private Consumer<Runnable> savedPoster;
    private Runnable savedTarget;
    private final List<Runnable> frame = new ArrayList<>();
    private int refreshes;

    @BeforeMethod
    public void hook() {
        savedPoster = AdventurePlayer.editorRefreshPoster;
        savedTarget = AdventurePlayer.editorRefreshTarget;
        frame.clear();
        refreshes = 0;
        AdventurePlayer.editorRefreshPoster = frame::add; // Gdx.app.postRunnable: runs after the current frame
        AdventurePlayer.editorRefreshTarget = () -> refreshes++;
    }

    @AfterMethod
    public void unhook() {
        AdventurePlayer.editorRefreshPoster = savedPoster;
        AdventurePlayer.editorRefreshTarget = savedTarget;
    }

    private void endFrame() {
        List<Runnable> posted = new ArrayList<>(frame);
        frame.clear();
        posted.forEach(Runnable::run);
    }

    @Test
    public void fiveAutoSellRewardsAndTheSaleRefreshOnce() {
        for (int i = 0; i < 5; i++)
            AdventurePlayer.requestEditorRefresh(); // addReward, one auto-sell card each
        AdventurePlayer.requestEditorRefresh();     // sellAutoSellPile, after autoSellCards.clear()
        AssertJUnit.assertEquals("nothing runs inside the frame", 0, refreshes);
        AssertJUnit.assertEquals("one runnable posted", 1, frame.size());
        endFrame();
        AssertJUnit.assertEquals(1, refreshes);
    }

    @Test
    public void aLaterFrameRefreshesAgain() {
        AdventurePlayer.requestEditorRefresh();
        endFrame();
        AdventurePlayer.requestEditorRefresh();
        AdventurePlayer.requestEditorRefresh();
        endFrame();
        AssertJUnit.assertEquals(2, refreshes);
        endFrame();
        AssertJUnit.assertEquals("no stray refresh", 2, refreshes);
    }
}
