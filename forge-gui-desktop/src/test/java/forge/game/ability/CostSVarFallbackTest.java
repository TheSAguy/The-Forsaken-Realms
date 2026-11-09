package forge.game.ability;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * TFR round 998 (logs-1109 P3-15): Relic Amulet's damage is {@code SVar$CostCountersRemoved}, a value its cost payment
 * sets on the ability. Read before a payment (the AI or the UI pricing the ability), AbilityUtils printed "SVar ... not
 * found in ability, fallback to Card" - 299 lines in one player's log - although the card has no such SVar either and
 * the very next branch returns 0 for an unpaid cost, as designed. The line is now printed only when the card really
 * supplies the value.
 */
public class CostSVarFallbackTest extends AITest {
    private static SpellAbility damageAbility(Card c) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.getApi() == ApiType.DealDamage) {
                return sa;
            }
        }
        throw new IllegalStateException("no damage ability on " + c);
    }

    private static String stderrOf(Runnable r) {
        PrintStream old = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setErr(old);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void relicAmuletBeforePaymentIsZeroAndQuiet() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card amulet = addCard("Relic Amulet", p);
        SpellAbility sa = damageAbility(amulet);
        sa.setActivatingPlayer(p);

        int[] dmg = new int[1];
        String err = stderrOf(() -> dmg[0] = AbilityUtils.calculateAmount(amulet, sa.getParam("NumDmg"), sa));
        AssertJUnit.assertEquals("an unpaid counter cost deals 0", 0, dmg[0]);
        AssertJUnit.assertFalse("no fallback line for an unpaid cost: " + err, err.contains("not found in ability"));
    }

    @Test
    public void relicAmuletAfterPaymentDealsTheCountersRemoved() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card amulet = addCard("Relic Amulet", p);
        SpellAbility sa = damageAbility(amulet);
        sa.setActivatingPlayer(p);
        sa.setSVar("CostCountersRemoved", "4"); // what CostRemoveCounter.payAsDecided sets

        AssertJUnit.assertEquals(4, AbilityUtils.calculateAmount(amulet, sa.getParam("NumDmg"), sa));
    }

    @Test
    public void aMissingScriptValueIsStillReported() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card amulet = addCard("Relic Amulet", p);
        SpellAbility sa = damageAbility(amulet);
        sa.setActivatingPlayer(p);

        int[] n = new int[1];
        String err = stderrOf(() -> n[0] = AbilityUtils.calculateAmount(amulet, "TfrMissingValue", sa));
        AssertJUnit.assertEquals(0, n[0]);
        AssertJUnit.assertTrue("a scripting error keeps its warning: " + err, err.contains("not defined in Card"));
    }
}
