package forge.adventure.util;

import org.testng.AssertJUnit;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * TFR round 989: the HUD starts hidden in a cave or dungeon, a next level keeps the player's choice, leaving puts the
 * world map's view back, and other maps are untouched (TfrHud, ported from TFR 2's Tfr2Hud).
 */
public class TfrHudTest {
    private static final class FakeHud implements TfrHud.View {
        boolean hidden;
        int calls;

        @Override
        public boolean hidden() {
            return hidden;
        }

        @Override
        public void hide(boolean hide) {
            hidden = hide;
            calls++;
        }
    }

    @BeforeMethod
    public void reset() {
        TfrHud.reset();
    }

    @Test
    public void kinds() {
        AssertJUnit.assertEquals("cave", TfrHud.kindOf("cave"));
        AssertJUnit.assertEquals("dungeon", TfrHud.kindOf("dungeon"));
        AssertJUnit.assertNull(TfrHud.kindOf("town"));
        AssertJUnit.assertNull(TfrHud.kindOf("castle"));
        AssertJUnit.assertNull(TfrHud.kindOf(null));
    }

    @Test
    public void townsAreUntouched() {
        FakeHud hud = new FakeHud();
        TfrHud.mapLoaded(hud, null, "town.tmx");
        TfrHud.mapLeft(hud);
        AssertJUnit.assertFalse(hud.hidden);
        AssertJUnit.assertEquals(0, hud.calls);
    }

    @Test
    public void dungeonHidesThenLeavingRestoresShown() {
        FakeHud hud = new FakeHud();
        TfrHud.mapLoaded(hud, "dungeon", "d1.tmx");
        AssertJUnit.assertTrue(hud.hidden);
        TfrHud.mapLeft(hud);
        AssertJUnit.assertFalse(hud.hidden);
    }

    @Test
    public void worldHiddenStaysHiddenAfterLeaving() {
        FakeHud hud = new FakeHud();
        hud.hidden = true;
        TfrHud.mapLoaded(hud, "cave", "c1.tmx");
        hud.hide(false);   // the player shows it inside
        TfrHud.mapLeft(hud);
        AssertJUnit.assertTrue(hud.hidden);
    }

    @Test
    public void nextLevelKeepsTheChoice() {
        FakeHud hud = new FakeHud();
        TfrHud.mapLoaded(hud, "dungeon", "d1.tmx");
        hud.hide(false);   // toggled shown
        TfrHud.mapLoaded(hud, "dungeon", "d2.tmx");
        AssertJUnit.assertFalse(hud.hidden);
        hud.hide(true);    // toggled hidden again
        int before = hud.calls;
        TfrHud.mapLoaded(hud, "dungeon", "d3.tmx");
        AssertJUnit.assertTrue(hud.hidden);
        AssertJUnit.assertEquals(before + 1, hud.calls);   // re-applied over showHideMap(false)'s fade
    }

    @Test
    public void everyDungeonStartsHiddenAgain() {
        FakeHud hud = new FakeHud();
        TfrHud.mapLoaded(hud, "dungeon", "d1.tmx");
        hud.hide(false);
        TfrHud.mapLeft(hud);
        TfrHud.mapLoaded(hud, "cave", "c1.tmx");
        AssertJUnit.assertTrue(hud.hidden);
    }
}
