package forge.adventure.util;

import org.testng.AssertJUnit;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** TFR round 997 (logs-1108 P3-8): the Trader and bank lines, throttled per run of the same action. */
public class EconomyLogTest {
    @BeforeMethod
    public void reset() {
        EconomyLog.reset();
    }

    @Test
    public void testARunLogsItsFirstThreeThenEveryTwentyFifth() {
        AssertJUnit.assertTrue(EconomyLog.shouldLog(1));
        AssertJUnit.assertTrue(EconomyLog.shouldLog(3));
        AssertJUnit.assertFalse(EconomyLog.shouldLog(4));
        AssertJUnit.assertFalse(EconomyLog.shouldLog(24));
        AssertJUnit.assertTrue(EconomyLog.shouldLog(25));
        AssertJUnit.assertTrue(EconomyLog.shouldLog(50));
        AssertJUnit.assertFalse(EconomyLog.shouldLog(51));
    }

    @Test
    public void testARunAddsUpAndADifferentActionStartsOver() {
        for (int i = 0; i < 37; i++)
            EconomyLog.count("trade|Sell", 80, 0, -5, 0);
        AssertJUnit.assertEquals("gold +2960, wood -185", EconomyLog.totals());
        AssertJUnit.assertEquals(1, EconomyLog.count("trade|Buy", -100, 0, 5, 0));
        AssertJUnit.assertEquals("gold -100, wood +5", EconomyLog.totals());
    }

    @Test
    public void testAmountsNameOnlyWhatMoved() {
        AssertJUnit.assertEquals("gold 100", EconomyLog.amounts(100, 0, 0, 0));
        AssertJUnit.assertEquals("wood 5, stone 5", EconomyLog.amounts(0, 0, 5, 5));
        AssertJUnit.assertEquals("nothing", EconomyLog.amounts(0, 0, 0, 0));
    }
}
