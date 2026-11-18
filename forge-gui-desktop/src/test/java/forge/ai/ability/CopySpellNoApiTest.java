package forge.ai.ability;

import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertSame;
import static org.testng.AssertJUnit.assertTrue;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiAbilityDecision;
import forge.ai.SpellApiToAi;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * A copy spell weighed against a stack spell with no API (round 1043, logs-1118 P3-38).
 *
 * A face-down cast (Morph, Disguise) is a plain Spell with no API. CopySpellAbilityAi.checkApiLogic asks
 * SpellApiToAi.Converter.get(top.getApi()) whether the AI understands the spell on top of the stack; with a null API
 * that fell through to the EnumMap's put(null, ...), a NullPointerException that threw away the AI's choice for the
 * priority (a player's 1.29 log: Water Elemental's Twincast against a face-down spell). get(null) now answers
 * CannotPlayAi, so the AI simply declines to copy.
 */
public class CopySpellNoApiTest extends AITest {

    @Test
    public void nullApiIsCannotPlay() {
        assertTrue(SpellApiToAi.Converter.get((ApiType) null) instanceof CannotPlayAi);
        // the second ask threw too before the fix (nothing was cached); it still answers
        assertTrue(SpellApiToAi.Converter.get((ApiType) null) instanceof CannotPlayAi);
        // a real API still gets its cached instance
        assertSame(SpellApiToAi.Converter.get(ApiType.CopySpellAbility), SpellApiToAi.Converter.get(ApiType.CopySpellAbility));
    }

    @Test
    public void twincastDeclinesAFaceDownSpell() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        for (final Player p : game.getPlayers()) {
            fillLibrary(p, 20);
        }
        addCards("Island", 2, ai);
        final Card twincast = addCardToZone("Twincast", ai, ZoneType.Hand);
        addCards("Plains", 3, opp);
        final Card angel = addCardToZone("Exalted Angel", opp, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        game.getAction().checkStateEffects(true);

        // the opponent casts Exalted Angel face down: the Morph spell has no API
        SpellAbility faceDown = null;
        for (final SpellAbility s : angel.getSpellAbilities()) {
            if (s.isCastFaceDown()) {
                faceDown = s;
            }
        }
        assertNotNull("Exalted Angel's face-down cast", faceDown);
        assertNull("a face-down cast has no API, the case that threw", faceDown.getApi());
        faceDown.setActivatingPlayer(opp);
        final Card onStack = game.getAction().moveToStack(angel, faceDown);
        faceDown.setHostCard(onStack);
        game.getStack().add(faceDown);
        assertSame(faceDown, game.getStack().peekAbility());

        final SpellAbility copy = twincast.getFirstSpellAbility();
        copy.setActivatingPlayer(ai);
        // threw NullPointerException at CopySpellAbilityAi.checkApiLogic before round 1043
        final AiAbilityDecision decision = SpellApiToAi.Converter.get(copy).canPlayWithSubs(ai, copy);
        assertFalse("nothing to copy: " + decision, decision.willingToPlay());
    }
}
