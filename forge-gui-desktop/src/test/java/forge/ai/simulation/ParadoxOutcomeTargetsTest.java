package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.List;

import org.testng.annotations.Test;

import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * TFR round 1050 (P3-40 in logs-1119): {@link ParadoxOutcome#sureCast} took any ChangeZone-to-hand spell whose
 * ValidTgts says YouCtrl for a Paradoxical Outcome and set the AI's nonland permanents as its targets with no legality
 * check. Life from the Loam (Origin$ Graveyard, Land.YouCtrl) and Raise Dead came back as a "sure cast" that could
 * never be cast, and PhaseHandler asked the AI again up to 999 times a priority. Now only a bounce from the battlefield
 * is taken, with the targets it can legally have.
 */
public class ParadoxOutcomeTargetsTest extends FlatMcTestBase {

    /** The AI's own main phase, four returnable permanents, mana for a 4-drop and a deep library. */
    private void board() {
        setUpGame();
        at(PhaseType.MAIN1, ai);
        put("Island", 4, ai, ZoneType.Battlefield);
        put("Sol Ring", ai, ZoneType.Battlefield);
        put("Arcane Signet", ai, ZoneType.Battlefield);
        put("Llanowar Elves", ai, ZoneType.Battlefield);
        put("Elvish Mystic", ai, ZoneType.Battlefield);
        put("Swamp", 14, ai, ZoneType.Library);
        put("Forest", ai, ZoneType.Graveyard);
        put("Grizzly Bears", ai, ZoneType.Graveyard);
    }

    private SpellAbility spell(final Card c) {
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    @Test
    public void lifeFromTheLoamAndRaiseDeadAreNoParadoxicalOutcome() {
        board();
        final SpellAbility loam = spell(put("Life from the Loam", ai, ZoneType.Hand));
        final SpellAbility raise = spell(put("Raise Dead", ai, ZoneType.Hand));

        assertNull(ParadoxOutcome.sureCast(ai, List.of(loam, raise)),
                "a graveyard-to-hand spell is never the sure cast (it got four battlefield targets and could not be cast)");
        assertEquals(loam.getTargets().size(), 0, "no targets are left on Life from the Loam");
        assertEquals(raise.getTargets().size(), 0, "no targets are left on Raise Dead");
    }

    @Test
    public void paradoxicalOutcomeIsStillTheSureCastWithLegalTargets() {
        board();
        final SpellAbility paradox = spell(put("Paradoxical Outcome", ai, ZoneType.Hand));

        assertSame(ParadoxOutcome.sureCast(ai, List.of(paradox)), paradox, "four returnable permanents: cast it");
        assertEquals(paradox.getTargets().size(), 4, "Sol Ring, Arcane Signet and the two elves");
        for (final Object o : paradox.getTargets()) {
            assertTrue(paradox.canTarget((Card) o), "every target is legal");
        }
        assertTrue(paradox.isTargetNumberValid());
    }

    @Test
    public void aSingleTargetBounceIsNoParadoxicalOutcome() {
        board();
        // Narrow Escape: "Return target permanent you control to its owner's hand" - one target, never four
        final SpellAbility escape = spell(put("Narrow Escape", ai, ZoneType.Hand));

        assertNull(ParadoxOutcome.sureCast(ai, List.of(escape)), "one target can't make the four the sure cast needs");
        assertEquals(escape.getTargets().size(), 0);
    }
}
