package forge.ai;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

/**
 * TFR round 1034 (round 1029's study F14): the random trade-on-block chance, {@link AiBlockController#randomTradeChance}.
 * Default.ai's MIN_CHANCE_TO_RANDOMLY_TRADE_ON_BLOCK=30 and MAX_...=70. The step used to be an int division, so it
 * truncated to 2 at 20 starting life and to 0 from 41 on: an enemy with scaled life (Insane) traded at 70% at full life.
 */
public class AiBlockControllerTradeChanceTest {

    @Test
    public void fullLifeIsTheMinimumAtEveryStartingLife() {
        for (final int start : new int[] {20, 30, 41, 50, 90}) {
            assertEquals(AiBlockController.randomTradeChance(30, 70, start, start), 30, "starting life " + start);
        }
    }

    @Test
    public void stepIsFractional() {
        // 20 starting life: 15 steps of 40/15 = 2.67. At 15 life: 70 - 10 * 2.67 = 43.3 (the int step gave 70 - 20 = 50).
        assertEquals(AiBlockController.randomTradeChance(30, 70, 20, 15), 43);
        // 50 starting life (Insane x2.5): 45 steps of 0.89. At 25 life: 70 - 20 * 0.89 = 52.2 (the int step gave 70).
        assertEquals(AiBlockController.randomTradeChance(30, 70, 50, 25), 52);
    }

    @Test
    public void chanceRisesAsLifeFalls() {
        for (final int start : new int[] {20, 30, 50}) {
            int last = -1;
            for (int life = start; life >= 1; life--) {
                final int c = AiBlockController.randomTradeChance(30, 70, start, life);
                assertTrue(c >= last, "starting life " + start + ", life " + life + ": " + c + " < " + last);
                assertTrue(c >= 30 && c <= 70, "out of range at life " + life + ": " + c);
                last = c;
            }
            assertTrue(last > 30, "starting life " + start + ": no rise at low life");
        }
    }

    @Test
    public void lifeAboveStartStaysAtTheMinimum() {
        assertEquals(AiBlockController.randomTradeChance(30, 70, 20, 35), 30);
    }

    @Test
    public void tinyStartingLifeKeepsOneStep() {
        assertEquals(AiBlockController.randomTradeChance(30, 70, 5, 5), 30); // numSteps floors at 1
    }
}
