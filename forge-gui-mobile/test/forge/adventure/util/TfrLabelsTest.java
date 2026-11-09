package forge.adventure.util;

import forge.adventure.data.DialogData;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * TFR round 992: the Abandon Quest confirm's reputation (the worst way through the failure dialog) and the Armory
 * Re-roll's days left.
 */
public class TfrLabelsTest {
    private static DialogData dialog(int rep, DialogData... options) {
        DialogData d = new DialogData();
        DialogData.ActionData a = new DialogData.ActionData();
        a.addMapReputation = rep;
        d.action = new DialogData.ActionData[]{a};
        d.options = options;
        return d;
    }

    @Test
    public void failureReputation() {
        AssertJUnit.assertEquals(0, TfrLabels.failureReputation(null));
        AssertJUnit.assertEquals(0, TfrLabels.failureReputation(dialog(0)));
        AssertJUnit.assertEquals(-2, TfrLabels.failureReputation(dialog(-2)));
        // a gain is not counted; the worst option adds to the root's own loss
        AssertJUnit.assertEquals(0, TfrLabels.failureReputation(dialog(3)));
        AssertJUnit.assertEquals(-3, TfrLabels.failureReputation(dialog(-1, dialog(-2), dialog(0))));
        AssertJUnit.assertEquals(-5, TfrLabels.failureReputation(dialog(0, dialog(-1, dialog(-4)))));
    }

    @Test
    public void daysLeft() {
        AssertJUnit.assertEquals(0, TfrLabels.daysLeft(null, 30, 7));
        AssertJUnit.assertEquals(7, TfrLabels.daysLeft(30, 30, 7));
        AssertJUnit.assertEquals(3, TfrLabels.daysLeft(26, 30, 7));
        AssertJUnit.assertEquals(0, TfrLabels.daysLeft(23, 30, 7));
        AssertJUnit.assertEquals(0, TfrLabels.daysLeft(10, 30, 7));
    }
}
